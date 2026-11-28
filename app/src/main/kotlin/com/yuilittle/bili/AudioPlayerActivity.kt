package com.yuilittle.bili

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.transition.TransitionManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 独立音乐播放器页（离线纯音频专属体验）：
 * - 极简现代音乐播放器布局：顶部返回 / 歌名与歌手 / 右上角三点「更多」；
 * - 中央视觉舞台支持 4 种风格切换：封面（默认）、黑胶唱片、复古磁带、律动粒子（跟随音频实时能量脉动）；
 * - 下方：时间轴（支持拖拽与时间戳） + 播控栏（左下「模式图标+首字」切换单曲循环/列表循环/随机/顺序，中间上一首/大播放暂停/下一首，右下「列表」按钮，加号按钮从已下载音频添加进播放列表）；
 * - 播放/暂停支持音量渐入渐出（Fade-In / Fade-Out），切歌时伴随视觉舞台平滑动效过渡；
 * - 右上角「更多」支持弹出面板切换风格、并支持动态展开拉大切换到「设置」（勾选「自动保存播放列表」，默认不勾选）；
 * - 右下角列表点击从底部滑出播放列表抽屉，支持切歌、移除与点击加号添加音频。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioPlayerActivity : Activity() {

    enum class VisualStyle(val label: String, val subtitle: String) {
        COVER("封面", "大圆角高清专辑封面"),
        VINYL("唱片", "黑胶唱盘随播放匀速旋转"),
        CASSETTE("磁带", "复古磁带双卷轴同步转动"),
        PARTICLES("粒子", "随音频能量实时律动的声场粒子");

        companion object {
            fun fromName(name: String?): VisualStyle =
                entries.firstOrNull { it.name == name } ?: COVER
        }
    }

    private var currentTaskId = 0L
    private var sortOrder = DownloadManager.SeasonSortOrder.DEFAULT
    private var playMode = DownloadManager.LocalPlayMode.SEASON_SEQUENCE
    private var visualStyle = VisualStyle.COVER

    // ── 在线后台播放模式 ──
    private var onlineLaunch: OnlineAudioLaunch? = null
    private var isOnlineMode = false
    private val onlineQueue = ArrayList<UgcEpisode>()
    private val onlineSourceQueue = ArrayList<UgcEpisode>()
    private var currentOnlineEpisode: UgcEpisode? = null
    private var onlineGeneration = 0
    private var onlineRequest: BiliRequest? = null
    private var pendingOnlineStartMs = 0L

    private val playlist = ArrayList<DownloadManager.Task>()
    private var autoSavePlaylist = false

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    // ── 服务绑定：播放器与队列的唯一归属在 AudioPlaybackService ──
    private var playbackEngine: AudioPlaybackEngine? = null
    private var serviceBound = false
    private var pendingLaunchApplied = false
    /** 从通知栏进入：只附着当前播放，不发起新请求。 */
    private var attachOnly = false
    /** 正在提交启动请求：此期间引擎快照不得反向覆盖 Intent 目标。 */
    private var submittingLaunch = false
    private var lastBoundTrackKey = ""
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as? AudioPlaybackService.LocalBinder ?: return
            serviceBound = true
            attachEngine(binder.engine())
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            serviceBound = false
            playbackEngine = null
        }
    }

    /** Activity 不再自己持有播放器，统一指向引擎实例。 */
    private val exoPlayer: ExoPlayer? get() = playbackEngine?.player
    private var playerListener: Player.Listener? = null
    private var volumeFadeAnimator: ValueAnimator? = null
    private var userWantsPlayback = true
    private var activityResumed = false
    private var isSeeking = false
    private var seekWasPlaying = false

    /** 实时音频能量电平（0f..1f），由 Media3 AudioProcessor 回调更新给粒子视觉层。 */
    @Volatile
    private var audioLevelSmooth = 0f
    @Volatile
    private var audioBassSmooth = 0f

    // ── UI 引用 ──
    private lateinit var rootLayout: FrameLayout
    private lateinit var stageContainer: FrameLayout
    private lateinit var stageHost: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var styleBadgeView: TextView
    private lateinit var seekBar: AudioSeekBar
    private lateinit var curTimeView: TextView
    private lateinit var totalTimeView: TextView
    private lateinit var modeBtn: FrameLayout
    private lateinit var modeIcon: PlayModeIconView
    private lateinit var playBtn: PlayControlButton
    private var prevBtn: SkipButton? = null
    private var nextBtn: SkipButton? = null
    private var modeHintRevertRunnable: Runnable? = null
    private var activeEqualizer: EqualizerBarsView? = null

    // 当前风格舞台子视图引用（用于控制旋转/暂停/粒子状态）
    private var vinylDiscView: VinylDiscView? = null
    private var cassetteView: CassetteDeckView? = null
    private var particleView: BeatParticleView? = null

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressUi()
            val player = exoPlayer
            if (activityResumed && player != null && (player.isPlaying || player.playWhenReady)) {
                handler.postDelayed(this, 200L)
            }
        }
    }

    /** 能量采样定时器：与进度刷新解耦，保证粒子响应足够细腻。 */
    private val energyRunnable = object : Runnable {
        override fun run() {
            sampleAudioEnergy()
            val player = exoPlayer
            if (activityResumed && player != null && (player.isPlaying || player.playWhenReady)) {
                handler.postDelayed(this, 60L)
            }
        }
    }

    /** 从引擎取实时能量并做快攻慢放平滑，供粒子与音柱使用。 */
    private fun sampleAudioEnergy() {
        val engine = playbackEngine
        val playing = engine?.isPlaying() == true
        val targetLevel = if (playing) engine.energyOverall.coerceIn(0f, 1f) else 0f
        val targetBass = if (playing) engine.energyBass.coerceIn(0f, 1f) else 0f
        // 上冲快、回落慢
        audioLevelSmooth = if (targetLevel > audioLevelSmooth) {
            audioLevelSmooth + (targetLevel - audioLevelSmooth) * 0.55f
        } else {
            audioLevelSmooth + (targetLevel - audioLevelSmooth) * 0.12f
        }
        audioBassSmooth = if (targetBass > audioBassSmooth) {
            audioBassSmooth + (targetBass - audioBassSmooth) * 0.55f
        } else {
            audioBassSmooth + (targetBass - audioBassSmooth) * 0.12f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Ds.applySystemBars(this)
        window.setBackgroundDrawable(ColorDrawable(Ds.colors.bg))
        LayoutMode.applyScreenOrientation(this)
        DownloadManager.init(this)

        currentTaskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        sortOrder = runCatching {
            DownloadManager.SeasonSortOrder.valueOf(intent.getStringExtra(EXTRA_SORT_ORDER).orEmpty())
        }.getOrDefault(DownloadManager.SeasonSortOrder.DEFAULT)
        onlineLaunch = OnlineAudioLauncher.read(intent)
        isOnlineMode = onlineLaunch != null
        // 从通知栏进入：不提交新请求，等绑定引擎后直接展示当前播放
        attachOnly = intent.getBooleanExtra(EXTRA_ATTACH_ONLY, false)

        autoSavePlaylist = prefs.getBoolean(KEY_AUTO_SAVE_PLAYLIST, false)
        visualStyle = VisualStyle.fromName(prefs.getString(KEY_VISUAL_STYLE, VisualStyle.COVER.name))
        playMode = runCatching {
            DownloadManager.LocalPlayMode.valueOf(prefs.getString(KEY_PLAY_MODE, "").orEmpty())
        }.getOrDefault(DownloadManager.LocalPlayMode.SEASON_SEQUENCE)

        if (!attachOnly && !initPlaylist()) {
            Toast.makeText(
                this,
                if (isOnlineMode) "无法获取在线音频信息" else "本地音频文件不存在或未下载完成",
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        setContentView(buildUi())
        bindCurrentTrackUi(animate = false)
        preparePlayerAndPlay(fadeIn = true)
    }

    private fun initPlaylist(): Boolean {
        if (isOnlineMode) return initOnlineQueue()
        val snap = DownloadManager.snapshot()
        val entry = snap.firstOrNull {
            it.id == currentTaskId && it.type == DownloadManager.TYPE_AUDIO && it.playable()
        } ?: return false

        playlist.clear()
        if (autoSavePlaylist) {
            val savedIds = loadSavedPlaylistIds()
            val restored = DownloadManager.resolveSavedAudioPlaylist(savedIds, snap)
            if (restored.isNotEmpty()) {
                val mergedIds = DownloadManager.mergeAudioPlaylist(restored.map { it.id }, listOf(entry.id))
                val byId = snap.associateBy { it.id }
                mergedIds.mapNotNullTo(playlist) { byId[it] }
                persistPlaylistIfEnabled()
                return playlist.isNotEmpty()
            }
        }

        if (entry.groupId.isNotBlank()) {
            val siblings = snap.filter {
                it.groupId == entry.groupId && it.type == DownloadManager.TYPE_AUDIO && it.playable()
            }
            playlist.addAll(DownloadManager.sortTasks(siblings, sortOrder).ifEmpty { listOf(entry) })
        } else {
            playlist.add(entry)
        }
        persistPlaylistIfEnabled()
        return playlist.isNotEmpty()
    }

    /** 在线模式：入口分集 + 合集队列，固定为合集顺序，不落盘。 */
    private fun initOnlineQueue(): Boolean {
        val launch = onlineLaunch ?: return false
        pendingOnlineStartMs = launch.startPositionMs.coerceAtLeast(0L)
        currentOnlineEpisode = launch.entry
        onlineSourceQueue.clear()
        launch.season?.episodes?.let { onlineSourceQueue.addAll(it) }
        if (onlineSourceQueue.none { it.sameAsEpisode(launch.entry) }) {
            onlineSourceQueue.add(0, launch.entry)
        }
        onlineQueue.clear()
        onlineQueue.addAll(sortOnlineQueue())
        return onlineQueue.isNotEmpty()
    }

    /** 收藏夹队列的 cid 可能为 0，因此优先按 bvid 比较。 */
    private fun UgcEpisode.sameAsEpisode(other: UgcEpisode): Boolean =
        if (bvid.isNotBlank() && other.bvid.isNotBlank()) bvid == other.bvid
        else if (cid > 0L) cid == other.cid
        else aid > 0L && aid == other.aid

    private fun sortOnlineQueue(): List<UgcEpisode> =
        DownloadManager.sortEpisodes(onlineSourceQueue, sortOrder)

    private fun currentOnlineIndex(): Int = onlineQueue.indexOfFirst {
        currentOnlineEpisode?.let { cur -> it.sameAsEpisode(cur) } == true
    }

    private fun loadSavedPlaylistIds(): List<Long> {        val raw = prefs.getString(KEY_SAVED_PLAYLIST_IDS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }
    }

    private fun persistPlaylistIfEnabled() {
        if (!autoSavePlaylist) return
        val serialized = playlist.joinToString(",") { it.id.toString() }
        prefs.edit().putString(KEY_SAVED_PLAYLIST_IDS, serialized).apply()
    }

    private fun currentTask(): DownloadManager.Task? =
        playlist.firstOrNull { it.id == currentTaskId } ?: playlist.firstOrNull()

    // ── 页面布局 ───────────────────────────────────────────────

    private fun buildUi(): View {
        val c = Ds.colors
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(c.bg)
            clipChildren = false
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(20))
            clipChildren = false
        }

        // ── 1. 顶栏（返回 + 标题副标 + 右上角三点更多） ──
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(Ds.backIcon(this, c.text) { closeWithFade() },
            LinearLayout.LayoutParams(dp(42), dp(42)))

        val headMeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        styleBadgeView = TextView(this).apply {
            text = "离线音频 · ${visualStyle.label}"
            textSize = 11f
            typeface = Ds.body(bold = true)
            setTextColor(c.muted)
            gravity = Gravity.CENTER
        }
        headMeta.addView(styleBadgeView)
        topBar.addView(headMeta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val moreBtn = MoreDotsButton(this, c.text).apply {
            contentDescription = "风格与设置"
            isClickable = true
            Ds.bindPressFeedback(this, 0.88f)
            setOnClickListener { showMoreOptionsDialog() }
        }
        topBar.addView(moreBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        col.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 2~5. 主体区：平板宽屏采用左右双栏（左视觉舞台，右歌曲信息+时间轴+播控），手机端保持经典垂直排版 ──
        val isWideTablet = LayoutMode.isWideScreen(this)

        stageContainer = FrameLayout(this).apply {
            clipChildren = false
        }
        stageHost = FrameLayout(this).apply {
            clipChildren = false
        }
        stageContainer.addView(stageHost, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val controlColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isWideTablet) Gravity.CENTER_VERTICAL else Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }

        // ── 3. 歌曲标题 + 作者/信息 ──
        titleView = TextView(this).apply {
            textSize = if (isWideTablet) 22f else 19.5f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        controlColumn.addView(titleView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        artistView = TextView(this).apply {
            textSize = 13.5f
            setTextColor(c.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        controlColumn.addView(artistView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        // ── 4. 控件上方时间轴 ──
        seekBar = AudioSeekBar(this).apply {
            onSeekStart = {
                isSeeking = true
                val p = exoPlayer
                seekWasPlaying = p != null && (p.isPlaying || (p.playWhenReady && userWantsPlayback))
            }
            onSeekChanged = { fraction ->
                val dur = exoPlayer?.duration?.takeIf { it > 0L } ?: currentTask()?.durationMs ?: 0L
                val target = (dur * fraction).toLong()
                curTimeView.text = DownloadUiShared.formatDurationMs(target)
            }
            onSeekEnd = { fraction ->
                val p = exoPlayer
                val dur = p?.duration?.takeIf { it > 0L } ?: currentTask()?.durationMs ?: 0L
                val target = (dur * fraction).toLong().coerceAtLeast(0L)
                p?.seekTo(target)
                curTimeView.text = DownloadUiShared.formatDurationMs(target)
                isSeeking = false
                if (seekWasPlaying && activityResumed && userWantsPlayback) {
                    p?.play()
                }
            }
        }
        controlColumn.addView(seekBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).apply {
            topMargin = dp(if (isWideTablet) 28 else 18)
        })

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        curTimeView = TextView(this).apply {
            text = "00:00"
            textSize = 11.5f
            fontFeatureSettings = "tnum"
            setTextColor(c.muted)
        }
        totalTimeView = TextView(this).apply {
            text = "00:00"
            textSize = 11.5f
            fontFeatureSettings = "tnum"
            setTextColor(c.muted)
        }
        timeRow.addView(curTimeView)
        timeRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        timeRow.addView(totalTimeView)
        controlColumn.addView(timeRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 5. 底部播控区：三级视觉层级（主播放钮 > 切歌 > 辅助工具） ──
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // 左辅：播放模式（无底色纯图标）
        modeBtn = FrameLayout(this).apply {
            background = transparentRipple(c.isDark)
            isClickable = true
            Ds.bindPressFeedback(this, 0.85f)
            setOnClickListener { cyclePlayMode() }
        }
        modeIcon = Ds.playModeIcon(this, playMode, modeTint(playMode)) as PlayModeIconView
        modeBtn.addView(modeIcon, FrameLayout.LayoutParams(
            dp(20), dp(20), Gravity.CENTER))
        controlsRow.addView(modeBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        controlsRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        // 二级：上一首（无底色）
        prevBtn = SkipButton(this, isNext = false, color = c.text).apply {
            contentDescription = "上一首"
            background = transparentRipple(c.isDark)
            isClickable = true
            Ds.bindPressFeedback(this, 0.85f)
            setOnClickListener { playPreviousTrack() }
        }
        controlsRow.addView(prevBtn, LinearLayout.LayoutParams(dp(48), dp(48)))

        // 一级主操作：播放/暂停（唯一实心强调圆盘）
        playBtn = PlayControlButton(this, c.accent, c.onAccent).apply {
            contentDescription = "播放或暂停"
            isClickable = true
            Ds.bindPressFeedback(this, 0.90f)
            setOnClickListener { togglePlayPauseWithFade() }
        }
        controlsRow.addView(playBtn, LinearLayout.LayoutParams(dp(66), dp(66)).apply {
            leftMargin = dp(20); rightMargin = dp(20)
        })

        // 二级：下一首
        nextBtn = SkipButton(this, isNext = true, color = c.text).apply {
            contentDescription = "下一首"
            background = transparentRipple(c.isDark)
            isClickable = true
            Ds.bindPressFeedback(this, 0.85f)
            setOnClickListener { playNextTrack(auto = false) }
        }
        controlsRow.addView(nextBtn, LinearLayout.LayoutParams(dp(48), dp(48)))

        controlsRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        // 右辅：播放列表（与左侧模式按钮对称）
        val listBtn = PlaylistIconButton(this, c.muted).apply {
            contentDescription = "播放列表"
            background = transparentRipple(c.isDark)
            isClickable = true
            Ds.bindPressFeedback(this, 0.85f)
            setOnClickListener { showPlaylistBottomSheet() }
        }
        controlsRow.addView(listBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        controlColumn.addView(controlsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(if (isWideTablet) 24 else 16)
        })

        if (isWideTablet) {
            val splitRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(12), dp(24), dp(12))
                clipChildren = false
                clipToPadding = false
            }
            splitRow.addView(stageContainer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.05f))
            splitRow.addView(controlColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.95f).apply {
                leftMargin = dp(36)
            })
            col.addView(splitRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            col.addView(stageContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(12); bottomMargin = dp(16)
            })
            col.addView(controlColumn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        rootLayout.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        updateModeButtonUi()
        updateSkipButtonsState()
        return rootLayout
    }

    /** 纯水波纹遮罩：常态 100% 透明，仅按下时出现圆形光斑。 */
    private fun transparentRipple(isDark: Boolean): Drawable {
        val color = if (isDark) 0x1FFFFFFF.toInt() else 0x1418191C.toInt()
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        return RippleDrawable(ColorStateList.valueOf(color), null, mask)
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun circularRipple(fill: Int, isDark: Boolean): Drawable {
        val color = if (isDark) 0x22FFFFFF.toInt() else 0x1818191C.toInt()
        val content = Ds.circle(fill)
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        return RippleDrawable(ColorStateList.valueOf(color), content, mask)
    }

    // ── 中央视觉舞台渲染与切歌过渡动画 ──────────────────────────────

    private fun bindCurrentTrackUi(animate: Boolean, slideDir: Int = 1) {
        if (isOnlineMode) {
            val ep = currentOnlineEpisode ?: return
            titleView.text = ep.title.ifBlank { "未命名音频" }
            val meta = listOfNotNull(
                onlineLaunch?.season?.title?.takeIf { it.isNotBlank() },
                DownloadUiShared.formatDurationMs(ep.duration.toLong() * 1000L)
            )
            artistView.text = meta.joinToString(" · ")
            totalTimeView.text = DownloadUiShared.formatDurationMs(ep.duration.toLong() * 1000L)
            styleBadgeView.text = "在线音频 · ${visualStyle.label}"
            swapStage(animate, slideDir) { createVisualStageView(null) }
            return
        }
        val task = currentTask() ?: return
        titleView.text = task.title.ifBlank { "未命名音频" }
        val meta = buildList {
            if (task.owner.isNotBlank()) add(task.owner)
            if (task.groupTitle.isNotBlank()) add(task.groupTitle)
            add(DownloadUiShared.formatBytes(DownloadManager.taskDiskBytes(task)))
        }
        artistView.text = meta.joinToString(" · ")
        totalTimeView.text = DownloadUiShared.formatDurationMs(task.durationMs)
        styleBadgeView.text = "离线音频 · ${visualStyle.label}"
        swapStage(animate, slideDir) { createVisualStageView(task) }
    }

    /** 舞台切换动画：统一承载本地与在线两种来源。 */
    private inline fun swapStage(
        animate: Boolean,
        slideDir: Int,
        crossinline build: () -> View
    ) {
        if (!animate || stageHost.childCount == 0 || MotionTokens.isReduced(this)) {
            stageHost.removeAllViews()
            stageHost.addView(build(), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            return
        }
        val newStage = build()
        val outgoing = stageHost.getChildAt(0)
        val widthPx = stageHost.width.takeIf { it > 0 } ?: dp(280)
        val travel = widthPx * 0.28f * slideDir

        newStage.alpha = 0f
        newStage.translationX = travel
        newStage.scaleX = 0.94f
        newStage.scaleY = 0.94f
        stageHost.addView(newStage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        outgoing?.animate()?.cancel()
        outgoing?.animate()
            ?.alpha(0f)
            ?.translationX(-travel)
            ?.scaleX(0.92f)
            ?.scaleY(0.92f)
            ?.setDuration(260L)
            ?.setInterpolator(MotionTokens.easeOut)
            ?.withEndAction { stageHost.removeView(outgoing) }
            ?.start()

        newStage.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300L)
            .setInterpolator(MotionTokens.easeOut)
            .start()
    }


    /** 统一舞台封面加载：本地任务优先本地文件，在线模式用分集封面。 */
    private fun loadStageCover(iv: ImageView, task: DownloadManager.Task?, onlineUrl: String) {
        if (task != null) {
            DownloadUiShared.loadTaskCover(iv, task)
            return
        }
        if (onlineUrl.isNotBlank()) {
            CoverLoader.load(iv, onlineUrl, cropThumb = false)
        } else {
            CoverLoader.clear(iv)
        }
    }

    private fun createVisualStageView(task: DownloadManager.Task?): View {
        vinylDiscView = null
        cassetteView = null
        particleView = null
        val isPlayingNow = exoPlayer?.isPlaying == true || (userWantsPlayback && activityResumed)
        val onlineEp = if (isOnlineMode) currentOnlineEpisode else null
        val coverUrl = onlineEp?.cover.orEmpty()

        return when (visualStyle) {
            VisualStyle.COVER -> buildCoverStage(task, coverUrl)
            VisualStyle.VINYL -> {
                val disc = VinylDiscView(this, task, coverUrl).apply { setSpinning(isPlayingNow) }
                vinylDiscView = disc
                FrameLayout(this).apply {
                    clipChildren = false
                    addView(disc, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
            }
            VisualStyle.CASSETTE -> {
                val deck = CassetteDeckView(
                    this, task,
                    onlineTitle = onlineEp?.title.orEmpty(),
                    onlineCoverUrl = coverUrl
                ).apply { setSpinning(isPlayingNow) }
                cassetteView = deck
                FrameLayout(this).apply {
                    addView(deck, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
            }
            VisualStyle.PARTICLES -> {
                val pv = BeatParticleView(this) { audioLevelSmooth to audioBassSmooth }.apply {
                    setActive(isPlayingNow)
                }
                particleView = pv
                FrameLayout(this).apply {
                    addView(pv, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
            }
        }
    }

    /** 封面舞台：按封面真实宽高比自适应，完整展示不裁切。 */
    private fun buildCoverStage(task: DownloadManager.Task?, onlineCoverUrl: String): View {
        val c = Ds.colors
        val wrapper = FrameLayout(this)
        val stageW = (stageHost.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(36)))
            .coerceAtLeast(dp(200))
        val stageH = (stageHost.height.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels * 0.44f).toInt())
            .coerceAtLeast(dp(160))
        val defaultW = stageW
        val defaultH = (defaultW * 9f / 16f).toInt().coerceAtMost(stageH)
        val card = FrameLayout(this).apply {
            background = Ds.card(this@AudioPlayerActivity, c.cover, c.lineSoft, Ds.Radius.card)
            Ds.clipRounded(this, Ds.Radius.card)
        }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        // 舞台封面按大尺寸加载，避免 120dp 缩略图放大后发糊
        if (task != null) {
            val localPath = task.coverPath
            val remoteUrl = task.coverUrl
            if (localPath.isNotBlank() && File(localPath).exists()) {
                CoverLoader.loadLocal(iv, localPath, dp(360)) { target ->
                    if (remoteUrl.isNotBlank()) CoverLoader.load(target, remoteUrl, cropThumb = false)
                }
            } else if (remoteUrl.isNotBlank()) {
                CoverLoader.load(iv, remoteUrl, cropThumb = false)
            } else {
                CoverLoader.clear(iv)
            }
        } else if (onlineCoverUrl.isNotBlank()) {
            CoverLoader.load(iv, onlineCoverUrl, cropThumb = false)
        } else {
            CoverLoader.clear(iv)
        }
        card.addView(iv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        wrapper.addView(card, FrameLayout.LayoutParams(defaultW, defaultH, Gravity.CENTER))

        // 拿到真实图片尺寸后按宽高比重排，保证完整显示
        iv.post {
            val d = iv.drawable ?: return@post
            val iw = d.intrinsicWidth
            val ih = d.intrinsicHeight
            if (iw <= 0 || ih <= 0 || !iv.isAttachedToWindow) return@post
            val aspect = (iw.toFloat() / ih.toFloat()).coerceIn(0.65f, 2.2f)
            var targetW = stageW
            var targetH = (targetW / aspect).toInt()
            if (targetH > stageH) {
                targetH = stageH
                targetW = (targetH * aspect).toInt()
            }
            targetW = targetW.coerceAtLeast(dp(160))
            targetH = targetH.coerceAtLeast(dp(120))
            val lp = card.layoutParams as FrameLayout.LayoutParams
            if (lp.width != targetW || lp.height != targetH) {
                lp.width = targetW
                lp.height = targetH
                card.layoutParams = lp
                card.invalidateOutline()
            }
        }
        return wrapper
    }


    private fun syncStageMotion(playing: Boolean) {
        vinylDiscView?.setSpinning(playing)
        cassetteView?.setSpinning(playing)
        particleView?.setActive(playing)
        playBtn.setPlaying(playing)
    }

    // ── 右上角三点弹窗（舞台风格 + 播放设置一屏直出） ──────────

    /**
     * 「更多」浮层：轻量一屏直出式微浮层。
     * 取消三页钻取，一屏展示「舞台风格 2×2 网格 + 播放设置」，单层容器无嵌套边框。
     */
    private fun showMoreOptionsDialog() {
        val c = Ds.colors
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.attributes?.gravity = Gravity.TOP or Gravity.END
        dialog.window?.setWindowAnimations(0)
        dialog.window?.attributes?.dimAmount = 0.22f

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@AudioPlayerActivity, c.surface, c.lineSoft, Ds.Radius.card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            elevation = dp(12).toFloat()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadow = if (c.isDark) 0x80000000.toInt() else 0x2618191C
            card.outlineAmbientShadowColor = shadow
            card.outlineSpotShadowColor = shadow
        }

        // ── 段一：舞台风格 ──
        card.addView(TextView(this).apply {
            text = "舞台风格"
            textSize = 11.5f
            typeface = Ds.body(bold = true)
            setTextColor(c.muted)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        val entries = VisualStyle.entries
        for (row in 0 until 2) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, if (row == 0) dp(8) else 0)
            }
            for (col in 0 until 2) {
                val index = row * 2 + col
                val style = entries.getOrNull(index) ?: break
                val selected = visualStyle == style
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), 0, dp(10), 0)
                    minimumHeight = dp(64)
                    background = if (selected) {
                        Ds.card(this@AudioPlayerActivity, c.accentSoft, c.accent, Ds.Radius.control, dp(2))
                    } else {
                        Ds.card(this@AudioPlayerActivity, c.surfaceRaised, null, Ds.Radius.control, 0)
                    }
                    isClickable = true
                    Ds.bindPressFeedback(this, 0.96f)
                    setOnClickListener {
                        visualStyle = style
                        prefs.edit().putString(KEY_VISUAL_STYLE, style.name).apply()
                        bindCurrentTrackUi(animate = true, slideDir = 1)
                        dialog.dismiss()
                    }
                }
                cell.addView(StyleGlyphView(this, style, if (selected) c.accent else c.muted),
                    LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
                cell.addView(TextView(this).apply {
                    text = style.label
                    textSize = 13.5f
                    typeface = Ds.body(bold = true)
                    setTextColor(if (selected) c.accent else c.text)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                rowView.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (col == 0) rightMargin = dp(8)
                })
            }
            card.addView(rowView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // ── 分隔 ──
        card.addView(View(this).apply { setBackgroundColor(c.lineSoft) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)).apply {
                topMargin = dp(12); bottomMargin = dp(12)
            })

        // ── 段二：播放设置 ──
        val settingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46)
        }
        settingRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@AudioPlayerActivity).apply {
                text = "自动保存播放列表"
                textSize = 13.5f
                typeface = Ds.body(bold = true)
                setTextColor(c.text)
            })
            addView(TextView(this@AudioPlayerActivity).apply {
                text = "下次打开时恢复当前队列"
                textSize = 11f
                setTextColor(c.muted)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(10)
        })
        settingRow.addView(CapsuleSwitch(this, autoSavePlaylist) { on ->
            autoSavePlaylist = on
            prefs.edit().putBoolean(KEY_AUTO_SAVE_PLAYLIST, on).apply()
            if (on) {
                persistPlaylistIfEnabled()
            } else {
                prefs.edit().remove(KEY_SAVED_PLAYLIST_IDS).apply()
            }
        }, LinearLayout.LayoutParams(dp(42), dp(24)))
        card.addView(settingRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val wrapper = FrameLayout(this).apply {
            setPadding(dp(14), dp(54), dp(14), dp(14))
            addView(card)
        }
        dialog.setContentView(wrapper)
        dialog.show()
        val targetW = minOf(dp(276), (resources.displayMetrics.widthPixels * 0.76f).toInt())
        dialog.window?.setLayout(targetW, WindowManager.LayoutParams.WRAP_CONTENT)
        if (!MotionTokens.isReduced(this)) {
            card.alpha = 0f
            card.scaleX = 0.88f
            card.scaleY = 0.88f
            card.translationY = -dp(6).toFloat()
            card.pivotX = targetW.toFloat()
            card.pivotY = 0f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(180L).setInterpolator(MotionTokens.easeOut).start()
        }
    }

    /** 风格卡片上的极简线框图标：封面 / 唱片 / 磁带 / 粒子。 */
    private inner class StyleGlyphView(
        context: Context,
        private val style: VisualStyle,
        private val tint: Int
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = dp(1.5f).toFloat()
        }
        private val path = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = tint
            val w = width.toFloat()
            val h = height.toFloat()
            path.rewind()
            when (style) {
                VisualStyle.COVER -> {
                    rect.set(w * 0.14f, h * 0.18f, w * 0.86f, h * 0.82f)
                    canvas.drawRoundRect(rect, w * 0.14f, w * 0.14f, paint)
                    path.moveTo(w * 0.24f, h * 0.68f)
                    path.lineTo(w * 0.42f, h * 0.44f)
                    path.lineTo(w * 0.58f, h * 0.66f)
                    path.lineTo(w * 0.68f, h * 0.54f)
                    path.lineTo(w * 0.78f, h * 0.68f)
                    canvas.drawPath(path, paint)
                }
                VisualStyle.VINYL -> {
                    canvas.drawCircle(w * 0.5f, h * 0.5f, w * 0.35f, paint)
                    canvas.drawCircle(w * 0.5f, h * 0.5f, w * 0.17f, paint)
                    canvas.drawCircle(w * 0.5f, h * 0.5f, w * 0.05f, paint)
                }
                VisualStyle.CASSETTE -> {
                    rect.set(w * 0.12f, h * 0.26f, w * 0.88f, h * 0.74f)
                    canvas.drawRoundRect(rect, w * 0.1f, w * 0.1f, paint)
                    canvas.drawCircle(w * 0.34f, h * 0.5f, w * 0.1f, paint)
                    canvas.drawCircle(w * 0.66f, h * 0.5f, w * 0.1f, paint)
                }
                else -> {
                    canvas.drawCircle(w * 0.28f, h * 0.32f, w * 0.11f, paint)
                    canvas.drawCircle(w * 0.68f, h * 0.26f, w * 0.16f, paint)
                    canvas.drawCircle(w * 0.5f, h * 0.7f, w * 0.09f, paint)
                    canvas.drawCircle(w * 0.8f, h * 0.66f, w * 0.12f, paint)
                }
            }
        }
    }

    private fun showPlaylistBottomSheet() {
        val c = Ds.colors
        val isWide = LayoutMode.isWideScreen(this)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)

        // 平板端右侧精巧悬浮抽屉：克制宽度（300~336dp，不超过屏宽 34%），留四周安全呼吸边距
        val drawerWidthPx = dp(328).coerceAtMost((resources.displayMetrics.widthPixels * 0.34f).toInt().coerceAtLeast(dp(292)))

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (isWide) {
                background = Ds.card(this@AudioPlayerActivity, c.surface, c.lineSoft, 20, Ds.hairline(this@AudioPlayerActivity))
                elevation = dp(12).toFloat()
                setPadding(dp(16), dp(14), dp(16), dp(14))
            } else {
                background = Ds.card(this@AudioPlayerActivity, c.surface, c.lineSoft, Ds.Radius.dialog)
                setPadding(dp(18), dp(16), dp(18), dp(16))
            }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headCount = TextView(this).apply {
            textSize = if (isWide) 15f else 16.5f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
        }
        head.addView(headCount, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        var dismissWithAnim: () -> Unit = { dialog.dismiss() }
        var currentRowView: View? = null

        fun rebuildList() {
            listContainer.removeAllViews()
            currentRowView = null
            activeEqualizer?.setAnimating(false)
            activeEqualizer = null
            val rowGap = dp(if (isWide) 6 else 8)
            if (isOnlineMode) {
                headCount.text = "播放列表（${onlineQueue.size}）"
                onlineQueue.forEachIndexed { idx, ep ->
                    val isCurrent = currentOnlineEpisode?.sameAsEpisode(ep) == true
                    val row = buildQueueRow(
                        index = idx,
                        title = ep.title.ifBlank { "未命名音频" },
                        subtitle = DownloadUiShared.formatDurationMs(ep.duration.toLong() * 1000L),
                        isCurrent = isCurrent,
                        showRemove = false,
                        compact = isWide,
                        onRemove = null
                    ) {
                        dismissWithAnim()
                        if (!isCurrent) {
                            switchOnlineEpisode(ep, slideDir = if (idx > currentOnlineIndex()) 1 else -1)
                        }
                    }
                    if (isCurrent) currentRowView = row
                    listContainer.addView(
                        row,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = rowGap }
                    )
                }
                return
            }
            headCount.text = "播放列表（${playlist.size}）"
            playlist.forEachIndexed { idx, ep ->
                val isCurrent = ep.id == currentTaskId
                val row = buildQueueRow(
                    index = idx,
                    title = ep.title.ifBlank { "未命名音频" },
                    subtitle = listOfNotNull(
                        ep.owner.takeIf { it.isNotBlank() },
                        DownloadUiShared.formatDurationMs(ep.durationMs)
                    ).joinToString(" · "),
                    isCurrent = isCurrent,
                    showRemove = playlist.size > 1 && !isCurrent,
                    compact = isWide,
                    onRemove = {
                        playlist.removeAll { it.id == ep.id }
                        persistPlaylistIfEnabled()
                        rebuildList()
                    }
                ) {
                    dismissWithAnim()
                    if (!isCurrent) {
                        switchToTask(
                            ep.id,
                            slideDir = if (idx > playlist.indexOfFirst { it.id == currentTaskId }) 1 else -1
                        )
                    }
                }
                if (isCurrent) currentRowView = row
                listContainer.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = rowGap }
                )
            }
        }

        // 在线模式：原「添加」位置换成合集排序；本地模式保留添加
        val actionChip: View = if (isOnlineMode) {
            buildSortChip { rebuildList() }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = Ds.pill(this@AudioPlayerActivity, c.accentSoft)
                setPadding(dp(11), dp(6), dp(12), dp(6))
                isClickable = true
                Ds.bindPressFeedback(this, 0.94f)
                setOnClickListener {
                    dismissWithAnim()
                    showAddAudioPickerSheet()
                }
                addView(PlusIconButton(this@AudioPlayerActivity, c.accent),
                    LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(4) })
                addView(TextView(this@AudioPlayerActivity).apply {
                    text = "添加"
                    textSize = 12.5f
                    typeface = Ds.body(bold = true)
                    setTextColor(c.accent)
                })
            }
        }
        head.addView(actionChip)
        sheet.addView(head, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        if (isWide) {
            sheet.addView(View(this).apply { setBackgroundColor(c.lineSoft) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)).apply {
                    bottomMargin = dp(10)
                })
        }

        rebuildList()

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listContainer)
        }
        if (isWide) {
            // 平板右侧悬浮面板：高度克制并上下留白、四周圆角，从右向左优雅滑入，且自动滚动定位到正在播放项
            sheet.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            val overlay = FrameLayout(this).apply {
                setBackgroundColor(0x3D000000)
                alpha = 0f
                isClickable = true
            }
            val drawerMargin = dp(14)
            val drawerMaxH = (resources.displayMetrics.heightPixels * 0.80f).toInt()
            sheet.isClickable = true
            sheet.translationX = (drawerWidthPx + drawerMargin * 2).toFloat()
            overlay.addView(sheet, FrameLayout.LayoutParams(
                drawerWidthPx,
                drawerMaxH,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                rightMargin = drawerMargin
                topMargin = drawerMargin
                bottomMargin = drawerMargin
            })
            var closing = false
            dismissWithAnim = {
                if (!closing) {
                    closing = true
                    overlay.animate().alpha(0f).setDuration(170L).start()
                    sheet.animate().translationX((drawerWidthPx + drawerMargin * 2).toFloat()).setDuration(190L)
                        .setInterpolator(android.view.animation.AccelerateInterpolator())
                        .withEndAction { if (dialog.isShowing) dialog.dismiss() }
                        .start()
                }
            }
            overlay.setOnClickListener { dismissWithAnim() }
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                    dismissWithAnim()
                    true
                } else false
            }
            dialog.setContentView(overlay)
            dialog.setOnDismissListener {
                activeEqualizer?.setAnimating(false)
                activeEqualizer = null
            }
            dialog.window?.apply {
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                setDimAmount(0f)
                attributes = attributes.apply { gravity = Gravity.FILL }
            }
            dialog.show()
            scroll.post {
                val target = currentRowView ?: return@post
                val y = (target.top - scroll.height / 3).coerceAtLeast(0)
                scroll.scrollTo(0, y)
            }
            overlay.animate().alpha(1f).setDuration(190L).start()
            sheet.animate().translationX(0f).setDuration(230L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        } else {
            dialog.window?.attributes?.apply {
                gravity = Gravity.BOTTOM
                windowAnimations = android.R.style.Animation_InputMethod
            }
            val count = if (isOnlineMode) onlineQueue.size else playlist.size
            val maxH = (resources.displayMetrics.heightPixels * 0.52f).toInt()
            sheet.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxH.coerceAtMost(dp(64) * count + dp(24))
            ))
            dialog.setContentView(sheet)
            dialog.setOnDismissListener {
                activeEqualizer?.setAnimating(false)
                activeEqualizer = null
            }
            dialog.show()
            LayoutMode.applyDialogWidth(dialog, this, maxTabletDp = 500, phoneMarginDp = 0)
        }
    }

    /** 播放列表单行：序号或跳动音柱 + 标题/副标题 + 可选移除。 */
    private fun buildQueueRow(
        index: Int,
        title: String,
        subtitle: String,
        isCurrent: Boolean,
        showRemove: Boolean,
        compact: Boolean = false,
        onRemove: (() -> Unit)?,
        onClick: () -> Unit
    ): View {
        val c = Ds.colors
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(
                this@AudioPlayerActivity,
                if (isCurrent) c.accentSoft else c.surfaceRaised,
                if (isCurrent) c.accent else c.lineSoft,
                if (compact) 10 else Ds.Radius.control
            )
            val vPad = dp(if (compact) 8 else 10)
            val hPad = dp(if (compact) 10 else 12)
            setPadding(hPad, vPad, dp(10), vPad)
            isClickable = true
            Ds.bindPressFeedback(this, 0.98f)
            setOnClickListener { onClick() }
        }
        val leadW = dp(if (compact) 22 else 26)
        if (isCurrent) {
            val eq = EqualizerBarsView(
                this,
                exoPlayer?.isPlaying == true || (userWantsPlayback && activityResumed)
            )
            activeEqualizer = eq
            row.addView(eq, LinearLayout.LayoutParams(leadW, dp(if (compact) 17 else 20)))
        } else {
            row.addView(TextView(this).apply {
                text = "${index + 1}"
                textSize = if (compact) 11.5f else 12f
                typeface = Ds.body(bold = true)
                fontFeatureSettings = "tnum"
                setTextColor(c.muted)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(leadW, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = title
            textSize = if (compact) 12.5f else 13.5f
            typeface = Ds.body(bold = isCurrent)
            setTextColor(if (isCurrent) c.accent else c.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            text = subtitle
            textSize = if (compact) 10.5f else 11f
            setTextColor(if (isCurrent) c.accent else c.muted)
            maxLines = 1
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })
        row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(6); rightMargin = dp(6)
        })

        if (showRemove && onRemove != null) {
            row.addView(TextView(this).apply {
                text = "移除"
                textSize = 11f
                setTextColor(c.muted)
                background = Ds.pill(this@AudioPlayerActivity, c.surface, c.lineSoft)
                setPadding(dp(9), dp(4), dp(9), dp(4))
                isClickable = true
                setOnClickListener { onRemove() }
            })
        }
        return row
    }

    /** 合集排序胶囊：图标 + 三态循环文字，点击即切并重排队列。 */
    private fun buildSortChip(onSorted: () -> Unit): View {
        val c = Ds.colors
        val tint = if (sortOrder == DownloadManager.SeasonSortOrder.DEFAULT) c.muted else c.accent
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (sortOrder == DownloadManager.SeasonSortOrder.DEFAULT) {
                Ds.pill(this@AudioPlayerActivity, c.surfaceRaised, c.lineSoft)
            } else {
                Ds.pill(this@AudioPlayerActivity, c.accentSoft, c.accent)
            }
            setPadding(dp(11), dp(6), dp(12), dp(6))
            minimumHeight = dp(30)
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
        }
        val icon = Ds.sortOrderIcon(this, sortOrder, tint) as SortOrderIconView
        val label = TextView(this).apply {
            text = sortOrder.seasonLabel()
            textSize = 12.5f
            typeface = Ds.body(bold = true)
            setTextColor(tint)
        }
        chip.addView(icon, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(4) })
        chip.addView(label)
        chip.setOnClickListener {
            sortOrder = sortOrder.nextOrder()
            val newTint =
                if (sortOrder == DownloadManager.SeasonSortOrder.DEFAULT) Ds.colors.muted else Ds.colors.accent
            icon.update(sortOrder, newTint)
            label.text = sortOrder.seasonLabel()
            label.setTextColor(newTint)
            chip.background = if (sortOrder == DownloadManager.SeasonSortOrder.DEFAULT) {
                Ds.pill(this, Ds.colors.surfaceRaised, Ds.colors.lineSoft)
            } else {
                Ds.pill(this, Ds.colors.accentSoft, Ds.colors.accent)
            }
            onlineQueue.clear()
            onlineQueue.addAll(sortOnlineQueue())
            updateSkipButtonsState()
            onSorted()
        }
        return chip
    }

    private fun showAddAudioPickerSheet() {
        val c = Ds.colors
        val existingIds = playlist.mapTo(HashSet()) { it.id }
        val candidates = DownloadManager.snapshot()
            .filter { it.type == DownloadManager.TYPE_AUDIO && it.playable() && it.id !in existingIds }
            .sortedByDescending { it.finishedAt.takeIf { f -> f > 0L } ?: it.createdAt }

        if (candidates.isEmpty()) {
            Toast.makeText(this, "暂无其他已下载音频可添加", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedIds = LinkedHashSet<Long>()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.attributes?.gravity = Gravity.BOTTOM

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@AudioPlayerActivity, c.surface, c.lineSoft, Ds.Radius.dialog)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        sheet.addView(TextView(this).apply {
            text = "从已下载音频添加到播放列表"
            textSize = 16.5f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val confirmBtn = TextView(this).apply {
            text = "请选择要添加的音频"
            textSize = 14.5f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            setTextColor(c.onAccent)
            background = Ds.pill(this@AudioPlayerActivity, c.accent)
            alpha = 0.5f
            isClickable = true
            Ds.bindPressFeedback(this, 0.96f)
        }

        fun updateConfirm() {
            val n = selectedIds.size
            confirmBtn.text = if (n == 0) "请选择要添加的音频" else "添加 $n 首到播放列表"
            confirmBtn.alpha = if (n == 0) 0.5f else 1f
        }

        candidates.forEach { task ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ds.card(this@AudioPlayerActivity, c.surfaceRaised, c.lineSoft, Ds.Radius.control)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
            }
            val cb = CheckBox(this).apply {
                isChecked = false
                isClickable = false
                isFocusable = false
            }
            row.addView(cb, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(8) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = task.title.ifBlank { "未命名音频" }
                textSize = 13.5f
                typeface = Ds.body(bold = true)
                setTextColor(c.text)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            info.addView(TextView(this).apply {
                val sub = listOfNotNull(
                    task.owner.takeIf { it.isNotBlank() },
                    DownloadUiShared.formatDurationMs(task.durationMs)
                ).joinToString(" · ")
                text = sub
                textSize = 11f
                setTextColor(c.muted)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            })
            row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                val nowChecked = task.id !in selectedIds
                if (nowChecked) selectedIds.add(task.id) else selectedIds.remove(task.id)
                cb.isChecked = nowChecked
                row.background = Ds.card(
                    this@AudioPlayerActivity,
                    if (nowChecked) c.accentSoft else c.surfaceRaised,
                    if (nowChecked) c.accent else c.lineSoft,
                    Ds.Radius.control
                )
                updateConfirm()
            }
            listCol.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(listCol)
        }
        val maxH = (resources.displayMetrics.heightPixels * 0.48f).toInt()
        sheet.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxH.coerceAtMost(dp(64) * candidates.size + dp(16))
        ))

        confirmBtn.setOnClickListener {
            if (selectedIds.isEmpty()) return@setOnClickListener
            val merged = DownloadManager.mergeAudioPlaylist(playlist.map { it.id }, selectedIds.toList())
            val byId = DownloadManager.snapshot().associateBy { it.id }
            playlist.clear()
            merged.mapNotNullTo(playlist) { byId[it] }
            persistPlaylistIfEnabled()
            dialog.dismiss()
            Toast.makeText(this, "已添加 ${selectedIds.size} 首到播放列表", Toast.LENGTH_SHORT).show()
            showPlaylistBottomSheet()
        }
        sheet.addView(confirmBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(12)
        })

        dialog.setContentView(sheet)
        dialog.show()
        LayoutMode.applyDialogWidth(dialog, this, maxTabletDp = 500, phoneMarginDp = 0)
    }

    // ── 播放控制、渐入渐出与切歌 ──────────────────────────────────

    private fun cyclePlayMode() {
        playMode = playMode.nextMode()
        prefs.edit().putString(KEY_PLAY_MODE, playMode.name).apply()
        playbackEngine?.setPlayMode(playMode)
        updateModeButtonUi()
        window?.decorView?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        showModeHint(playMode.label())
        updateSkipButtonsState()
    }

    private fun updateModeButtonUi() {
        modeIcon.update(playMode, modeTint(playMode))
        // 轻快弹簧脉冲：先复位再缩放，避免连点时停在中间态
        modeIcon.animate().cancel()
        modeIcon.scaleX = 1f
        modeIcon.scaleY = 1f
        modeIcon.animate().scaleX(0.78f).scaleY(0.78f)
            .setDuration(70L).setInterpolator(MotionTokens.easeOut)
            .withEndAction {
                modeIcon.animate().scaleX(1f).scaleY(1f)
                    .setDuration(MotionTokens.smallMs).setInterpolator(MotionTokens.easeOut).start()
            }.start()
    }

    private fun modeTint(mode: DownloadManager.LocalPlayMode): Int =
        if (mode == DownloadManager.LocalPlayMode.SEASON_SEQUENCE) Ds.colors.muted else Ds.colors.accent

    /** 顶栏副标临时接管为模式提示，1.3s 无新点击后自动回落，绝不排队叠加。 */
    private fun showModeHint(label: String) {
        val badge = styleBadgeView ?: return
        modeHintRevertRunnable?.let { handler.removeCallbacks(it) }
        badge.animate().cancel()
        badge.text = "已切换 · $label"
        badge.setTextColor(Ds.colors.accent)
        badge.alpha = 1f
        if (!MotionTokens.isReduced(this)) {
            badge.alpha = 0.35f
            badge.translationY = dp(3).toFloat()
            badge.animate().alpha(1f).translationY(0f)
                .setDuration(100L).setInterpolator(MotionTokens.easeOut).start()
        }
        val revert = Runnable {
            val view = styleBadgeView ?: return@Runnable
            view.animate().cancel()
            view.text = "离线音频 · ${visualStyle.label}"
            view.setTextColor(Ds.colors.muted)
            if (!MotionTokens.isReduced(this)) {
                view.animate().alpha(0.35f).setDuration(80L).setInterpolator(MotionTokens.easeOut)
                    .withEndAction {
                        view.alpha = 1f
                        view.animate().alpha(1f).setDuration(80L).start()
                    }.start()
            }
        }
        modeHintRevertRunnable = revert
        handler.postDelayed(revert, 1300L)
    }

    private fun switchToTask(targetId: Long, slideDir: Int) {
        if (targetId == currentTaskId) return
        val exists = playlist.any { it.id == targetId && it.playable() }
        if (!exists) return
        currentTaskId = targetId
        userWantsPlayback = true
        bindCurrentTrackUi(animate = true, slideDir = slideDir)
        playbackEngine?.selectLocal(targetId)
        updateSkipButtonsState()
    }

    /** 顺序播放下的首/尾曲禁用切歌键；其余模式均可回绕。 */
    private fun updateSkipButtonsState() {
        val engine = playbackEngine
        if (engine != null) {
            prevBtn?.setSkipEnabled(engine.canSkipPrevious())
            nextBtn?.setSkipEnabled(engine.canSkipNext())
            return
        }
        if (isOnlineMode) {
            val idx = currentOnlineIndex()
            val multi = onlineQueue.size > 1
            val sequential = playMode == DownloadManager.LocalPlayMode.SEASON_SEQUENCE
            prevBtn?.setSkipEnabled(multi && (!sequential || idx > 0))
            nextBtn?.setSkipEnabled(multi && (!sequential || (idx >= 0 && idx < onlineQueue.lastIndex)))
            return
        }
        val curIdx = playlist.indexOfFirst { it.id == currentTaskId }
        val multi = playlist.size > 1
        val sequential = playMode == DownloadManager.LocalPlayMode.SEASON_SEQUENCE
        prevBtn?.setSkipEnabled(multi && (!sequential || curIdx > 0))
        nextBtn?.setSkipEnabled(multi && (!sequential || (curIdx >= 0 && curIdx < playlist.lastIndex)))
    }

    private fun playPreviousTrack() {
        playbackEngine?.playPrevious()
        updateSkipButtonsState()
    }

    private fun playNextTrack(auto: Boolean) {
        playbackEngine?.playNext(auto)
        updateSkipButtonsState()
    }

    /** 在线切集：命令交给引擎，UI 由引擎快照回调刷新。 */
    private fun switchOnlineEpisode(target: UgcEpisode, slideDir: Int) {
        if (currentOnlineEpisode?.sameAsEpisode(target) == true) return
        currentOnlineEpisode = target
        userWantsPlayback = true
        bindCurrentTrackUi(animate = true, slideDir = slideDir)
        playbackEngine?.selectOnline(target)
        updateSkipButtonsState()
    }

    /** 播放/暂停：音量淡变已上移到服务，Activity 只发命令。 */
    private fun togglePlayPauseWithFade() {
        val engine = playbackEngine ?: return
        val player = engine.player
        if (player.playbackState == Player.STATE_ENDED) {
            userWantsPlayback = true
            engine.seekTo(0L)
            engine.play()
            syncStageMotion(true)
            return
        }
        if (player.isPlaying || player.playWhenReady) {
            userWantsPlayback = false
            engine.pause()
            syncStageMotion(false)
        } else {
            userWantsPlayback = true
            engine.play()
            syncStageMotion(true)
        }
    }

    private fun fadeVolume(from: Float, to: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        volumeFadeAnimator?.cancel()
        val player = exoPlayer ?: return
        if (MotionTokens.isReduced(this)) {
            player.volume = to
            onEnd?.invoke()
            return
        }
        player.volume = from
        volumeFadeAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                exoPlayer?.volume = v
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd?.invoke()
                }
            })
            start()
        }
    }

    /** 离开音频页：停止播放并退出后台播放状态（切到后台不受影响）。 */
    private fun closeWithFade() {
        playbackEngine?.stopPlayback()
        finish()
    }

    // ── Media3 音频引擎与实时采样分析 ──────────────────────────────

    /**
     * 接入服务持有的播放引擎：队列与播放器均以引擎为准，
     * Activity 只负责 UI 与命令下发。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun attachEngine(engine: AudioPlaybackEngine?) {
        if (engine == null) return
        playbackEngine = engine
        engine.addListener(engineListener)
        // 首次接入：提交本次启动请求（新播放或重新附着当前曲目）
        if (!pendingLaunchApplied) {
            pendingLaunchApplied = true
            if (attachOnly) {
                // 从通知栏进入：直接采用引擎当前状态，不发起新请求
                isOnlineMode = engine.isOnlineMode()
                syncQueuesFromEngine(engine)
                bindCurrentTrackUi(animate = false)
                syncStageMotion(engine.isPlaying())
                updateProgressUi()
            } else {
                // 先标记已提交，避免引擎快照回调把 currentTaskId 覆盖成 0
                submittingLaunch = true
                try {
                    submitLaunchRequest(engine)
                } finally {
                    submittingLaunch = false
                }
            }
        } else {
            syncQueuesFromEngine(engine)
            bindCurrentTrackUi(animate = false)
            syncStageMotion(engine.isPlaying())
            updateProgressUi()
        }
        updateSkipButtonsState()
    }

    private val engineListener = object : EngineListener {
        override fun onSnapshotChanged(snapshot: PlaybackSnapshot) {
            if (isFinishing || isDestroyed) return
            syncQueuesFromEngine(playbackEngine ?: return)
            // 只在曲目真正变化时重建舞台，避免每次快照都重绘导致闪烁
            val trackKey = if (snapshot.isOnline) {
                "online:${snapshot.onlineEpisode?.bvid}:${snapshot.onlineEpisode?.cid}"
            } else "local:${snapshot.localTaskId}"
            if (trackKey != lastBoundTrackKey) {
                lastBoundTrackKey = trackKey
                bindCurrentTrackUi(animate = false)
            }
            updateSkipButtonsState()
            updateProgressUi()
            if (snapshot.error.isNotBlank()) {
                Toast.makeText(this@AudioPlayerActivity, snapshot.error, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isFinishing || isDestroyed) return
            syncStageMotion(isPlaying)
            handler.removeCallbacks(progressRunnable)
            handler.removeCallbacks(energyRunnable)
            if (isPlaying) {
                handler.post(progressRunnable)
                handler.post(energyRunnable)
            } else {
                audioLevelSmooth = 0f
                audioBassSmooth = 0f
            }
        }

        override fun onPlaybackEnded() = Unit
    }

    /** 把引擎的队列同步回 Activity 的展示副本。 */
    private fun syncQueuesFromEngine(engine: AudioPlaybackEngine) {
        // 提交期间引擎队列尚未建立，不能反向覆盖本次 Intent 的目标
        if (submittingLaunch || !pendingLaunchApplied) return
        if (isOnlineMode) {
            onlineQueue.clear()
            onlineQueue.addAll(engine.onlineQueueSnapshot())
            currentOnlineEpisode = engine.currentOnlineEpisode()
        } else {
            playlist.clear()
            playlist.addAll(engine.localQueueSnapshot())
            currentTaskId = engine.currentLocalTaskId()
        }
        sortOrder = engine.getSortOrder()
        playMode = engine.getPlayMode()
    }

    /** 首次接入时提交播放请求：视频交接带起始位置，普通入口从头播。 */
    private fun submitLaunchRequest(engine: AudioPlaybackEngine) {
        val launch = onlineLaunch
        // 必须在任何快照回调覆盖 currentTaskId 之前捕获本次请求的目标
        val requestedTaskId = currentTaskId
        val requestedSort = sortOrder
        val requestedMode = playMode
        if (launch != null) {
            engine.setSortOrder(requestedSort)
            engine.setPlayMode(requestedMode)
            engine.startOnline(launch)
        } else {
            engine.setSortOrder(requestedSort)
            engine.setPlayMode(requestedMode)
            engine.startLocal(requestedTaskId, requestedSort.name, autoSavePlaylist)
        }
        lastBoundTrackKey = if (isOnlineMode) {
            "online:${currentOnlineEpisode?.bvid}:${currentOnlineEpisode?.cid}"
        } else "local:$currentTaskId"
        bindCurrentTrackUi(animate = false)
        updateSkipButtonsState()
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }


    /** 播放源构建已迁到引擎，这里只保证接入完成后有内容可播。 */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun preparePlayerAndPlay(fadeIn: Boolean) {
        playbackEngine ?: return
    }

    private fun playerSpinnerVisible(visible: Boolean) {
        // 在线取流期间复用播放按钮的加载态提示，避免额外控件
        if (::playBtn.isInitialized) playBtn.alpha = if (visible) 0.55f else 1f
    }

    private fun updateProgressUi() {
        val player = exoPlayer ?: return
        if (isSeeking) return
        val dur = player.duration.takeIf { it > 0L }
            ?: currentTask()?.durationMs
            ?: (currentOnlineEpisode?.duration?.toLong()?.times(1000L) ?: 0L)
        val pos = player.currentPosition.coerceAtLeast(0L)
        if (dur > 0L) {
            val fraction = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            seekBar.setFraction(fraction)
            cassetteView?.setProgress(fraction)
            totalTimeView.text = DownloadUiShared.formatDurationMs(dur)
        } else {
            seekBar.setFraction(0f)
        }
        curTimeView.text = DownloadUiShared.formatDurationMs(pos)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (LayoutMode.isTablet) {
            setContentView(buildUi())
            bindCurrentTrackUi(animate = false)
            updateProgressUi()
        }
    }

    override fun onStart() {
        super.onStart()
        // 绑定播放服务：同进程私有 action，取回引擎实例
        val intent = android.content.Intent(this, AudioPlaybackService::class.java)
            .setAction(AudioPlaybackService.ACTION_LOCAL_BIND)
        runCatching { bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE) }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        // 不覆盖通知栏/锁屏设定的播放意图，仅刷新 UI 与进度
        handler.post(progressRunnable)
        handler.post(energyRunnable)
        playbackEngine?.let {
            syncStageMotion(it.isPlaying())
            updateProgressUi()
        }
    }

    override fun onPause() {
        activityResumed = false
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(energyRunnable)
        // 播放交给服务继续，此处不暂停
        super.onPause()
    }

    override fun onStop() {
        if (serviceBound) {
            playbackEngine?.removeListener(engineListener)
            runCatching { unbindService(serviceConnection) }
            serviceBound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        volumeFadeAnimator?.cancel()
        onlineRequest?.cancel()
        onlineRequest = null
        onlineGeneration++
        handler.removeCallbacksAndMessages(null)
        // 播放器与队列由服务持有，Activity 不释放
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 从通知栏点回已有页面：只刷新展示，不重走加载
        if (intent?.getBooleanExtra(EXTRA_ATTACH_ONLY, false) == true) {
            playbackEngine?.let {
                isOnlineMode = it.isOnlineMode()
                syncQueuesFromEngine(it)
                bindCurrentTrackUi(animate = false)
                syncStageMotion(it.isPlaying())
                updateProgressUi()
                updateSkipButtonsState()
            }
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        closeWithFade()
    }

    // ── 自定义图形与动效控件 ────────────────────────────────────

    /** 实时 PCM 能量采样 AudioProcessor（16-bit PCM，无须 RECORD_AUDIO 权限）。 */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    /**
     * 风格 2：黑胶唱片舞台。
     * 静态盘面（渐变 + 沟槽 + 高光弧 + 暗角）与旋转内芯（Label 贴纸 + 封面 + 压模环）分层，
     * 保证环境高光不随盘旋转，符合真实物理。
     */
    private inner class VinylDiscView(
        context: Context,
        task: DownloadManager.Task?,
        private val onlineCoverUrl: String = ""
    ) : FrameLayout(context) {
        private val discSize: Int
        private val spinView: FrameLayout
        private val spinAnimator: ObjectAnimator
        private val toneArm: ToneArmView
        private var platter: VinylPlatterView? = null

        init {
            // 盘面缩小并预留四周空间：外圈投影、以及唱臂（盘径 1.12 倍）都不能被父容器裁剪
            discSize = min(
                resources.displayMetrics.widthPixels - dp(104),
                (resources.displayMetrics.heightPixels * 0.29f).toInt()
            ).coerceAtLeast(dp(170))

            // 静态盘面：光影与沟槽固定不动
            val platterView = VinylPlatterView(context)
            platter = platterView
            addView(platterView, LayoutParams(discSize, discSize, Gravity.CENTER))

            // 旋转层：Label 贴纸 + 封面 + 压模环
            val label = (discSize * 0.83f).toInt()
            val coverSize = (discSize * 0.348f).toInt()
            spinView = FrameLayout(context).apply {
                addView(object : View(context) {
                    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                    }
                    private val rect = RectF()

                    override fun onDraw(canvas: Canvas) {
                        val cx = width / 2f
                        val cy = height / 2f
                        val r = min(cx, cy)
                        // 纸标边沿
                        ringPaint.color = 0xFFF0EDE5.toInt()
                        ringPaint.style = Paint.Style.STROKE
                        ringPaint.strokeWidth = r * 0.07f
                        canvas.drawCircle(cx, cy, r * 0.7725f, ringPaint)
                        // 压边暗槽
                        ringPaint.color = 0xD908090C.toInt()
                        ringPaint.strokeWidth = r * 0.026f
                        canvas.drawCircle(cx, cy, r * 0.810f, ringPaint)
                        // 细环纹
                        ringPaint.color = 0x2E18191C.toInt()
                        ringPaint.strokeWidth = r * 0.012f
                        canvas.drawCircle(cx, cy, r * 0.745f, ringPaint)
                    }
                }, LayoutParams(label, label, Gravity.CENTER))

                val coverCircle = FrameLayout(context).apply {
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                    clipToOutline = true
                }
                val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                loadStageCover(iv, task, onlineCoverUrl)
                coverCircle.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                coverCircle.addView(object : View(context) {
                    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                    }
                    override fun onDraw(canvas: Canvas) {
                        val cx = width / 2f
                        val cy = height / 2f
                        val r = min(cx, cy)
                        // 压模环
                        ringPaint.color = 0x3A000000.toInt()
                        ringPaint.strokeWidth = dp(1.2f).toFloat()
                        canvas.drawCircle(cx, cy, r * 0.392f, ringPaint)
                        ringPaint.color = 0x2EFFFFFF.toInt()
                        ringPaint.strokeWidth = dp(0.8f).toFloat()
                        canvas.drawCircle(cx, cy, r * 0.378f, ringPaint)
                        // 边缘暗角
                        ringPaint.color = 0x22000000.toInt()
                        ringPaint.strokeWidth = dp(1.5f).toFloat()
                        canvas.drawCircle(cx, cy, r * 0.932f, ringPaint)
                    }
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                // 中心轴孔
                addView(object : View(context) {
                    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                    }
                    override fun onDraw(canvas: Canvas) {
                        val cx = width / 2f
                        val cy = height / 2f
                        val r = min(cx, cy)
                        fillPaint.color = 0x66000000.toInt()
                        canvas.drawCircle(cx, cy, r * 0.058f, fillPaint)
                        fillPaint.color = Ds.colors.bg
                        canvas.drawCircle(cx, cy, r * 0.048f, fillPaint)
                    }
                }, LayoutParams((coverSize * 0.34f).toInt(), (coverSize * 0.34f).toInt(), Gravity.CENTER))
                addView(coverCircle, LayoutParams(coverSize, coverSize, Gravity.CENTER))
            }
            addView(spinView, LayoutParams(discSize, discSize, Gravity.CENTER))

            toneArm = ToneArmView(context)
            addView(toneArm, LayoutParams((discSize * 1.12f).toInt(), (discSize * 1.12f).toInt(), Gravity.CENTER))

            spinAnimator = ObjectAnimator.ofFloat(spinView, View.ROTATION, 0f, 360f).apply {
                duration = 14_000L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
        }

        fun setSpinning(spinning: Boolean) {
            toneArm.setEngaged(spinning)
            if (MotionTokens.isReduced(context)) return
            if (spinning) {
                if (!spinAnimator.isStarted) spinAnimator.start()
                else if (spinAnimator.isPaused) spinAnimator.resume()
            } else if (spinAnimator.isRunning) {
                spinAnimator.pause()
            }
        }

        override fun onDetachedFromWindow() {
            spinAnimator.cancel()
            super.onDetachedFromWindow()
        }
    }

    /** 黑胶静态盘面：锥形渐变 + 音轨沟槽 + 曲目分隔带 + 双高光弧 + 边缘暗角。 */
    private inner class VinylPlatterView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val rect = RectF()
        private var sweepShader: SweepGradient? = null
        private var vignetteShader: RadialGradient? = null
        private var bakedSize = 0

        private val sweepColors = intArrayOf(
            0xFF0E0F13.toInt(), 0xFF282C35.toInt(), 0xFF363B47.toInt(), 0xFF1F222A.toInt(),
            0xFF0D0E12.toInt(), 0xFF121419.toInt(), 0xFF2B2F39.toInt(), 0xFF3E4452.toInt(),
            0xFF242730.toInt(), 0xFF0E0F13.toInt(), 0xFF0E0F13.toInt()
        )
        private val sweepPositions = floatArrayOf(
            0f, 0.10f, 0.13f, 0.19f, 0.32f, 0.48f, 0.58f, 0.63f, 0.69f, 0.84f, 1f
        )
        private val trackGaps = floatArrayOf(0.805f, 0.665f, 0.515f)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            bakedSize = min(w, h)
            if (bakedSize <= 0) return
            val cx = w / 2f
            val cy = h / 2f
            sweepShader = SweepGradient(cx, cy, sweepColors, sweepPositions)
            vignetteShader = RadialGradient(
                cx, cy, (bakedSize * 0.5f).coerceAtLeast(1f),
                intArrayOf(0x00000000.toInt(), 0x00000000.toInt(), 0x480A0B0F.toInt(), 0x1408090C.toInt(), 0x7A07080B.toInt()),
                floatArrayOf(0f, 0.82f, 0.90f, 0.94f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            // 留出至少 4dp 给外圈投影，超出会被 View 边界裁切
            val r = (min(cx, cy) - dp(5)).coerceAtLeast(1f)

            // 外围投影
            paint.style = Paint.Style.STROKE
            paint.shader = null
            paint.color = 0x2E000000.toInt()
            paint.strokeWidth = dp(3f).toFloat()
            canvas.drawCircle(cx, cy, r * 1.015f, paint)
            paint.color = 0x14000000.toInt()
            paint.strokeWidth = dp(4f).toFloat()
            canvas.drawCircle(cx, cy, r * 1.028f, paint)

            // 盘体锥形光泽
            paint.style = Paint.Style.FILL
            paint.shader = sweepShader
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = vignetteShader
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null

            // 沟槽与曲目分隔带
            groovePaint.color = 0x26FFFFFF.toInt()
            groovePaint.strokeWidth = dp(0.7f).toFloat()
            canvas.drawCircle(cx, cy, r * 0.972f, groovePaint)
            for ((groupIndex, group) in listOf(0.94f to 0.82f, 0.79f to 0.68f, 0.65f to 0.53f).withIndex()) {
                val (outer, inner) = group
                val steps = 6
                for (i in 0 until steps) {
                    val ratio = outer - (outer - inner) * (i / (steps - 1f))
                    groovePaint.strokeWidth = dp(if (i % 2 == 0) 0.6f else 0.9f).toFloat()
                    groovePaint.color = when (i % 3) {
                        0 -> 0x0DFFFFFF.toInt()
                        1 -> 0x18FFFFFF.toInt()
                        else -> 0x22FFFFFF.toInt()
                    }
                    canvas.drawCircle(cx, cy, r * ratio, groovePaint)
                }
            }
            for (gap in trackGaps) {
                groovePaint.color = 0x6606070A.toInt()
                groovePaint.strokeWidth = dp(2.2f).toFloat()
                canvas.drawCircle(cx, cy, r * gap, groovePaint)
                groovePaint.color = 0x16FFFFFF.toInt()
                groovePaint.strokeWidth = dp(0.6f).toFloat()
                canvas.drawCircle(cx, cy, r * (gap + 0.008f), groovePaint)
                canvas.drawCircle(cx, cy, r * (gap - 0.008f), groovePaint)
            }
            // Run-out 收尾
            groovePaint.color = 0x18FFFFFF.toInt()
            groovePaint.strokeWidth = dp(0.6f).toFloat()
            canvas.drawCircle(cx, cy, r * 0.465f, groovePaint)

            // 双高光弧（固定不随盘转）
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.BUTT
            rect.set(cx - r * 0.73f, cy - r * 0.73f, cx + r * 0.73f, cy + r * 0.73f)
            paint.color = 0x0CE2EAFF.toInt()
            paint.strokeWidth = r * 0.38f
            canvas.drawArc(rect, 202f, 46f, false, paint)
            paint.color = 0x14EAF0FF.toInt()
            paint.strokeWidth = r * 0.34f
            canvas.drawArc(rect, 212f, 26f, false, paint)
            paint.color = 0x1CF2F6FF.toInt()
            paint.strokeWidth = r * 0.32f
            canvas.drawArc(rect, 220f, 10f, false, paint)
            paint.color = 0x0DE2EAFF.toInt()
            paint.strokeWidth = r * 0.34f
            canvas.drawArc(rect, 30f, 30f, false, paint)
            paint.color = 0x14EAF0FF.toInt()
            paint.strokeWidth = r * 0.32f
            canvas.drawArc(rect, 40f, 10f, false, paint)
        }
    }

    /** 唱臂总成：底座、配重、J 形臂管、支点关节、唱头壳与唱针。 */
    private inner class ToneArmView(context: Context) : View(context) {
        private var angle = -23f
        private var anim: ValueAnimator? = null
        private var tremorPhase = 0f
        private var tremorAnimator: ValueAnimator? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rect = RectF()
        private val armPath = Path()
        private val shadowPath = Path()
        private val matrix = android.graphics.Matrix()
        private var baseShader: RadialGradient? = null

        fun setEngaged(engaged: Boolean) {
            val target = if (engaged) -4f else -23f
            anim?.cancel()
            if (MotionTokens.isReduced(context)) {
                angle = target
                invalidate()
                return
            }
            anim = ValueAnimator.ofFloat(angle, target).apply {
                duration = 280L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    angle = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            startTremor(engaged)
        }

        /** 播放时极微幅循轨微颤，仅视觉呼吸。 */
        private fun startTremor(active: Boolean) {
            tremorAnimator?.cancel()
            tremorAnimator = null
            if (!active || MotionTokens.isReduced(context)) {
                tremorPhase = 0f
                invalidate()
                return
            }
            tremorAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
                duration = 2400L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    tremorPhase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            anim?.cancel()
            tremorAnimator?.cancel()
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val px = w * 0.87f
            val py = h * 0.14f
            val radius = (w * 0.088f).coerceAtLeast(1f)
            baseShader = RadialGradient(
                px - radius * 0.35f, py - radius * 0.35f, radius * 1.6f,
                intArrayOf(0xFF3A3E4A.toInt(), 0xFF343844.toInt(), 0xFF16181E.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val px = w * 0.87f
            val py = h * 0.14f
            val tremor = if (tremorPhase == 0f) 0f else (0.25f * kotlin.math.sin(tremorPhase.toDouble())).toFloat()

            // ── 静态：休止架 ──
            paint.style = Paint.Style.FILL
            paint.color = 0xFF2A2E38.toInt()
            rect.set(w * 0.787f, h * 0.298f, w * 0.823f, h * 0.316f)
            canvas.drawRoundRect(rect, dp(2f).toFloat(), dp(2f).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(0.8f).toFloat()
            paint.color = 0x33FFFFFF.toInt()
            canvas.drawRoundRect(rect, dp(2f).toFloat(), dp(2f).toFloat(), paint)

            // ── 静态：万向底座 ──
            val baseR = w * 0.088f
            paint.style = Paint.Style.FILL
            paint.shader = baseShader
            canvas.drawCircle(px, py, baseR, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.2f).toFloat()
            paint.color = 0x28FFFFFF.toInt()
            canvas.drawCircle(px, py, baseR, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xFF121419.toInt()
            canvas.drawCircle(px, py, w * 0.062f, paint)

            // ── 旋转部分（含阴影偏移） ──
            val save = canvas.save()
            canvas.rotate(angle + tremor, px, py)

            armPath.rewind()
            armPath.moveTo(w * 0.895f, h * 0.055f)
            armPath.lineTo(w * 0.870f, h * 0.140f)
            armPath.lineTo(w * 0.790f, h * 0.380f)
            armPath.lineTo(w * 0.695f, h * 0.515f)

            // 投影
            shadowPath.rewind()
            matrix.reset()
            matrix.setTranslate(dp(2.5f).toFloat(), dp(3.5f).toFloat())
            armPath.transform(matrix, shadowPath)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3.2f).toFloat()
            paint.color = 0x42000000.toInt()
            canvas.drawPath(shadowPath, paint)

            // 臂管本体 + 高光芯
            paint.color = 0xFF9AA0AD.toInt()
            canvas.drawPath(armPath, paint)
            paint.strokeWidth = dp(1f).toFloat()
            paint.color = 0x66FFFFFF.toInt()
            canvas.drawPath(armPath, paint)

            // 配重块
            val cwX = w * 0.892f
            val cwY = h * 0.065f
            val cwW = w * 0.044f
            val cwH = h * 0.078f
            paint.style = Paint.Style.FILL
            paint.color = 0xFF5B606E.toInt()
            rect.set(cwX - cwH * 0.5f, cwY - cwW * 0.5f, cwX + cwH * 0.5f, cwY + cwW * 0.5f)
            canvas.drawRoundRect(rect, dp(3f).toFloat(), dp(3f).toFloat(), paint)
            paint.color = 0xFFC8CDD8.toInt()
            rect.set(cwX - cwH * 0.18f, cwY - cwW * 0.5f, cwX + cwH * 0.05f, cwY + cwW * 0.5f)
            canvas.drawRoundRect(rect, dp(2f).toFloat(), dp(2f).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f).toFloat()
            paint.color = Ds.colors.accent
            canvas.drawCircle(cwX, cwY, cwW * 0.5f, paint)

            // 支点关节盖
            paint.style = Paint.Style.FILL
            paint.color = 0xFFB8BDC9.toInt()
            canvas.drawCircle(px, py, w * 0.036f, paint)
            paint.color = 0xFF545966.toInt()
            canvas.drawCircle(px, py, w * 0.022f, paint)
            paint.color = 0xFF2A2D36.toInt()
            canvas.drawCircle(px, py, w * 0.015f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f).toFloat()
            paint.color = Ds.colors.accent
            canvas.drawCircle(px, py, w * 0.040f, paint)

            // 唱头壳
            val hx = w * 0.695f
            val hy = h * 0.515f
            val hs = canvas.save()
            canvas.rotate(37f, hx, hy)
            paint.style = Paint.Style.FILL
            paint.color = 0xFF23262F.toInt()
            rect.set(hx - w * 0.016f, hy - h * 0.032f, hx + w * 0.016f, hy + h * 0.032f)
            canvas.drawRoundRect(rect, dp(2.5f).toFloat(), dp(2.5f).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f).toFloat()
            paint.color = 0xFF8E95A4.toInt()
            canvas.drawLine(hx - w * 0.016f, hy - h * 0.030f, hx + w * 0.016f, hy - h * 0.030f, paint)
            // 指托
            paint.strokeWidth = dp(1.5f).toFloat()
            paint.color = 0xFFB8BECB.toInt()
            canvas.drawLine(hx + w * 0.016f, hy, hx + w * 0.030f, hy - h * 0.014f, paint)
            // 唱针指示块
            paint.style = Paint.Style.FILL
            paint.color = Ds.colors.accent
            rect.set(hx - w * 0.010f, hy + h * 0.022f, hx + w * 0.010f, hy + h * 0.030f)
            canvas.drawRoundRect(rect, dp(1f).toFloat(), dp(1f).toFloat(), paint)
            canvas.restoreToCount(hs)

            canvas.restoreToCount(save)
        }
    }

    /** 风格 3：复古磁带播放器舞台（注塑外壳 + 纸标贴纸 + 动态卷饼 + 走带细节）。 */
    private inner class CassetteDeckView(
        context: Context,
        private val task: DownloadManager.Task?,
        private val onlineTitle: String = "",
        private val onlineCoverUrl: String = ""
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Ds.display(bold = true)
            textAlign = Paint.Align.LEFT
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
        }
        private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
        }
        private val bodyRect = RectF()
        private val stickerRect = RectF()
        private val winRect = RectF()
        private val tmpRect = RectF()
        private val winPath = Path()
        private val trapPath = Path()
        private val matrix = android.graphics.Matrix()

        private fun displayTitle(): String =
            onlineTitle.ifBlank { task?.title.orEmpty().ifBlank { "未命名音频" } }

        private fun displayOwner(): String =
            task?.owner.orEmpty().ifBlank { if (onlineTitle.isNotBlank()) "B 站音频" else "未知作者" }
        private var bodyShader: LinearGradient? = null
        private var bevelShader: LinearGradient? = null
        private var rotationDeg = 0f
        private var spinVelocity = 0f
        private var progress = 0f
        private var lastFrameNs = 0L
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                val dt = if (lastFrameNs == 0L) 0.016f else ((now - lastFrameNs) / 1_000_000_000f)
                    .coerceIn(0f, 0.05f)
                lastFrameNs = now
                rotationDeg = (rotationDeg + dt * spinVelocity * 120f) % 360f
                invalidate()
            }
        }

        fun setSpinning(active: Boolean) {
            if (MotionTokens.isReduced(context)) {
                spinVelocity = 0f
                invalidate()
                return
            }
            val target = if (active) 1f else 0f
            ValueAnimator.ofFloat(spinVelocity, target).apply {
                duration = 320L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    spinVelocity = it.animatedValue as Float
                    if (spinVelocity > 0.01f) {
                        if (!animator.isStarted) animator.start()
                        else if (animator.isPaused) animator.resume()
                    } else if (animator.isRunning) {
                        animator.pause()
                    }
                    invalidate()
                }
                start()
            }
        }

        /** 由进度条驱动左右卷饼厚度（面积守恒）。 */
        fun setProgress(fraction: Float) {
            progress = fraction.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val boxW = min(w * 0.90f, dp(328).toFloat())
            val boxH = boxW * 0.635f
            bodyShader = LinearGradient(
                0f, 0f, boxW, boxH,
                intArrayOf(0xFF272B34.toInt(), 0xFF1B1E25.toInt()),
                null, android.graphics.Shader.TileMode.CLAMP
            )
            bevelShader = LinearGradient(
                0f, 0f, 0f, boxH,
                intArrayOf(0x32FFFFFF.toInt(), 0x08FFFFFF.toInt(), 0x44000000.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val boxW = min(width * 0.90f, dp(328).toFloat())
            val boxH = boxW * 0.635f
            val left = (width - boxW) / 2f
            val top = (height - boxH) / 2f
            bodyRect.set(left, top, left + boxW, top + boxH)

            // ── 叠层投影 ──
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = 0x22000000.toInt()
            tmpRect.set(bodyRect)
            tmpRect.offset(0f, dp(3f).toFloat())
            canvas.drawRoundRect(tmpRect, dp(14f).toFloat(), dp(14f).toFloat(), paint)
            paint.color = 0x10000000.toInt()
            tmpRect.offset(0f, dp(3f).toFloat())
            canvas.drawRoundRect(tmpRect, dp(14f).toFloat(), dp(14f).toFloat(), paint)

            // ── 注塑主壳 ──
            paint.color = 0xFFFFFFFF.toInt()
            paint.shader = bodyShader
            canvas.drawRoundRect(bodyRect, dp(14f).toFloat(), dp(14f).toFloat(), paint)
            paint.shader = null

            // 内凹台阶
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f).toFloat()
            paint.color = 0x33000000.toInt()
            tmpRect.set(
                bodyRect.left + boxW * 0.042f, bodyRect.top + boxH * 0.058f,
                bodyRect.right - boxW * 0.042f, bodyRect.top + boxH * 0.755f
            )
            canvas.drawRoundRect(tmpRect, dp(8f).toFloat(), dp(8f).toFloat(), paint)
            paint.color = 0x18FFFFFF.toInt()
            tmpRect.offset(0f, dp(1f).toFloat())
            canvas.drawRoundRect(tmpRect, dp(8f).toFloat(), dp(8f).toFloat(), paint)

            // 顶部倒角高光
            paint.strokeWidth = dp(1.2f).toFloat()
            paint.shader = bevelShader
            tmpRect.set(
                bodyRect.left + dp(0.7f), bodyRect.top + dp(0.7f),
                bodyRect.right - dp(0.7f), bodyRect.bottom - dp(0.7f)
            )
            canvas.drawRoundRect(tmpRect, dp(13f).toFloat(), dp(13f).toFloat(), paint)
            paint.shader = null

            // 两侧防滑肋纹
            paint.strokeWidth = dp(1.2f).toFloat()
            paint.color = 0x28FFFFFF.toInt()
            for (i in 0 until 6) {
                val y = bodyRect.top + boxH * (0.25f + i * 0.08f)
                canvas.drawLine(
                    bodyRect.left + boxW * 0.012f, y,
                    bodyRect.left + boxW * 0.032f, y, paint
                )
                canvas.drawLine(
                    bodyRect.right - boxW * 0.032f, y,
                    bodyRect.right - boxW * 0.012f, y, paint
                )
            }

            // ── 标签贴纸 ──
            stickerRect.set(
                bodyRect.left + boxW * 0.075f, bodyRect.top + boxH * 0.088f,
                bodyRect.right - boxW * 0.075f, bodyRect.top + boxH * 0.720f
            )
            paint.style = Paint.Style.FILL
            paint.color = 0xFFDCD7CB.toInt()
            canvas.drawRoundRect(stickerRect, dp(6f).toFloat(), dp(6f).toFloat(), paint)

            // 贴纸顶栏：SIDE A 徽标
            val badgeX = stickerRect.left + boxW * 0.030f
            val badgeY = stickerRect.top + boxH * 0.014f
            paint.color = 0xFF1B1C20.toInt()
            tmpRect.set(badgeX, badgeY, badgeX + dp(14f), badgeY + dp(13f))
            canvas.drawRoundRect(tmpRect, dp(2f).toFloat(), dp(2f).toFloat(), paint)
            smallPaint.color = 0xFFFFFFFF.toInt()
            smallPaint.textSize = dp(9.5f).toFloat()
            smallPaint.typeface = Ds.body(bold = true)
            canvas.drawText("A", badgeX + dp(4.5f), badgeY + dp(10f), smallPaint)
            smallPaint.color = 0x991B1C20.toInt()
            smallPaint.textSize = dp(8.5f).toFloat()
            smallPaint.typeface = Ds.body(bold = false)
            canvas.drawText("SIDE A · HI-FI", badgeX + dp(18f), badgeY + dp(10f), smallPaint)

            // 右上时长
            smallPaint.color = 0x991B1C20.toInt()
            smallPaint.textSize = dp(9f).toFloat()
            val durText = DownloadUiShared.formatDurationMs(task?.durationMs ?: 0L)
            smallPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(durText, stickerRect.right - boxW * 0.030f, badgeY + dp(10f), smallPaint)
            smallPaint.textAlign = Paint.Align.LEFT

            // 标题导引线
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(0.7f).toFloat()
            paint.color = 0x241B1C20.toInt()
            val lineLeft = stickerRect.left + boxW * 0.025f
            val lineRight = stickerRect.right - boxW * 0.025f
            val lineY1 = stickerRect.top + boxH * 0.148f
            val lineY2 = stickerRect.top + boxH * 0.211f
            canvas.drawLine(lineLeft, lineY1, lineRight, lineY1, paint)
            canvas.drawLine(lineLeft, lineY2, lineRight, lineY2, paint)

            // 标题
            titlePaint.color = 0xFF1B1C20.toInt()
            titlePaint.textSize = dp(13f).toFloat()
            val titleMax = (lineRight - lineLeft).toInt()
            val shownTitle = android.text.TextUtils.ellipsize(
                displayTitle(),
                android.text.TextPaint(titlePaint),
                titleMax.toFloat(),
                android.text.TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(shownTitle, lineLeft, lineY1 - dp(5f), titlePaint)

            // 副标题（UP 主）
            subPaint.color = 0x9E1B1C20.toInt()
            subPaint.textSize = dp(10f).toFloat()
            val shownOwner = android.text.TextUtils.ellipsize(
                displayOwner(),
                android.text.TextPaint(subPaint),
                titleMax.toFloat(),
                android.text.TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(shownOwner, lineLeft, lineY2 - dp(4f), subPaint)

            // 底部彩色饰带
            paint.style = Paint.Style.FILL
            paint.color = Ds.colors.accent
            tmpRect.set(
                stickerRect.left, stickerRect.top + boxH * 0.552f,
                stickerRect.right, stickerRect.top + boxH * 0.596f
            )
            canvas.drawRect(tmpRect, paint)
            paint.color = Ds.colors.cold
            tmpRect.set(
                stickerRect.left, stickerRect.top + boxH * 0.604f,
                stickerRect.right, stickerRect.top + boxH * 0.618f
            )
            canvas.drawRect(tmpRect, paint)

            // ── 中央观察窗 ──
            winRect.set(
                bodyRect.left + boxW * 0.135f, bodyRect.top + boxH * 0.340f,
                bodyRect.right - boxW * 0.135f, bodyRect.top + boxH * 0.615f
            )
            winPath.rewind()
            winPath.addRoundRect(winRect, dp(7f).toFloat(), dp(7f).toFloat(), Path.Direction.CW)
            paint.style = Paint.Style.FILL
            paint.color = 0xFF0D0F13.toInt()
            canvas.drawRoundRect(winRect, dp(7f).toFloat(), dp(7f).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f).toFloat()
            paint.color = 0x88000000.toInt()
            canvas.drawRoundRect(winRect, dp(7f).toFloat(), dp(7f).toFloat(), paint)

            val winSave = canvas.save()
            canvas.clipPath(winPath)
            val leftCx = winRect.left + winRect.width() * 0.30f
            val rightCx = winRect.left + winRect.width() * 0.70f
            val reelCy = winRect.centerY()
            val rHub = boxH * 0.088f
            val rMax = boxH * 0.235f
            val p = progress.coerceIn(0.04f, 0.96f)
            val rHubSq = rHub * rHub
            val spanSq = rMax * rMax - rHubSq
            val rLeft = kotlin.math.sqrt(rHubSq + (1f - p) * spanSq)
            val rRight = kotlin.math.sqrt(rHubSq + p * spanSq)

            drawTapePie(canvas, leftCx, reelCy, rLeft)
            drawTapePie(canvas, rightCx, reelCy, rRight)
            drawReel(canvas, leftCx, reelCy, rHub)
            drawReel(canvas, rightCx, reelCy, rHub)

            // 视窗刻度桥
            paint.style = Paint.Style.FILL
            paint.color = 0x441B1E26.toInt()
            tmpRect.set(
                winRect.centerX() - boxW * 0.055f, winRect.top + winRect.height() * 0.28f,
                winRect.centerX() + boxW * 0.055f, winRect.top + winRect.height() * 0.82f
            )
            canvas.drawRoundRect(tmpRect, dp(3f).toFloat(), dp(3f).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(0.8f).toFloat()
            paint.color = 0x55FFFFFF.toInt()
            for (i in 0 until 5) {
                val x = tmpRect.left + dp(3f) + i * dp(4.5f)
                val hh = if (i == 2) dp(9f) else dp(5f)
                canvas.drawLine(x, tmpRect.centerY() - hh, x, tmpRect.centerY() + hh, paint)
            }

            // 亚克力反光
            paint.style = Paint.Style.FILL
            paint.color = 0x12FFFFFF.toInt()
            canvas.save()
            canvas.rotate(32f, winRect.centerX(), winRect.centerY())
            tmpRect.set(
                winRect.centerX() - winRect.width() * 0.5f, winRect.centerY() - winRect.height() * 0.10f,
                winRect.centerX() + winRect.width() * 0.5f, winRect.centerY() + winRect.height() * 0.02f
            )
            canvas.drawRect(tmpRect, paint)
            canvas.restore()
            canvas.restoreToCount(winSave)

            // ── 底部磁头区 ──
            trapPath.rewind()
            trapPath.moveTo(bodyRect.left + boxW * 0.21f, bodyRect.bottom)
            trapPath.lineTo(bodyRect.left + boxW * 0.26f, bodyRect.top + boxH * 0.765f)
            trapPath.lineTo(bodyRect.right - boxW * 0.26f, bodyRect.top + boxH * 0.765f)
            trapPath.lineTo(bodyRect.right - boxW * 0.21f, bodyRect.bottom)
            trapPath.close()
            paint.style = Paint.Style.FILL
            paint.color = 0xFF22262E.toInt()
            canvas.drawPath(trapPath, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f).toFloat()
            paint.color = 0x20FFFFFF.toInt()
            canvas.drawPath(trapPath, paint)

            // 定位孔与磁头窗
            paint.style = Paint.Style.FILL
            paint.color = 0xFF0B0C0F.toInt()
            canvas.drawCircle(bodyRect.left + boxW * 0.315f, bodyRect.top + boxH * 0.905f, boxH * 0.036f, paint)
            canvas.drawCircle(bodyRect.right - boxW * 0.315f, bodyRect.top + boxH * 0.905f, boxH * 0.036f, paint)
            tmpRect.set(
                bodyRect.left + boxW * 0.400f - boxW * 0.014f, bodyRect.top + boxH * 0.895f - boxH * 0.018f,
                bodyRect.left + boxW * 0.400f + boxW * 0.014f, bodyRect.top + boxH * 0.895f + boxH * 0.018f
            )
            canvas.drawRect(tmpRect, paint)
            tmpRect.offset(bodyRect.width() * 0.20f, 0f)
            canvas.drawRect(tmpRect, paint)
            paint.color = 0xFF0A0B0E.toInt()
            tmpRect.set(
                bodyRect.centerX() - boxW * 0.045f, bodyRect.top + boxH * 0.910f,
                bodyRect.centerX() + boxW * 0.045f, bodyRect.bottom
            )
            canvas.drawRect(tmpRect, paint)
            // 压带海绵
            paint.color = 0xFFB88A52.toInt()
            tmpRect.set(
                bodyRect.centerX() - boxW * 0.020f, bodyRect.top + boxH * 0.930f,
                bodyRect.centerX() + boxW * 0.020f, bodyRect.top + boxH * 0.955f
            )
            canvas.drawRoundRect(tmpRect, dp(1.5f).toFloat(), dp(1.5f).toFloat(), paint)

            // 走带高光微粒
            if (spinVelocity > 0.05f && !MotionTokens.isReduced(context)) {
                paint.color = 0x48D4A894.toInt()
                val y = bodyRect.top + boxH * 0.915f
                val span = bodyRect.width() * 0.37f
                val startX = bodyRect.centerX() - span / 2f
                val shift = ((System.nanoTime() / 1_000_000L) % 600L) / 600f * span
                for (i in 0 until 4) {
                    val x = startX + ((shift + i * span / 4f) % span)
                    canvas.drawCircle(x, y, dp(1.5f).toFloat(), paint)
                }
            }

            // ── 五颗十字螺丝 ──
            drawScrew(canvas, bodyRect.left + boxW * 0.042f, bodyRect.top + boxH * 0.075f, 15f)
            drawScrew(canvas, bodyRect.right - boxW * 0.042f, bodyRect.top + boxH * 0.075f, 42f)
            drawScrew(canvas, bodyRect.left + boxW * 0.042f, bodyRect.bottom - boxH * 0.075f, 78f)
            drawScrew(canvas, bodyRect.right - boxW * 0.042f, bodyRect.bottom - boxH * 0.075f, 110f)
            drawScrew(canvas, bodyRect.centerX(), bodyRect.bottom - boxH * 0.165f, 28f)
        }

        private fun drawTapePie(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            paint.style = Paint.Style.FILL
            paint.color = 0xFF1B1514.toInt()
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f).toFloat()
            paint.color = 0x336D564E.toInt()
            canvas.drawCircle(cx, cy, r, paint)
            paint.strokeWidth = dp(0.6f).toFloat()
            paint.color = 0x16FFFFFF.toInt()
            for (i in 1..3) {
                canvas.drawCircle(cx, cy, r * (0.62f + i * 0.115f), paint)
            }
        }

        private fun drawReel(canvas: Canvas, cx: Float, cy: Float, rHub: Float) {
            val save = canvas.save()
            canvas.rotate(rotationDeg, cx, cy)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.2f).toFloat()
            paint.color = 0xFFEAECEF.toInt()
            canvas.drawCircle(cx, cy, rHub, paint)
            // 3 扇镂空弧窗
            paint.strokeWidth = rHub * 0.27f
            paint.color = 0xFF23262F.toInt()
            for (i in 0 until 3) {
                val start = i * 120f + 20f
                canvas.drawArc(
                    RectF(cx - rHub * 0.685f, cy - rHub * 0.685f, cx + rHub * 0.685f, cy + rHub * 0.685f),
                    start, 65f, false, paint
                )
            }
            // 6 颗内啮合梯形齿
            paint.strokeWidth = dp(2.6f).toFloat()
            paint.color = 0xFFECEFF4.toInt()
            for (i in 0 until 6) {
                val rad = Math.toRadians((i * 60).toDouble())
                val c = kotlin.math.cos(rad).toFloat()
                val s = kotlin.math.sin(rad).toFloat()
                canvas.drawLine(
                    cx + c * rHub * 0.34f, cy + s * rHub * 0.34f,
                    cx + c * rHub * 0.78f, cy + s * rHub * 0.78f, paint
                )
            }
            // 中心驱动孔
            paint.style = Paint.Style.FILL
            paint.color = 0xFF090A0D.toInt()
            canvas.drawCircle(cx, cy, rHub * 0.33f, paint)
            canvas.restoreToCount(save)
        }

        private fun drawScrew(canvas: Canvas, cx: Float, cy: Float, angleDeg: Float) {
            paint.style = Paint.Style.FILL
            paint.color = 0x55000000.toInt()
            canvas.drawCircle(cx, cy, dp(3.5f).toFloat(), paint)
            paint.color = 0xFF686E7C.toInt()
            canvas.drawCircle(cx, cy, dp(2.8f).toFloat(), paint)
            val save = canvas.save()
            canvas.rotate(angleDeg, cx, cy)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f).toFloat()
            paint.color = 0x990D0E12.toInt()
            canvas.drawLine(cx - dp(2f), cy, cx + dp(2f), cy, paint)
            canvas.drawLine(cx, cy - dp(2f), cx, cy + dp(2f), paint)
            canvas.restoreToCount(save)
        }
    }

    /** 粒子数据（预分配复用，避免动画中分配对象）。 */
    private class BeatStageParticle(
        var radius: Float,
        var angle: Float,
        var size: Float,
        var speed: Float,
        var angularDrift: Float
    )

    /** 风格 4：声场脉冲核心 + 36 瓣环形频谱 + 径向爆发粒子（无封面）。 */
    private inner class BeatParticleView(
        context: Context,
        private val levelProvider: () -> Pair<Float, Float>
    ) : View(context) {

        private val particles = Array(72) { i ->
            if (i < 48) {
                BeatStageParticle(
                    radius = 0.22f + (i % 12) * 0.06f,
                    angle = (i * 360f / 48f) + (i % 5) * 6f,
                    size = 1.4f + (i % 4) * 0.6f,
                    speed = 0.65f + (i % 7) * 0.1f,
                    angularDrift = (-14f + (i % 9) * 3.5f) * 0.0174f
                )
            } else {
                BeatStageParticle(
                    radius = 0.45f + ((i - 48) % 8) * 0.062f,
                    angle = ((i - 48) * 360f / 24f) + (i % 3) * 5f,
                    size = 1.1f + (i % 3) * 0.55f,
                    speed = 0f,
                    angularDrift = (-4f + (i % 5) * 2f) * 0.0174f
                )
            }
        }

        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val glowMatrix = android.graphics.Matrix()
        private val coreMatrix = android.graphics.Matrix()
        private var glowShader: RadialGradient? = null
        private var coreShader: RadialGradient? = null
        private var unitRadius = 1f

        private var active = false
        private var phase = 0f
        private var displayBass = 0f
        private var prevBass = 0f
        private var lastShockMs = 0L
        private var lastFrameNs = 0L
        private val shockProgress = FloatArray(3) { -1f }

        private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { tick(canvasDt()) }
        }

        private var pendingDt = 0.016f

        private fun canvasDt(): Float = pendingDt

        fun setActive(running: Boolean) {
            active = running
            if (MotionTokens.isReduced(context)) {
                invalidate()
                return
            }
            if (running) {
                if (!ticker.isStarted) ticker.start()
                else if (ticker.isPaused) ticker.resume()
            } else if (ticker.isRunning) {
                ticker.pause()
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            ticker.cancel()
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val cx = w / 2f
            val cy = h / 2f
            unitRadius = min(cx, cy).coerceAtLeast(1f)
            glowShader = RadialGradient(
                cx, cy, unitRadius,
                intArrayOf(0x3BF27D9E.toInt(), 0x167EA6E8.toInt(), 0x00000000.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            coreShader = RadialGradient(
                cx, cy, unitRadius * 0.25f,
                intArrayOf(0xFFFFF4F7.toInt(), Ds.colors.accent, 0x00F27D9E),
                floatArrayOf(0f, 0.45f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }

        private fun tick(dt: Float) {
            if (width <= 0 || height <= 0) {
                invalidate()
                return
            }
            val nowNs = System.nanoTime()
            val realDt = if (lastFrameNs == 0L) dt else ((nowNs - lastFrameNs) / 1_000_000_000f)
                .coerceIn(0f, 0.05f)
            lastFrameNs = nowNs
            val (level, bassRaw) = if (active) levelProvider() else (0f to 0f)

            // 快攻慢放包络
            displayBass = if (bassRaw > displayBass) {
                displayBass * 0.55f + bassRaw * 0.45f
            } else {
                displayBass * 0.88f + bassRaw * 0.12f
            }
            phase = (phase + realDt * (1.8f + level * 3.5f)) % 1f

            // 鼓点冲击波
            val nowMs = System.currentTimeMillis()
            if (bassRaw - prevBass > 0.12f && nowMs - lastShockMs >= 240L) {
                val slot = shockProgress.indexOfFirst { it < 0f }
                if (slot >= 0) shockProgress[slot] = 0f
                lastShockMs = nowMs
            }
            prevBass = bassRaw
            for (i in shockProgress.indices) {
                if (shockProgress[i] >= 0f) {
                    shockProgress[i] += realDt / 0.85f
                    if (shockProgress[i] > 1f) shockProgress[i] = -1f
                }
            }

            // 粒子推进
            val levelNow = if (active) level else 0f
            for (i in 0 until 48) {
                val p = particles[i]
                val dist = realDt * (0.14f + levelNow * 0.42f + displayBass * 0.58f) * p.speed
                p.radius += dist
                p.angle += p.angularDrift * realDt * (1f + levelNow) * 57.3f
                if (p.radius > 0.96f) {
                    p.radius = 0.22f + (i % 5) * 0.008f
                    p.angle = (p.angle + 137f) % 360f
                }
            }
            for (i in 48 until 72) {
                val p = particles[i]
                p.angle += p.angularDrift * realDt * 57.3f
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val maxR = min(cx, cy) * 0.48f
            if (maxR <= 0f) return
            val (level, _) = if (active) levelProvider() else (0f to 0f)
            val bass = displayBass

            // 氛围底晕
            val glowR = maxR * (0.66f + bass * 0.26f)
            glowShader?.let {
                glowMatrix.reset()
                val scale = (glowR / unitRadius).coerceAtLeast(0.001f)
                glowMatrix.setScale(scale, scale, cx, cy)
                it.setLocalMatrix(glowMatrix)
                corePaint.shader = it
                canvas.drawCircle(cx, cy, glowR, corePaint)
                corePaint.shader = null
            }

            // 中心能量核心
            val coreR = maxR * (0.115f + bass * 0.050f)
            coreShader?.let {
                corePaint.shader = it
                canvas.drawCircle(cx, cy, coreR, corePaint)
                corePaint.shader = null
            }
            corePaint.color = Ds.colors.accent
            canvas.drawCircle(cx, cy, maxR * 0.042f * (1f + bass * 0.22f), corePaint)

            // 偏振双环
            ringPaint.color = Ds.colors.accent
            ringPaint.alpha = 0x73
            ringPaint.strokeWidth = dp(1.2f).toFloat()
            canvas.drawCircle(cx, cy, maxR * 0.155f, ringPaint)
            dotPaint.color = Ds.colors.cold
            dotPaint.alpha = 0x66
            for (i in 0 until 24) {
                val rad = Math.toRadians((i * 15f + phase * 360f).toDouble())
                val rr = maxR * 0.195f
                canvas.drawCircle(
                    cx + (rr * kotlin.math.cos(rad)).toFloat(),
                    cy + (rr * kotlin.math.sin(rad)).toFloat(),
                    dp(0.9f).toFloat(), dotPaint
                )
            }

            // 36 瓣环形频谱
            val rBase = maxR * 0.225f
            for (i in 0 until 36) {
                val theta = i * (2f * Math.PI / 36f)
                val waveA = 0.5f + 0.5f * kotlin.math.sin((i * 3f * (2f * Math.PI / 36f) + phase * 6.283f).toFloat())
                val waveB = 0.5f + 0.5f * kotlin.math.cos((i * 6f * (2f * Math.PI / 36f) - phase * 8.796f).toFloat())
                val amp = (bass * 0.62f * waveA + level * 0.48f * waveB).coerceIn(0f, 1f)
                val barLen = maxR * (0.020f + amp * 0.125f)
                val c = kotlin.math.cos(theta.toFloat())
                val s = kotlin.math.sin(theta.toFloat())
                barPaint.color = lerpColor(Ds.colors.accent, Ds.colors.cold, waveB * 0.7f)
                barPaint.alpha = (110 + amp * 135).toInt().coerceIn(0, 255)
                barPaint.strokeWidth = dp(2.6f).toFloat()
                canvas.drawLine(
                    cx + c * rBase, cy + s * rBase,
                    cx + c * (rBase + barLen), cy + s * (rBase + barLen), barPaint
                )
            }

            // 冲击波
            for (i in shockProgress.indices) {
                val prog = shockProgress[i]
                if (prog < 0f) continue
                val rr = maxR * (0.18f + prog * 0.68f)
                ringPaint.color = Ds.colors.accent
                ringPaint.alpha = ((1f - prog) * 0x78).toInt().coerceIn(0, 255)
                ringPaint.strokeWidth = dp(2.2f).toFloat() * (1f - prog) + dp(0.6f)
                canvas.drawCircle(cx, cy, rr, ringPaint)
            }

            // 爆发粒子 + 拖尾
            for (i in 0 until 48) {
                val p = particles[i]
                val rad = Math.toRadians(p.angle.toDouble())
                val c = kotlin.math.cos(rad).toFloat()
                val s = kotlin.math.sin(rad).toFloat()
                val dist = maxR * p.radius
                val px = cx + dist * c
                val py = cy + dist * s
                val life = ((p.radius - 0.22f) / 0.74f).coerceIn(0f, 1f)
                val alpha = (kotlin.math.sin(life * Math.PI).toFloat() * (0.42f + level * 0.58f) * 255).toInt()
                    .coerceIn(0, 255)
                if (alpha <= 2) continue
                val size = dp(p.size * (0.85f + bass * 0.55f)).toFloat()
                val tailLen = (maxR * (0.018f + (level + bass) * 0.055f))
                    .coerceAtMost((dist - maxR * 0.20f).coerceAtLeast(0f))
                if (tailLen > 1f) {
                    barPaint.color = Ds.colors.accent
                    barPaint.alpha = (alpha * 0.34f).toInt().coerceIn(0, 255)
                    barPaint.strokeWidth = size * 0.72f
                    canvas.drawLine(px - c * tailLen, py - s * tailLen, px, py, barPaint)
                }
                dotPaint.color = Ds.colors.accent
                dotPaint.alpha = alpha
                canvas.drawCircle(px, py, size, dotPaint)
            }

            // 远景星尘
            for (i in 48 until 72) {
                val p = particles[i]
                val rad = Math.toRadians(p.angle.toDouble())
                val dist = maxR * p.radius
                val twinkle = 0.15f + 0.33f * (0.5f + 0.5f * kotlin.math.sin((phase * 6.283f + i).toFloat()))
                dotPaint.color = if (i % 3 == 0) Ds.colors.cold else Ds.colors.muted
                dotPaint.alpha = (twinkle * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(
                    cx + dist * kotlin.math.cos(rad).toFloat(),
                    cy + dist * kotlin.math.sin(rad).toFloat(),
                    dp(p.size).toFloat(), dotPaint
                )
            }
        }
    }

    /** 音乐时间轴（支持按压与拖拽预览）。 */
    private inner class AudioSeekBar(context: Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var fraction = 0f
        private var dragging = false

        var onSeekStart: (() -> Unit)? = null
        var onSeekChanged: ((Float) -> Unit)? = null
        var onSeekEnd: ((Float) -> Unit)? = null

        fun setFraction(v: Float) {
            if (!dragging) {
                fraction = v.coerceIn(0f, 1f)
                invalidate()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (width <= 0) return false
            val f = ((event.x - dp(6)) / (width - dp(12)).coerceAtLeast(1)).coerceIn(0f, 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    dragging = true
                    fraction = f
                    onSeekStart?.invoke()
                    onSeekChanged?.invoke(f)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    fraction = f
                    onSeekChanged?.invoke(f)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    dragging = false
                    fraction = f
                    onSeekEnd?.invoke(f)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val c = Ds.colors
            val pad = dp(6).toFloat()
            val cy = height / 2f
            val trackH = dp(4).toFloat()
            val usableW = (width - pad * 2f).coerceAtLeast(1f)

            trackPaint.color = c.lineSoft
            canvas.drawRoundRect(RectF(pad, cy - trackH / 2f, pad + usableW, cy + trackH / 2f), trackH / 2f, trackH / 2f, trackPaint)

            val fillEnd = pad + usableW * fraction
            activePaint.color = c.accent
            canvas.drawRoundRect(RectF(pad, cy - trackH / 2f, fillEnd, cy + trackH / 2f), trackH / 2f, trackH / 2f, activePaint)

            thumbPaint.color = c.accent
            canvas.drawCircle(fillEnd, cy, if (dragging) dp(7).toFloat() else dp(5.5f).toFloat(), thumbPaint)
        }
    }

    /** 播控模式图标（配合首字：单 / 列 / 随 / 顺）。 */
    /** 胶囊开关：替代原生 CheckBox，轨道与滑块均 Canvas 手绘。 */
    private inner class CapsuleSwitch(
        context: Context,
        initial: Boolean,
        private val onToggle: (Boolean) -> Unit
    ) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val rect = RectF()
        private var progress = if (initial) 1f else 0f
        private var on = initial
        private var animator: ValueAnimator? = null

        init {
            isClickable = true
            setOnClickListener {
                on = !on
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                animateTo(on)
                onToggle(on)
            }
        }

        fun setChecked(value: Boolean, animate: Boolean = false) {
            if (on == value) return
            on = value
            if (animate) animateTo(value) else {
                progress = if (value) 1f else 0f
                invalidate()
            }
        }

        private fun animateTo(on: Boolean) {
            animator?.cancel()
            if (MotionTokens.isReduced(context)) {
                progress = if (on) 1f else 0f
                invalidate()
                return
            }
            animator = ValueAnimator.ofFloat(progress, if (on) 1f else 0f).apply {
                duration = 140L
                interpolator = MotionTokens.easeOut
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = height.toFloat()
            val r = h / 2f
            val accent = Ds.colors.accent
            val line = Ds.colors.line
            trackPaint.color = lerpColor(line, accent, progress)
            rect.set(0f, 0f, width.toFloat(), h)
            canvas.drawRoundRect(rect, r, r, trackPaint)
            val knobR = (h - dp(6)) / 2f
            val minX = knobR + dp(3)
            val maxX = width - knobR - dp(3)
            canvas.drawCircle(minX + (maxX - minX) * progress, h / 2f, knobR, knobPaint)
        }
    }

    /** 播放列表「正在播放」三柱均衡器指示器。 */
    private inner class EqualizerBarsView(context: Context, initiallyAnimating: Boolean) : View(context) {
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Ds.colors.accent
        }
        private val barRect = RectF()
        private val phases = floatArrayOf(0.00f, 0.35f, 0.65f)
        private val freqs = floatArrayOf(2f, 3f, 2f)
        private val minRanges = floatArrayOf(0.25f, 0.35f, 0.22f)
        private val maxRanges = floatArrayOf(0.88f, 1.00f, 0.82f)
        private var animator: ValueAnimator? = null
        private var progress = 0f
        private var shouldAnimate = initiallyAnimating

        private fun dpf(v: Float): Float = v * resources.displayMetrics.density

        init {
            if (!shouldAnimate) progress = 0.28f
        }

        fun setAnimating(active: Boolean) {
            shouldAnimate = active
            if (active) startIfNeeded() else stopAndFreeze()
        }

        private fun startIfNeeded() {
            if (animator != null) return
            if (MotionTokens.isReduced(context)) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200L
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun stopAndFreeze() {
            animator?.cancel()
            animator = null
            progress = 0.28f
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (shouldAnimate) startIfNeeded()
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val barW = dpf(2.6f)
            val gap = dpf(2.4f)
            val totalW = barW * 3 + gap * 2
            val left = (width - totalW) / 2f
            val maxH = dpf(14f)
            val minH = dpf(3.5f)
            val baseline = (height + maxH) / 2f
            for (i in 0..2) {
                val raw = 0.5f + 0.5f * kotlin.math.sin(
                    (2.0 * Math.PI * (freqs[i] * progress + phases[i])).toFloat()
                )
                val ratio = minRanges[i] + (maxRanges[i] - minRanges[i]) * raw
                val h = minH + (maxH - minH) * ratio
                val x = left + i * (barW + gap)
                barRect.set(x, baseline - h, x + barW, baseline)
                canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, barPaint)
            }
        }
    }

    private inner class PlayControlButton(context: Context, private val fillColor: Int, private val iconColor: Int) : View(context) {
        private var playing = false
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
        private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = iconColor; style = Paint.Style.FILL }
        private val tri = Path()

        fun setPlaying(isPlaying: Boolean) {
            if (playing != isPlaying) {
                playing = isPlaying
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, min(cx, cy), bgPaint)
            if (playing) {
                val barW = dp(5).toFloat()
                val barH = dp(18).toFloat()
                val gap = dp(5).toFloat()
                canvas.drawRoundRect(RectF(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f), barW / 2f, barW / 2f, fgPaint)
                canvas.drawRoundRect(RectF(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f), barW / 2f, barW / 2f, fgPaint)
            } else {
                val s = dp(10).toFloat()
                tri.rewind()
                tri.moveTo(cx - s * 0.55f, cy - s * 0.9f)
                tri.lineTo(cx + s * 1.0f, cy)
                tri.lineTo(cx - s * 0.55f, cy + s * 0.9f)
                tri.close()
                canvas.drawPath(tri, fgPaint)
            }
        }
    }

    private inner class SkipButton(context: Context, private val isNext: Boolean, private val color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = dp(2.2f).toFloat()
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val tri = Path()
        private val barRect = RectF()
        private var enabledLook = true

        fun setSkipEnabled(enabled: Boolean) {
            if (enabledLook == enabled) return
            enabledLook = enabled
            isEnabled = enabled
            isClickable = enabled
            alpha = if (enabled) 1f else 0.30f
            paint.color = if (enabled) color else Ds.colors.subtle
            paint.strokeWidth = dp(2.2f).toFloat()
            scaleX = 1f
            scaleY = 1f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val halfW = dp(8.5f).toFloat()
            val halfH = dp(9f).toFloat()
            val barW = dp(3f).toFloat()
            val gap = dp(0.75f).toFloat()
            if (isNext) {
                tri.rewind()
                tri.moveTo(cx - halfW, cy - halfH)
                tri.lineTo(cx + halfW - barW - gap, cy)
                tri.lineTo(cx - halfW, cy + halfH)
                tri.close()
                canvas.drawPath(tri, paint)
                barRect.set(cx + halfW - barW, cy - halfH, cx + halfW, cy + halfH)
            } else {
                barRect.set(cx - halfW, cy - halfH, cx - halfW + barW, cy + halfH)
                tri.rewind()
                tri.moveTo(cx + halfW, cy - halfH)
                tri.lineTo(cx - halfW + barW + gap, cy)
                tri.lineTo(cx + halfW, cy + halfH)
                tri.close()
                canvas.drawPath(tri, paint)
            }
            canvas.drawRoundRect(barRect, dp(1.5f).toFloat(), dp(1.5f).toFloat(), paint)
        }
    }

    private inner class MoreDotsButton(context: Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = dp(2).toFloat()
            canvas.drawCircle(cx, cy - dp(6), r, paint)
            canvas.drawCircle(cx, cy, r, paint)
            canvas.drawCircle(cx, cy + dp(6), r, paint)
        }
    }

    private inner class PlusIconButton(context: Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val arm = dp(7).toFloat()
            canvas.drawLine(cx - arm, cy, cx + arm, cy, paint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, paint)
        }
    }

    private inner class PlaylistIconButton(context: Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = dp(1.8f).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawLine(w * 0.28f, h * 0.34f, w * 0.72f, h * 0.34f, paint)
            canvas.drawLine(w * 0.28f, h * 0.50f, w * 0.72f, h * 0.50f, paint)
            canvas.drawLine(w * 0.28f, h * 0.66f, w * 0.60f, h * 0.66f, paint)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        const val EXTRA_TASK_ID = "audio_task_id"
        const val EXTRA_SORT_ORDER = "audio_sort_order"
        /** 仅附着到当前正在播放的音频（从通知栏进入），不提交新播放请求。 */
        const val EXTRA_ATTACH_ONLY = "audio_attach_only"
        private const val PREFS_NAME = "yuibili_audio_player"
        private const val KEY_VISUAL_STYLE = "visual_style"
        private const val KEY_PLAY_MODE = "play_mode"
        private const val KEY_AUTO_SAVE_PLAYLIST = "auto_save_playlist"
        private const val KEY_SAVED_PLAYLIST_IDS = "saved_playlist_ids"

        fun open(context: Context, taskId: Long, sortOrderName: String = DownloadManager.SeasonSortOrder.DEFAULT.name) {
            context.startActivity(Intent(context, AudioPlayerActivity::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_SORT_ORDER, sortOrderName)
            })
        }

        /** 从通知栏点回音频页：只展示当前播放，不重新发起加载。 */
        fun attachIntent(context: Context): Intent =
            Intent(context, AudioPlayerActivity::class.java).apply {
                putExtra(EXTRA_ATTACH_ONLY, true)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}

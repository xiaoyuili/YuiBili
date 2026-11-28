package com.yuilittle.bili

import android.Manifest
import android.animation.ValueAnimator
import android.animation.Animator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ReplacementSpan
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Scroller
import android.widget.TextView
import android.widget.Toast
import kotlin.math.cos
import kotlin.math.sin
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class VideoDetailActivity : Activity() {
    private lateinit var activityRoot: FrameLayout
    private lateinit var page: LinearLayout
    private var tabletSplitDivider: View? = null
    private lateinit var playerHost: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var danmakuView: DanmakuOverlayView
    private var danmakuController: DanmakuController? = null
    private var danmakuSettingsDrawer: DanmakuUi.FullscreenSettingsDrawer? = null
    private var danmakuToggleBtn: DanmakuUi.ToggleIconView? = null
    private var danmakuSettingsBtn: DanmakuUi.SettingsIconView? = null
    private var portraitDanmakuToggle: DanmakuUi.PortraitTabToggle? = null
    private val danmakuUiSettingsListener: (DanmakuSettingsStore.Snapshot) -> Unit = { s ->
        danmakuToggleBtn?.setDanmakuEnabled(s.enabled)
        portraitDanmakuToggle?.updateState(s.enabled)
    }
    private lateinit var gestureLayer: PlayerGestureView
    private lateinit var playerBack: View
    private lateinit var playerSpinner: BrandSpinnerView
    private lateinit var playerError: TextView
    private lateinit var infoContent: LinearLayout
    private lateinit var infoState: TextView
    private lateinit var gestureDetector: GestureDetector
    private val handler = Handler(Looper.getMainLooper())
    private val reducedMotion by lazy(LazyThreadSafetyMode.NONE) { MotionTokens.isReduced(this) }
    private var exoPlayer: ExoPlayer? = null
    private var currentBvid = ""
    private var currentAid = 0L
    private var currentCid = 0L
    private var detailRequest: BiliRequest? = null
    private var detailGeneration = 0
    private var detailResponse: Pair<VideoItem, List<VideoPart>>? = null
    private var detailSession = ""
    /** 本地下载文件播放模式：支持音视频播放、离线合集分集列表、4 种播放模式。 */
    private var isLocalMode = false
    private var localTaskId = 0L
    private var localSortOrder = DownloadManager.SeasonSortOrder.DEFAULT
    private var localPlayMode = DownloadManager.LocalPlayMode.SEASON_SEQUENCE
    private var localPlaylist: List<DownloadManager.Task> = emptyList()
    /** 收藏夹「播放全部」队列；非空时播放页以收藏夹列表为准。 */
    private var favoritePlayback: FavoritePlayback? = null
    private var onlinePlayMode = DownloadManager.LocalPlayMode.SEASON_SEQUENCE
    private var pendingIntro: VideoItem? = null
    private var localInfoHost: FrameLayout? = null
    private var localAudioCoverView: ImageView? = null
    private var localAudioCoverOverlay: FrameLayout? = null

    // ── 全屏合集抽屉（仅全屏且有合集时显示入口箭头） ──
    private var seasonEntryBtn: View? = null
    private var seasonDrawerOverlay: FrameLayout? = null
    private var seasonDrawerPanel: LinearLayout? = null
    private var seasonDrawerList: ListView? = null
    private var seasonDrawerCountText: TextView? = null
    private var seasonDrawerTitleText: TextView? = null
    private var seasonDrawerAdapter: BaseAdapter? = null
    private var seasonDrawerOpen = false
    private var seasonDrawerAnimating = false
    private var seasonDrawerItems: List<FullscreenSeasonItem> = emptyList()
    /** 切集请求进行中 currentDetail 会置空，用该缓存保证抽屉仍可展开。 */
    private var cachedSeason: UgcSeason? = null
    private var isFullscreen = false
    private var currentStream: BiliPlayUrl.DashStream? = null
    private var streamSession = ""
    private var streamLoadedAt = 0L
    private var playbackRequest: BiliRequest? = null
    private var playerListener: Player.Listener? = null
    private var segmentTimeline = PlaybackTimeline(emptyList())
    /** The single-track stream actually fed to ExoPlayer (after stable selection). */
    private var playingStream: BiliPlayUrl.DashStream? = null
    private val failedCodecTracks = HashSet<Pair<Int, String>>()
    private val qualityPrefs by lazy { getSharedPreferences("bili_player", MODE_PRIVATE) }
    private val localPlaybackPrefs by lazy { getSharedPreferences("bili_local_playback", MODE_PRIVATE) }
    private var requestedQuality = BiliPlayUrl.QUALITY_720

    // ── Player control overlay ──
    private lateinit var controlOverlay: ViewGroup
    private lateinit var topControlsBar: LinearLayout
    private lateinit var topBarSpacer: View
    private lateinit var backgroundPlayBtn: BackgroundPlayIcon
    private var backgroundSwitchLocked = false
    private var fullscreenStatusBar: LinearLayout? = null
    private var statusClockText: TextView? = null
    private var statusBatteryIcon: BatteryStatusIcon? = null
    private var statusBatteryPercentText: TextView? = null
    private var statusReceiverRegistered = false
    private val statusTimeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm", Locale.US)
    }
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_TIME_TICK,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> updateStatusClock()
                Intent.ACTION_BATTERY_CHANGED -> applyBatteryState(intent)
            }
        }
    }
    private lateinit var playerTopTitle: TextView
    private lateinit var playerSeekHint: TextView
    private lateinit var playerSkipHint: TextView
    private val hideSkipHint = Runnable { if (::playerSkipHint.isInitialized) playerSkipHint.visibility = View.GONE }
    private var speedPopup: PopupWindow? = null
    private var introPlayMode: TextView? = null
    private var drawerPlayMode: PlayModeIconView? = null
    private var drawerPlayModeButton: View? = null
    private var drawerModePopup: PopupWindow? = null
    private var localModeIcon: PlayModeIconView? = null
    private var localModeLabel: TextView? = null
    private var localModeButton: View? = null
    private lateinit var replayBtn: ReplayIcon
    private lateinit var bottomBar: ViewGroup
    private lateinit var playPauseBtn: PlayPauseIcon
    private var nextEpisodeBtn: NextEpisodeIcon? = null
    private lateinit var progressContainer: PlaybackProgressBar
    private lateinit var infoArea: View
    private lateinit var timeText: TextView
    private lateinit var durationText: TextView
    private lateinit var speedBtn: TextView
    private lateinit var qualityBtn: TextView
    private lateinit var fsBtn: FullscreenIcon
    private lateinit var progressRow: LinearLayout
    private lateinit var favBtn: ActionIconView
    private var favFolderIds: List<Long> = emptyList()
    private lateinit var boostIndicator: TextView
    private var boostAnimator: ValueAnimator? = null
    private var endedState = false
    private var fsTransitioning = false
    private var controlsVisible = true
    private var controlsAnimating = false
    private var controlsHideRunnable: Runnable? = null
    private val speeds = floatArrayOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 1
    private var isSeeking = false
    private var seekWasPlaying = false
    private var normalSpeed = 1.0f

    // ── Info tabs: 简介 / 评论 ──
    private lateinit var tabIntro: TextView
    private lateinit var tabComment: TextView
    private lateinit var tabIntroIndicator: View
    private lateinit var tabCommentIndicator: View
    private lateinit var infoPager: HorizontalPagerView
    private lateinit var introScroll: ScrollView
    private lateinit var commentScroll: CommentListView
    private var restoredComments: Bundle? = null
    private var currentDetail: VideoItem? = null
    private var currentTab = 0

    // ── Intro collapse/expand (title row with chevron; only the description collapses) ──
    private lateinit var titleText: TextView
    private lateinit var titleArrow: View
    private lateinit var titleClip: FrameLayout
    private lateinit var introExpandWrap: LinearLayout
    private lateinit var introDescWrap: LinearLayout
    private lateinit var introDescClip: FrameLayout
    private var introExpanded = false
    private var titleLineHeight = 0
    private var titleFullHeight = 0
    private var introDescHeight = 0
    private var titleHeightAnimator: ValueAnimator? = null
    private var descHeightAnimator: ValueAnimator? = null
    // ── Comments: sort (0=hot likes, 1=newest) + auto paging + total badge ──
    private lateinit var commentBubble: TextView
    /** Fixed info-tab navigation row; reply sheets start immediately below it. */
    private var infoTabBarAnchor: View? = null
    // ── Follow button state (server-backed; no local fake store) ──
    private var followBtnRef: TextView? = null
    private var followInFlight = false
    private var currentFollowed = false
    // ── Video action bar (like / favorite / download / share) ──
    private var videoLiked = false
    /** Automatic retries left for transient playback IO errors. */
    private var playRetryCount = 0
    private var playbackGeneration = 0
    private var pendingResumePositionMs = 0L
    private var initialHistoryMedia: HistoryMediaId? = null
    private var lastHistorySample: Pair<HistoryMediaId, Long>? = null
    private var lastHistoryWriteAtMs = 0L
    /** 切集期间为 true，避免把上一集的位置写回新分集。 */
    private var switchingLocalTask = false
    private var userWantsPlayback = true
    private var activityResumed = false
    private var playbackStopped = false
    private var resumePlayerOnStart = false
    private var resumeDetailOnStart = false
    private var resumeSegmentIndex = 0
    private var restoreEndedState = false
    private var retryScheduled = false
    private var stallRetries = 0
    private val stallMonitor = PlaybackStallMonitor(STALL_TIMEOUT_MS)
    private val playbackDiagnostics by lazy { PlaybackDiagnostics(this, "ugc") }
    /** 只按可播缓冲的前沿判断进展，网络收到了字节不等于视频已可播放。 */
    private val stallWatchdog: Runnable by lazy(LazyThreadSafetyMode.NONE) {
        Runnable {
            if (isFinishing || isDestroyed || !activityResumed || !userWantsPlayback || isSeeking || playerListener == null) return@Runnable
            val player = exoPlayer ?: return@Runnable
            if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady && !retryScheduled) {
                val now = SystemClock.elapsedRealtime()
                stallMonitor.observe(now, playerGlobalBufferedPosition(player))
                val remaining = stallMonitor.remainingMs(now)
                if (remaining > 0L) { handler.postDelayed(stallWatchdog, remaining); return@Runnable }
                if (stallRetries >= MAX_STALL_RETRIES) {
                    Log.w("YuiBiliPlayer", "stall watchdog: 已达重试上限，停止重试")
                    playerSpinner.visibility = View.GONE
                    retryScheduled = true
                    showError("加载超时，点这里重试")
                    return@Runnable
                }
                stallRetries++
                retryScheduled = true
                Log.w("YuiBiliPlayer", "stall watchdog: 可播缓冲停滞，第 ${stallRetries} 次恢复")
                playerSpinner.visibility = View.VISIBLE
                playerError.visibility = View.GONE
                rememberPlaybackPosition()
                playbackDiagnostics.recovering(pendingResumePositionMs, playerGlobalBufferedPosition(player), stallRetries)
                if (isLocalMode) {
                    loadLocalPlayer()
                } else if (currentBvid.isNotBlank() && currentCid > 0L) {
                    loadPlayer(PreferredVideoQuality.resumeRequestQuality(requestedQuality, lastRequestedQuality,
                        BiliSessionStore.isLoggedIn(), streamSession != BiliSessionStore.cookie()), bypassCache = true)
                }
            }
        }
    }

    private var lastRequestedQuality = 0
    private var startupAtMs = 0L
    private var playPreviewNoticeShown = false
    private var lastPositionSecond = -1L
    private var lastDurationSecond = -1L
    private var lastPlaybackError: String? = null
    private var videoLikesCount = 0L

    // ── Brightness / volume gesture ──
    private val audioManager: AudioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private lateinit var adjustIndicator: AdjustIndicator
    private var adjustMode = 0 // 0 none, 1 brightness, 2 volume
    private var adjustStartValue = 0f

    // ── Gesture state ──
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureSeeking = false
    private var gestureSeekStartPos = 0L
    private var gestureSeekTargetPos = 0L
    private var gestureSeekDuration = 0L
    private var gestureSeekWasPlaying = false
    private var longPressBoost = false
    private var longPressDown = false
    private var longPressCandidate = false
    private val longPressBoostRunnable = Runnable { applyLongPressBoost() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        BiliSessionStore.init(this)
        restoredComments = savedInstanceState?.getBundle("comments")
        DanmakuSettingsStore.init(this)
        currentBvid = savedInstanceState?.getString(EXTRA_BVID) ?: intent.getStringExtra(EXTRA_BVID).orEmpty()
        currentAid = savedInstanceState?.getLong(EXTRA_AID) ?: intent.getLongExtra(EXTRA_AID, 0L)
        currentCid = savedInstanceState?.getLong(EXTRA_CID) ?: intent.getLongExtra(EXTRA_CID, 0L)
        resumeSegmentIndex = savedInstanceState?.getInt(STATE_SEGMENT_INDEX) ?: 0
        userWantsPlayback = savedInstanceState?.getBoolean(STATE_PLAY_WHEN_READY, true) ?: true
        restoreEndedState = savedInstanceState?.getBoolean(STATE_PLAY_ENDED) ?: false
        speedIndex = savedInstanceState?.getInt(STATE_SPEED_INDEX)?.coerceIn(speeds.indices) ?: 1
        localTaskId = savedInstanceState?.getLong(EXTRA_LOCAL_TASK_ID, intent.getLongExtra(EXTRA_LOCAL_TASK_ID, 0L))
            ?: intent.getLongExtra(EXTRA_LOCAL_TASK_ID, 0L)
        localSortOrder = runCatching {
            DownloadManager.SeasonSortOrder.valueOf(
                intent.getStringExtra(EXTRA_LOCAL_SORT_ORDER)
                    ?: localPlaybackPrefs.getString(KEY_LOCAL_SORT_ORDER, "").orEmpty()
            )
        }.getOrDefault(DownloadManager.SeasonSortOrder.DEFAULT)
        localPlayMode = runCatching {
            DownloadManager.LocalPlayMode.valueOf(
                localPlaybackPrefs.getString(KEY_LOCAL_PLAY_MODE, "").orEmpty()
            )
        }.getOrDefault(DownloadManager.LocalPlayMode.SEASON_SEQUENCE)
        isLocalMode = localTaskId > 0L
        // 重建时队列仍来自 Intent，但应恢复当前分集，而非队列最初的入口项。
        favoritePlayback = FavoritePlaybackLauncher.read(intent)
        val savedPosition = savedInstanceState?.takeIf { it.containsKey(STATE_PLAY_POSITION) }?.getLong(STATE_PLAY_POSITION)
        favoritePlayback?.takeIf { savedPosition == null }?.let { fav ->
            val entry = fav.entries.getOrNull(fav.startIndex) ?: fav.entries.first()
            currentBvid = entry.bvid
            currentAid = entry.aid
            currentCid = entry.cid
        }
        val start = historyPlaybackStart(savedPosition, savedInstanceState?.getBoolean(STATE_HISTORY_PENDING) == true,
            favoritePlayback?.startPositionMs, isLocalMode, intent.getLongExtra(EXTRA_HISTORY_POSITION_MS, 0L))
        pendingResumePositionMs = start.positionMs
        if (start.validateHistory) initialHistoryMedia = HistoryMediaId(currentBvid, currentAid, currentCid)
        onlinePlayMode = savedOnlinePlayMode(qualityPrefs)
        requestedQuality = loadQualityPreference()
        if (currentBvid.isBlank() && currentAid <= 0L && !isLocalMode) { finish(); return }
        if (!isLocalMode) {
            startupAtMs = SystemClock.elapsedRealtime()
            BiliPlayUrl.prewarm()
        }
        if (isLocalMode) {
            DownloadManager.init(this)
            val local = DownloadManager.task(localTaskId)
            if (local == null || !local.playable()) { finish(); return }
            refreshLocalPlaylist(local)
        }
        // 视频播放页顶部紧邻黑色视频窗口，状态栏固定纯黑配白色图标，底栏跟随主题色
        applyPlayerSystemBars()
        LayoutMode.applyScreenOrientation(this)

        activityRoot = FrameLayout(this).apply { setBackgroundColor(COLOR_BACKGROUND) }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }

        // ── Gesture detector ──
        gestureDetector = playerTapDetector(this, single = { if (!longPressBoost) toggleControls() }, armDouble = {
            handler.removeCallbacks(longPressBoostRunnable)
            longPressCandidate = false
        }) { x ->
            val delta = PlayerSeekPolicy.doubleTapDelta(isFullscreen, x, playerHost.width)
            if (delta == 0L) togglePlayPause() else skipByDoubleTap(delta)
        }

        // ── Player host: Media3 PlayerView owns aspect-ratio and video surface ──
        playerHost = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = buildConfiguredPlayerView()
        playerHost.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // 弹幕层位于视频之上、手势与控制层之下，不拦截触控；本地离线模式不显示弹幕
        danmakuView = DanmakuOverlayView(this).apply {
            visibility = if (isLocalMode) View.GONE else View.VISIBLE
        }
        playerHost.addView(danmakuView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (!isLocalMode) {
            danmakuController = DanmakuController(danmakuView)
            DanmakuSettingsStore.addListener(danmakuUiSettingsListener)
        }
        if (isLocalMode) {
            localAudioCoverOverlay = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
            }
            localAudioCoverView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            localAudioCoverOverlay!!.addView(localAudioCoverView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            localAudioCoverOverlay!!.addView(View(this).apply {
                setBackgroundColor(0x45000000)
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            localAudioCoverOverlay!!.addView(TextView(this).apply {
                text = "纯音频离线播放"
                textSize = 11.5f
                typeface = Ds.body(bold = true)
                setTextColor(Color.WHITE)
                background = Ds.pill(this@VideoDetailActivity, 0x80000000.toInt(), 0x33FFFFFF)
                setPadding(dp(11), dp(4), dp(11), dp(4))
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END).apply {
                topMargin = dp(12); rightMargin = dp(14)
            })
            playerHost.addView(localAudioCoverOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        // Gesture layer stays below all buttons, so it never steals their click events.
        gestureLayer = PlayerGestureView(this)
        playerHost.addView(gestureLayer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        playerSpinner = BrandSpinnerView(this, COLOR_ROSE, Color.WHITE).apply {
            contentDescription = "正在缓冲"
        }
        playerHost.addView(playerSpinner, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))

        playerError = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ds.card(this@VideoDetailActivity, 0xCC14161B.toInt(), 0x26FFFFFF, Ds.Radius.card)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            isClickable = true
            Ds.bindPressFeedback(this, 0.96f)
            setOnClickListener { retryPlayer() }
        }
        playerHost.addView(playerError, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(24); rightMargin = dp(24)
        })

        // ── 顶部控制栏：返回键 + 全屏时标题（随控制层同显同隐，按压回弹） ──
        topControlsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(14), dp(6))
        }
        playerBack = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.STROKE
                strokeWidth = dpf(2.2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            private val path = Path()
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f; val cy = height / 2f; val arm = dpf(6.5f)
                path.rewind()
                path.moveTo(cx + arm * 0.45f, cy - arm)
                path.lineTo(cx - arm * 0.45f, cy)
                path.lineTo(cx + arm * 0.45f, cy + arm)
                canvas.drawPath(path, paint)
            }
        }.apply {
            contentDescription = "返回"
            isClickable = true
            Ds.bindPressFeedback(this, 0.88f)
            setOnClickListener { if (isFullscreen) toggleFullscreen() else finish() }
        }
        playerTopTitle = TextView(this).apply {
            textSize = 14.5f
            typeface = Ds.display(bold = true)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(dpf(4f), 0f, dpf(1f), 0x80000000.toInt())
            visibility = View.GONE
        }
        topControlsBar.addView(playerBack, LinearLayout.LayoutParams(dp(38), dp(38)))
        topControlsBar.addView(playerTopTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
        })
        // 标题隐藏时用占位 spacer 把右侧按钮顶到最右，避免小窗下按钮贴到返回键旁边
        topBarSpacer = View(this).apply { visibility = View.VISIBLE }
        topControlsBar.addView(topBarSpacer, LinearLayout.LayoutParams(0, 0, 1f))

        // 右上角「后台播放」：转成在线音频页并从当前进度继续
        backgroundPlayBtn = BackgroundPlayIcon(this).apply {
            contentDescription = "后台播放"
            isClickable = true
            Ds.bindPressFeedback(this, 0.88f)
            setOnClickListener { enterBackgroundAudioPlayback() }
        }
        topControlsBar.addView(backgroundPlayBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
            leftMargin = dp(6)
        })

        // ── Center replay button (only visible after playback ends) ──
        replayBtn = ReplayIcon(this).apply {
            visibility = View.GONE; alpha = 0f
            isClickable = true
            Ds.bindPressFeedback(this, 0.90f)
            setOnClickListener { replay() }
        }
        playerHost.addView(replayBtn, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))

        // ── Boost indicator: translucent pill shown above the video while long-press boosting ──
        boostIndicator = TextView(this).apply {
            text = "2.0x 倍速播放中"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = Ds.pill(this@VideoDetailActivity, 0xA616181D.toInt(), 0x33FFFFFF)
            alpha = 0f
            visibility = View.GONE
        }
        playerHost.addView(boostIndicator, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(18) })

        // ── Brightness / volume adjustment indicator (center overlay) ──
        adjustIndicator = AdjustIndicator(this).apply {
            alpha = 0f
            visibility = View.GONE
            // Compact in portrait; full size in fullscreen (scale updated on toggle).
            scaleX = COMPACT_ADJUST_SCALE
            scaleY = COMPACT_ADJUST_SCALE
        }
        playerHost.addView(adjustIndicator, FrameLayout.LayoutParams(dp(72), dp(170), Gravity.CENTER))

        // ── Control overlay ──
        createControlOverlay()
        playerSeekHint = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setShadowLayer(dpf(3f), 0f, dpf(1f), 0xB3000000.toInt())
            visibility = View.GONE
        }
        playerHost.addView(playerSeekHint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        progressRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionSeekHint() }
        playerSkipHint = TextView(this).apply {
            textSize = 13f
            typeface = Ds.body(true)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(dpf(3f), 0f, dpf(1f), 0xB3000000.toInt())
            translationY = dpf(38f)
            visibility = View.GONE
        }
        playerHost.addView(playerSkipHint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        infoArea = createInfoArea()
        tabletSplitDivider = View(this).apply {
            setBackgroundColor(Ds.colors.lineSoft)
            visibility = View.GONE
        }
        page.addView(playerHost)
        page.addView(tabletSplitDivider)
        page.addView(infoArea)
        applyPageSplitLayout()
        activityRoot.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(activityRoot)
        if (isLocalMode) {
            loadLocalPlayer()
        } else {
            loadVideoAndPlayer()
            BiliSessionStore.refreshVipInfo()
        }
    }

    // ─────────────────────────────────────────────────
    //  Control Overlay
    // ─────────────────────────────────────────────────

    private fun createControlOverlay() {
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = null
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(6))
        }

        // Play/Pause
        playPauseBtn = PlayPauseIcon(this).apply {
            isClickable = true
            Ds.bindPressFeedback(this, 0.88f)
            setOnClickListener { togglePlayPause() }
        }
        bottomBar.addView(playPauseBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER })

        // Next episode（仅全屏且有下一集时显示，按合集排序顺序）
        nextEpisodeBtn = NextEpisodeIcon(this).apply {
            isClickable = true
            contentDescription = "下一集"
            visibility = View.GONE
            Ds.bindPressFeedback(this, 0.88f)
            setOnClickListener { playNextEpisode() }
        }
        bottomBar.addView(nextEpisodeBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(8)
        })

        // 弹幕开关 + 弹幕设置（仅在线播放且全屏时展示）
        if (!isLocalMode) {
            danmakuToggleBtn = DanmakuUi.ToggleIconView(this, onDarkPlayer = true).apply {
                visibility = View.GONE
                isClickable = true
                setDanmakuEnabled(DanmakuSettingsStore.snapshot().enabled)
                Ds.bindPressFeedback(this, 0.88f)
                setOnClickListener {
                    DanmakuSettingsStore.toggleEnabled()
                    if (exoPlayer?.isPlaying == true) scheduleAutoHide()
                }
            }
            bottomBar.addView(danmakuToggleBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(10)
            })

            danmakuSettingsBtn = DanmakuUi.SettingsIconView(this).apply {
                visibility = View.GONE
                isClickable = true
                Ds.bindPressFeedback(this, 0.88f)
                setOnClickListener { openDanmakuSettingsDrawer() }
            }
            bottomBar.addView(danmakuSettingsBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(6)
            })
        }

        // Right side: speed / quality / fullscreen
        bottomBar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        // Speed
        speedBtn = TextView(this).apply {
            text = "${speeds[speedIndex]}x"; textSize = 11f
            setTextColor(Color.WHITE); typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
            maxLines = 1
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = Ds.pill(this@VideoDetailActivity, 0x33000000, 0x2EFFFFFF)
            isClickable = true
            Ds.bindPressFeedback(this, 0.90f)
            setOnClickListener { showSpeedMenu() }
        }
        bottomBar.addView(speedBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)).apply {
            gravity = Gravity.CENTER
            rightMargin = dp(6)
        })

        // Quality (shows the actual playing resolution, e.g. 1080p60)
        qualityBtn = TextView(this).apply {
            text = "自动"; textSize = 11f
            setTextColor(Color.WHITE); typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
            maxLines = 1
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = Ds.pill(this@VideoDetailActivity, 0x33000000, 0x2EFFFFFF)
            isClickable = true
            Ds.bindPressFeedback(this, 0.90f)
            setOnClickListener { showQualityMenu(this) }
        }
        bottomBar.addView(qualityBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)).apply {
            gravity = Gravity.CENTER
            rightMargin = dp(6)
        })
        // 本地文件播放时画质固定，不展示切换按钮
        if (isLocalMode) qualityBtn.visibility = View.GONE

        // Fullscreen
        fsBtn = FullscreenIcon(this).apply {
            isClickable = true
            Ds.bindPressFeedback(this, 0.88f)
            setOnClickListener { toggleFullscreen() }
        }
        bottomBar.addView(fsBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER })

        // ── Progress row: current time | seek bar | total duration ──
        progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        timeText = TextView(this).apply {
            text = "00:00"
            textSize = 10.5f
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        progressRow.addView(timeText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL })

        progressContainer = PlaybackProgressBar(this).apply {
            isClickable = true
            var touchGeneration = -1
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchGeneration = playbackGeneration
                else if (!isDragging || touchGeneration != playbackGeneration) return@setOnTouchListener true
                val p = exoPlayer ?: return@setOnTouchListener true
                val totalDuration = playerGlobalDuration(p)
                if (totalDuration <= 0L || width <= 0) return@setOnTouchListener true
                val ratio = (event.x / width.toFloat()).coerceIn(0f, 1f)
                val seekPos = (ratio * totalDuration).toLong()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        controlsHideRunnable?.let(handler::removeCallbacks)
                        isDragging = true
                        isSeeking = true
                        seekWasPlaying = p.playWhenReady && userWantsPlayback
                        p.pause()
                        seekToFraction(ratio)
                        showSeekHint(seekPos, totalDuration)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        seekToFraction(ratio)
                        showSeekHint(seekPos, totalDuration)
                    }
                    MotionEvent.ACTION_UP -> {
                        seekPlayerGlobal(p, seekPos)
                        danmakuController?.onSeeked(seekPos)
                        if (seekWasPlaying && activityResumed && userWantsPlayback) p.play()
                        isDragging = false
                        isSeeking = false
                        hideSeekHint()
                        if (p.isPlaying || seekWasPlaying) scheduleAutoHide()
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (seekWasPlaying && activityResumed && userWantsPlayback) p.play()
                        isDragging = false
                        isSeeking = false
                        hideSeekHint()
                        if (p.isPlaying || seekWasPlaying) scheduleAutoHide()
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_UP) updatePlaybackProgress()
                else onManualSeek(seekPos, totalDuration)
                true
            }
        }
        progressRow.addView(progressContainer, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            leftMargin = dp(8); rightMargin = dp(8) })

        durationText = TextView(this).apply {
            text = "00:00"
            textSize = 10.5f
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            includeFontPadding = false
        }
        progressRow.addView(durationText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER_VERTICAL })

        controlOverlay = FrameLayout(this).apply {
            visibility = View.VISIBLE
            background = null
        }
        // 顶部/底部柔和暗角遮罩（不拦截点击，确保白色控件和文字清晰）
        controlOverlay.addView(View(this).apply {
            isClickable = false
            isFocusable = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), 0x00000000)
            )
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(72), Gravity.TOP))
        controlOverlay.addView(View(this).apply {
            isClickable = false
            isFocusable = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xCC000000.toInt())
            )
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(104), Gravity.BOTTOM))

        controlOverlay.addView(buildFullscreenStatusBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(3)
        })
        controlOverlay.addView(topControlsBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        controlOverlay.addView(progressRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(24), Gravity.BOTTOM).apply {
            leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(32)
        })
        controlOverlay.addView(bottomBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // 右侧合集入口箭头：只在全屏且有合集时显示，随控制层同显同隐
        seasonEntryBtn = buildSeasonEntryButton().also { btn ->
            controlOverlay.addView(btn, FrameLayout.LayoutParams(
                dp(34), dp(52), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = dp(10)
            })
        }

        playerHost.addView(controlOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        playerHost.addView(buildSeasonDrawerOverlay(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (!isLocalMode) {
            danmakuSettingsDrawer = DanmakuUi.FullscreenSettingsDrawer(
                context = this,
                reducedMotion = reducedMotion,
                onBeforeOpen = {
                    if (seasonDrawerOpen) closeSeasonDrawer(immediate = true)
                    controlsHideRunnable?.let { handler.removeCallbacks(it) }
                    if (controlsVisible) hideControls()
                }
            ).also { drawer ->
                playerHost.addView(drawer.overlay, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
        }
        applyControlSizing()
    }

    /**
     * 控制栏随容器切换紧凑/全屏尺寸，避免在 16:9 小窗中过度抢占画面，也避免全屏时按钮过小。
     */
    private fun applyControlSizing() {
        if (!::bottomBar.isInitialized) return
        updateSeasonEntryVisibility()
        val compact = !isFullscreen
        bottomBar.setPadding(dp(if (compact) 8 else 14), dp(if (compact) 2 else 6), dp(if (compact) 8 else 14), dp(if (compact) 6 else 12))
        playPauseBtn.layoutParams = LinearLayout.LayoutParams(
            dp(if (compact) 30 else 36), dp(if (compact) 30 else 36)).apply { gravity = Gravity.CENTER }
        nextEpisodeBtn?.layoutParams = LinearLayout.LayoutParams(
            dp(if (compact) 30 else 36), dp(if (compact) 30 else 36)).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(8)
        }
        val showFullscreenDanmaku = isFullscreen && !isLocalMode
        danmakuToggleBtn?.apply {
            visibility = if (showFullscreenDanmaku) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(10)
            }
        }
        danmakuSettingsBtn?.apply {
            visibility = if (showFullscreenDanmaku) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(6)
            }
        }
        speedBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(if (compact) 26 else 30)).apply {
            gravity = Gravity.CENTER; rightMargin = dp(if (compact) 6 else 8)
        }
        speedBtn.minWidth = dp(if (compact) 40 else 48)
        speedBtn.setPadding(dp(if (compact) 8 else 11), 0, dp(if (compact) 8 else 11), 0)
        speedBtn.textSize = if (compact) 11f else 12f
        qualityBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(if (compact) 26 else 30)).apply {
            gravity = Gravity.CENTER; rightMargin = dp(if (compact) 6 else 8)
        }
        qualityBtn.minWidth = dp(if (compact) 46 else 56)
        qualityBtn.setPadding(dp(if (compact) 10 else 13), 0, dp(if (compact) 10 else 13), 0)
        qualityBtn.textSize = if (compact) 11f else 12f
        fsBtn.layoutParams = LinearLayout.LayoutParams(
            dp(if (compact) 30 else 36), dp(if (compact) 30 else 36)).apply { gravity = Gravity.CENTER }
        if (::progressRow.isInitialized) {
            progressRow.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(if (compact) 24 else 28), Gravity.BOTTOM).apply {
                leftMargin = dp(if (compact) 12 else 18)
                rightMargin = dp(if (compact) 12 else 18)
                bottomMargin = dp(if (compact) 32 else 46)
            }
            timeText.textSize = if (compact) 10.5f else 11.5f
            durationText.textSize = if (compact) 10.5f else 11.5f
        }
        if (::topControlsBar.isInitialized) {
            topControlsBar.setPadding(dp(if (compact) 8 else 14), dp(if (compact) 6 else 14), dp(if (compact) 8 else 14), dp(6))
            val topBtnSize = dp(if (compact) 38 else 42)
            playerBack.layoutParams = LinearLayout.LayoutParams(topBtnSize, topBtnSize)
            backgroundPlayBtn.layoutParams = LinearLayout.LayoutParams(topBtnSize, topBtnSize).apply {
                leftMargin = dp(6)
            }
            backgroundPlayBtn.setCompact(compact)
            val showTitle = isFullscreen && !playerTopTitle.text.isNullOrBlank()
            playerTopTitle.visibility = if (showTitle) View.VISIBLE else View.GONE
            topBarSpacer.visibility = if (showTitle) View.GONE else View.VISIBLE
            updateBackgroundPlayVisibility()
        }
        updateFullscreenStatusVisibility()
    }

    // ─────────────────────────────────────────────────
    //  Fullscreen status bar (clock + battery)
    // ─────────────────────────────────────────────────

    /** 顶部居中的系统时间 + 电池电量，仅横屏全屏且控制面板可见时展示。 */
    private fun buildFullscreenStatusBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        val clock = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            maxLines = 1
            setTextColor(0xE6FFFFFF.toInt())
            setShadowLayer(dpf(2.5f), 0f, dpf(1f), 0x80000000.toInt())
        }
        bar.addView(clock)
        val battery = BatteryStatusIcon(this)
        bar.addView(battery, LinearLayout.LayoutParams(dp(20), dp(11)).apply {
            leftMargin = dp(6)
        })
        val percentText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
            typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            maxLines = 1
            setTextColor(0xCCFFFFFF.toInt())
            setShadowLayer(dpf(2.5f), 0f, dpf(1f), 0x80000000.toInt())
            visibility = View.GONE
        }
        bar.addView(percentText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(3)
        })
        fullscreenStatusBar = bar
        statusClockText = clock
        statusBatteryIcon = battery
        statusBatteryPercentText = percentText
        updateStatusClock()
        return bar
    }

    private fun updateStatusClock() {
        val clock = statusClockText ?: return
        val pattern = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        val format = statusTimeFormat.get() ?: return
        if (format.toPattern() != pattern) format.applyPattern(pattern)
        clock.text = format.format(Date())
    }

    private fun applyBatteryState(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
        statusBatteryIcon?.update(percent, charging)
        val percentText = statusBatteryPercentText ?: return
        if (percent < 0) {
            percentText.visibility = View.GONE
            return
        }
        percentText.visibility = View.VISIBLE
        percentText.text = "$percent%"
        percentText.setTextColor(
            when {
                charging -> 0xFF70C493.toInt()
                percent <= 20 -> 0xFFEB6F79.toInt()
                else -> 0xCCFFFFFF.toInt()
            }
        )
    }

    private fun refreshBatteryState() {
        val intent = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return
        applyBatteryState(intent)
    }

    /** 横屏全屏才显示；需在 enterFullscreen / onConfigurationChanged / applyControlSizing 处同步。 */
    private fun updateFullscreenStatusVisibility() {
        val bar = fullscreenStatusBar ?: return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        bar.visibility = if (isFullscreen && landscape) View.VISIBLE else View.GONE
    }

    private fun registerStatusReceiver() {
        if (statusReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(statusReceiver, filter)
            }
            statusReceiverRegistered = true
        }
    }

    private fun unregisterStatusReceiver() {
        if (!statusReceiverRegistered) return
        runCatching { unregisterReceiver(statusReceiver) }
        statusReceiverRegistered = false
    }

    // ─────────────────────────────────────────────────
    //  Fullscreen season drawer
    // ─────────────────────────────────────────────────
    /** 全屏抽屉统一条目：在线合集用 UgcEpisode，本地合集用 taskId。 */
    private data class FullscreenSeasonItem(
        val label: String,
        val title: String,
        val isCurrent: Boolean,
        val episode: UgcEpisode? = null,
        val localTaskId: Long = 0L
    )

    private fun buildSeasonEntryButton(): View = object : View(this) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.8f * density
            color = 0xE6FFFFFF.toInt()
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            // 左指折线箭头：表达从右侧滑出抽屉
            path.rewind()
            path.moveTo(w * 0.36f, h * 0.36f)
            path.lineTo(w * 0.18f, h * 0.50f)
            path.lineTo(w * 0.36f, h * 0.64f)
            canvas.drawPath(path, paint)
            // 右侧三条等距列表线
            canvas.drawLine(w * 0.50f, h * 0.36f, w * 0.84f, h * 0.36f, paint)
            canvas.drawLine(w * 0.50f, h * 0.50f, w * 0.84f, h * 0.50f, paint)
            canvas.drawLine(w * 0.50f, h * 0.64f, w * 0.74f, h * 0.64f, paint)
        }
    }.apply {
        background = Ds.card(this@VideoDetailActivity, 0x7314161B, 0x38FFFFFF, Ds.Radius.round)
        isClickable = true
        contentDescription = "合集列表"
        visibility = View.GONE
        Ds.bindPressFeedback(this, 0.90f)
        setOnClickListener { openSeasonDrawer() }
    }

    private fun buildSeasonDrawerOverlay(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x4D000000)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeSeasonDrawer() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.plate(0xC414161B.toInt(), 0x2EFFFFFF, Ds.hairline(this@VideoDetailActivity), dp(18).toFloat())
            isClickable = true
            Ds.clipRounded(this, 18)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
        }
        val headerTitle = TextView(this).apply {
            text = "合集"
            textSize = 13f
            typeface = Ds.display(bold = true)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(150)
        }
        seasonDrawerTitleText = headerTitle
        header.addView(headerTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        seasonDrawerCountText = TextView(this).apply {
            text = ""
            textSize = 12f
            typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
            setTextColor(0xFFF27D9E.toInt())
        }
        header.addView(seasonDrawerCountText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        drawerPlayMode = PlayModeIconView(this, currentPlayMode(), Color.WHITE, 1.7f)
        drawerPlayModeButton = FrameLayout(this).apply {
            isFocusable = true
            contentDescription = "播放模式：${currentPlayMode().label()}"
            addView(drawerPlayMode, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener {
                val modes = DownloadManager.LocalPlayMode.entries
                drawerModePopup?.dismiss()
                drawerModePopup = showAnchoredMenu(this, modes.map { it.label() }, modes.indexOf(currentPlayMode()), playerStyle = true) { i ->
                    if (isLocalMode) setLocalPlayMode(modes[i]) else setOnlinePlayMode(modes[i])
                }
                drawerModePopup?.setOnDismissListener { drawerModePopup = null }
            }
        }
        header.addView(drawerPlayModeButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(FrameLayout(this).apply {
            isFocusable = true
            contentDescription = "关闭合集"
            addView(Ds.closeIcon(this@VideoDetailActivity, 0xB3FFFFFF.toInt(), 1.7f),
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { closeSeasonDrawer() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(View(this).apply { setBackgroundColor(0x1FFFFFFF) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)))

        val drawerAdapter = seasonDrawerAdapter()
        seasonDrawerAdapter = drawerAdapter
        val list = ListView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(4)
            setSelector(android.R.color.transparent)
            setPadding(dp(8), dp(6), dp(8), dp(10))
            clipToPadding = false
            adapter = drawerAdapter
        }
        seasonDrawerList = list
        panel.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        overlay.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END or Gravity.CENTER_VERTICAL).apply {
            topMargin = dp(12); bottomMargin = dp(12); rightMargin = dp(12)
        })
        seasonDrawerOverlay = overlay
        seasonDrawerPanel = panel
        return overlay
    }

    private fun seasonDrawerAdapter(): BaseAdapter = object : BaseAdapter() {
        override fun getCount(): Int = seasonDrawerItems.size
        override fun getItem(position: Int): FullscreenSeasonItem = seasonDrawerItems[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = seasonDrawerItems[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@VideoDetailActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                isClickable = true
                Ds.bindPressFeedback(this, 0.97f)
                addView(TextView(this@VideoDetailActivity).apply {
                    textSize = 12f
                    typeface = Ds.body(bold = true)
                    fontFeatureSettings = "tnum"
                    includeFontPadding = false
                    maxLines = 1
                    minWidth = dp(34)
                    tag = "label"
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                })
                addView(TextView(this@VideoDetailActivity).apply {
                    textSize = 13f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                    setLineSpacing(dpf(2f), 1f)
                    tag = "title"
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val labelTv = row.findViewWithTag<TextView>("label")
            val titleTv = row.findViewWithTag<TextView>("title")
            val accent = 0xFFF27D9E.toInt()
            // 收藏夹态不写 P 编号，当前项前改用动态音柱图标
            val inFavorite = favoritePlayback != null
            labelTv.text = if (inFavorite) "" else item.label
            labelTv.setCompoundDrawablesWithIntrinsicBounds(
                if (inFavorite && item.isCurrent) PlayingBarsDrawable(0xFFF27D9E.toInt(), dp(11)) else null,
                null, null, null
            )
            labelTv.compoundDrawablePadding = dp(4)
            labelTv.minWidth = if (inFavorite) 0 else dp(34)
            titleTv.text = item.title
            if (item.isCurrent) {
                row.background = Ds.card(
                    this@VideoDetailActivity, 0x2BF27D9E, accent, Ds.Radius.control,
                    Ds.hairline(this@VideoDetailActivity)
                )
                labelTv.setTextColor(accent)
                titleTv.setTextColor(accent)
                titleTv.typeface = Ds.body(bold = true)
            } else {
                row.background = null
                labelTv.setTextColor(0x99FFFFFF.toInt())
                titleTv.setTextColor(0xEBFFFFFF.toInt())
                titleTv.typeface = Ds.body(bold = false)
            }
            row.setOnClickListener {
                if (item.isCurrent) {
                    closeSeasonDrawer()
                    return@setOnClickListener
                }
                closeSeasonDrawer()
                item.episode?.let(::selectEpisode) ?: switchLocalTask(item.localTaskId)
            }
            return row
        }
    }

    private fun hasFullscreenSeason(): Boolean = collectSeasonItems().size > 1

    private fun collectSeasonItems(): List<FullscreenSeasonItem> {
        // 收藏夹播放态：列表以收藏夹为准，屏蔽视频自身的 ugc_season
        favoritePlayback?.let { fav ->
            val currentIdx = fav.entries.playingIndex(currentBvid, currentAid, currentCid)
            return fav.entries.mapIndexed { index, episode ->
                FullscreenSeasonItem(
                    label = "${index + 1}",
                    title = episode.title,
                    episode = episode,
                    isCurrent = index == currentIdx
                )
            }
        }
        if (isLocalMode) {
            return localPlaylist.mapIndexed { index, task ->
                FullscreenSeasonItem(
                    label = if (task.episodeNo > 0) "P${task.episodeNo}" else "P${index + 1}",
                    title = task.title,
                    localTaskId = task.id,
                    isCurrent = task.id == localTaskId
                )
            }
        }
        val episodes = (currentDetail?.season ?: cachedSeason)?.episodes.orEmpty()
        val currentIndex = episodes.playingIndex(currentBvid, currentAid, currentCid)
        return episodes.mapIndexed { index, episode ->
            FullscreenSeasonItem(
                label = "P${index + 1}",
                title = episode.title,
                episode = episode,
                isCurrent = index == currentIndex
            )
        }
    }

    /** 合集排序中的下一集（不受播放模式影响，手动切换专用）。 */
    private sealed class NextSeasonTarget {
        data class Online(val episode: UgcEpisode) : NextSeasonTarget()
        data class Local(val taskId: Long) : NextSeasonTarget()
    }

    private fun resolveNextSeasonTarget(): NextSeasonTarget? {
        if (isLocalMode) {
            val idx = localPlaylist.indexOfFirst { it.id == localTaskId }
            if (idx < 0 || idx >= localPlaylist.lastIndex) return null
            return NextSeasonTarget.Local(localPlaylist[idx + 1].id)
        }
        val episodes = favoritePlayback?.entries ?: (currentDetail?.season ?: cachedSeason)?.episodes.orEmpty()
        val idx = episodes.playingIndex(currentBvid, currentAid, currentCid)
        if (episodes.size <= 1 || idx !in 0 until episodes.lastIndex) return null
        return NextSeasonTarget.Online(episodes[idx + 1])
    }

    private fun playNextEpisode() {
        val target = resolveNextSeasonTarget() ?: return
        if (seasonDrawerOpen) closeSeasonDrawer(immediate = true)
        when (target) {
            is NextSeasonTarget.Online -> {
                val episode = target.episode
                if (episode.bvid.isBlank() && episode.aid <= 0L) return
                selectEpisode(episode)
            }
            is NextSeasonTarget.Local -> switchLocalTask(target.taskId)
        }
    }

    private fun updateSeasonEntryVisibility() {
        val btn = seasonEntryBtn ?: return
        val show = isFullscreen && hasFullscreenSeason()
        btn.visibility = if (show) View.VISIBLE else View.GONE
        if (!show && seasonDrawerOpen) closeSeasonDrawer()
        updateNextEpisodeVisibility()
    }

    /** 下一集按钮：仅全屏且存在合集排序中的下一集时显示。 */
    private fun updateNextEpisodeVisibility() {
        val btn = nextEpisodeBtn ?: return
        btn.visibility = if (isFullscreen && resolveNextSeasonTarget() != null) View.VISIBLE else View.GONE
    }

    /** 抽屉展开期间发生切集（含自动连播）时，同步刷新条目与当前集高亮。 */
    private fun refreshSeasonDrawerSelection() {
        updateSeasonEntryVisibility()
        if (!seasonDrawerOpen) return
        seasonDrawerItems = collectSeasonItems()
        val currentIndex = seasonDrawerItems.indexOfFirst { it.isCurrent }
        seasonDrawerTitleText?.text = favoritePlayback?.folderName?.takeIf { it.isNotBlank() } ?: "合集"
        val unit = if (favoritePlayback != null) "个" else "集"
        seasonDrawerCountText?.text = if (currentIndex >= 0) {
            "${currentIndex + 1}/${seasonDrawerItems.size}"
        } else "共 ${seasonDrawerItems.size} $unit"
        seasonDrawerAdapter?.notifyDataSetChanged()
        if (currentIndex >= 0) {
            seasonDrawerList?.post {
                seasonDrawerList?.setSelectionFromTop(
                    currentIndex, ((seasonDrawerList?.height ?: 0) / 3).coerceAtLeast(0))
            }
        }
    }

    private fun openSeasonDrawer() {
        if (seasonDrawerOpen || seasonDrawerAnimating) return
        val overlay = seasonDrawerOverlay ?: return
        val panel = seasonDrawerPanel ?: return
        val list = seasonDrawerList ?: return
        seasonDrawerItems = collectSeasonItems()
        if (seasonDrawerItems.size <= 1) return
        val currentIndex = seasonDrawerItems.indexOfFirst { it.isCurrent }
        seasonDrawerTitleText?.text = favoritePlayback?.folderName?.takeIf { it.isNotBlank() } ?: "合集"
        val unit = if (favoritePlayback != null) "个" else "集"
        seasonDrawerCountText?.text = if (currentIndex >= 0) {
            "${currentIndex + 1}/${seasonDrawerItems.size}"
        } else "共 ${seasonDrawerItems.size} $unit"
        seasonDrawerAdapter?.notifyDataSetChanged()

        controlsHideRunnable?.let { handler.removeCallbacks(it) }
        if (controlsVisible) hideControls()

        val panelWidth = computeSeasonPanelWidth()
        panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply { width = panelWidth }
        panel.requestLayout()

        seasonDrawerOpen = true
        overlay.visibility = View.VISIBLE
        overlay.alpha = if (reducedMotion) 1f else 0f
        panel.translationX = if (reducedMotion) 0f else panelWidth.toFloat()
        if (reducedMotion) {
            seasonDrawerAnimating = false
        } else {
            seasonDrawerAnimating = true
            overlay.animate().alpha(1f).setDuration(MotionTokens.panelMs)
                .setInterpolator(MotionTokens.easeOut).start()
            panel.animate().translationX(0f).setDuration(MotionTokens.panelMs)
                .setInterpolator(MotionTokens.drawer).withEndAction { seasonDrawerAnimating = false }.start()
        }
        if (currentIndex >= 0) {
            list.post { list.setSelectionFromTop(currentIndex, (list.height / 3).coerceAtLeast(0)) }
        }
    }

    private fun closeSeasonDrawer(immediate: Boolean = false) {
        drawerModePopup?.dismiss()
        if (!seasonDrawerOpen) return
        seasonDrawerOpen = false
        val overlay = seasonDrawerOverlay ?: return
        val panel = seasonDrawerPanel ?: return
        if (immediate || reducedMotion) {
            overlay.visibility = View.GONE
            overlay.alpha = 1f
            panel.translationX = 0f
            seasonDrawerAnimating = false
            return
        }
        seasonDrawerAnimating = true
        val panelWidth = computeSeasonPanelWidth()
        panel.animate().translationX(panelWidth.toFloat()).setDuration(MotionTokens.smallMs)
            .setInterpolator(MotionTokens.easeOut).start()
        overlay.animate().alpha(0f).setDuration(MotionTokens.smallMs)
            .setInterpolator(MotionTokens.easeOut).withEndAction {
                overlay.visibility = View.GONE
                overlay.alpha = 1f
                panel.translationX = 0f
                seasonDrawerAnimating = false
            }.start()
    }

    /** 面板宽度按横屏实际宽高实时计算，避免旋转未完成时按竖屏宽度算窄。 */
    private fun computeSeasonPanelWidth(): Int {
        val host = if (::playerHost.isInitialized) playerHost else null
        val base = host?.let { maxOf(it.width, it.height) }?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        return (base * 0.38f).toInt().coerceIn(dp(248), dp(320))
    }

    // ─────────────────────────────────────────────────
    //  Info Area
    // ─────────────────────────────────────────────────

    private fun createInfoArea(): View {
        // 本地播放：不展示简介/评论，只显示标题、大小、时长，其余留白。
        if (isLocalMode) return createLocalInfoArea()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }

        // ── Tab bar: 简介 / 评论 + 极细底部分割线 ──
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        fun buildTab(label: String, active: Boolean, withBadge: Boolean = false): Pair<TextView, View> {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), 0, dp(2), 0)
                isClickable = true
            }
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(7))
            }
            val labelView = TextView(this).apply {
                text = label; textSize = 15f
                typeface = Ds.display(bold = true)
                gravity = Gravity.CENTER
                setTextColor(if (active) COLOR_ROSE else COLOR_MUTED)
            }
            labelRow.addView(labelView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (withBadge) {
                commentBubble = TextView(this).apply {
                    textSize = 11.5f
                    typeface = Ds.body(bold = false)
                    fontFeatureSettings = "tnum"
                    setTextColor(COLOR_MUTED)
                    text = ""
                    visibility = View.GONE
                }
                labelRow.addView(commentBubble, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(4)
                })
            }
            val indicator = View(this).apply {
                background = Ds.pill(this@VideoDetailActivity, COLOR_ROSE)
                alpha = if (active) 1f else 0f
            }
            container.addView(labelRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            container.addView(indicator, LinearLayout.LayoutParams(dp(20), dp(3)))
            return labelView to indicator
        }
        val introTab = buildTab("简介", active = true)
        val commentTab = buildTab("评论", active = false, withBadge = true)
        tabIntro = introTab.first
        tabIntroIndicator = introTab.second
        tabComment = commentTab.first
        tabCommentIndicator = commentTab.second
        (introTab.second.parent as? View)?.setOnClickListener { selectTab(0) }
        (commentTab.second.parent as? View)?.setOnClickListener { selectTab(1) }
        tabBar.addView(introTab.second.parent as View, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        tabBar.addView(commentTab.second.parent as View, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(28)
        })
        tabBar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        portraitDanmakuToggle = DanmakuUi.PortraitTabToggle(this).apply {
            updateState(DanmakuSettingsStore.snapshot().enabled)
            setOnClickListener { DanmakuSettingsStore.toggleEnabled() }
        }
        tabBar.addView(portraitDanmakuToggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL })
        root.addView(tabBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(View(this).apply { setBackgroundColor(COLOR_BORDER) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)))
        infoTabBarAnchor = tabBar

        // ── Intro tab content ──
        introScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        infoContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(36))
        }
        infoState = TextView(this).apply {
            text = "正在加载简介…"
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(COLOR_MUTED)
            setPadding(0, dp(28), 0, dp(28))
        }
        infoContent.addView(infoState, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        introScroll.addView(infoContent, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── Comment tab content ──
        commentScroll = CommentListView(this, { showSnackbar(it) }, {
            val pos = IntArray(2)
            infoTabBarAnchor?.getLocationOnScreen(pos)
            pos[1] + (infoTabBarAnchor?.height ?: dp(64))
        }).apply {
            restoreState(restoredComments)
            restoreRetainedState(lastNonConfigurationInstance as? CommentListView.RetainedState)
        }
        restoredComments = null

        // ── Horizontal pager: 简介 | 评论 (drag + fling, tab bar stays in sync) ──
        infoPager = HorizontalPagerView(this).apply {
            pageChangeListener = { index -> updateTabUI(index) }
            // Tab visuals follow the swipe in real time (fade + text color lerp).
            pageProgressListener = { progress -> syncTabProgress(progress) }
        }
        infoPager.addView(introScroll, 0)
        infoPager.addView(commentScroll, 1)
        root.addView(infoPager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        selectTab(0)
        return root
    }

    private fun selectTab(index: Int) {
        if (index == 1) ensureCommentsStarted()
        if (currentTab == index) return
        infoPager.scrollToPage(index, smooth = true)
        // Tab visuals follow the pager via pageProgressListener; updateTabUI fires on settle.
    }

    private fun ensureCommentsStarted() {
        val item = currentDetail ?: return
        if (::commentScroll.isInitialized && item.aid > 0L) {
            commentScroll.bindTarget(item.aid.toString(), 1, item.ownerMid)
            commentScroll.setPageActive(activityResumed && !isFullscreen)
        }
    }

    private fun updateTabUI(index: Int) {
        if (currentTab == index) {
            syncTabVisuals(index)
            if (::commentScroll.isInitialized) commentScroll.setPageActive(index == 1 && activityResumed && !isFullscreen)
            return
        }
        currentTab = index
        syncTabVisuals(index)
        if (index == 1) ensureCommentsStarted()
        if (::commentScroll.isInitialized) commentScroll.setPageActive(index == 1 && activityResumed && !isFullscreen)
    }

    /** Final visual state once the pager has settled on a page. */
    private fun syncTabVisuals(index: Int) {
        val intro = index == 0
        tabIntro.setTextColor(if (intro) COLOR_ROSE else COLOR_MUTED)
        tabComment.setTextColor(if (intro) COLOR_MUTED else COLOR_ROSE)
        tabIntroIndicator.alpha = if (intro) 1f else 0f
        tabCommentIndicator.alpha = if (intro) 0f else 1f
        if (::commentBubble.isInitialized) {
            commentBubble.setTextColor(if (intro) COLOR_MUTED else COLOR_ROSE)
        }
    }

    /** Live tab visuals while the pager is being dragged: fade + color lerp follow the finger. */
    private fun syncTabProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        // 横滑明确朝评论页移动时立即提前发评论请求，省去切页动画结束后再等的 260ms
        if (p > 0.18f) ensureCommentsStarted()
        tabIntro.setTextColor(lerpColor(COLOR_ROSE, COLOR_MUTED, p))
        tabComment.setTextColor(lerpColor(COLOR_MUTED, COLOR_ROSE, p))
        tabIntroIndicator.alpha = 1f - p
        tabCommentIndicator.alpha = p
        if (::commentBubble.isInitialized) {
            commentBubble.setTextColor(lerpColor(COLOR_MUTED, COLOR_ROSE, p))
        }
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int =
        (android.animation.ArgbEvaluator().evaluate(t.coerceIn(0f, 1f), from, to) as Int)

    /** Video like/unlike: optimistic UI, then real server sync when logged in. */
    private fun toggleVideoLike(btn: ActionIconView) {
        if (!BiliSessionStore.isLoggedIn()) {
            showSnackbar("请先登录后再点赞")
            return
        }
        val aid = currentDetail?.aid ?: 0L
        if (aid <= 0L) {
            showSnackbar("视频信息未就绪，请稍后再试")
            return
        }
        val previousLiked = videoLiked
        val previousCount = videoLikesCount
        val nextLiked = !previousLiked
        videoLiked = nextLiked
        videoLikesCount = (previousCount + if (nextLiked) 1 else -1).coerceAtLeast(0L)
        btn.setActive(videoLiked)
        btn.setCount(videoLikesCount)
        btn.animate().scaleX(1.25f).scaleY(1.25f).setDuration(100)
            .withEndAction { btn.animate().scaleX(1f).scaleY(1f).setDuration(150).start() }.start()
        BiliApi.likeVideo(aid, nextLiked) { ok, error ->
            if (isFinishing || isDestroyed) return@likeVideo
            if (!ok) {
                // Roll back so the UI never claims a like the server rejected.
                videoLiked = previousLiked
                videoLikesCount = previousCount
                btn.setActive(videoLiked)
                btn.setCount(videoLikesCount)
                showSnackbar(error ?: "点赞失败，请稍后再试")
            } else {
                // Bridge the few-second server lag on has/like + stat.like so a
                // quick re-enter still shows the just-confirmed action.
                LikeStateBridge.rememberVideo(aid, videoLiked, videoLikesCount)
            }
        }
    }

    /**
     * 主题化「收藏到文件夹」弹窗：
     * 圆角卡片 + 可多选收藏夹行 + 底部取消/确定，贴合下载弹窗视觉。
     */
    /** 已收藏状态下点按收藏按钮：一键取消收藏（无需弹窗）。 */
    private fun cancelFavoriteDirect(btn: ActionIconView) {
        if (!BiliSessionStore.isLoggedIn()) {
            showSnackbar("请先登录后再收藏")
            return
        }
        val item = currentDetail ?: return
        btn.isEnabled = false
        // B 站无公开 multi_fav 查询口，del 需要收藏夹 id：用已缓存或全部收藏夹 id 兜底。
        BiliApi.fetchFavoriteFolders { folders, error ->
            if (isFinishing || isDestroyed) return@fetchFavoriteFolders
            if (error != null) {
                btn.isEnabled = true
                showSnackbar(error)
                return@fetchFavoriteFolders
            }
            val delAll = if (favFolderIds.isNotEmpty())
                favFolderIds.joinToString(",")
            else
                folders.joinToString(",") { it.id.toString() }
            if (delAll.isBlank()) {
                btn.isEnabled = true
                showSnackbar("还没有收藏夹")
                return@fetchFavoriteFolders
            }
            BiliApi.favoriteVideo(item.aid, "", delAll) { ok, err ->
                if (isFinishing || isDestroyed) return@favoriteVideo
                btn.isEnabled = true
                if (ok) {
                    favFolderIds = emptyList()
                    btn.setActive(false)
                    showSnackbar("已取消收藏")
                } else {
                    showSnackbar(err ?: "取消收藏失败，请稍后再试")
                }
            }
        }
    }

    private fun openFavoriteDialog(btn: ActionIconView) {
        val item = currentDetail ?: return
        if (!BiliSessionStore.isLoggedIn()) {
            showSnackbar("请先登录后再收藏")
            return
        }
        // 收藏不弹中间 loading：列表回来后直接出主题化选择框（与改版前一致）。
        BiliApi.fetchFavoriteFolders { folders, error ->
            if (isFinishing || isDestroyed) return@fetchFavoriteFolders
            if (error != null) { showSnackbar(error); return@fetchFavoriteFolders }
            if (folders.isEmpty()) {
                showSnackbar("还没有收藏夹，请先在网页端创建")
                return@fetchFavoriteFolders
            }
            showFavoriteFolderPicker(item, folders, btn)
        }
    }

    private fun showFavoriteFolderPicker(
        item: VideoItem,
        folders: List<FavoriteFolder>,
        btn: ActionIconView
    ) {
        val checked = BooleanArray(folders.size)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@VideoDetailActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.dialog)
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "收藏到文件夹"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
        })
        root.addView(TextView(this).apply {
            text = "选择收藏夹，确定后同步到 B 站"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(4), 0, 0)
        })

        val listScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val maxH = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        folders.forEachIndexed { index, folder ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ds.card(this@VideoDetailActivity,
                    if (checked[index]) COLOR_ROSE_SOFT else COLOR_SOFT_FILL,
                    if (checked[index]) COLOR_ROSE else Ds.colors.lineSoft,
                    dp(14))
                setPadding(dp(14), dp(13), dp(14), dp(13))
                isClickable = true
                isFocusable = true
            }
            // 自绘圆形勾选框：选中 = 玫瑰底白勾；未选中 = 灰色描边圆。
            val check = object : View(this) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val cx = width / 2f; val cy = height / 2f
                    val r = width / 2f - dpf(1.5f)
                    if (checked[index]) {
                        paint.color = COLOR_ROSE
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx, cy, r, paint)
                        paint.color = Color.WHITE
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = dpf(1.8f)
                        paint.strokeCap = Paint.Cap.ROUND
                        canvas.drawLine(cx - r * 0.45f, cy, cx - r * 0.12f, cy + r * 0.38f, paint)
                        canvas.drawLine(cx - r * 0.12f, cy + r * 0.38f, cx + r * 0.5f, cy - r * 0.42f, paint)
                    } else {
                        paint.color = COLOR_MUTED
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = dpf(1.4f)
                        canvas.drawCircle(cx, cy, r, paint)
                    }
                }
            }
            val name = TextView(this).apply {
                text = folder.name
                textSize = 14f
                setTextColor(COLOR_INK)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(12), 0, 0, 0)
            }
            val count = TextView(this).apply {
                text = "${folder.count}"
                textSize = 12f
                setTextColor(COLOR_MUTED)
            }
            fun applyChecked(on: Boolean) {
                checked[index] = on
                check.invalidate()
                row.background = Ds.card(this@VideoDetailActivity,
                    if (on) COLOR_ROSE_SOFT else COLOR_SOFT_FILL,
                    if (on) COLOR_ROSE else Ds.colors.lineSoft,
                    dp(14))
            }
            row.setOnClickListener { applyChecked(!checked[index]) }
            row.addView(check, LinearLayout.LayoutParams(dp(22), dp(22)))
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(count)
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) topMargin = dp(8) })
        }
        listScroll.addView(list, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(listScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        listScroll.post {
            if (listScroll.height > maxH) {
                listScroll.layoutParams = listScroll.layoutParams.apply { height = maxH }
                listScroll.requestLayout()
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        btnRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        val cancel = TextView(this).apply {
            text = "取消"
            textSize = 14f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        val confirm = TextView(this).apply {
            text = "确定"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(COLOR_ROSE, dp(18))
            setPadding(dp(24), dp(10), dp(24), dp(10))
            isClickable = true
        }
        confirm.setOnClickListener {
            // 纯收藏弹窗：勾选的收藏夹加进去（已收藏时由星星按钮直接取消，不走这里）。
            val addIds = folders.filterIndexed { i, _ -> checked[i] }
                .joinToString(",") { it.id.toString() }
            if (addIds.isBlank()) {
                dialog.dismiss()
                return@setOnClickListener
            }
            confirm.isEnabled = false
            confirm.alpha = 0.6f
            BiliApi.favoriteVideo(item.aid, addIds, "") { ok, err ->
                if (isFinishing || isDestroyed) return@favoriteVideo
                if (ok) {
                    dialog.dismiss()
                    favFolderIds = folders.filterIndexed { i, _ -> checked[i] }.map { it.id }
                    btn.setActive(true)
                    showSnackbar("已收藏")
                    btn.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100)
                        .withEndAction {
                            btn.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                        }.start()
                } else {
                    confirm.isEnabled = true
                    confirm.alpha = 1f
                    showSnackbar(err ?: "操作失败，请稍后再试")
                }
            }
        }
        btnRow.addView(cancel)
        btnRow.addView(confirm, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) })
        root.addView(btnRow)

        dialog.setContentView(root)
        LayoutMode.applyDialogWidth(dialog, this, maxTabletDp = 440)
        dialog.show()
        // 入场：轻微上浮 + 淡入
        root.alpha = 0f
        root.translationY = dp(14).toFloat()
        root.animate().alpha(1f).translationY(0f)
            .setDuration(200)
            .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
            .start()
    }

    /**
     * 打开全屏下载选择页（替代旧下载弹窗）。
     * 先复用已加载的播放流；没有时才异步查询一次，避免给不支持的视频列出 4K 等画质。
     */
    private fun openDownloadDialog() {
        val item = currentDetail ?: return
        if (item.charge) {
            showSnackbar("该视频为充电/付费视频，禁止下载")
            return
        }
        if (item.bvid.isBlank() || item.cid <= 0L) {
            showSnackbar("无法获取分集信息")
            return
        }
        val cached = currentStream?.takeIf { streamSession == BiliSessionStore.cookie() }
        if (cached != null && cached.acceptedQualities.isNotEmpty()) {
            launchDownloadPicker(item, cached.acceptedQualities)
            return
        }
        val loading = showThemedLoadingDialog(
            message = "正在获取可用画质…",
            title = "下载到本地"
        )
        BiliPlayUrl.fetchPlayUrl(item.bvid, item.cid, BiliPlayUrl.QUALITY_4K) { stream, _ ->
            if (isFinishing || isDestroyed) return@fetchPlayUrl
            if (stream != null) {
                runOnUiThread {
                    loading.dismiss()
                    launchDownloadPicker(item, stream.acceptedQualities)
                }
            } else {
                BiliPlayUrl.fetchPlayUrl(item.bvid, item.cid, BiliPlayUrl.QUALITY_720) { fallback, _ ->
                    if (isFinishing || isDestroyed) return@fetchPlayUrl
                    runOnUiThread {
                        loading.dismiss()
                        launchDownloadPicker(item, fallback?.acceptedQualities ?: emptyList())
                    }
                }
            }
        }
    }

    /** 携带视频信息与可用画质跳转下载页。收藏夹播放态同时带上收藏夹队列。 */
    private fun launchDownloadPicker(item: VideoItem, acceptedQualities: List<Int>) {
        val intent = Intent(this, DownloadPickerActivity::class.java)
            .putExtra(DownloadPickerActivity.EXTRA_ITEM_JSON, DownloadPickerActivity.itemToJson(item))
            .putExtra(DownloadPickerActivity.EXTRA_ACCEPTED_QNS, acceptedQualities.toIntArray())
        favoritePlayback?.let { FavoritePlaybackLauncher.writeTo(intent, it) }
        startActivity(intent)
    }

    /**
     * 主题化加载弹窗。
     * @param title 标题（下载/收藏等场景不同，禁止写死「下载到本地」）
     * @param message 副文案
     */
    private fun showThemedLoadingDialog(
        message: String,
        title: String = "请稍候"
    ): Dialog {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@VideoDetailActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.dialog)
            setPadding(dp(22), dp(20), dp(22), dp(20))
            gravity = Gravity.CENTER
        }
        body.addView(TextView(this).apply {
            text = title
            textSize = 19f
            typeface = Ds.display()
            setTextColor(COLOR_INK)
            gravity = Gravity.CENTER
        })
        body.addView(TextView(this).apply {
            text = message
            textSize = 13f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Ds.Space.md)
        })
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(body)
        LayoutMode.applyDialogWidth(dialog, this, maxTabletDp = 340, phoneMarginDp = 72)
        dialog.show()
        return dialog
    }
    /** Share = copy 【标题】+ 链接 to the clipboard. */
    private fun shareVideo() {
        val item = currentDetail ?: return
        val link = "https://www.bilibili.com/video/${item.bvid}"
        val text = "【${item.title}】$link"
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("B站视频", text))
        showSnackbar("已复制链接，去分享给朋友吧")
    }

    /** 关注/取关 UP：必须登录并真正请求 B 站 relation/modify；失败则回滚 UI。 */
    private fun toggleFollow(mid: Long, btn: TextView) {
        if (mid <= 0L) { showSnackbar("暂不支持关注该用户"); return }
        if (!BiliSessionStore.isLoggedIn()) {
            showSnackbar("请先登录后再关注")
            return
        }
        if (followInFlight) return
        val newFollow = !currentFollowed
        // 乐观更新
        applyFollowUi(btn, newFollow)
        currentFollowed = newFollow
        LikeStateBridge.rememberFollow(mid, newFollow)
        followInFlight = true
        btn.animate().scaleX(1.12f).scaleY(1.12f).setDuration(100)
            .withEndAction { btn.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }.start()
        BiliApi.modifyRelation(mid, newFollow) { ok, error ->
            if (isFinishing || isDestroyed) return@modifyRelation
            followInFlight = false
            if (!ok) {
                // 失败回滚到上一次状态
                val rollback = !newFollow
                currentFollowed = rollback
                LikeStateBridge.rememberFollow(mid, rollback)
                applyFollowUi(btn, rollback)
                showSnackbar(error ?: "关注失败，请稍后再试")
            } else {
                // 成功后短暂桥接，避免重进立刻读到旧关系
                LikeStateBridge.rememberFollow(mid, newFollow)
            }
        }
    }

    private fun applyFollowUi(btn: TextView, followed: Boolean) {
        btn.text = if (followed) "已关注" else "关注"
        btn.typeface = Ds.body(true)
        btn.setTextColor(if (followed) COLOR_MUTED else COLOR_ON_ROSE)
        btn.background = if (followed) Ds.pill(this, COLOR_FOLLOWED_CHIP, Ds.colors.lineSoft)
        else Ds.pill(this, COLOR_ROSE)
    }

    /** 进入详情后异步查询真实关注关系，并叠加最近一次成功操作。 */
    private fun refreshFollowState(mid: Long, btn: TextView) {
        if (mid <= 0L) return
        if (!BiliSessionStore.isLoggedIn()) {
            currentFollowed = false
            applyFollowUi(btn, false)
            return
        }
        // 先用桥接/默认状态画一次，再等服务器确认
        val bridged = LikeStateBridge.resolveFollow(mid, currentFollowed)
        currentFollowed = bridged
        applyFollowUi(btn, bridged)
        BiliApi.fetchRelation(mid) { following, _ ->
            if (isFinishing || isDestroyed) return@fetchRelation
            if (following == null) return@fetchRelation
            val resolved = LikeStateBridge.resolveFollow(mid, following)
            currentFollowed = resolved
            applyFollowUi(btn, resolved)
        }
    }

    private fun showSnackbar(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────
    //  Player Lifecycle
    // ─────────────────────────────────────────────────

    // ── 本地下载文件播放（支持视频与纯音频、已下载合集列表切换、4 种播放模式） ──

    private fun refreshLocalPlaylist(current: DownloadManager.Task) {
        val raw = if (current.groupId.isBlank()) {
            listOf(current)
        } else {
            DownloadManager.snapshot()
                .filter { it.groupId == current.groupId && it.type == current.type && it.playable() }
                .ifEmpty { listOf(current) }
        }
        localPlaylist = DownloadManager.sortTasks(raw, localSortOrder)
    }

    /** 切换合集排序：播放列表立即按新顺序重建，顺序播放随即可按该顺序调度。 */
    private fun setLocalSortOrder(order: DownloadManager.SeasonSortOrder) {
        if (localSortOrder == order) return
        localSortOrder = order
        localPlaybackPrefs.edit().putString(KEY_LOCAL_SORT_ORDER, order.name).apply()
        rebuildLocalInfoArea()
        refreshSeasonDrawerSelection()
    }

    /** 切换播放模式（顺序 / 单集循环 / 随机）。 */
    private fun currentPlayMode() = if (isLocalMode) localPlayMode else onlinePlayMode

    private fun setOnlinePlayMode(mode: DownloadManager.LocalPlayMode) {
        onlinePlayMode = mode
        qualityPrefs.edit().putString("online_queue_mode", mode.name).apply()
        syncPlayModeViews()
    }

    private fun syncPlayModeViews() {
        val mode = currentPlayMode()
        introPlayMode?.text = mode.label()
        introPlayMode?.contentDescription = "合集播放模式：${mode.label()}"
        drawerPlayMode?.update(mode, Color.WHITE)
        drawerPlayModeButton?.contentDescription = "播放模式：${mode.label()}"
        localModeIcon?.update(mode, modeTint(mode))
        localModeLabel?.text = mode.label()
        localModeLabel?.setTextColor(modeTint(mode))
        localModeButton?.background = modeChipBackground(mode)
        localModeButton?.contentDescription = "播放模式：${mode.label()}"
    }

    private fun setLocalPlayMode(mode: DownloadManager.LocalPlayMode) {
        if (localPlayMode == mode) return
        localPlayMode = mode
        localPlaybackPrefs.edit().putString(KEY_LOCAL_PLAY_MODE, mode.name).apply()
        syncPlayModeViews()
    }

    private fun switchLocalTask(targetId: Long) {
        val target = DownloadManager.task(targetId) ?: return
        if (!target.playable()) {
            showSnackbar("该分集尚未缓存完成")
            return
        }
        localTaskId = targetId
        stallRetries = 0
        pendingResumePositionMs = 0L
        resumeSegmentIndex = 0
        endedState = false
        restoreEndedState = false
        refreshLocalPlaylist(target)
        rebuildLocalInfoArea()
        switchingLocalTask = true
        try {
            loadLocalPlayer()
        } finally {
            switchingLocalTask = false
        }
        refreshSeasonDrawerSelection()
    }

    private fun createLocalInfoArea(): View {
        val host = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
        }
        localInfoHost = host
        rebuildLocalInfoArea()
        return host
    }

    private fun rebuildLocalInfoArea() {
        val host = localInfoHost ?: return
        host.removeAllViews()
        val task = DownloadManager.task(localTaskId) ?: return
        refreshLocalPlaylist(task)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            setPadding(0, 0, 0, dp(24))
        }
        root.addView(View(this).apply { setBackgroundColor(COLOR_BORDER) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)))

        // ── 1. 基本信息区（标题 + 类型/UP主/大小/时长 + 播放模式切换） ──
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        inner.addView(TextView(this).apply {
            text = task.title.ifBlank { if (task.type == DownloadManager.TYPE_AUDIO) "本地音频" else "本地视频" }
            textSize = 17f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            setLineSpacing(dpf(3f), 1f)
        })

        val metaChips = mutableListOf<String>()
        if (task.owner.isNotBlank()) metaChips.add(task.owner)
        metaChips.add(if (task.type == DownloadManager.TYPE_AUDIO) "纯音频" else task.qualityLabel.ifBlank { "本地视频" })
        metaChips.add(DownloadUiShared.formatBytes(DownloadManager.taskDiskBytes(task)))
        if (task.durationMs > 0L) metaChips.add(localFormatDuration(task.durationMs))

        inner.addView(TextView(this).apply {
            text = metaChips.joinToString(" · ")
            textSize = 12.5f
            fontFeatureSettings = "tnum"
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // ── 2. 播放模式、排序与分集切换统一由下方合集区承担 ──
        root.addView(inner)

        // ── 3. 本地合集分集列表（样式与在线合集选集保持一致，支持横向滚动与全部展开） ──
        if (localPlaylist.size > 1 || task.groupId.isNotBlank()) {
            root.addView(buildLocalSeasonSection(task))
        }

        scroll.addView(root)
        host.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** 本地合集区：合集标题 + 排序/播放模式图标胶囊 + 竖向分集列表（封面、标题、发布日期、时长）。 */
    private fun buildLocalSeasonSection(currentTask: DownloadManager.Task): View {
        val currentIdx = localPlaylist.indexOfFirst { it.id == currentTask.id }.coerceAtLeast(0)
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
        }
        section.addView(View(this).apply { setBackgroundColor(COLOR_BORDER) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)).apply {
                bottomMargin = dp(12)
            })

        // ── 合集标题行 ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val prefix = if (currentTask.type == DownloadManager.TYPE_AUDIO) "音频合集" else "视频合集"
        header.addView(TextView(this).apply {
            text = "$prefix · ${currentTask.groupTitle.ifBlank { currentTask.title }}"
            textSize = 14f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "${currentIdx + 1}/${localPlaylist.size}"
            textSize = 12f
            typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
            setTextColor(COLOR_ROSE)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        section.addView(header)

        // ── 排序 + 播放模式图标胶囊 ──
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val modeIcon = PlayModeIconView(this, localPlayMode, modeTint(localPlayMode), 1.65f)
        localModeIcon = modeIcon
        val modeLabel = TextView(this).apply {
            text = localPlayMode.label()
            textSize = 11.5f
            typeface = Ds.body(bold = true)
            setTextColor(modeTint(localPlayMode))
        }
        localModeLabel = modeLabel
        val modeBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(12), 0)
            minimumHeight = dp(30)
            background = modeChipBackground(localPlayMode)
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener {
                val modes = DownloadManager.LocalPlayMode.entries
                showAnchoredMenu(this, modes.map { it.label() }, modes.indexOf(localPlayMode)) { i -> setLocalPlayMode(modes[i]) }
            }
        }
        localModeButton = modeBtn
        modeBtn.contentDescription = "播放模式：${localPlayMode.label()}"
        modeBtn.addView(modeIcon, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(4) })
        modeBtn.addView(modeLabel)
        controlRow.addView(modeBtn)

        val sortIcon = Ds.sortOrderIcon(this@VideoDetailActivity, localSortOrder, sortTint(localSortOrder))
        val sortLabel = TextView(this).apply {
            text = localSortOrder.seasonLabel()
            textSize = 11.5f
            typeface = Ds.body(bold = true)
            setTextColor(sortTint(localSortOrder))
        }
        val sortBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(12), 0)
            minimumHeight = dp(30)
            background = sortChipBackground(localSortOrder)
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener {
                setLocalSortOrder(localSortOrder.nextOrder())
            }
        }
        sortBtn.addView(sortIcon, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(4) })
        sortBtn.addView(sortLabel)
        controlRow.addView(sortBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })

        controlRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        section.addView(controlRow)

        // ── 竖向分集列表 ──
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        localPlaylist.forEachIndexed { idx, ep ->
            list.addView(buildLocalEpisodeRow(ep, idx, ep.id == currentTask.id),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                })
        }
        section.addView(list)
        return section
    }

    /** 单条本地分集：左侧封面（含时长角标），右侧标题与「P号 · 发布日期」。 */
    private fun buildLocalEpisodeRow(ep: DownloadManager.Task, index: Int, isCurrent: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(12), dp(8))
            minimumHeight = dp(74)
            background = Ds.card(
                this@VideoDetailActivity,
                fill = if (isCurrent) COLOR_ROSE_SOFT else Ds.colors.surface,
                border = if (isCurrent) COLOR_ROSE else Ds.colors.lineSoft,
                radiusDp = Ds.Radius.control,
                borderPx = Ds.hairline(this@VideoDetailActivity)
            )
            isClickable = true
            Ds.bindPressFeedback(this, 0.985f)
            setOnClickListener { if (!isCurrent) switchLocalTask(ep.id) }
        }

        val thumbHost = FrameLayout(this).apply {
            setBackgroundColor(Ds.colors.cover)
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumbHost.addView(thumb, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        DownloadUiShared.loadTaskCover(thumb, ep)
        if (ep.durationMs > 0L) {
            thumbHost.addView(TextView(this).apply {
                text = DownloadUiShared.formatDurationMs(ep.durationMs)
                textSize = 9.5f
                typeface = Ds.body(bold = true)
                fontFeatureSettings = "tnum"
                setTextColor(Color.WHITE)
                background = Ds.card(this@VideoDetailActivity, 0xAD000000.toInt(), null, 4, 0)
                setPadding(dp(5), dp(1), dp(5), dp(1))
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(4); bottomMargin = dp(4)
            })
        }
        row.addView(thumbHost, LinearLayout.LayoutParams(dp(100), dp(60)))

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        infoCol.addView(TextView(this).apply {
            text = ep.title
            textSize = 13.5f
            typeface = Ds.body(bold = isCurrent)
            setTextColor(if (isCurrent) COLOR_ROSE else COLOR_INK)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(dpf(2f), 1f)
        })
        infoCol.addView(TextView(this).apply {
            text = buildLocalEpisodeMeta(ep, index)
            textSize = 11.5f
            fontFeatureSettings = "tnum"
            setTextColor(if (isCurrent) COLOR_ROSE else COLOR_MUTED)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        row.addView(infoCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })
        return row
    }

    private fun buildLocalEpisodeMeta(ep: DownloadManager.Task, index: Int): String {
        val parts = ArrayList<String>(2)
        parts.add(if (ep.episodeNo > 0) "P${ep.episodeNo}" else "P${index + 1}")
        val publishedMs = ep.publishedAt.takeIf { it > 0L }?.times(1000L)
            ?: ep.createdAt.takeIf { it > 0L }
        if (publishedMs != null) parts.add(DownloadUiShared.formatDateTimeMs(publishedMs))
        return parts.joinToString(" · ")
    }

    private fun modeTint(mode: DownloadManager.LocalPlayMode): Int =
        if (mode == DownloadManager.LocalPlayMode.SEASON_SEQUENCE) COLOR_MUTED else COLOR_ROSE

    private fun modeChipBackground(mode: DownloadManager.LocalPlayMode) =
        if (mode == DownloadManager.LocalPlayMode.SEASON_SEQUENCE) {
            Ds.pill(this, Ds.colors.surfaceRaised, Ds.colors.lineSoft)
        } else {
            Ds.pill(this, COLOR_ROSE_SOFT, COLOR_ROSE)
        }

    private fun sortTint(order: DownloadManager.SeasonSortOrder): Int =
        if (order == DownloadManager.SeasonSortOrder.DEFAULT) COLOR_MUTED else COLOR_ROSE

    private fun sortChipBackground(order: DownloadManager.SeasonSortOrder) =
        if (order == DownloadManager.SeasonSortOrder.DEFAULT) {
            Ds.pill(this, Ds.colors.surfaceRaised, Ds.colors.lineSoft)
        } else {
            Ds.pill(this, COLOR_ROSE_SOFT, COLOR_ROSE)
        }

    private fun localTaskSizeBytes(task: DownloadManager.Task?): Long = when {
        task == null -> 0L
        task.destination == DownloadDestination.LIBRARY -> task.bytesTotal
        task.mpdPath.isNotBlank() && File(task.mpdPath).exists() -> {
            val dir = File(task.mpdPath).parentFile
            dir?.listFiles()?.filter {
                it.name.startsWith("${task.id}_") && (it.name.endsWith(".m4s") || it.name.endsWith(".mp4"))
            }?.sumOf { it.length() } ?: task.bytesTotal
        }
        task.audioPath.isNotBlank() && File(task.audioPath).exists() -> File(task.audioPath).length()
        else -> task.singlePaths.sumOf { File(it).length() }.takeIf { it > 0L } ?: task.bytesTotal
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildConfiguredPlayerView(): PlayerView = PlayerView(this).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
        setBackgroundColor(Color.BLACK)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun loadLocalPlayer() {
        val task = DownloadManager.task(localTaskId) ?: run { finish(); return }
        if (!task.playable()) {
            showError("本地文件尚未完成或已缺失，请返回下载页检查")
            return
        }
        if (::playerTopTitle.isInitialized) {
            playerTopTitle.text = task.title.ifBlank { "本地播放" }
            playerTopTitle.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        }
        // 纯音频模式展示封面图覆盖黑屏区域（置于 playerHost 层，全屏时也同步隐藏/显示）
        localAudioCoverOverlay?.let { overlay ->
            if (task.type == DownloadManager.TYPE_AUDIO) {
                overlay.visibility = View.VISIBLE
                localAudioCoverView?.let { iv ->
                    DownloadUiShared.loadTaskCover(iv, task)
                }
            } else {
                overlay.visibility = View.GONE
            }
        }
        val generation = beginPlaybackChange()
        val mediaSources: List<MediaSource>
        try {
            segmentTimeline = PlaybackTimeline(emptyList())
            mediaSources = task.localMediaSources(this)
            if (mediaSources.isEmpty()) {
                showError("本地文件缺失，请返回下载页检查")
                return
            }
        } catch (e: Exception) {
            showError("本地播放初始化失败：${e.message ?: "未知错误"}\n\n点这里重试")
            return
        }
        bindPlayerSource(mediaSources, generation)
    }

    private fun localFormatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0B"
        val mb = bytes / 1024.0 / 1024.0
        return when {
            mb >= 1024.0 -> String.format(java.util.Locale.US, "%.2fGB", mb / 1024.0)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1fMB", mb)
            else -> String.format(java.util.Locale.US, "%.0fKB", bytes / 1024.0)
        }
    }

    private fun localFormatDuration(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }

    private fun loadVideoAndPlayer() {
        val session = BiliSessionStore.cookie()
        if (currentBvid.isNotBlank() && currentCid > 0L && playbackRequest == null && playerListener == null &&
            playerError.visibility != View.VISIBLE
        ) loadPlayer()
        val cached = detailResponse
        if (cached != null && detailSession == session && cached.first.bvid == currentBvid) {
            applyDetail(cached.first, cached.second)
            return
        }
        detailRequest?.cancel()
        val generation = ++detailGeneration
        infoState.text = "正在加载简介…"
        infoState.setOnClickListener(null)
        detailRequest = BiliApi.fetchDetail(currentBvid, currentAid) { item, parts, error ->
            if (isFinishing || isDestroyed || generation != detailGeneration) return@fetchDetail
            detailRequest = null
            if (session != BiliSessionStore.cookie()) {
                if (!playbackStopped) loadVideoAndPlayer()
                return@fetchDetail
            }
            if (item == null) {
                infoState.text = "简介加载失败：${error ?: "未知错误"}\n\n点这里重试"
                infoState.setOnClickListener { loadVideoAndPlayer() }
                if (currentCid <= 0L || currentBvid.isBlank()) showError("无法获取视频信息，点这里重试")
                return@fetchDetail
            }
            detailResponse = item to parts
            detailSession = session
            applyDetail(item, parts)
        }
    }

    private fun applyDetail(item: VideoItem, parts: List<VideoPart>) {
        val part = selectPlaybackPart(parts, currentCid, item.cid)
        val cid = part?.cid ?: item.cid.takeIf { it > 0L } ?: currentCid
        val previousCid = currentCid
        val previousBvid = currentBvid
        val aid = item.aid.takeIf { it > 0L } ?: currentAid
        val selected = item.copy(
            aid = aid,
            cid = cid,
            title = if (part != null && cid != item.cid) part.title else item.title,
            duration = part?.duration?.takeIf { it > 0 } ?: item.duration,
            season = if (favoritePlayback != null) null else seasonForEpisode(cachedSeason, item.season, item.bvid, aid, cid)
        )
        currentBvid = item.bvid.ifBlank { currentBvid }
        currentAid = aid
        currentCid = cid
        currentDetail = selected
        // 收藏夹播放态下屏蔽视频自身合集，列表只以收藏夹为准
        cachedSeason = if (favoritePlayback != null) null else selected.season
        if (::playerTopTitle.isInitialized) {
            playerTopTitle.text = selected.title
            playerTopTitle.visibility = if (isFullscreen && selected.title.isNotBlank()) View.VISIBLE else View.GONE
        }
        val corrected = previousCid > 0L && previousCid != cid || previousBvid.isNotBlank() && previousBvid != currentBvid
        if (corrected) {
            clearPlaybackForMediaChange()
            loadPlayer()
        } else if (previousCid <= 0L || previousBvid.isBlank()) {
            loadPlayer()
        }
        bindDanmakuForCurrentMedia()
        if (currentBvid.isBlank() || currentCid <= 0L) showError("无法获取视频信息，点这里重试")
        if (isFullscreen) pendingIntro = selected else renderIntroduction(selected)
        HistoryStore.record(applicationContext, selected.copy(bvid = currentBvid))
        if (!isFullscreen && currentTab == 1) ensureCommentsStarted()
        playingStream?.let(::showPreviewNotice)
        refreshSeasonDrawerSelection()
    }

    private fun selectEpisode(episode: UgcEpisode) {
        reportPlayHistory(status = 2)
        pendingIntro = null
        requestedQuality = loadQualityPreference()
        val sameVideo = episode.bvid.isNotBlank() && episode.bvid == currentBvid ||
            episode.aid > 0L && episode.aid == currentAid
        currentBvid = episode.bvid.ifBlank { if (sameVideo) currentBvid else "" }
        currentAid = episode.aid
        currentCid = episode.cid
        detailGeneration++
        detailRequest?.cancel()
        detailRequest = null
        currentDetail = null
        if (!sameVideo) resetComments()
        clearPlaybackForMediaChange()
        infoContent.removeAllViews()
        infoContent.addView(infoState)
        startupAtMs = SystemClock.elapsedRealtime()
        loadVideoAndPlayer()
        refreshSeasonDrawerSelection()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun clearPlaybackForMediaChange() {
        stallRetries = 0
        isSeeking = false
        gestureSeeking = false
        progressContainer.isDragging = false
        progressContainer.setProgress(0L, 0L, 0L)
        playbackGeneration++
        playbackRequest?.cancel()
        playbackRequest = null
        currentStream = null
        playingStream = null
        failedCodecTracks.clear()
        segmentTimeline = PlaybackTimeline(emptyList())
        pendingResumePositionMs = 0L
        resumeSegmentIndex = 0
        restoreEndedState = false
        endedState = false
        showReplay(false)
        playPauseBtn.setEnded(false)
        playPauseBtn.setPlaying(false)
        lastPositionSecond = -1L
        lastDurationSecond = -1L
        playPreviewNoticeShown = false
        playRetryCount = 0
        retryScheduled = false
        lastPlaybackError = null
        playerView.setKeepContentOnPlayerReset(false)
        stopPlayerSource()
        danmakuController?.clearMedia()
        playerView.player = null
        playerSpinner.visibility = View.VISIBLE
        playerError.visibility = View.GONE
    }

    // ─────────────────────────────────────────────────
    //  Info Tab
    // ─────────────────────────────────────────────────

    private fun renderIntroduction(item: VideoItem) {
        if (isFinishing || isDestroyed) return
        infoContent.removeAllViews()
        introExpanded = false
        titleHeightAnimator?.cancel()
        descHeightAnimator?.cancel()
        // Comment total inline beside 评论 tab label.
        if (::commentBubble.isInitialized) {
            if (item.replyCount > 0L) {
                commentBubble.text = formatViews(item.replyCount)
                commentBubble.visibility = View.VISIBLE
            } else {
                commentBubble.visibility = View.GONE
            }
        }

        // ── 1. UP 主行放在最顶部（参考官方布局：头像 + 名称/粉丝 + 关注按钮）──
        introExpandWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val upRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val upInfoTap = View.OnClickListener {
            if (item.ownerMid > 0L) {
                SpaceActivity.open(this@VideoDetailActivity, item.ownerMid, item.owner, item.ownerAvatar)
            }
        }
        val avatar = RoundImageView(this).apply {
            setBackgroundColor(COLOR_COVER)
            if (item.ownerMid > 0L) {
                isClickable = true
                Ds.bindPressFeedback(this, 0.94f)
                setOnClickListener(upInfoTap)
            }
        }
        if (item.ownerAvatar.isNotBlank()) {
            CoverLoader.load(avatar, item.ownerAvatar + "@52w_52h_1c.webp")
        }
        upRow.addView(avatar, LinearLayout.LayoutParams(dp(36), dp(36)))
        val upText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
            if (item.ownerMid > 0L) {
                isClickable = true
                setOnClickListener(upInfoTap)
            }
        }
        upText.addView(TextView(this).apply {
            text = item.owner.ifBlank { "哔哩哔哩用户" }
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_ROSE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val fansText = TextView(this).apply {
            text = "粉丝数加载中…"
            textSize = 11.5f; setTextColor(COLOR_MUTED)
            fontFeatureSettings = "tnum"
            setPadding(0, dp(2), 0, 0)
        }
        upText.addView(fansText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        upRow.addView(upText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val initialFollowed = LikeStateBridge.resolveFollow(item.ownerMid, item.following)
        currentFollowed = initialFollowed
        val followBtn = TextView(this).apply {
            textSize = 12.5f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(6), dp(15), dp(6))
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
        }
        applyFollowUi(followBtn, initialFollowed)
        followBtnRef = followBtn
        followBtn.setOnClickListener { toggleFollow(item.ownerMid, followBtn) }
        upRow.addView(followBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(10)
        })
        refreshFollowState(item.ownerMid, followBtn)
        introExpandWrap.addView(upRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 2. 标题行：默认单行省略，点击整行平滑展开多行完整标题 + 简介 ──
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            isClickable = true
            setOnClickListener { toggleIntro() }
        }
        titleText = TextView(this).apply {
            text = item.title
            textSize = 16.5f
            typeface = Ds.display(bold = true)
            letterSpacing = -0.01f
            setLineSpacing(dpf(3f), 1f)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        titleClip = FrameLayout(this).apply {
            clipChildren = true
        }
        titleClip.addView(titleText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        titleArrow = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_MUTED; style = Paint.Style.STROKE
                strokeWidth = dpf(1.7f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            private val path = Path()
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f; val cy = height / 2f; val s = dpf(4.5f)
                path.reset()
                path.moveTo(cx - s, cy - s * 0.55f)
                path.lineTo(cx, cy + s * 0.55f)
                path.lineTo(cx + s, cy - s * 0.55f)
                canvas.drawPath(path, paint)
            }
        }
        titleRow.addView(titleClip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(titleArrow, LinearLayout.LayoutParams(dp(24), dp(22)).apply {
            leftMargin = dp(6)
        })
        introExpandWrap.addView(titleRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 3. 数据行（紧贴标题下方，点击也可展开/收起简介，手势更顺手）──
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            isClickable = true
            setOnClickListener { toggleIntro() }
        }
        statsRow.addView(object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_MUTED; style = Paint.Style.STROKE
                strokeWidth = dpf(1.3f); strokeCap = Paint.Cap.ROUND
            }
            private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_MUTED; style = Paint.Style.FILL
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat(); val h = height.toFloat()
                val r = h * 0.36f
                canvas.drawOval(RectF(w / 2f - r * 1.45f, h / 2f - r, w / 2f + r * 1.45f, h / 2f + r), paint)
                canvas.drawCircle(w / 2f, h / 2f, r * 0.32f, dotPaint)
            }
        }, LinearLayout.LayoutParams(dp(15), dp(10)).apply { gravity = Gravity.CENTER_VERTICAL })

        val viewsText = TextView(this).apply {
            text = formatViews(item.views)
            textSize = 12f; setTextColor(COLOR_MUTED)
            fontFeatureSettings = "tnum"
            setPadding(dp(5), 0, dp(10), 0)
        }
        statsRow.addView(viewsText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val dateText = TextView(this).apply {
            text = formatPublish(item.publishedAt)
            textSize = 12f; setTextColor(COLOR_MUTED)
            fontFeatureSettings = "tnum"
        }
        statsRow.addView(dateText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val onlineText = TextView(this).apply {
            text = ""
            textSize = 12f; setTextColor(COLOR_MUTED)
            fontFeatureSettings = "tnum"
            setPadding(dp(8), 0, 0, 0)
            maxLines = 1
        }
        statsRow.addView(onlineText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        introExpandWrap.addView(statsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 4. 可折叠简介正文（紧随数据行下方，展开时自然衔接在操作栏上方）──
        introDescWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        if (item.bvid.isNotBlank()) {
            introDescWrap.addView(TextView(this).apply {
                text = item.bvid
                textSize = 12f
                fontFeatureSettings = "tnum"
                setTextColor(COLOR_MUTED)
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
        }
        introDescWrap.addView(TextView(this).apply {
            text = item.description.ifBlank { "暂无简介" }
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setLineSpacing(dpf(3f), 1f)
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        introDescClip = FrameLayout(this).apply {
            clipChildren = true
        }
        introDescClip.addView(introDescWrap, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        introExpandWrap.addView(introDescClip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 5. 轻盈开放式操作栏（无硬边框盒子，紧贴在简介下方）──
        run {
            val bridged = LikeStateBridge.resolveVideo(item.aid, item.liked, item.likes)
            videoLiked = bridged.first
            videoLikesCount = bridged.second
        }
        val likeBtn = ActionIconView(this, KIND_LIKE)
        likeBtn.setCount(videoLikesCount)
        likeBtn.setActive(videoLiked)
        likeBtn.setOnClickListener { toggleVideoLike(likeBtn) }
        if (BiliSessionStore.isLoggedIn() && item.aid > 0L) {
            BiliApi.fetchHasLiked(item.aid) { liked, _ ->
                if (isFinishing || isDestroyed || liked == null) return@fetchHasLiked
                val bridged = LikeStateBridge.resolveVideo(item.aid, liked, videoLikesCount)
                if (videoLiked == bridged.first && videoLikesCount == bridged.second) return@fetchHasLiked
                videoLiked = bridged.first
                videoLikesCount = bridged.second
                likeBtn.setActive(videoLiked)
                likeBtn.setCount(videoLikesCount)
            }
        }
        val favBtn = ActionIconView(this, KIND_FAVORITE)
        this.favBtn = favBtn
        favBtn.setOnClickListener {
            if (favBtn.isActive()) {
                cancelFavoriteDirect(favBtn)
            } else {
                openFavoriteDialog(favBtn)
            }
        }
        if (BiliSessionStore.isLoggedIn() && item.aid > 0L) {
            BiliApi.fetchFavoriteDeal(item.aid) { isFav, favIds, _ ->
                if (isFinishing || isDestroyed) return@fetchFavoriteDeal
                favFolderIds = favIds
                favBtn.setActive(isFav)
            }
        }
        val dlBtn = ActionIconView(this, KIND_DOWNLOAD)
        dlBtn.setOnClickListener { openDownloadDialog() }
        val shareBtn = ActionIconView(this, KIND_SHARE)
        shareBtn.setOnClickListener { shareVideo() }
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        }
        listOf(likeBtn, favBtn, dlBtn, shareBtn).forEach { btn ->
            Ds.bindPressFeedback(btn, 0.92f)
            actionBar.addView(btn, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        introExpandWrap.addView(actionBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        infoContent.addView(introExpandWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 合集/多 P：视频属于官方合集或同 BV 的 pages 多 P 时展示。
        renderSeasonSection(item)

        // Measure heights: title width excludes the 30dp chevron+margin so multi-line
        // measurement matches actual laid-out height accurately!
        val availWidth = if (infoContent.width > 0) {
            infoContent.width - infoContent.paddingLeft - infoContent.paddingRight
        } else {
            resources.displayMetrics.widthPixels - dp(32)
        }
        val titleWidth = (availWidth - dp(30)).coerceAtLeast(dp(120))
        val unconstrained = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        titleText.maxLines = 1
        titleClip.measure(
            View.MeasureSpec.makeMeasureSpec(titleWidth, View.MeasureSpec.EXACTLY), unconstrained)
        titleLineHeight = titleClip.measuredHeight
        titleText.maxLines = Int.MAX_VALUE
        titleText.ellipsize = null
        titleClip.measure(
            View.MeasureSpec.makeMeasureSpec(titleWidth, View.MeasureSpec.EXACTLY), unconstrained)
        titleFullHeight = titleClip.measuredHeight
        titleText.maxLines = 1
        titleText.ellipsize = android.text.TextUtils.TruncateAt.END
        titleClip.layoutParams = LinearLayout.LayoutParams(0, titleLineHeight, 1f)
        introDescWrap.measure(
            View.MeasureSpec.makeMeasureSpec(availWidth, View.MeasureSpec.EXACTLY), unconstrained)
        introDescHeight = introDescWrap.measuredHeight
        introDescClip.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0)

        // ── Fans + online (async) ──
        if (item.ownerMid > 0L) {
            BiliApi.fetchFans(item.ownerMid) { fans, _ ->
                if (isFinishing || isDestroyed || currentDetail?.bvid != item.bvid) return@fetchFans
                fansText.text = formatFans(fans ?: 0L)
            }
        } else {
            fansText.text = "粉丝数未知"
        }
        if (item.aid > 0L && item.cid > 0L) {
            BiliApi.fetchOnline(item.aid, item.cid) { online, _ ->
                if (isFinishing || isDestroyed || currentDetail !== item) return@fetchOnline
                onlineText.text = if (online != null && online > 0L) "${online}人正在看" else ""
            }
        } else {
            onlineText.text = ""
        }
    }

    private fun toggleIntro() = setIntroExpanded(!introExpanded)

    /**
     * 简介下方的「合集/选集」区块（重写设计）：
     * - 头部一行式收起控制：标题 + 玫瑰粉高亮当前进度（如 `3 / 12`）+「全部 ›」展开全集弹窗，简洁无冗余盒子。
     * - 默认直接暴露横向选集卡片流（HorizontalScrollView）：展示集数编号 / 标题（最多两行）/ 时长，
     *   当前集用柔和玫瑰底与粉描边高亮并自动居左滚动定位，滑动不跟外层 简介/评论 HorizontalPagerView 冲突。
     * - 点击头部右侧「全部 ›」弹出底部半屏选集清单，长合集也能一秒定位跳集。
     */
    /** 当前播放项前的动态音柱图标（Drawable 版本，便于放到 TextView 左侧）。 */
    private class PlayingBarsDrawable(
        private val tint: Int,
        private val sizePx: Int
    ) : android.graphics.drawable.Drawable(), Runnable {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.FILL
        }
        private var phase = 0f

        init {
            setBounds(0, 0, sizePx, sizePx)
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val h = b.height().toFloat()
            val barW = b.width() / 5f
            for (i in 0 until 3) {
                val wave = kotlin.math.sin(phase + i * 1.1f) * 0.5f + 0.5f
                val barH = h * (0.35f + 0.55f * wave)
                val left = b.left + i * barW * 1.9f + barW * 0.3f
                canvas.drawRoundRect(
                    left, b.bottom - barH, left + barW, b.bottom.toFloat(),
                    barW * 0.5f, barW * 0.5f, paint
                )
            }
        }

        override fun run() {
            phase += 0.12f
            if (phase > (2 * Math.PI).toFloat()) phase -= (2 * Math.PI).toFloat()
            invalidateSelf()
            scheduleSelf(this, android.os.SystemClock.uptimeMillis() + 40L)
        }

        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            unscheduleSelf(this)
            if (visible) scheduleSelf(this, android.os.SystemClock.uptimeMillis() + 40L)
            return super.setVisible(visible, restart)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun renderSeasonSection(item: VideoItem) {
        // 收藏夹播放态：把收藏夹包装成虚拟合集，完全复用合集渲染与底部弹窗
        val favorite = favoritePlayback
        val season = if (favorite != null) {
            UgcSeason(
                id = favorite.folderId,
                title = favorite.folderName,
                cover = "",
                episodes = favorite.entries,
                isMultiPage = false
            )
        } else {
            item.season ?: return
        }
        if (isFinishing || isDestroyed || season.episodes.isEmpty()) return
        val episodes = season.episodes
        val currentIndex = episodes.playingIndex(item.bvid, item.aid, item.cid)

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(4))
        }
        infoContent.addView(section, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // 顶部细分割线，视觉上把“简介与操作”与“合集/选集”自然区隔开
        section.addView(View(this).apply { setBackgroundColor(COLOR_BORDER) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)).apply {
                bottomMargin = dp(12)
            })

        // ── 头部行：合集标题（左） + 集数进度 / 全部入口（右）──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // 按基线对齐：标题 14sp、计数/全部 12sp、chip 带内边距，
            // 用 CENTER_VERTICAL 会按盒子中心对齐导致基线错开，必须走基线对齐。
            isBaselineAligned = true
            setPadding(0, 0, 0, dp(10))
            isClickable = true
            Ds.bindPressFeedback(this, 0.98f)
            setOnClickListener { showSeasonSheet(season, episodes, currentIndex) }
        }
        val titlePrefix = if (favorite != null) "收藏夹" else if (season.isMultiPage) "分集" else "合集"
        header.addView(TextView(this).apply {
            text = "$titlePrefix · ${season.title}"
            textSize = 14f
            typeface = Ds.display(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        introPlayMode = seasonModeChip(this, { onlinePlayMode }, ::setOnlinePlayMode)
        header.addView(introPlayMode)
        if (currentIndex >= 0) {
            header.addView(TextView(this).apply {
                text = "${currentIndex + 1}/${episodes.size}"
                textSize = 12f
                typeface = Ds.body(bold = true)
                fontFeatureSettings = "tnum"
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(COLOR_ROSE)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
            })
        }
        header.addView(TextView(this).apply {
            text = if (currentIndex >= 0) "· 全部 ›" else "共 ${episodes.size} 集 ›"
            textSize = 12f
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(COLOR_MUTED)
            setPadding(dp(4), 0, 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        section.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ── 横向分集卡片流（拦截外层 HorizontalPagerView 横滑，大合集采用视口按需虚拟化渲染，避免一次性创建上百视图卡顿）──
        val cardW = dp(148)
        val cardGap = dp(8)
        val stepPx = cardW + cardGap
        val totalStripW = (episodes.size * stepPx - cardGap).coerceAtLeast(cardW)
        val cardHeightEstimate = dp(66)

        val strip = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(totalStripW, MeasureSpec.EXACTLY),
                    heightMeasureSpec
                )
            }
        }

        val activePool = HashMap<Int, LinearLayout>()
        val recycledPool = ArrayDeque<LinearLayout>()

        fun acquireCard(): LinearLayout {
            val recycled = recycledPool.removeFirstOrNull()
            if (recycled != null) return recycled
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(9), dp(11), dp(9))
                isClickable = true
                Ds.bindPressFeedback(this, 0.96f)
                val metaRow = LinearLayout(this@VideoDetailActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val indexTv = TextView(this@VideoDetailActivity).apply {
                    textSize = 11f
                    fontFeatureSettings = "tnum"
                }
                val statusTv = TextView(this@VideoDetailActivity).apply {
                    textSize = 10.5f
                    fontFeatureSettings = "tnum"
                }
                metaRow.addView(indexTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                metaRow.addView(statusTv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(metaRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                val titleTv = TextView(this@VideoDetailActivity).apply {
                    textSize = 12.5f
                    maxLines = 2
                    minLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setLineSpacing(dpf(2f), 1f)
                    includeFontPadding = false
                }
                addView(titleTv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(5)
                })
                tag = arrayOf(indexTv, statusTv, titleTv)
            }
        }

        fun bindCard(card: LinearLayout, index: Int) {
            val episode = episodes[index]
            val isCurrent = index == currentIndex
            @Suppress("UNCHECKED_CAST")
            val views = card.tag as Array<TextView>
            val indexTv = views[0]
            val statusTv = views[1]
            val titleTv = views[2]
            card.background = Ds.card(
                this@VideoDetailActivity,
                fill = if (isCurrent) COLOR_ROSE_SOFT else COLOR_SOFT_FILL,
                border = if (isCurrent) COLOR_ROSE else null,
                radiusDp = Ds.Radius.control,
                borderPx = Ds.hairline(this@VideoDetailActivity)
            )
            // 保留 P 编号；收藏夹当前项在编号前加动态音柱图标
            indexTv.text = "P${index + 1}"
            indexTv.typeface = Ds.body(bold = true)
            indexTv.setTextColor(if (isCurrent) COLOR_ROSE else COLOR_MUTED)
            indexTv.setCompoundDrawablesWithIntrinsicBounds(
                if (favorite != null && isCurrent) PlayingBarsDrawable(COLOR_ROSE, dp(11)) else null,
                null, null, null
            )
            indexTv.compoundDrawablePadding = dp(4)
            statusTv.text = when {
                isCurrent -> "播放中"
                episode.duration > 0 -> formatDuration(episode.duration)
                else -> ""
            }
            statusTv.typeface = Ds.body(bold = isCurrent)
            statusTv.setTextColor(if (isCurrent) COLOR_ROSE else Ds.colors.subtle)
            titleTv.text = episode.title
            titleTv.typeface = Ds.body(bold = isCurrent)
            titleTv.setTextColor(if (isCurrent) COLOR_ROSE else COLOR_INK)
            card.setOnClickListener {
                if (!isCurrent && (episode.bvid.isNotBlank() || episode.aid > 0L)) {
                    selectEpisode(episode)
                }
            }
        }

        var lastFirst = -1
        var lastLast = -1
        fun syncVisibleCards(scrollX: Int, viewportWidth: Int) {
            val width = if (viewportWidth > 0) viewportWidth else resources.displayMetrics.widthPixels
            val buffer = 2
            val first = ((scrollX / stepPx) - buffer).coerceIn(0, episodes.lastIndex)
            val last = (((scrollX + width) / stepPx) + buffer).coerceIn(0, episodes.lastIndex)
            if (first == lastFirst && last == lastLast && activePool.isNotEmpty()) return
            lastFirst = first
            lastLast = last
            val iterator = activePool.entries.iterator()
            while (iterator.hasNext()) {
                val (idx, view) = iterator.next()
                if (idx < first || idx > last) {
                    strip.removeView(view)
                    recycledPool.addLast(view)
                    iterator.remove()
                }
            }
            for (idx in first..last) {
                if (idx !in activePool) {
                    val card = acquireCard()
                    bindCard(card, idx)
                    val lp = FrameLayout.LayoutParams(cardW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = idx * stepPx
                    }
                    strip.addView(card, lp)
                    activePool[idx] = card
                }
            }
        }

        val hScroll = object : HorizontalScrollView(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (canScrollHorizontally(-1) || canScrollHorizontally(1)) {
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                            parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return super.dispatchTouchEvent(ev)
            }

            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt)
                if (isInLayout) post { syncVisibleCards(scrollX, width) }
                else if (width > 0) syncVisibleCards(l, width)
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            strip.minimumHeight = cardHeightEstimate
            addView(strip, FrameLayout.LayoutParams(totalStripW, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        section.addView(hScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        hScroll.bindEpisodeViewport(currentIndex, stepPx, cardW, totalStripW, ::syncVisibleCards)
    }

    /** 底部半屏全集选择清单（点击头部「全部 ›」呼出）。 */
    private fun showSeasonSheet(season: UgcSeason, episodes: List<UgcEpisode>, currentIndex: Int) {
        if (isFinishing || isDestroyed) return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x2E000000)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedTop(COLOR_BACKGROUND, dp(18))
            isClickable = true
        }
        // Drag handle
        panel.addView(View(this).apply {
            background = Ds.pill(this@VideoDetailActivity, COLOR_BORDER)
        }, LinearLayout.LayoutParams(dp(34), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })
        // Sheet header
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            addView(LinearLayout(this@VideoDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@VideoDetailActivity).apply {
                    text = season.title
                    textSize = 15.5f
                    typeface = Ds.display(bold = true)
                    setTextColor(COLOR_INK)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(this@VideoDetailActivity).apply {
                    text = if (currentIndex >= 0) "正在播放第 ${currentIndex + 1} 集 · 共 ${episodes.size} 集"
                    else "共 ${episodes.size} 集"
                    textSize = 11.5f
                    fontFeatureSettings = "tnum"
                    setTextColor(COLOR_MUTED)
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@VideoDetailActivity).apply {
                text = "✕"
                textSize = 14f
                typeface = Ds.body(bold = true)
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                background = Ds.pill(this@VideoDetailActivity, COLOR_CARD, Ds.colors.lineSoft)
                Ds.bindPressFeedback(this, 0.92f)
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                leftMargin = dp(10)
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(View(this).apply { setBackgroundColor(COLOR_BORDER) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)))

        val rowHeight = dp(76)
        val thumbW = dp(96)
        val thumbH = dp(56)
        val list = ListView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            divider = ColorDrawable(COLOR_BORDER)
            dividerHeight = Ds.hairline(this@VideoDetailActivity)
            setSelector(android.R.color.transparent)
            adapter = object : BaseAdapter() {
                override fun getCount() = episodes.size
                override fun getItem(position: Int) = episodes[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val row = (convertView as? LinearLayout) ?: LinearLayout(this@VideoDetailActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                        layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)

                        val thumbHost = FrameLayout(this@VideoDetailActivity).apply {
                            background = rounded(COLOR_COVER, dp(7))
                            Ds.clipRounded(this, 7)
                        }
                        val thumb = ImageView(this@VideoDetailActivity).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                        val durationBadge = TextView(this@VideoDetailActivity).apply {
                            textSize = 9.5f
                            typeface = Ds.body(bold = true)
                            fontFeatureSettings = "tnum"
                            setTextColor(Color.WHITE)
                            background = rounded(0x99000000.toInt(), dp(4))
                            setPadding(dp(4), dp(1), dp(4), dp(1))
                        }
                        thumbHost.addView(thumb, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        thumbHost.addView(durationBadge, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM or Gravity.END).apply {
                            rightMargin = dp(4)
                            bottomMargin = dp(4)
                        })

                        val textCol = LinearLayout(this@VideoDetailActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        val title = TextView(this@VideoDetailActivity).apply {
                            textSize = 13.5f
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setLineSpacing(dpf(2f), 1f)
                            includeFontPadding = false
                        }
                        val sub = TextView(this@VideoDetailActivity).apply {
                            textSize = 11.5f
                            fontFeatureSettings = "tnum"
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                        textCol.addView(title, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        textCol.addView(sub, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(4)
                        })

                        addView(thumbHost, LinearLayout.LayoutParams(thumbW, thumbH))
                        addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            leftMargin = dp(12)
                        })
                        tag = EpisodeSheetRow(thumb, durationBadge, title, sub)
                    }
                    val holder = row.tag as EpisodeSheetRow
                    val episode = getItem(position)
                    val isCurrent = position == currentIndex
                    row.setBackgroundColor(if (isCurrent) COLOR_ROSE_SOFT else Color.TRANSPARENT)

                    val coverUrl = episode.cover.ifBlank { season.cover }
                    if (coverUrl.isNotBlank()) {
                        CoverLoader.load(holder.thumb, coverUrl, cropThumb = true)
                    } else {
                        CoverLoader.clear(holder.thumb)
                    }
                    if (episode.duration > 0) {
                        holder.durationBadge.text = formatDuration(episode.duration)
                        holder.durationBadge.visibility = View.VISIBLE
                    } else {
                        holder.durationBadge.visibility = View.GONE
                    }

                    holder.title.text = episode.title
                    holder.title.typeface = Ds.body(bold = isCurrent)
                    holder.title.setTextColor(if (isCurrent) COLOR_ROSE else COLOR_INK)

                    val metaText = buildString {
                        if (isCurrent) append("播放中")
                        if (episode.publishedAt > 0L) {
                            if (isNotEmpty()) append(" · ")
                            append(formatPublishedDate(episode.publishedAt))
                        }
                        if (isEmpty()) append(if (favoritePlayback != null) "" else "第 ${position + 1} 集")
                    }
                    holder.sub.text = metaText
                    holder.sub.setTextColor(if (isCurrent) COLOR_ROSE else COLOR_MUTED)
                    return row
                }
            }
            setOnItemClickListener { _, _, index, _ ->
                val episode = episodes[index]
                dialog.dismiss()
                if (index != currentIndex && (episode.bvid.isNotBlank() || episode.aid > 0L)) {
                    selectEpisode(episode)
                }
            }
        }
        panel.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val useSideDrawer = LayoutMode.isWideScreen(this)
        val anchorTop = infoTabBarAnchor?.let { anchor ->
            val pos = IntArray(2)
            anchor.getLocationOnScreen(pos)
            pos[1]
        } ?: (resources.displayMetrics.widthPixels * 9 / 16)
        val sheetHeight = (resources.displayMetrics.heightPixels - anchorTop).coerceAtLeast(dp(300))

        if (useSideDrawer) {
            val drawerW = (resources.displayMetrics.widthPixels * 0.38f).toInt().coerceIn(dp(360), dp(460))
            panel.background = Ds.card(this, COLOR_BACKGROUND, Ds.colors.lineSoft, 0, Ds.hairline(this))
            overlay.addView(panel, FrameLayout.LayoutParams(
                drawerW, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
            overlay.setOnClickListener { dialog.dismiss() }
            dialog.setContentView(overlay)
            dialog.window?.apply {
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.END)
                setBackgroundDrawable(ColorDrawable(0x00000000))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            dialog.show()
            if (currentIndex >= 0) {
                list.post { list.setSelectionFromTop(currentIndex, (resources.displayMetrics.heightPixels - dp(120) - rowHeight) / 2) }
            }
            panel.translationX = drawerW.toFloat()
            panel.animate().translationX(0f).setDuration(220L).setInterpolator(DecelerateInterpolator()).start()
        } else {
            overlay.addView(panel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, sheetHeight, Gravity.BOTTOM))
            overlay.setOnClickListener { dialog.dismiss() }
            dialog.setContentView(overlay)
            dialog.window?.apply {
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.BOTTOM)
                setBackgroundDrawable(ColorDrawable(0x00000000))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            dialog.show()
            if (currentIndex >= 0) {
                list.post { list.setSelectionFromTop(currentIndex, (sheetHeight - dp(90) - rowHeight) / 2) }
            }
            panel.translationY = sheetHeight.toFloat()
            panel.animate().translationY(0f).setDuration(220L).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private data class EpisodeSheetRow(
        val thumb: ImageView,
        val durationBadge: TextView,
        val title: TextView,
        val sub: TextView
    )

    private fun setIntroExpanded(expanded: Boolean) {
        if (introExpanded == expanded || !::introDescWrap.isInitialized) return
        introExpanded = expanded
        titleArrow.animate().rotation(if (expanded) 180f else 0f).setDuration(240)
            .setInterpolator(DecelerateInterpolator()).start()
        titleHeightAnimator?.cancel()
        descHeightAnimator?.cancel()
        // Measure current actual height BEFORE starting collapse so we never jump from
        // WRAP_CONTENT (-2) to 0 in a single frame!
        val curDescH = if (introDescClip.height > 0) introDescClip.height else introDescHeight
        if (expanded) {
            titleText.maxLines = Int.MAX_VALUE
            titleText.ellipsize = null
            if (introDescClip.layoutParams.height < 0) {
                introDescClip.layoutParams = introDescClip.layoutParams.apply { height = curDescH }
            }
            titleHeightAnimator = animateHeight(titleClip, titleFullHeight, 260L)
            descHeightAnimator = animateHeight(introDescClip, introDescHeight, 280L).apply {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (introExpanded) {
                            introDescClip.layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT)
                        }
                    }
                })
            }
        } else {
            val curTitleH = if (titleClip.height > 0) titleClip.height else titleFullHeight
            if (titleClip.layoutParams.height < 0) {
                titleClip.layoutParams = titleClip.layoutParams.apply { height = curTitleH }
            }
            // Pin introDescClip to its actual pixel height before animating down to 0
            // (previously layoutParams.height was WRAP_CONTENT == -2, causing ValueAnimator
            // ofInt(-2, 0) to snap shut instantly!).
            introDescClip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, curDescH)
            titleHeightAnimator = animateHeight(titleClip, titleLineHeight, 220L).apply {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!introExpanded) {
                            titleText.maxLines = 1
                            titleText.ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                    }
                })
            }
            descHeightAnimator = animateHeight(introDescClip, 0, 240L)
        }
    }

    private fun animateHeight(view: View, target: Int, duration: Long): ValueAnimator {
        val start = if (view.layoutParams.height >= 0) view.layoutParams.height else view.height
        return ValueAnimator.ofInt(start, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val lp = view.layoutParams
                lp.height = va.animatedValue as Int
                view.layoutParams = lp
            }
            start()
        }
    }

    /** 粉丝数：万以下显示具体数字，万以上显示 "1 万粉丝"（最多一位小数）。 */
    private fun formatFans(count: Long): String {
        if (count <= 0L) return "粉丝数未知"
        return if (count < 10_000L) "$count 粉丝"
        else "${formatViews(count)} 粉丝"
    }

    /** 发布时间精确到分钟，例如 "2026年7月31日 22:22"。 */
    private fun formatPublish(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "时间未知"
        return SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)
            .format(Date(epochSeconds * 1000L))
    }

    // ─────────────────────────────────────────────────
    //  Comment Tab
    // ─────────────────────────────────────────────────

    private fun resetComments() {
        if (::commentScroll.isInitialized) commentScroll.bindTarget("", 1, 0L)
    }

    private fun loadPlayer(quality: Int = effectiveQuality(), bypassCache: Boolean = false) {
        if (playbackStopped) {
            resumePlayerOnStart = true
            return
        }
        if (currentBvid.isBlank() || currentCid <= 0L) {
            showError("无法获取视频信息，点这里重试")
            return
        }
        val session = BiliSessionStore.cookie()
        if (streamSession != session) {
            currentStream = null
            playingStream = null
            failedCodecTracks.clear()
        }
        val generation = beginPlaybackChange()
        bindDanmakuForCurrentMedia()
        lastRequestedQuality = quality
        playbackRequest = BiliPlayUrl.fetchPlayUrlDetailed(
            currentBvid,
            currentCid,
            quality,
            allowProgressiveFallback = true,
            bypassCache = bypassCache
        ) { stream, error, permissionDenied ->
            handler.post {
                if (!canUsePlayback(generation)) return@post
                playbackRequest = null
                if (session != BiliSessionStore.cookie()) {
                    loadPlayer(quality = effectiveQuality(), bypassCache = true)
                    return@post
                }
                if (stream == null) {
                    val message = error ?: "未知错误"
                    val paywalled = currentDetail?.charge == true || permissionDenied
                    showError(if (paywalled) "该视频需要登录或大会员权限：$message\n\n点这里重试"
                        else "播放失败：$message\n\n点这里重试")
                    return@post
                }
                currentStream = stream
                streamSession = session
                streamLoadedAt = SystemClock.elapsedRealtime()
                val selected = selectStableStream(stream, quality)
                val targetId = selected.videoTracks.firstOrNull()?.id ?: selected.actualQuality
                if (requestedQuality != PreferredVideoQuality.HIGHEST && stream.isDash && quality > BiliPlayUrl.QUALITY_720 && targetId < quality &&
                    quality in stream.acceptedQualities
                ) {
                    val hint = if (quality >= BiliPlayUrl.QUALITY_1080_PLUS && !BiliSessionStore.isBigVip()) {
                        "${qualityName(quality)}需要大会员权限，已切换为${qualityName(targetId)}"
                    } else {
                        "未获取到${qualityName(quality)}视频流，当前播放${qualityName(targetId)}"
                    }
                    showSnackbar(hint)
                }
                startExoPlayer(selected, generation)
                showPreviewNotice(stream)
            }
        }
    }

    private fun beginPlaybackChange(): Int {
        clearPlayerFeedback()
        val generation = ++playbackGeneration
        playbackRequest?.cancel()
        playbackRequest = null
        // 切集时不能把上一集的位置带到新分集，否则新视频会直接 seek 到结尾
        if (!switchingLocalTask) rememberPlaybackPosition()
        stopPlayerSource()
        retryScheduled = false
        playerSpinner.visibility = View.VISIBLE
        playerError.visibility = View.GONE
        return generation
    }

    private fun rememberPlaybackPosition() {
        val player = exoPlayer ?: return
        if (playerListener == null || player.mediaItemCount == 0) return
        val current = playerGlobalPosition(player)
        if (player.playbackState == Player.STATE_READY || pendingResumePositionMs == 0L || current > 0L) {
            pendingResumePositionMs = current
        }
        resumeSegmentIndex = if (segmentTimeline.durationMs == 0L) player.currentMediaItemIndex else 0
        restoreEndedState = restoreEndedState || endedState
    }

    private fun stopPlayerSource() {
        playerHost.keepScreenOn = false
        handler.removeCallbacks(stallWatchdog)
        stallMonitor.reset()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(historyTick)
        val listener = playerListener
        playerListener = null
        cancelLongPressBoost()
        danmakuController?.freezeClock()
        exoPlayer?.let { player ->
            listener?.let(player::removeListener)
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
    }

    private fun canUsePlayback(generation: Int): Boolean =
        generation == playbackGeneration && !playbackStopped && !isFinishing && !isDestroyed

    private fun startExoPlayer(stream: BiliPlayUrl.DashStream, generation: Int) {
        if (!canUsePlayback(generation)) return
        val sources = try {
            OnlinePlaybackSource.create(stream)
        } catch (e: Exception) {
            showError("播放地址无效：${e.message ?: "未知错误"}\n\n点这里重试")
            return
        }
        playingStream = stream
        segmentTimeline = PlaybackTimeline(stream.durlSegments.map { it.lengthMs }, stream.durationMs)
        bindPlayerSource(sources, generation)
    }

    private fun showPreviewNotice(stream: BiliPlayUrl.DashStream) {
        val item = currentDetail ?: return
        if (playPreviewNoticeShown) return
        val looksPreview = stream.durationMs > 0L && item.duration > 0 && stream.durationMs < item.duration * 600L
        if (item.charge || looksPreview) {
            playPreviewNoticeShown = true
            showSnackbar("该视频为充电/付费视频，当前仅可试看部分内容，解锁后可在 B 站客户端完整观看")
        }
    }

    private fun selectStableStream(stream: BiliPlayUrl.DashStream, effective: Int): BiliPlayUrl.DashStream {
        val selected = BiliPlayUrl.selectDashStreamTrack(stream,
            PreferredVideoQuality.selectionQuality(requestedQuality, effective), failedCodecTracks)
        val video = selected.videoTracks.firstOrNull()
        val audio = selected.audioTracks.firstOrNull()
        if (video != null) {
            Log.i(
                "YuiBiliPlayer",
                "stable-select req=$effective video=${video.id}/${video.width}x${video.height}/${video.codecs}/bw=${video.bandwidth}, audio=${audio?.id}"
            )
        }
        return selected
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun bindPlayerSource(sources: List<MediaSource>, generation: Int) {
        if (!canUsePlayback(generation)) return
        val player = exoPlayer ?: createPlayer().also { exoPlayer = it }
        playbackDiagnostics.bind(if (isLocalMode) null else playingStream)
        playerListener?.let(player::removeListener)
        endedState = restoreEndedState
        playPauseBtn.setEnded(endedState)
        showReplay(endedState)
        playerView.player = player
        playerView.setKeepContentOnPlayerReset(true)
        initialHistoryMedia?.let { media ->
            val duration = segmentTimeline.durationMs.takeIf { it > 0L } ?: playingStream?.durationMs ?: 0L
            pendingResumePositionMs = checkedHistoryStart(media, HistoryMediaId(currentBvid, currentAid, currentCid),
                pendingResumePositionMs, duration, sources.size == 1 || segmentTimeline.durationMs > 0L)
            initialHistoryMedia = null
        }
        val resume = if (segmentTimeline.durationMs > 0L) {
            segmentTimeline.locate(pendingResumePositionMs)
        } else {
            PlaybackTimeline.Position(resumeSegmentIndex.coerceIn(0, sources.lastIndex), pendingResumePositionMs)
        }
        player.setMediaSources(sources, resume.index, resume.offsetMs)
        player.playbackParameters = PlaybackParameters(speeds[speedIndex])
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (!canUsePlayback(generation) || player !== exoPlayer) return
                when (state) {
                    Player.STATE_READY -> {
                        handler.removeCallbacks(stallWatchdog)
                        stallRetries = 0
                        playerSpinner.visibility = View.GONE
                        playerError.visibility = View.GONE
                        pendingResumePositionMs = 0L
                        restoreEndedState = false
                        retryScheduled = false
                        playPauseBtn.setEnded(endedState)
                        playPauseBtn.setPlaying(player.isPlaying)
                        playingStream?.let(::updateQualityState)
                    }
                    Player.STATE_BUFFERING -> if (!isSeeking) {
                        playerSpinner.visibility = View.VISIBLE
                    }
                    Player.STATE_ENDED -> {
                        reportPlayHistory(status = 2)
                        playerSpinner.visibility = View.GONE
                        cancelLongPressBoost()
                        if (!isLocalMode && userWantsPlayback && activityResumed) {
                            val episodes = favoritePlayback?.entries ?: cachedSeason?.episodes.orEmpty()
                            val idx = episodes.playingIndex(currentBvid, currentAid, currentCid)
                            nextEpisodeIndex(episodes.size, idx, onlinePlayMode, automatic = true)?.let { next ->
                                if (next == idx) replay() else selectEpisode(episodes[next])
                                return
                            }
                        }
                        if (isLocalMode) {
                            val nextId = DownloadManager.resolveNextPlayableTaskId(
                                localPlaylist, localTaskId, localPlayMode
                            )
                            if (nextId != null) {
                                if (nextId == localTaskId) {
                                    // 单集循环（或仅一集的列表循环）：直接从头重播
                                    replay()
                                    return
                                } else {
                                    switchLocalTask(nextId)
                                    return
                                }
                            }
                        }
                        endedState = true
                        playPauseBtn.setEnded(true)
                        playPauseBtn.setPlaying(false)
                        showReplay(true)
                        showControls()
                    }
                }
                updatePlaybackProgress()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (canUsePlayback(generation) && player === exoPlayer) updatePlaybackProgress()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!canUsePlayback(generation) || player !== exoPlayer) return
                handler.removeCallbacks(historyTick)
                if (isPlaying) {
                    if (!isLocalMode) {
                        reportPlayHistory(status = 3)
                        handler.postDelayed(historyTick, 30_000L)
                    }
                    endedState = false
                    playPauseBtn.setEnded(false)
                    playPauseBtn.setPlaying(true)
                    showReplay(false)
                    scheduleAutoHide()
                } else {
                    playPauseBtn.setEnded(endedState)
                    playPauseBtn.setPlaying(false)
                    if (activityResumed) showControls()
                }
                updatePlaybackProgress()
            }

            override fun onRenderedFirstFrame() {
                if (canUsePlayback(generation) && player === exoPlayer) {
                    val format = player.videoFormat
                    val size = player.videoSize
                    if (format != null || size.width > 0) {
                        Log.i(
                            "YuiBiliPlayer",
                            "rendered-format id=${format?.id} codecs=${format?.codecs} " +
                                "format=${format?.width}x${format?.height} size=${size.width}x${size.height} " +
                                "playingQuality=${playingStream?.actualQuality}"
                        )
                    }
                    if (startupAtMs > 0L) {
                        Log.d("YuiBiliPlayer", "First frame in ${SystemClock.elapsedRealtime() - startupAtMs} ms")
                        startupAtMs = 0L
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (canUsePlayback(generation) && player === exoPlayer && videoSize.width > 0) {
                    Log.i(
                        "YuiBiliPlayer",
                        "video-size ${videoSize.width}x${videoSize.height} playingQuality=${playingStream?.actualQuality}"
                    )
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (!canUsePlayback(generation) || player !== exoPlayer) return
                val stream = playingStream
                if (isLocalMode || stream == null) {
                    showError("本地文件播放失败：${error.message ?: error.errorCodeName}\n\n点这里重试")
                } else {
                    recoverPlayback(stream, error, generation)
                }
            }
        }
        playerListener = listener
        player.addListener(listener)
        player.playWhenReady = userWantsPlayback && activityResumed && !endedState
        player.prepare()
        updatePlaybackProgress()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun createPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder().apply {
            if (isLocalMode) {
                setBufferDurationsMs(5_000, 20_000, 250, 500)
                setTargetBufferBytes(16 * 1024 * 1024)
            } else {
                setBufferDurationsMs(18_000, 50_000, 1_200, 3_200)
                setTargetBufferBytes(36 * 1024 * 1024)
                setBackBuffer(4_000, false)
            }
        }.build()
        return ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(loadControl)
            .build().also { it.addAnalyticsListener(playbackDiagnostics) }
    }

    /**
     * Quality the user is allowed to play right now. Guests are capped at
     * 480p (720p and above require login, 1080p+ requires a big member).
     */
    private fun effectiveQuality(): Int =
        PreferredVideoQuality.requestQuality(requestedQuality, BiliSessionStore.isLoggedIn())

    private fun switchQuality(quality: Int) {
        requestedQuality = quality
        PreferredVideoQuality.onManualSelection(this, quality)
        if (quality == playingStream?.actualQuality && playerListener != null &&
            playerError.visibility != View.VISIBLE && streamSession == BiliSessionStore.cookie() &&
            playbackRequest == null && !retryScheduled
        ) return
        playRetryCount = 0
        lastPlaybackError = null
        val effective = effectiveQuality()
        val stream = currentStream
        if (stream != null && streamSession == BiliSessionStore.cookie() && stream.isDash &&
            stream.videoTracks.any { it.id == effective }
        ) {
            lastRequestedQuality = effective
            startExoPlayer(selectStableStream(stream, effective), beginPlaybackChange())
        } else {
            loadPlayer(effective)
        }
    }

    private fun loadQualityPreference(): Int = PreferredVideoQuality.initial(this)

    private fun recoverPlayback(
        stream: BiliPlayUrl.DashStream,
        error: androidx.media3.common.PlaybackException,
        generation: Int
    ) {
        if (!canUsePlayback(generation) || retryScheduled || currentCid <= 0L) return
        lastPlaybackError = "${error.errorCodeName} ${error.message ?: ""}"
        val transientIo = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        val decodeError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        val manifestError = stream.isDash && (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)
        if (!transientIo && !decodeError && !manifestError) {
            showError("播放出错：${error.errorCodeName}\n\n${error.message ?: ""}\n\n点这里重试")
            return
        }

        retryScheduled = true
        rememberPlaybackPosition()
        playRetryCount++
        playerSpinner.visibility = View.VISIBLE
        playerError.visibility = View.GONE

        val fullStream = currentStream
        val currentTrack = stream.videoTracks.firstOrNull()
        // 解码或清单异常时，优先在同一画质下尝试其他编码轨（如 AVC <-> HEVC <-> AV1），避免直接跌回 DURL 720p
        val alternateCodec = if ((decodeError || manifestError) && stream.isDash && fullStream != null && currentTrack != null) {
            failedCodecTracks.add(currentTrack.id to currentTrack.codecs)
            fullStream.videoTracks.firstOrNull { track ->
                track.id == currentTrack.id && (track.id to track.codecs) !in failedCodecTracks
            }?.let { nextTrack ->
                Log.w("YuiBiliPlayer", "codec-fallback quality=${nextTrack.id} from=${currentTrack.codecs} to=${nextTrack.codecs}")
                selectStableStream(fullStream, currentTrack.id)
            }
        } else null

        val alternateCdn = if (playRetryCount == 1 && transientIo) rotateCdnCandidates(stream).takeIf { it != stream } else null
        when {
            alternateCodec != null -> startExoPlayer(alternateCodec, beginPlaybackChange())
            playRetryCount == 1 && (transientIo || BiliPlayUrl.isStreamNearExpiry(stream)) &&
                SystemClock.elapsedRealtime() - streamLoadedAt > 20_000L -> {
                // 地址可能过期或 403，优先强制刷新最新 PlayUrl
                loadPlayer(minOf(lastRequestedQuality, effectiveQuality()), bypassCache = true)
            }
            alternateCdn != null -> startExoPlayer(alternateCdn, beginPlaybackChange())
            playRetryCount <= 2 && stream.isDash -> {
                playRetryCount = 2
                val nextGeneration = beginPlaybackChange()
                retryScheduled = true
                val session = BiliSessionStore.cookie()
                val durlQuality = minOf(lastRequestedQuality, effectiveQuality(), BiliPlayUrl.QUALITY_1080)
                playbackRequest = BiliPlayUrl.fetchDurlPlayUrl(currentBvid, currentCid, durlQuality, bypassCache = true) { fallback, _ ->
                    handler.post {
                        if (!canUsePlayback(nextGeneration)) return@post
                        playbackRequest = null
                        retryScheduled = false
                        when {
                            session != BiliSessionStore.cookie() -> loadPlayer(bypassCache = true)
                            fallback != null -> startExoPlayer(fallback, nextGeneration)
                            else -> lowerQualityAndReload()
                        }
                    }
                }
            }
            playRetryCount <= 3 -> lowerQualityAndReload()
            else -> showError(
                "播放失败：网络或解码器持续异常\n\n${lastPlaybackError ?: ""}\n\n已尝试刷新地址、备用线路和低画质。点这里重试"
            )
        }
    }

    private fun rotateCdnCandidates(stream: BiliPlayUrl.DashStream): BiliPlayUrl.DashStream {
        fun <T> rotate(list: List<T>): List<T> = if (list.size > 1) list.drop(1) + list.first() else list
        if (!stream.isDash) {
            val original = stream.durlSegments.ifEmpty {
                listOf(BiliPlayUrl.DurlSegment(stream.fallbackUrls, stream.durationMs, 0L))
            }
            val segments = original.map { segment ->
                segment.copy(urls = rotate(segment.urls.filter(String::isNotBlank)))
            }
            return stream.copy(
                fallbackUrls = segments.firstOrNull()?.urls.orEmpty(),
                durlSegments = segments
            )
        }
        return stream.copy(
            videoTracks = stream.videoTracks.map { track ->
                val urls = rotate((listOf(track.url) + track.backups).distinct())
                track.copy(url = urls.first(), backups = urls.drop(1))
            },
            audioTracks = stream.audioTracks.map { track ->
                val urls = rotate((listOf(track.url) + track.backups).distinct())
                track.copy(url = urls.first(), backups = urls.drop(1))
            }
        )
    }

    private fun lowerQualityAndReload() {
        val next = when {
            lastRequestedQuality > BiliPlayUrl.QUALITY_720 -> BiliPlayUrl.QUALITY_720
            lastRequestedQuality > BiliPlayUrl.QUALITY_480 -> BiliPlayUrl.QUALITY_480
            else -> BiliPlayUrl.QUALITY_360
        }
        loadPlayer(minOf(next, effectiveQuality()))
    }

    private fun playerGlobalPosition(player: ExoPlayer): Long =
        segmentTimeline.global(player.currentMediaItemIndex, player.currentPosition)

    // ─────────────────────────────────────────────────
    //  后台播放：移交到在线音频页
    // ─────────────────────────────────────────────────

    /** 仅在线视频与本地视频可切；本地纯音频本身已是音频，隐藏入口。 */
    /** 后台播放仅对在线视频有意义：本地下载（视频或纯音频）均不显示。 */
    private fun updateBackgroundPlayVisibility() {
        if (!::backgroundPlayBtn.isInitialized) return
        backgroundPlayBtn.visibility = if (isLocalMode) View.GONE else View.VISIBLE
        backgroundPlayBtn.isEnabled = !backgroundSwitchLocked
        backgroundPlayBtn.alpha = if (backgroundSwitchLocked) 0.45f else 1f
    }

    /** 把当前视频的播放位置与合集队列交给音频页，从同一进度继续。 */
    private fun enterBackgroundAudioPlayback() {
        if (backgroundSwitchLocked) return
        if (isLocalMode) {
            showSnackbar("本地视频请直接在下载管理中打开对应音频")
            return
        }
        val bvid = currentBvid
        val cid = currentCid
        if (bvid.isBlank() || cid <= 0L) {
            showSnackbar("视频尚未加载完成")
            return
        }
        val player = exoPlayer ?: return
        val positionMs = playerGlobalPosition(player).coerceAtLeast(0L)
        val fallbackDurationMs = playerGlobalDuration(player).takeIf { it > 0L }
            ?: currentDetail?.duration?.toLong()?.times(1000L) ?: 0L

        backgroundSwitchLocked = true
        updateBackgroundPlayVisibility()
        userWantsPlayback = false
        player.pause()

        val launch = OnlineAudioLaunch(
            entry = buildOnlineEntry(bvid, cid, fallbackDurationMs),
            startPositionMs = positionMs,
            season = if (favoritePlayback != null) null else (currentDetail?.season ?: cachedSeason),
            favorite = favoritePlayback
        )
        startActivity(OnlineAudioLauncher.intent(this, launch))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun buildOnlineEntry(bvid: String, cid: Long, durationMs: Long): UgcEpisode {
        val detail = currentDetail
        return UgcEpisode(
            bvid = bvid,
            aid = currentAid,
            cid = cid,
            title = detail?.title.orEmpty().ifBlank { "未命名音频" },
            duration = (durationMs / 1000L).coerceAtLeast(0L).toInt(),
            cover = detail?.cover.orEmpty(),
            publishedAt = detail?.publishedAt ?: 0L
        )
    }

    private fun playerGlobalBufferedPosition(player: ExoPlayer): Long =
        segmentTimeline.global(player.currentMediaItemIndex, player.bufferedPosition)

    private fun playerGlobalDuration(player: ExoPlayer): Long =
        segmentTimeline.durationMs.takeIf { it > 0L }
            ?: player.duration.takeIf { it > 0L }
            ?: playingStream?.durationMs ?: 0L

    private fun seekPlayerGlobal(player: ExoPlayer, positionMs: Long) {
        stallMonitor.reset()
        handler.removeCallbacks(stallWatchdog)
        if (endedState && positionMs < playerGlobalDuration(player)) clearEndedState()
        if (segmentTimeline.durationMs == 0L) {
            player.seekTo(positionMs.coerceAtLeast(0L))
        } else {
            val position = segmentTimeline.locate(positionMs)
            player.seekTo(position.index, position.offsetMs)
        }
    }

    private fun updateQualityState(stream: BiliPlayUrl.DashStream) {
        // 本地播放不展示画质切换与在线信息
        if (isLocalMode) return
        val full = currentStream ?: stream
        val available = full.videoTracks.map { it.id }.distinct().sortedDescending()
        // Show the tier actually being played (the probed track), not the
        // highest tier the API offered — they can differ after fallbacks.
        val playingId = stream.videoTracks.firstOrNull()?.id
            ?: if (!stream.isDash) stream.actualQuality
            else available.firstOrNull() ?: stream.actualQuality
        val suffix = if (available.isEmpty()) "" else " · 可用：${available.joinToString("/") { qualityName(it) }}"
        if (infoState.parent == null) {
            infoState.text = "当前播放：${qualityLabelOf(playingId)}$suffix"
            infoState.setTextColor(COLOR_MUTED)
        }
        qualityBtn.text = formatQualityButtonText(playingId)
    }

    private fun qualityLabelOf(id: Int): String = BiliPlayUrl.qualityLabel(id)

    private fun qualityResLabel(id: Int): String =
        formatQualityButtonText(id)

    private fun qualityName(id: Int): String = when (id) {
        127 -> "8K"; 126 -> "杜比视界"; 125 -> "HDR"; 120 -> "4K"; 116 -> "1080P 60帧"
        112 -> "1080P 高码"; 80 -> "1080P"; 64 -> "720P"; 32 -> "480P"; 16 -> "360P"
        else -> "${id}档"
    }

    // ─────────────────────────────────────────────────
    //  Progress & Time
    // ─────────────────────────────────────────────────

    private val progressUpdater = Runnable { updatePlaybackProgress() }

    private fun updatePlaybackProgress() {
        handler.removeCallbacks(progressUpdater)
        val player = exoPlayer ?: return
        playerHost.keepScreenOn = activityResumed && playerListener != null && player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (!activityResumed || playerListener == null) return
        playbackDiagnostics.sample(player)
        if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady && userWantsPlayback && !isSeeking && !retryScheduled) {
            val newlyBuffering = !stallMonitor.started
            stallMonitor.observe(SystemClock.elapsedRealtime(), playerGlobalBufferedPosition(player))
            if (newlyBuffering) handler.postDelayed(stallWatchdog, STALL_TIMEOUT_MS)
        } else {
            stallMonitor.reset()
            handler.removeCallbacks(stallWatchdog)
        }
        val duration = playerGlobalDuration(player)
        val position = playerGlobalPosition(player)
        val speed = player.playbackParameters.speed.takeIf { it > 0f } ?: 1f
        danmakuController?.onPlaybackTick(
            positionMs = position,
            durationMs = duration,
            playing = player.isPlaying && !isSeeking,
            speed = speed
        )
        if (duration > 0L && !isSeeking) {
            progressContainer.setProgress(position, playerGlobalBufferedPosition(player), duration)
            if (position / 1000L != lastPositionSecond) {
                lastPositionSecond = position / 1000L
                timeText.text = formatTime(position)
            }
            if (duration / 1000L != lastDurationSecond) {
                lastDurationSecond = duration / 1000L
                durationText.text = formatTime(duration)
            }
        }
        if (player.isPlaying || player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
            handler.postDelayed(progressUpdater, 250L)
        }
    }

    /** Shared by the seek bar and the swipe gesture: updates time text + bar, clears ended state when scrubbing back. */
    private fun onManualSeek(pos: Long, dur: Long) {
        lastPositionSecond = pos / 1000L
        timeText.text = formatTime(pos)
        progressContainer.seekToFraction(pos.toFloat() / dur.toFloat())
    }

    private fun clearEndedState() {
        endedState = false
        playPauseBtn.setEnded(false)
        playPauseBtn.setPlaying(false)
        showReplay(false)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return String.format(Locale.CHINA, "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    // ─────────────────────────────────────────────────
    //  Controls: Play/Pause, Speed, Quality, Fullscreen
    // ─────────────────────────────────────────────────

    private fun togglePlayPause() {
        val p = exoPlayer ?: return
        if (endedState) { replay() }
        else if (p.playWhenReady) {
            // isPlaying is false while buffering; playWhenReady reflects whether
            // playback will resume automatically and is the correct pause toggle.
            userWantsPlayback = false
            p.pause()
        } else {
            userWantsPlayback = true
            if (activityResumed) p.play()
        }
    }

    private fun replay() {
        val p = exoPlayer ?: return
        endedState = false
        playPauseBtn.setEnded(false)
        playPauseBtn.setPlaying(false)
        showReplay(false)
        userWantsPlayback = true
        p.seekTo(0, 0L)
        danmakuController?.onSeeked(0L)
        p.play()
        showControls()
    }

    private fun showReplay(show: Boolean) {
        if (show) {
            replayBtn.visibility = View.VISIBLE
            replayBtn.alpha = 0f
            replayBtn.scaleX = 0.8f
            replayBtn.scaleY = 0.8f
            replayBtn.animate().cancel()
            replayBtn.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        } else {
            replayBtn.animate().cancel()
            replayBtn.visibility = View.GONE
        }
    }

    // ── Long-press boost (manual timing so it works on every press) ──

    private fun applyLongPressBoost() {
        val p = exoPlayer ?: return
        if (!longPressDown || !longPressCandidate || endedState || !p.isPlaying) return
        longPressBoost = true
        normalSpeed = p.playbackParameters.speed.let { if (it > 0f) it else 1f }
        p.playbackParameters = PlaybackParameters(2.0f)
        updatePlaybackProgress()
        showBoostIndicator()
    }

    private fun cancelLongPressBoost() {
        longPressDown = false
        longPressCandidate = false
        handler.removeCallbacks(longPressBoostRunnable)
        if (longPressBoost) {
            longPressBoost = false
            exoPlayer?.playbackParameters = PlaybackParameters(normalSpeed)
            updatePlaybackProgress()
            hideBoostIndicator()
        }
    }

    private fun showBoostIndicator() {
        boostIndicator.visibility = View.VISIBLE
        boostIndicator.alpha = 0f
        boostIndicator.animate().cancel()
        boostIndicator.animate().alpha(1f).setDuration(150).start()
        boostAnimator?.cancel()
        boostAnimator = ValueAnimator.ofFloat(1f, 1.08f).apply {
            duration = 420
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val s = it.animatedValue as Float
                boostIndicator.scaleX = s
                boostIndicator.scaleY = s
            }
            start()
        }
    }

    private fun hideBoostIndicator() {
        boostAnimator?.cancel(); boostAnimator = null
        boostIndicator.animate().cancel()
        boostIndicator.visibility = View.GONE
        boostIndicator.alpha = 0f
        boostIndicator.scaleX = 1f
        boostIndicator.scaleY = 1f
    }

    private fun positionSeekHint() {
        if (!::playerSeekHint.isInitialized) return
        val lp = playerSeekHint.layoutParams as FrameLayout.LayoutParams
        val progressLayout = progressRow.layoutParams as FrameLayout.LayoutParams
        val bottom = progressLayout.bottomMargin + progressLayout.height + dp(6)
        if (lp.bottomMargin != bottom) { lp.bottomMargin = bottom; playerSeekHint.layoutParams = lp }
    }

    private fun showSeekHint(posMs: Long, durMs: Long) {
        if (!::playerSeekHint.isInitialized) return
        positionSeekHint()
        playerSeekHint.text = "${formatTime(posMs)}  /  ${formatTime(durMs)}"
        playerSeekHint.animate().cancel()
        if (playerSeekHint.visibility != View.VISIBLE) {
            playerSeekHint.visibility = View.VISIBLE
            playerSeekHint.alpha = 0f
            playerSeekHint.animate().alpha(1f).setDuration(110).start()
        } else {
            playerSeekHint.alpha = 1f
        }
    }

    private fun hideSeekHint() {
        if (!::playerSeekHint.isInitialized) return
        playerSeekHint.animate().cancel()
        if (playerSeekHint.visibility == View.VISIBLE) {
            playerSeekHint.animate().alpha(0f).setDuration(140)
                .withEndAction { playerSeekHint.visibility = View.GONE }.start()
        }
    }

    private fun showSkipHint(text: String) {
        handler.removeCallbacks(hideSkipHint)
        playerSkipHint.text = text
        playerSkipHint.visibility = View.VISIBLE
        handler.postDelayed(hideSkipHint, 800L)
    }

    private fun clearPlayerFeedback() {
        speedPopup?.dismiss()
        speedPopup = null
        handler.removeCallbacks(hideSkipHint)
        hideSkipHint.run()
        if (::playerSeekHint.isInitialized) {
            playerSeekHint.animate().cancel()
            playerSeekHint.visibility = View.GONE
        }
    }

    private fun skipByDoubleTap(delta: Long) {
        val p = exoPlayer ?: return
        val duration = playerGlobalDuration(p)
        if (duration <= 0 || playerListener == null || !p.isCurrentMediaItemSeekable) return
        val target = (playerGlobalPosition(p) + delta).coerceIn(0L, duration)
        seekPlayerGlobal(p, target)
        danmakuController?.onSeeked(target)
        onManualSeek(target, duration)
        updatePlaybackProgress()
        showSkipHint(if (delta < 0L) "后退 10 秒" else "前进 10 秒")
    }

    private fun showSpeedMenu() {
        speedPopup?.dismiss()
        controlsHideRunnable?.let(handler::removeCallbacks)
        speedPopup = showPlayerSpeedMenu(speedBtn, playerHost, speeds, speedIndex, onDismiss = {
            speedPopup = null
            if (activityResumed && exoPlayer?.isPlaying == true) scheduleAutoHide()
        }) { index ->
            cancelLongPressBoost()
            speedIndex = index
            val speed = speeds[index]
            exoPlayer?.playbackParameters = PlaybackParameters(speed)
            speedBtn.text = "${speed}x"
            updatePlaybackProgress()
        }
    }

    private fun showQualityMenu(anchor: View) {
        val stream = currentStream ?: return
        val qualities = stream.acceptedQualities.ifEmpty { stream.videoTracks.map { it.id } }
            .distinct().sortedDescending()
        if (qualities.isEmpty()) return
        val loggedIn = BiliSessionStore.isLoggedIn()
        val playingId = playingStream?.actualQuality ?: effectiveQuality()
        val availableIds = stream.videoTracks.map { it.id }.toSet()

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = Ds.plate(0xE6181A20.toInt(), 0x26FFFFFF, Ds.hairline(this@VideoDetailActivity), dp(14).toFloat())
        }
        lateinit var popup: PopupWindow
        qualities.forEachIndexed { idx, quality ->
            val available = quality in availableIds
            val isSelected = quality == playingId
            val suffix = when {
                !available -> " · 暂无流"
                quality <= BiliPlayUrl.QUALITY_480 -> ""
                quality < BiliPlayUrl.QUALITY_1080 -> if (loggedIn) "" else " · 需登录"
                else -> if (BiliSessionStore.isBigVip()) " · 大会员" else " · 需大会员"
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                if (isSelected) background = rounded(0x28F27D9E, dp(9))
                isClickable = true
                Ds.bindPressFeedback(this, 0.96f)
                setOnClickListener {
                    popup.dismiss()
                    if (!available) {
                        val reason = when {
                            quality >= BiliPlayUrl.QUALITY_1080_PLUS && !BiliSessionStore.isBigVip() ->
                                "${qualityName(quality)}需要大会员权限"
                            quality > BiliPlayUrl.QUALITY_480 && !loggedIn ->
                                "${qualityName(quality)}需要登录账号"
                            else -> "正在尝试获取${qualityName(quality)}视频流"
                        }
                        showSnackbar(reason)
                        if (!loggedIn && quality > BiliPlayUrl.QUALITY_480) return@setOnClickListener
                        if (quality >= BiliPlayUrl.QUALITY_1080_PLUS && !BiliSessionStore.isBigVip()) return@setOnClickListener
                    }
                    switchQuality(quality)
                }
            }
            row.addView(TextView(this).apply {
                text = qualityName(quality)
                textSize = 13f
                typeface = Ds.body(bold = isSelected)
                setTextColor(when {
                    isSelected -> Ds.colors.accent
                    available -> Color.WHITE
                    else -> 0xFF9EA3AE.toInt()
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (suffix.isNotEmpty()) {
                row.addView(TextView(this).apply {
                    text = suffix.removePrefix(" · ")
                    textSize = 10.5f
                    setTextColor(if (isSelected) Ds.colors.accent else 0xFF9EA3AE.toInt())
                })
            }
            menu.addView(row, LinearLayout.LayoutParams(dp(172), dp(36)).apply {
                if (idx > 0) topMargin = dp(2)
            })
        }
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(dp(220), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuW = menu.measuredWidth.coerceAtLeast(dp(188))
        val menuH = menu.measuredHeight
        popup = PopupWindow(menu, menuW, LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(10).toFloat()
            isOutsideTouchable = true
        }
        val xOff = anchor.width - menuW
        val yOff = -(menuH + anchor.height + dp(8))
        popup.showAsDropDown(anchor, xOff, yOff)
    }

    // ─────────────────────────────────────────────────
    //  Controls Visibility
    // ─────────────────────────────────────────────────

    private fun toggleControls() {
        if (controlsAnimating) return
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsAnimating = true
        controlsVisible = true
        controlOverlay.animate().cancel()
        controlOverlay.visibility = View.VISIBLE
        if (reducedMotion) {
            controlOverlay.alpha = 1f
            controlsAnimating = false
        } else {
            controlOverlay.alpha = 0f
            controlOverlay.animate().alpha(1f).setDuration(MotionTokens.panelMs)
                .setInterpolator(MotionTokens.easeOut)
                .withEndAction { controlsAnimating = false }.start()
        }
        controlsHideRunnable?.let { handler.removeCallbacks(it) }
        if (exoPlayer?.isPlaying == true) scheduleAutoHide()
    }

    private fun hideControls() {
        controlsAnimating = true
        controlsVisible = false
        controlOverlay.animate().cancel()
        if (reducedMotion) {
            controlOverlay.visibility = View.GONE
            controlsAnimating = false
        } else {
            controlOverlay.animate().alpha(0f).setDuration(MotionTokens.panelMs)
                .setInterpolator(MotionTokens.easeOut)
                .withEndAction {
                    controlOverlay.visibility = View.GONE
                    controlsAnimating = false
                }.start()
        }
    }

    private fun scheduleAutoHide() {
        controlsHideRunnable?.let { handler.removeCallbacks(it) }
        controlsHideRunnable = Runnable { if (exoPlayer?.isPlaying == true && speedPopup?.isShowing != true) hideControls() }
        handler.postDelayed(controlsHideRunnable!!, 3500)
    }

    // ─────────────────────────────────────────────────
    //  全屏仅改变布局，保留视频表面和弹幕层的窗口绑定。
    // ─────────────────────────────────────────────────

    private fun toggleFullscreen() {
        if (!::infoArea.isInitialized || !::playerHost.isInitialized) return
        if (fsTransitioning) return
        clearPlayerFeedback()
        isFullscreen = !isFullscreen
        fsBtn.setFullscreen(isFullscreen)
        if (isFullscreen) danmakuView.setFullscreenMode(true)
        if (!isFullscreen) {
            closeSeasonDrawer(immediate = true)
            danmakuSettingsDrawer?.close(immediate = true)
        }
        controlsHideRunnable?.let { handler.removeCallbacks(it) }
        if (isFullscreen) enterFullscreen() else movePlayerBackToPage()
        if (::commentScroll.isInitialized) commentScroll.setPageActive(activityResumed && !isFullscreen && currentTab == 1)
        if (!isFullscreen && currentTab == 1) ensureCommentsStarted()
    }

    private fun isTabletSplitActive(): Boolean =
        LayoutMode.isTablet && !isFullscreen && resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels &&
            (resources.displayMetrics.widthPixels / resources.displayMetrics.density) >= 600f

    /** 根据当前模式与屏幕方向排列播放器与详情区：手机端恒定上下；平板横屏左播放(62%)右详情(38%)，平板竖屏上限46%高。 */
    private fun applyPageSplitLayout() {
        if (!::page.isInitialized || !::playerHost.isInitialized || !::infoArea.isInitialized) return
        if (isFullscreen) return
        val dm = resources.displayMetrics
        if (isTabletSplitActive()) {
            page.orientation = LinearLayout.HORIZONTAL
            val rightW = (dm.widthPixels * 0.36f).toInt().coerceIn(dp(340), dp(460))
            playerHost.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            tabletSplitDivider?.apply {
                visibility = View.VISIBLE
                layoutParams = LinearLayout.LayoutParams(Ds.hairline(this@VideoDetailActivity), LinearLayout.LayoutParams.MATCH_PARENT)
            }
            infoArea.layoutParams = LinearLayout.LayoutParams(rightW, LinearLayout.LayoutParams.MATCH_PARENT)
            if (::danmakuView.isInitialized) danmakuView.setFullscreenMode(true)
        } else {
            page.orientation = LinearLayout.VERTICAL
            val raw16x9 = dm.widthPixels * 9 / 16
            val playerHeight = if (LayoutMode.isTablet || dm.widthPixels > dm.heightPixels) {
                minOf(raw16x9, (dm.heightPixels * 0.45f).toInt())
            } else {
                raw16x9
            }
            playerHost.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, playerHeight)
            tabletSplitDivider?.visibility = View.GONE
            infoArea.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            if (::danmakuView.isInitialized) danmakuView.setFullscreenMode(false)
        }
        page.requestLayout()
    }

    private fun enterFullscreen() {
        fsTransitioning = true
        LayoutMode.applyScreenOrientation(this, fullscreen = true)
        infoArea.visibility = View.GONE
        tabletSplitDivider?.visibility = View.GONE
        playerHost.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        hideSystemBars()
        adjustIndicator.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        applyControlSizing()
        showControls()
        fsTransitioning = false
    }

    private fun movePlayerBackToPage() {
        fsTransitioning = true
        LayoutMode.applyScreenOrientation(this)
        showSystemBars()
        infoArea.visibility = View.VISIBLE
        applyPageSplitLayout()
        pendingIntro?.let { item ->
            pendingIntro = null
            renderIntroduction(item)
            if (currentTab == 1) ensureCommentsStarted()
        }
        adjustIndicator.animate().scaleX(COMPACT_ADJUST_SCALE).scaleY(COMPACT_ADJUST_SCALE)
            .setDuration(200).start()
        applyControlSizing()
        showControls()
        fsTransitioning = false
    }

    private fun applyPlayerSystemBars() {
        // 播放页顶部状态栏保持纯黑+白色图标；平板端底部导航栏同样保持纯黑+白色图标（避免浅色背景退全屏时导航栏白闪）。
        val navDark = LayoutMode.isTablet || Theme.isDark
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = if (navDark) Color.BLACK else COLOR_BACKGROUND
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        // 访问 insetsController 前必须先触发 decorView 初始化，否则 setContentView 之前调用会 NPE
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            val appearance = if (navDark) 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            decor.windowInsetsController?.setSystemBarsAppearance(appearance, mask)
        } else {
            @Suppress("DEPRECATION")
            var flags = decor.systemUiVisibility and
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                0x00000010.inv()
            if (!navDark) flags = flags or 0x00000010
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = flags
        }
    }

    private fun hideSystemBars() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decor.windowInsetsController?.apply {
                systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun showSystemBars() {
        applyPlayerSystemBars()
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decor.windowInsetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        applyPlayerSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        speedPopup?.dismiss()
        drawerModePopup?.dismiss()
        if (!::activityRoot.isInitialized) return
        activityRoot.post {
            if (!isFullscreen) applyPageSplitLayout()
            applyControlSizing()
        }
        updateFullscreenStatusVisibility()
    }

    private fun retryPlayer() {
        rememberPlaybackPosition()
        stallRetries = 0
        playRetryCount = 0
        retryScheduled = false
        userWantsPlayback = true
        if (isLocalMode) { loadLocalPlayer(); return }
        if (currentBvid.isNotBlank() && currentCid > 0L) {
            loadPlayer(bypassCache = true)
        } else {
            playerError.visibility = View.GONE
            playerSpinner.visibility = View.VISIBLE
            loadVideoAndPlayer()
        }
    }
    private fun showError(msg: String) {
        playerSpinner.visibility = View.GONE; playerError.text = msg; playerError.visibility = View.VISIBLE
    }
    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    /** Top-rounded panel background for bottom sheets. */
    private fun roundedTop(fill: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadii = floatArrayOf(radius.toFloat(), radius.toFloat(), radius.toFloat(), radius.toFloat(), 0f, 0f, 0f, 0f)
        }

/** Action bar icon: modern outline style (thin strokes, no emoji) + label. */
    private inner class ActionIconView(context: Context, private val kind: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dpf(11f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private var active = false
        private var count = 0L

        fun setActive(value: Boolean) { active = value; invalidate() }
        fun isActive(): Boolean = active
        fun setCount(value: Long) { count = value.coerceAtLeast(0L); invalidate() }

        override fun onDraw(canvas: Canvas) {
            val color = if (active) COLOR_ROSE else COLOR_MUTED
            paint.color = color
            paint.strokeWidth = dpf(1.7f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val cx = width / 2f
            val iconCy = height / 2f - dpf(7f)
            val labelCy = height - dpf(11f)
            // 与「我的」页 MenuIconView 同批：1.7dp 圆角线条；点赞/收藏激活时实心
            when (kind) {
                KIND_LIKE -> {
                    paint.style = if (active) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                    val thumb = Path().apply {
                        moveTo(cx - dpf(1.2f), iconCy - dpf(5.8f))
                        quadTo(cx + dpf(1.6f), iconCy - dpf(7.2f), cx + dpf(3.2f), iconCy - dpf(4.4f))
                        lineTo(cx + dpf(2.2f), iconCy - dpf(1.2f))
                        lineTo(cx + dpf(5.6f), iconCy - dpf(0.6f))
                        quadTo(cx + dpf(7.2f), iconCy + dpf(0.2f), cx + dpf(6.6f), iconCy + dpf(2.6f))
                        lineTo(cx + dpf(5.8f), iconCy + dpf(5.6f))
                        quadTo(cx + dpf(5.2f), iconCy + dpf(6.8f), cx + dpf(3.6f), iconCy + dpf(6.6f))
                        lineTo(cx - dpf(3.8f), iconCy + dpf(6.4f))
                        quadTo(cx - dpf(5.4f), iconCy + dpf(6.2f), cx - dpf(5.6f), iconCy + dpf(4.6f))
                        lineTo(cx - dpf(5.4f), iconCy + dpf(0.6f))
                        quadTo(cx - dpf(5.2f), iconCy - dpf(1.0f), cx - dpf(3.4f), iconCy - dpf(1.4f))
                        lineTo(cx - dpf(1.8f), iconCy - dpf(1.6f))
                        close()
                    }
                    canvas.drawPath(thumb, paint)
                }
                KIND_FAVORITE -> {
                    // 空心五角星（与「我的收藏」同款）
                    paint.style = if (active) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                    val outer = dpf(8.2f)
                    val inner = outer * 0.42f
                    val star = Path()
                    for (i in 0 until 5) {
                        val aOut = Math.toRadians((-90 + i * 72).toDouble())
                        val aIn = Math.toRadians((-90 + i * 72 + 36).toDouble())
                        val ox = cx + (outer * Math.cos(aOut)).toFloat()
                        val oy = iconCy + (outer * Math.sin(aOut)).toFloat()
                        val ix = cx + (inner * Math.cos(aIn)).toFloat()
                        val iy = iconCy + (inner * Math.sin(aIn)).toFloat()
                        if (i == 0) star.moveTo(ox, oy) else star.lineTo(ox, oy)
                        star.lineTo(ix, iy)
                    }
                    star.close()
                    canvas.drawPath(star, paint)
                }
                KIND_DOWNLOAD -> {
                    // 下行箭头 + 托盘
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(cx, iconCy - dpf(7.2f), cx, iconCy + dpf(2.2f), paint)
                    canvas.drawLine(cx - dpf(4.6f), iconCy - dpf(1.4f), cx, iconCy + dpf(2.6f), paint)
                    canvas.drawLine(cx + dpf(4.6f), iconCy - dpf(1.4f), cx, iconCy + dpf(2.6f), paint)
                    val tray = Path().apply {
                        moveTo(cx - dpf(6.8f), iconCy + dpf(4.2f))
                        lineTo(cx - dpf(6.8f), iconCy + dpf(6.8f))
                        lineTo(cx + dpf(6.8f), iconCy + dpf(6.8f))
                        lineTo(cx + dpf(6.8f), iconCy + dpf(4.2f))
                    }
                    canvas.drawPath(tray, paint)
                }
                KIND_SHARE -> {
                    // 三节点分享：与菜单线条同风格
                    paint.style = Paint.Style.STROKE
                    val r = dpf(2.4f)
                    val topX = cx + dpf(4.8f); val topY = iconCy - dpf(5.2f)
                    val midX = cx - dpf(5.0f); val midY = iconCy
                    val botX = cx + dpf(4.8f); val botY = iconCy + dpf(5.2f)
                    canvas.drawLine(midX, midY, topX, topY, paint)
                    canvas.drawLine(midX, midY, botX, botY, paint)
                    canvas.drawCircle(topX, topY, r, paint)
                    canvas.drawCircle(midX, midY, r, paint)
                    canvas.drawCircle(botX, botY, r, paint)
                }
            }
            textPaint.color = color
            val label = when (kind) {
                KIND_LIKE -> if (count > 0L) formatViews(count) else "点赞"
                KIND_FAVORITE -> "收藏"
                KIND_DOWNLOAD -> "下载"
                else -> "分享"
            }
            canvas.drawText(label, cx, labelCy, textPaint)
        }
    }

    private inner class HorizontalPagerView(context: Context) : ViewGroup(context) {
        private val scroller = Scroller(context, DecelerateInterpolator())
        private val velocityTracker = VelocityTracker.obtain()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var dragging = false
        private var pageIndex = 0
        var pageChangeListener: ((Int) -> Unit)? = null
        // 0..1 horizontal progress between the first and the last page; drives tab visuals.
        var pageProgressListener: ((Float) -> Unit)? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val childW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
            val childH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) getChildAt(i).measure(childW, childH)
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = width
            for (i in 0 until childCount) {
                getChildAt(i).layout(i * w, 0, (i + 1) * w, height)
            }
        }

        private fun notifyProgress() {
            pageProgressListener?.invoke((scrollX.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f))
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; lastX = ev.x; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.x
                    velocityTracker.clear()
                    velocityTracker.addMovement(ev)
                    scroller.forceFinished(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val d = lastX - ev.x
                    lastX = ev.x
                    velocityTracker.addMovement(ev)
                    val maxX = (childCount - 1) * width
                    scrollTo((scrollX + d.toInt()).coerceIn(0, maxX), 0)
                    notifyProgress()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    velocityTracker.addMovement(ev)
                    velocityTracker.computeCurrentVelocity(1000)
                    val vx = velocityTracker.xVelocity
                    val maxX = (childCount - 1) * width
                    val offset = scrollX - pageIndex * width
                    val target = when {
                        // A fast fling switches page even without dragging far.
                        vx > 900 -> (pageIndex - 1).coerceAtLeast(0)
                        vx < -900 -> (pageIndex + 1).coerceAtMost(childCount - 1)
                        // Otherwise only ~1/4 of the page width is enough.
                        offset > width * 0.25f -> (pageIndex + 1).coerceAtMost(childCount - 1)
                        offset < -width * 0.25f -> (pageIndex - 1).coerceAtLeast(0)
                        else -> pageIndex
                    }
                    velocityTracker.clear()
                    scrollToPage(target, smooth = true)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    velocityTracker.clear()
                    scrollToPage(pageIndex, smooth = true)
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        fun scrollToPage(index: Int, smooth: Boolean) {
            val target = index.coerceIn(0, childCount - 1)
            if (smooth) {
                scroller.forceFinished(true)
                scroller.startScroll(scrollX, 0, target * width - scrollX, 0, 260)
                invalidate()
            } else {
                scroller.forceFinished(true)
                scrollTo(target * width, 0)
                pageIndex = target
                notifyProgress()
                pageChangeListener?.invoke(target)
            }
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.currX, 0)
                notifyProgress()
                postInvalidateOnAnimation()
            } else {
                val idx = if (width > 0) (scrollX + width / 2) / width else 0
                if (idx != pageIndex) {
                    pageIndex = idx
                    notifyProgress()
                    pageChangeListener?.invoke(idx)
                }
            }
        }
    }

    /** Floating pill showing brightness/volume level while adjusting via vertical drag. */
    private inner class AdjustIndicator(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC14161B.toInt(); style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = dpf(1f)
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x3DFFFFFF; style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ds.colors.accent; style = Paint.Style.FILL
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
            typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
        }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = dpf(1.7f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        var mode = 1 // 1 brightness, 2 volume
            set(value) { field = value; invalidate() }
        var fraction = 0.5f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val rect = RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f)
            canvas.drawRoundRect(rect, dpf(16f), dpf(16f), bgPaint)
            canvas.drawRoundRect(rect, dpf(16f), dpf(16f), borderPaint)

            val cx = w / 2f
            val iconCy = dpf(24f)
            if (mode == 1) {
                // 极简太阳图标（圆心 + 8 条短射线）
                val r = dpf(4.2f)
                canvas.drawCircle(cx, iconCy, r, glyphPaint)
                val innerR = dpf(6.5f)
                val outerR = dpf(8.5f)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4.0
                    canvas.drawLine(
                        cx + (innerR * cos(a)).toFloat(), iconCy + (innerR * sin(a)).toFloat(),
                        cx + (outerR * cos(a)).toFloat(), iconCy + (outerR * sin(a)).toFloat(),
                        glyphPaint
                    )
                }
            } else {
                // 极简喇叭 + 声波
                val spk = Path().apply {
                    moveTo(cx - dpf(5.5f), iconCy - dpf(2.2f))
                    lineTo(cx - dpf(2.8f), iconCy - dpf(2.2f))
                    lineTo(cx + dpf(1.2f), iconCy - dpf(5.5f))
                    lineTo(cx + dpf(1.2f), iconCy + dpf(5.5f))
                    lineTo(cx - dpf(2.8f), iconCy + dpf(2.2f))
                    lineTo(cx - dpf(5.5f), iconCy + dpf(2.2f))
                    close()
                }
                canvas.drawPath(spk, glyphPaint)
                if (fraction > 0.02f) {
                    canvas.drawArc(
                        RectF(cx - dpf(1f), iconCy - dpf(4f), cx + dpf(5.5f), iconCy + dpf(4f)),
                        -45f, 90f, false, glyphPaint
                    )
                }
                if (fraction > 0.45f) {
                    canvas.drawArc(
                        RectF(cx - dpf(1f), iconCy - dpf(6.8f), cx + dpf(8.2f), iconCy + dpf(6.8f)),
                        -45f, 90f, false, glyphPaint
                    )
                }
            }

            val barLeft = cx - dpf(2.5f)
            val barTop = dpf(42f)
            val barBot = h - dpf(30f)
            canvas.drawRoundRect(RectF(barLeft, barTop, barLeft + dpf(5f), barBot), dpf(2.5f), dpf(2.5f), trackPaint)
            val fillH = (barBot - barTop) * fraction
            if (fillH > 0f) {
                canvas.drawRoundRect(RectF(barLeft, barBot - fillH, barLeft + dpf(5f), barBot),
                    dpf(2.5f), dpf(2.5f), fillPaint)
            }
            iconPaint.textSize = dpf(11.5f)
            canvas.drawText("${(fraction * 100).roundToInt()}%", cx, h - dpf(12f), iconPaint)
        }
    }

    private inner /**
         * Circular avatar. Never draws a square background — that was the
         * "square shadow border" around comment avatars (COLOR_COVER square bg
         * peeking out from under a circular bitmap).
         */
        class RoundImageView(context: Context) : ImageView(context) {
        private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_COVER
        }
        private val shaderMatrix = Matrix()
        private var cachedShaderBmp: Bitmap? = null
        private var cachedShaderW = 0
        private var cachedShaderH = 0

        init {
            // Background must stay transparent; placeholder is drawn as a circle.
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ScaleType.CENTER_CROP
        }

        override fun setBackgroundColor(color: Int) {
            // Ignore square fills from call sites; keep circular placeholder only.
            if (color == Color.TRANSPARENT || color == 0) {
                super.setBackgroundColor(Color.TRANSPARENT)
            } else {
                placeholderPaint.color = color
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val radius = minOf(width, height) / 2f
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, radius, placeholderPaint)

            val d = drawable ?: return
            val bmp = when (d) {
                is BitmapDrawable -> d.bitmap
                else -> {
                    val saved = canvas.save()
                    canvas.clipPath(Path().apply {
                        addCircle(cx, cy, radius, Path.Direction.CW)
                    })
                    d.setBounds(0, 0, width, height)
                    d.draw(canvas)
                    canvas.restoreToCount(saved)
                    return
                }
            } ?: return
            if (bmp.isRecycled) return
            if (cachedShaderBmp !== bmp || cachedShaderW != width || cachedShaderH != height) {
                cachedShaderBmp = bmp
                cachedShaderW = width
                cachedShaderH = height
                val scale = (width.toFloat() / bmp.width.coerceAtLeast(1))
                    .coerceAtLeast(height.toFloat() / bmp.height.coerceAtLeast(1))
                val dx = (width - bmp.width * scale) * 0.5f
                val dy = (height - bmp.height * scale) * 0.5f
                shaderMatrix.setScale(scale, scale)
                shaderMatrix.postTranslate(dx, dy)
                val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shader.setLocalMatrix(shaderMatrix)
                shaderPaint.shader = shader
            }
            canvas.drawCircle(cx, cy, radius, shaderPaint)
        }
    }

    // ─────────────────────────────────────────────────
    //  Gesture layer. It sits below controls and never intercepts their clicks.
    // ─────────────────────────────────────────────────

    private inner class PlayerGestureView(context: Context) : View(context) {
        init { isClickable = true }
        private var touchGeneration = -1

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchGeneration = playbackGeneration
            else if (ev.pointerCount > 1 || touchGeneration != playbackGeneration) {
                if (touchGeneration != -1) {
                    cancelTapDetector(gestureDetector, ev)
                    cancelLongPressBoost()
                    if (gestureSeeking) { gestureSeeking = false; isSeeking = false; hideSeekHint(); updatePlaybackProgress() }
                    if (adjustMode != 0) { adjustMode = 0; hideAdjust() }
                }
                touchGeneration = -1
                return true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = ev.x
                    gestureStartY = ev.y
                    gestureSeeking = false
                    adjustMode = 0
                    longPressBoost = false
                    longPressDown = true
                    longPressCandidate = true
                    handler.removeCallbacks(longPressBoostRunnable)
                    handler.postDelayed(longPressBoostRunnable, 450)
                    gestureDetector.onTouchEvent(ev)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longPressBoost) return true
                    val dx = ev.x - gestureStartX
                    val dy = ev.y - gestureStartY
                    if (longPressCandidate &&
                        (abs(dx) > dpf(16f) || abs(dy) > dpf(16f))) {
                        longPressCandidate = false
                        handler.removeCallbacks(longPressBoostRunnable)
                    }
                    if (gestureSeeking) {
                        val duration = gestureSeekDuration
                        val newPos = PlayerSeekPolicy.swipePosition(gestureSeekStartPos, duration, dx, width, dpf(20f))
                        gestureSeekTargetPos = newPos
                        onManualSeek(newPos, duration)
                        showSeekHint(newPos, duration)
                    } else if (adjustMode != 0) {
                        applyAdjust(dy)
                    } else {
                        // Decide gesture type: horizontal drag → seek; vertical drag on the
                        // left/right third → brightness/volume; otherwise pass to detector.
                        if (!gestureSeeking && abs(dx) > dpf(20f) && abs(dx) > abs(dy) * 1.5f) {
                            val p = exoPlayer
                            val dur = if (p != null) playerGlobalDuration(p) else 0L
                            if (p != null && dur > 0L) {
                                cancelTapDetector(gestureDetector, ev)
                                gestureSeeking = true
                                isSeeking = true
                                gestureSeekDuration = dur
                                gestureSeekStartPos = playerGlobalPosition(p)
                                gestureSeekTargetPos = PlayerSeekPolicy.swipePosition(gestureSeekStartPos, dur, dx, width, dpf(20f))
                                showSeekHint(gestureSeekTargetPos, dur)
                                gestureSeekWasPlaying = p.playWhenReady && userWantsPlayback
                                controlsHideRunnable?.let(handler::removeCallbacks)
                            }
                        } else if (abs(dy) > dpf(20f) && abs(dy) > abs(dx) * 1.2f && width > 0) {
                            val zone = when {
                                gestureStartX < width / 3f -> 1 // brightness
                                gestureStartX > width * 2f / 3f -> 2 // volume
                                else -> 0
                            }
                            if (zone != 0) {
                                cancelTapDetector(gestureDetector, ev)
                                adjustMode = zone
                                adjustStartValue = if (zone == 1) {
                                    val b = window.attributes.screenBrightness
                                    if (b in 0.01f..1f) b else 0.5f
                                } else {
                                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                }
                                showAdjust(zone, adjustStartValue)
                            }
                        } else {
                            gestureDetector.onTouchEvent(ev)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val claimed = longPressBoost || gestureSeeking || adjustMode != 0
                    cancelLongPressBoost()
                    if (adjustMode != 0) {
                        adjustMode = 0
                        hideAdjust()
                    }
                    if (gestureSeeking) {
                        val p = exoPlayer
                        if (ev.actionMasked == MotionEvent.ACTION_UP && p != null && gestureSeekDuration > 0L) {
                            seekPlayerGlobal(p, gestureSeekTargetPos)
                            danmakuController?.onSeeked(gestureSeekTargetPos)
                        }
                        if (gestureSeekWasPlaying && activityResumed && userWantsPlayback) {
                            p?.play()
                        }
                        isSeeking = false
                        hideSeekHint()
                        updatePlaybackProgress()
                        if (p?.isPlaying == true || gestureSeekWasPlaying) scheduleAutoHide()
                    }
                    if (claimed) cancelTapDetector(gestureDetector, ev) else {
                        gestureDetector.onTouchEvent(ev)
                    }
                    gestureSeeking = false
                    return true
                }
            }
            return true
        }
    }

    /** Vertical drag amount → brightness (zone 1) or volume (zone 2). */
    private fun applyAdjust(dy: Float) {
        val travel = (playerHost.height.coerceAtLeast(1)) * 0.7f
        val delta = -dy / travel
        if (adjustMode == 1) {
            val target = (adjustStartValue + delta).coerceIn(0.05f, 1f)
            val lp = window.attributes
            lp.screenBrightness = target
            window.attributes = lp
            updateAdjust(target)
        } else if (adjustMode == 2) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) {
                val target = (adjustStartValue + delta * max).roundToInt().coerceIn(0, max)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                updateAdjust(target / max.toFloat())
            }
        }
    }

    private fun showAdjust(mode: Int, startValue: Float) {
        adjustIndicator.mode = mode
        adjustIndicator.fraction = if (mode == 1) startValue else {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) startValue / max else 0.5f
        }
        adjustIndicator.animate().cancel()
        adjustIndicator.visibility = View.VISIBLE
        adjustIndicator.alpha = 0f
        val targetScale = if (isFullscreen) 1f else COMPACT_ADJUST_SCALE
        adjustIndicator.scaleX = targetScale * 0.85f
        adjustIndicator.scaleY = targetScale * 0.85f
        adjustIndicator.animate().alpha(1f).scaleX(targetScale).scaleY(targetScale)
            .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun updateAdjust(fraction: Float) {
        adjustIndicator.fraction = fraction
    }

    private fun hideAdjust() {
        adjustIndicator.animate().alpha(0f).setDuration(180)
            .withEndAction { adjustIndicator.visibility = View.GONE }.start()
    }

    // ─────────────────────────────────────────────────
    //  Custom Drawable Icons
    // ─────────────────────────────────────────────────

    private inner class PlayPauseIcon(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = dpf(2f); strokeCap = Paint.Cap.ROUND
        }
        var isPlaying = false
            private set
        private var ended = false
        private val path = Path()

        fun setPlaying(playing: Boolean) {
            if (isPlaying != playing) { isPlaying = playing; invalidate() }
        }

        fun setEnded(end: Boolean) {
            if (ended != end) { ended = end; invalidate() }
        }

        private fun dpf(v: Float): Float = v * resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            if (ended) {
                // Replay icon: circular arrow
                val iconR = dpf(8f)
                canvas.drawArc(RectF(cx - iconR, cy - iconR, cx + iconR, cy + iconR),
                    30f, 285f, false, arcPaint)
                val endDeg = java.lang.Math.toRadians(315.0)
                val tipX = cx + iconR * kotlin.math.cos(endDeg).toFloat()
                val tipY = cy + iconR * kotlin.math.sin(endDeg).toFloat()
                val tanX = kotlin.math.sin(endDeg).toFloat()
                val tanY = -kotlin.math.cos(endDeg).toFloat()
                val wing = dpf(4f)
                path.reset()
                val r1 = java.lang.Math.toRadians(150.0)
                path.moveTo(tipX, tipY)
                path.lineTo(tipX + (tanX * kotlin.math.cos(r1).toFloat() - tanY * kotlin.math.sin(r1).toFloat()) * wing,
                    tipY + (tanX * kotlin.math.sin(r1).toFloat() + tanY * kotlin.math.cos(r1).toFloat()) * wing)
                val r2 = java.lang.Math.toRadians(-150.0)
                path.moveTo(tipX, tipY)
                path.lineTo(tipX + (tanX * kotlin.math.cos(r2).toFloat() - tanY * kotlin.math.sin(r2).toFloat()) * wing,
                    tipY + (tanX * kotlin.math.sin(r2).toFloat() + tanY * kotlin.math.cos(r2).toFloat()) * wing)
                canvas.drawPath(path, paint)
                return
            }
            path.reset()
            if (isPlaying) {
                val barW = dpf(3.5f); val gap = dpf(2.5f); val h = dpf(10f)
                val left = cx - gap / 2f - barW
                path.addRect(left, cy - h / 2f, left + barW, cy + h / 2f, Path.Direction.CW)
                path.addRect(cx + gap / 2f, cy - h / 2f, cx + gap / 2f + barW, cy + h / 2f, Path.Direction.CW)
            } else {
                // Slightly smaller than the pause bars so both states feel balanced.
                val size = dpf(7f)
                path.moveTo(cx - size * 0.5f, cy - size)
                path.lineTo(cx + size * 0.7f, cy)
                path.lineTo(cx - size * 0.5f, cy + size)
                path.close()
            }
            canvas.drawPath(path, paint)
        }
    }

    private inner class ReplayIcon(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x59000000.toInt(); style = Paint.Style.FILL
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = dpf(2.5f); strokeCap = Paint.Cap.ROUND
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            canvas.drawCircle(cx, cy, dp(28).toFloat(), bgPaint)
            val iconR = dpf(13f)
            canvas.drawArc(RectF(cx - iconR, cy - iconR, cx + iconR, cy + iconR),
                30f, 285f, false, arcPaint)
            val endDeg = java.lang.Math.toRadians(315.0)
            val tipX = cx + iconR * kotlin.math.cos(endDeg).toFloat()
            val tipY = cy + iconR * kotlin.math.sin(endDeg).toFloat()
            val tanX = kotlin.math.sin(endDeg).toFloat()
            val tanY = -kotlin.math.cos(endDeg).toFloat()
            val wing = dpf(6f)
            val r1 = java.lang.Math.toRadians(150.0)
            val r2 = java.lang.Math.toRadians(-150.0)
            path.reset()
            path.moveTo(tipX, tipY)
            path.lineTo(tipX + (tanX * kotlin.math.cos(r1).toFloat() - tanY * kotlin.math.sin(r1).toFloat()) * wing,
                tipY + (tanX * kotlin.math.sin(r1).toFloat() + tanY * kotlin.math.cos(r1).toFloat()) * wing)
            path.moveTo(tipX, tipY)
            path.lineTo(tipX + (tanX * kotlin.math.cos(r2).toFloat() - tanY * kotlin.math.sin(r2).toFloat()) * wing,
                tipY + (tanX * kotlin.math.sin(r2).toFloat() + tanY * kotlin.math.cos(r2).toFloat()) * wing)
            canvas.drawPath(path, iconPaint)
        }
    }

    private inner class FullscreenIcon(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = dpf(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private var isFullscreen = false
        fun setFullscreen(fs: Boolean) { isFullscreen = fs; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val s = dpf(8f); val gap = dpf(2f)
            if (isFullscreen) {
                // Exit fullscreen: inward arrows (top-right, bottom-left)
                canvas.drawLine(cx + s - gap, cy - s + gap, cx + s - gap, cy - gap, paint)
                canvas.drawLine(cx + s - gap, cy - gap, cx + gap, cy - gap, paint)
                canvas.drawLine(cx - s + gap, cy + s - gap, cx - s + gap, cy + gap, paint)
                canvas.drawLine(cx - s + gap, cy + gap, cx - gap, cy + gap, paint)
            } else {
                // Enter fullscreen: outward arrows
                canvas.drawLine(cx - s, cy - s + gap, cx - gap, cy - s + gap, paint)
                canvas.drawLine(cx - s, cy - s + gap, cx - s, cy - gap, paint)
                canvas.drawLine(cx + s, cy + s - gap, cx + gap, cy + s - gap, paint)
                canvas.drawLine(cx + s, cy + s - gap, cx + s, cy + gap, paint)
            }
        }
    }

    /** 电池图标：外壳 + 正极 + 内部电量填充，低电变红、充电变绿并叠加闪电。 */
    private inner class BatteryStatusIcon(context: Context) : View(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(1.2f)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val shellRect = RectF()
        private val capRect = RectF()
        private val fillRect = RectF()
        private val boltPath = Path()
        private var percent = -1
        private var charging = false

        private fun dpf(v: Float): Float = v * resources.displayMetrics.density

        fun update(level: Int, isCharging: Boolean) {
            if (percent == level && charging == isCharging) return
            percent = level
            charging = isCharging
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cy = height / 2f
            val bodyW = dpf(15.5f)
            val bodyH = dpf(9.5f)
            val ox = (width - dpf(18f)) / 2f
            val top = cy - bodyH / 2f
            val bot = cy + bodyH / 2f
            val radius = dpf(2f)

            val lowBattery = !charging && percent in 0..20
            val shellColor = when {
                charging -> 0xE6FFFFFF.toInt()
                lowBattery -> 0xFFEB6F79.toInt()
                percent < 0 -> 0x99FFFFFF.toInt()
                else -> 0xD9FFFFFF.toInt()
            }
            strokePaint.color = shellColor
            shellRect.set(
                ox + dpf(0.6f), top + dpf(0.6f),
                ox + bodyW - dpf(0.6f), bot - dpf(0.6f)
            )
            canvas.drawRoundRect(shellRect, radius, radius, strokePaint)

            fillPaint.color = shellColor
            capRect.set(
                ox + bodyW + dpf(0.5f), cy - dpf(2.2f),
                ox + bodyW + dpf(2.1f), cy + dpf(2.2f)
            )
            canvas.drawRoundRect(capRect, dpf(0.8f), dpf(0.8f), fillPaint)

            if (percent >= 0) {
                fillPaint.color = when {
                    charging -> 0xFF70C493.toInt()
                    lowBattery -> 0xFFEB6F79.toInt()
                    else -> 0xE6FFFFFF.toInt()
                }
                val inL = ox + dpf(2f)
                val inT = top + dpf(2f)
                val inB = bot - dpf(2f)
                val maxW = bodyW - dpf(4f)
                val inR = inL + (maxW * (percent / 100f)).coerceIn(0f, 1f)
                if (inR - inL >= dpf(1.2f)) {
                    fillRect.set(inL, inT, inR, inB)
                    canvas.drawRoundRect(fillRect, dpf(0.9f), dpf(0.9f), fillPaint)
                }
            }

            if (charging) {
                val bx = ox + bodyW / 2f
                fillPaint.color = 0xFFFFFFFF.toInt()
                boltPath.rewind()
                boltPath.moveTo(bx + dpf(0.6f), cy - dpf(3.8f))
                boltPath.lineTo(bx - dpf(2.2f), cy + dpf(0.3f))
                boltPath.lineTo(bx - dpf(0.1f), cy + dpf(0.3f))
                boltPath.lineTo(bx - dpf(0.6f), cy + dpf(3.8f))
                boltPath.lineTo(bx + dpf(2.2f), cy - dpf(0.3f))
                boltPath.lineTo(bx + dpf(0.1f), cy - dpf(0.3f))
                boltPath.close()
                canvas.drawPath(boltPath, fillPaint)
            }
        }
    }

    /** 「下一集」图标：右指实心三角 + 右侧竖线，与 PlayPauseIcon 的实心风格一致。 */
    /** 「后台播放」图标：耳机轮廓 + 居中播放三角，语义为转为音频继续播放。 */
    private inner class BackgroundPlayIcon(context: Context) : View(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val arcRect = RectF()
        private val earRect = RectF()
        private val triPath = Path()
        private var compact = true

        private fun dpf(v: Float): Float = v * resources.displayMetrics.density

        fun setCompact(value: Boolean) {
            if (compact == value) return
            compact = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val s = dpf(if (compact) 18f else 20f)
            val u = s / 20f
            strokePaint.strokeWidth = dpf(1.85f)

            // 头梁
            arcRect.set(cx - 6.8f * u, cy - 7.2f * u, cx + 6.8f * u, cy + 6.4f * u)
            canvas.drawArc(arcRect, 180f, 180f, false, strokePaint)
            canvas.drawLine(cx - 6.8f * u, cy - 0.4f * u, cx - 6.8f * u, cy + 2.0f * u, strokePaint)
            canvas.drawLine(cx + 6.8f * u, cy - 0.4f * u, cx + 6.8f * u, cy + 2.0f * u, strokePaint)

            // 左右耳罩
            earRect.set(cx - 7.2f * u, cy + 0.8f * u, cx - 4.4f * u, cy + 6.4f * u)
            canvas.drawRoundRect(earRect, 1.5f * u, 1.5f * u, strokePaint)
            earRect.set(cx + 4.4f * u, cy + 0.8f * u, cx + 7.2f * u, cy + 6.4f * u)
            canvas.drawRoundRect(earRect, 1.5f * u, 1.5f * u, strokePaint)

            // 居中播放三角
            triPath.rewind()
            triPath.moveTo(cx - 1.6f * u, cy - 0.8f * u)
            triPath.lineTo(cx + 2.5f * u, cy + 1.8f * u)
            triPath.lineTo(cx - 1.6f * u, cy + 4.4f * u)
            triPath.close()
            canvas.drawPath(triPath, fillPaint)
        }
    }

    private inner class NextEpisodeIcon(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val path = Path()
        private val barRect = RectF()

        private fun dpf(v: Float): Float = v * resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            path.reset()
            path.moveTo(cx - dpf(6.5f), cy - dpf(6f))
            path.lineTo(cx + dpf(2.2f), cy)
            path.lineTo(cx - dpf(6.5f), cy + dpf(6f))
            path.close()
            canvas.drawPath(path, paint)
            barRect.set(
                cx + dpf(4f), cy - dpf(5.8f),
                cx + dpf(6.5f), cy + dpf(5.8f)
            )
            canvas.drawRoundRect(barRect, dpf(0.9f), dpf(0.9f), paint)
        }
    }

    private fun openDanmakuSettingsDrawer() {
        if (!isFullscreen || isLocalMode) return
        danmakuSettingsDrawer?.open()
    }

    private fun bindDanmakuForCurrentMedia() {
        if (isLocalMode) return
        val cid = currentCid
        if (cid <= 0L) return
        val referer = if (currentBvid.isNotBlank()) {
            "https://www.bilibili.com/video/$currentBvid/"
        } else {
            "https://www.bilibili.com/"
        }
        val durationMs = exoPlayer?.let { playerGlobalDuration(it) }?.takeIf { it > 0L }
            ?: playingStream?.durationMs?.takeIf { it > 0L }
            ?: currentDetail?.duration?.toLong()?.times(1000L)
            ?: 0L
        danmakuController?.bindMedia(
            cid = cid,
            aid = currentAid,
            referer = referer,
            durationMs = durationMs,
            startPositionMs = pendingResumePositionMs
        )
    }

    override fun onBackPressed() {
        if (danmakuSettingsDrawer?.opened == true) {
            danmakuSettingsDrawer?.close()
            return
        }
        if (seasonDrawerOpen) {
            closeSeasonDrawer()
            return
        }
        if (isFullscreen) toggleFullscreen() else super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        if (!playbackStopped || isFinishing || !::playerView.isInitialized) return
        playbackStopped = false
        val restoreSource = resumePlayerOnStart
        val restoreDetails = resumeDetailOnStart
        resumePlayerOnStart = false
        resumeDetailOnStart = false
        val sessionChanged = !isLocalMode && streamSession.isNotBlank() && streamSession != BiliSessionStore.cookie()
        // 如果切后台期间保留了原 ExoPlayer 实例且账号未变更、无播放错误、流未临期，直接重绑视图即可；
        // 否则重新拉取播放地址并准备播放器。
        val retained = exoPlayer
        val retainedUsable = retained != null &&
            playerListener != null &&
            retained.playerError == null &&
            !sessionChanged &&
            (isLocalMode || playingStream?.let { !BiliPlayUrl.isStreamNearExpiry(it) } != false)
        if (retainedUsable) {
            playerView.player = retained
            playerSpinner.visibility = View.GONE
            if (retained.playbackState == Player.STATE_ENDED) {
                endedState = true
                playPauseBtn.setEnded(true)
                showReplay(true)
            }
            bindDanmakuForCurrentMedia()
            updatePlaybackProgress()
        } else {
            if (retained != null) {
                releasePlayer()
            }
            if (restoreSource) {
                if (isLocalMode) {
                    loadLocalPlayer()
                } else if (currentCid > 0L && currentBvid.isNotBlank()) {
                    val cached = playingStream?.takeIf {
                        !sessionChanged &&
                            SystemClock.elapsedRealtime() - streamLoadedAt < STREAM_RESUME_TTL_MS &&
                            !BiliPlayUrl.isStreamNearExpiry(it)
                    }
                    if (cached != null) {
                        startExoPlayer(cached, beginPlaybackChange())
                    } else {
                        playRetryCount = 0
                        loadPlayer(PreferredVideoQuality.resumeRequestQuality(requestedQuality, lastRequestedQuality,
                            BiliSessionStore.isLoggedIn(), sessionChanged), bypassCache = sessionChanged)
                    }
                }
            }
        }
        if (!isLocalMode && restoreDetails) loadVideoAndPlayer()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        backgroundSwitchLocked = false
        updateBackgroundPlayVisibility()
        registerStatusReceiver()
        updateStatusClock()
        refreshBatteryState()
        if (userWantsPlayback && !endedState && playerListener != null) exoPlayer?.play()
        updatePlaybackProgress()
        if (::commentScroll.isInitialized) commentScroll.setPageActive(currentTab == 1 && !isFullscreen)
        if (currentTab == 1 && !isFullscreen) ensureCommentsStarted()
    }

    override fun onPause() {
        reportPlayHistory(status = 2)
        activityResumed = false
        if (::commentScroll.isInitialized) commentScroll.setPageActive(false)
        handler.removeCallbacks(stallWatchdog)
        stallMonitor.reset()
        clearPlayerFeedback()
        if (::playerHost.isInitialized) playerHost.keepScreenOn = false
        handler.removeCallbacks(historyTick)
        handler.removeCallbacks(progressUpdater)
        controlsHideRunnable?.let(handler::removeCallbacks)
        cancelLongPressBoost()
        unregisterStatusReceiver()
        closeSeasonDrawer(immediate = true)
        danmakuSettingsDrawer?.close(immediate = true)
        exoPlayer?.pause()
        danmakuController?.freezeClock()
        super.onPause()
    }

    override fun onStop() {
        danmakuController?.onStop()
        if (::playerView.isInitialized) {
            val retained = exoPlayer
            val hasPreparedReadyPlayer = retained != null &&
                playerListener != null &&
                retained.playerError == null &&
                retained.playbackState != Player.STATE_IDLE &&
                playerError.visibility != View.VISIBLE
            resumePlayerOnStart = playerError.visibility != View.VISIBLE &&
                (playerListener != null || playbackRequest != null)
            resumeDetailOnStart = detailRequest != null
            rememberPlaybackPosition()
            if (playbackRequest != null || retryScheduled) playingStream = null
            playbackStopped = true
            // 若播放器已真正就绪且无错误，保留 ExoPlayer 实例与已缓冲数据，仅解绑视频渲染表面；
            // 仍在 IDLE/未就绪、已有错误、或正在切换配置销毁时释放。
            val keepPreparedPlayer = !isChangingConfigurations && !isFinishing && hasPreparedReadyPlayer
            if (keepPreparedPlayer) {
                playbackRequest?.cancel()
                playbackRequest = null
                detailRequest?.cancel()
                detailRequest = null
                playerView.player = null
            } else {
                cancelPlaybackRequests()
                releasePlayer()
            }
        }
        super.onStop()
    }

    override fun onRetainNonConfigurationInstance(): Any? =
        if (::commentScroll.isInitialized) commentScroll.retainState() else null

    override fun onSaveInstanceState(outState: Bundle) {
        if (::commentScroll.isInitialized) outState.putBundle("comments", commentScroll.saveState())
        rememberPlaybackPosition()
        outState.putString(EXTRA_BVID, currentBvid)
        outState.putLong(EXTRA_AID, currentAid)
        outState.putLong(EXTRA_CID, currentCid)
        outState.putLong(STATE_PLAY_POSITION, pendingResumePositionMs)
        outState.putBoolean(STATE_HISTORY_PENDING, pendingResumePositionMs > 0L &&
            initialHistoryMedia?.samePart(HistoryMediaId(currentBvid, currentAid, currentCid)) == true)
        outState.putLong(EXTRA_LOCAL_TASK_ID, localTaskId)
        outState.putInt(STATE_SEGMENT_INDEX, resumeSegmentIndex)
        outState.putInt(STATE_SPEED_INDEX, speedIndex)
        outState.putBoolean(STATE_PLAY_WHEN_READY, userWantsPlayback)
        outState.putBoolean(STATE_PLAY_ENDED, restoreEndedState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::commentScroll.isInitialized) commentScroll.dispose()
        DanmakuSettingsStore.removeListener(danmakuUiSettingsListener)
        danmakuController?.onDestroy()
        danmakuController = null
        unregisterStatusReceiver()
        cancelPlaybackRequests()
        handler.removeCallbacksAndMessages(null)
        titleHeightAnimator?.cancel()
        descHeightAnimator?.cancel()
        if (::playerView.isInitialized) releasePlayer()
        super.onDestroy()
    }

    private fun cancelPlaybackRequests() {
        playbackGeneration++
        detailGeneration++
        playbackRequest?.cancel()
        detailRequest?.cancel()
        playbackRequest = null
        detailRequest = null
    }

    private fun releasePlayer() {
        playerHost.keepScreenOn = false
        val player = exoPlayer ?: return
        playerListener?.let(player::removeListener)
        playerListener = null
        exoPlayer = null
        playerView.player = null
        player.release()
    }

    private val historyTick = object : Runnable {
        override fun run() {
            if (activityResumed && exoPlayer?.isPlaying == true) {
                reportPlayHistory(status = 3)
                handler.postDelayed(this, 30_000L)
            }
        }
    }

    private fun reportPlayHistory(status: Int) {
        if (isLocalMode || playerListener == null || currentCid <= 0L || streamSession != BiliSessionStore.cookie()) return
        val player = exoPlayer ?: return
        val ended = player.playbackState == Player.STATE_ENDED
        val positionMs = playerGlobalPosition(player)
        if (!ended && (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE ||
                player.playbackState != Player.STATE_READY && positionMs < 1000L)) return
        // 分段长度不明时拿到的是当前段内位置，不能作为整集的观看秒数。
        if (player.mediaItemCount > 1 && segmentTimeline.durationMs == 0L && !ended) return
        val media = HistoryMediaId(currentBvid, currentAid, currentCid)
        val detail = currentDetail?.takeIf { media.samePart(it.historyMedia) }
        val durationMs = if (player.mediaItemCount > 1 && segmentTimeline.durationMs == 0L) playingStream?.durationMs ?: 0L
            else playerGlobalDuration(player)
        val (progress, duration) = historySample(positionMs, durationMs.takeIf { it > 0L } ?: (detail?.duration ?: 0) * 1000L, ended)
        val sample = media to progress
        val now = SystemClock.elapsedRealtime()
        if (progress != 0L && sample != lastHistorySample && (status == 2 || lastHistorySample?.first != media ||
                now - lastHistoryWriteAtMs >= 10_000L)) {
            val video = detail ?: VideoItem(currentBvid, "", "", "", 0L, duration, "", cid = currentCid, aid = currentAid)
            HistoryStore.record(applicationContext, video.copy(duration = duration), progress)
            lastHistorySample = sample
            lastHistoryWriteAtMs = now
        }
        if (currentAid > 0L) {
            val reported = if (progress == -1L && duration > 0) duration.toLong() else progress
            BiliApi.reportHistory(currentAid, currentCid, reported, duration.toLong(), status)
        }
    }

    companion object {
        const val EXTRA_BVID = "bvid"
        const val EXTRA_AID = "aid"
        const val EXTRA_CID = "cid"
        const val EXTRA_LOCAL_TASK_ID = "local_task_id"
        const val EXTRA_LOCAL_SORT_ORDER = "local_sort_order"
        private const val EXTRA_HISTORY_POSITION_MS = "history_position_ms"
        private const val STATE_HISTORY_PENDING = "history_pending"
        private const val STATE_PLAY_POSITION = "play_position"
        private const val STATE_PLAY_WHEN_READY = "play_when_ready"
        private const val STATE_PLAY_ENDED = "play_ended"
        private const val STATE_SEGMENT_INDEX = "segment_index"
        private const val STATE_SPEED_INDEX = "speed_index"
        private const val STREAM_RESUME_TTL_MS = 5 * 60_000L
        /** 卡在 BUFFERING 多久后强制重试。 */
        private const val STALL_TIMEOUT_MS = 20_000L
        /** 看门狗最多连续重试次数，避免无限重建。 */
        private const val MAX_STALL_RETRIES = 2
        /** Comments longer than this line count collapse cleanly. */
        private const val COMMENT_COLLAPSE_LINES = 4
        /** Brightness/volume indicator scale when the player is NOT fullscreen. */
        private const val COMPACT_ADJUST_SCALE = 0.68f
        private const val KEY_LOCAL_SORT_ORDER = "local_sort_order"
        private const val KEY_LOCAL_PLAY_MODE = "local_play_mode"
        // Action bar icon kinds.
        const val KIND_LIKE = 0
        const val KIND_FAVORITE = 1
        const val KIND_DOWNLOAD = 2
        const val KIND_SHARE = 3
        fun open(context: Context, bvid: String, aid: Long = 0L, cid: Long = 0L) {
            if (bvid.isBlank() && aid <= 0L) return
            context.startActivity(Intent(context, VideoDetailActivity::class.java)
                .putExtra(EXTRA_BVID, bvid)
                .putExtra(EXTRA_AID, aid)
                .putExtra(EXTRA_CID, cid))
        }

        internal fun openHistory(context: Context, row: VideoItem) {
            val item = HistoryStore.forPlayback(context, row)
            if (item.bvid.isBlank() && item.aid <= 0L) return
            context.startActivity(Intent(context, VideoDetailActivity::class.java)
                .putExtra(EXTRA_BVID, item.bvid)
                .putExtra(EXTRA_AID, item.aid)
                .putExtra(EXTRA_CID, item.cid)
                .putExtra(EXTRA_HISTORY_POSITION_MS, historyResumeMs(item.cid, item.watchProgressSec, item.duration)))
        }

        /** 播放本地下载任务（复用同一播放器，本地模式只显示标题/大小/时长）。 */
        fun openLocal(context: Context, taskId: Long) {
            context.startActivity(Intent(context, VideoDetailActivity::class.java)
                .putExtra(EXTRA_LOCAL_TASK_ID, taskId))
        }
    }
}

internal data class HistorySample(val progressSec: Long, val durationSec: Int)

internal fun historySample(positionMs: Long, durationMs: Long, ended: Boolean): HistorySample {
    // 保留不足一秒的尾段：进度向下取整，时长向上取整。
    val duration = if (durationMs > 0L) (1L + (durationMs - 1L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
    val progress = if (ended || durationMs > 0L && positionMs >= durationMs) -1L else positionMs.coerceAtLeast(0L) / 1000L
    return HistorySample(progress, duration)
}

internal data class HistoryPlaybackStart(val positionMs: Long, val validateHistory: Boolean = false)

internal fun historyPlaybackStart(savedMs: Long?, savedHistoryPending: Boolean, favoriteMs: Long?, local: Boolean,
                                 historyMs: Long): HistoryPlaybackStart = when {
    savedMs != null -> HistoryPlaybackStart(savedMs.coerceAtLeast(0L), savedHistoryPending && savedMs > 0L && favoriteMs == null && !local)
    local -> HistoryPlaybackStart(0L)
    favoriteMs != null -> HistoryPlaybackStart(favoriteMs.coerceAtLeast(0L))
    else -> HistoryPlaybackStart(historyMs.coerceAtLeast(0L), historyMs > 0L)
}

internal fun checkedHistoryStart(target: HistoryMediaId, actual: HistoryMediaId, positionMs: Long,
                                 durationMs: Long, canMapPosition: Boolean): Long =
    if (canMapPosition && target.samePart(actual) && positionMs > 0L && positionMs < durationMs) positionMs else 0L

internal fun formatQualityButtonText(id: Int): String = when (id) {
    127 -> "8K"
    126 -> "杜比"
    125 -> "HDR"
    120 -> "4K"
    116 -> "1080p60"
    112 -> "1080p+"
    80 -> "1080p"
    64 -> "720p"
    32 -> "480p"
    16 -> "360p"
    else -> BiliPlayUrl.qualityLabel(id).replace("P", "p")
}

internal fun buildEmoteCandidates(rawUrl: String): List<String> =
    CommentSectionUi.buildEmoteCandidates(rawUrl)

/** 合集排序三态循环与短标签（图标胶囊旁文字）。 */
internal fun DownloadManager.SeasonSortOrder.nextOrder(): DownloadManager.SeasonSortOrder = when (this) {
    DownloadManager.SeasonSortOrder.DEFAULT -> DownloadManager.SeasonSortOrder.OLDEST_FIRST
    DownloadManager.SeasonSortOrder.OLDEST_FIRST -> DownloadManager.SeasonSortOrder.NEWEST_FIRST
    DownloadManager.SeasonSortOrder.NEWEST_FIRST -> DownloadManager.SeasonSortOrder.DEFAULT
}

internal fun DownloadManager.SeasonSortOrder.seasonLabel(): String = when (this) {
    DownloadManager.SeasonSortOrder.DEFAULT -> "默认排序"
    DownloadManager.SeasonSortOrder.OLDEST_FIRST -> "从旧到新"
    DownloadManager.SeasonSortOrder.NEWEST_FIRST -> "从新到旧"
}

/** 本地合集只提供三档播放模式，历史遗留的四档值统一回退到顺序播放。 */
internal fun DownloadManager.LocalPlayMode.seasonLabel(): String = when (this) {
    DownloadManager.LocalPlayMode.SEASON_SEQUENCE -> "顺序播放"
    DownloadManager.LocalPlayMode.SINGLE_LOOP -> "单集循环"
    DownloadManager.LocalPlayMode.SHUFFLE -> "随机播放"
    else -> "顺序播放"
}
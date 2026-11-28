package com.yuilittle.bili

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 视频详情页进入的下载页：
 * - 单视频模式：展示封面/标题/UP主/时长等信息；提供「封面保存相册」「单音频下载」「选画质视频下载」。
 * - 当视频属于合集/多P时，右上角提供一眼可识别的「视频 | 合集」切换胶囊（默认高亮「视频」）。
 * - 合集模式：顶部展示已选集数、全选/取消全选、排序（默认/从旧到新/从新到旧）、画质选择与「仅下载音频」开关；
 *   下方逐项展示封面（内嵌时长）、标题、发布时间与勾选框，底部确认统一入队。
 */
class DownloadPickerActivity : Activity() {

    private lateinit var item: VideoItem
    private var acceptedQns: IntArray = IntArray(0)
    private lateinit var qualities: List<DownloadQuality>
    private var selectedQuality: DownloadQuality? = null

    private var isSeasonMode = false
    private var sortOrder = DownloadManager.SeasonSortOrder.DEFAULT
    private var audioOnlyInSeason = false
    private val selectedEpisodes = HashSet<String>()

    private lateinit var tabVideoBtn: TextView
    private lateinit var tabSeasonBtn: TextView
    private lateinit var singlePage: ScrollView
    private lateinit var seasonPage: LinearLayout

    // 合集模式控件
    private lateinit var seasonCountLabel: TextView
    private lateinit var seasonSelectAllBtn: TextView
    private lateinit var seasonSortLabel: TextView
    private lateinit var seasonSortChip: LinearLayout
    private var seasonSortIcon: SortOrderIconView? = null
    private lateinit var seasonQualityValue: TextView
    private lateinit var seasonQualityRow: LinearLayout
    private lateinit var seasonAudioSwitch: Switch
    private lateinit var seasonListView: ListView
    private lateinit var seasonConfirmBtn: TextView

    private var displayedEpisodes: List<IndexedEpisode> = emptyList()
    /** 收藏夹下载态：队列来自收藏夹播放全部，用于改胶囊文案与分组 id。 */
    private var favoritePlayback: FavoritePlayback? = null

    private class IndexedEpisode(
        val originalIndex: Int,
        val episode: UgcEpisode
    ) {
        val key: String get() = "${episode.bvid}_${episode.aid}_${episode.cid}_$originalIndex"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Ds.applySystemBars(this)
        PlayerSettingsStore.applyHomeOrientation(this)
        window.setBackgroundDrawable(ColorDrawable(Ds.colors.bg))
        DownloadManager.init(this)

        val raw = intent.getStringExtra(EXTRA_ITEM_JSON).orEmpty()
        item = parseVideoItem(raw)
        acceptedQns = intent.getIntArrayExtra(EXTRA_ACCEPTED_QNS) ?: IntArray(0)
        if (item.bvid.isBlank() && item.aid <= 0L) {
            Toast.makeText(this, "视频信息缺失", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // 收藏夹播放态：把收藏夹队列包装成虚拟合集，复用合集下载列表
        favoritePlayback = FavoritePlaybackLauncher.read(intent)
        favoritePlayback?.let { fav ->
            if (item.season == null && fav.entries.isNotEmpty()) {
                item = item.copy(
                    season = UgcSeason(
                        id = fav.folderId,
                        title = fav.folderName,
                        cover = item.cover,
                        episodes = fav.entries
                    )
                )
            }
        }
        qualities = availableDownloadQualities()
        selectedQuality = qualities.firstOrNull { !it.needsVip && it.qn == BiliPlayUrl.QUALITY_1080 }
            ?: qualities.firstOrNull { !it.needsVip }
            ?: qualities.firstOrNull()

        item.season?.episodes?.forEachIndexed { idx, ep ->
            if (!ep.charge) selectedEpisodes.add(episodeKey(ep, idx))
        }

        setContentView(buildUi())
    }

    private fun episodeKey(ep: UgcEpisode, fallbackIndex: Int): String =
        "${ep.bvid}_${ep.aid}_${ep.cid}_$fallbackIndex"

    private fun buildUi(): View {
        val season = item.season
        val hasSeason = season != null && season.episodes.isNotEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ds.colors.bg)
        }

        // ── 顶栏：返回 + 标题 + 右上角「视频 | 合集」胶囊切换 ──
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ds.Space.sm), dp(Ds.Space.sm), dp(Ds.Space.lg), dp(Ds.Space.sm))
        }
        topBar.addView(Ds.backIcon(this, Ds.colors.text) { finish() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        topBar.addView(TextView(this).apply {
            text = "下载"
            textSize = 19f
            typeface = Ds.display(bold = true)
            setTextColor(Ds.colors.text)
            setPadding(dp(4), 0, 0, 0)
        })
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        if (hasSeason) {
            val switcher = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ds.pill(this@DownloadPickerActivity, Ds.colors.surfaceRaised, Ds.colors.line)
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            tabVideoBtn = TextView(this).apply {
                text = "视频"
                textSize = 12.5f
                typeface = Ds.body(bold = true)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isClickable = true
                Ds.bindPressFeedback(this, 0.94f)
                setOnClickListener { switchMode(false) }
            }
            tabSeasonBtn = TextView(this).apply {
                text = if (favoritePlayback != null) "收藏夹" else "合集"
                textSize = 12.5f
                typeface = Ds.body(bold = true)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isClickable = true
                Ds.bindPressFeedback(this, 0.94f)
                setOnClickListener { switchMode(true) }
            }
            switcher.addView(tabVideoBtn)
            switcher.addView(tabSeasonBtn)
            topBar.addView(switcher)
        }
        root.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(View(this).apply { setBackgroundColor(Ds.colors.lineSoft) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)))

        val host = FrameLayout(this)
        singlePage = buildSingleVideoPage()
        host.addView(singlePage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        if (hasSeason) {
            seasonPage = buildSeasonPage(season!!)
            seasonPage.visibility = View.GONE
            host.addView(seasonPage, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            updateSwitcherUi()
        }

        // 平板宽屏铺满整幅容器，单视频与合集各自采用专属双栏布局（告别窄居中单列）
        root.addView(host, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun switchMode(toSeason: Boolean) {
        if (isSeasonMode == toSeason) return
        isSeasonMode = toSeason
        updateSwitcherUi()
        singlePage.visibility = if (toSeason) View.GONE else View.VISIBLE
        if (::seasonPage.isInitialized) {
            seasonPage.visibility = if (toSeason) View.VISIBLE else View.GONE
        }
    }

    private fun updateSwitcherUi() {
        if (!::tabVideoBtn.isInitialized || !::tabSeasonBtn.isInitialized) return
        val videoActive = !isSeasonMode
        tabVideoBtn.setTextColor(if (videoActive) Ds.colors.onAccent else Ds.colors.muted)
        tabVideoBtn.background = if (videoActive) Ds.pill(this, Ds.colors.accent) else null
        tabSeasonBtn.setTextColor(if (!videoActive) Ds.colors.onAccent else Ds.colors.muted)
        tabSeasonBtn.background = if (!videoActive) Ds.pill(this, Ds.colors.accent) else null
    }

    // ── 单视频下载页 ─────────────────────────────────────────

    private fun buildSingleVideoPage(): ScrollView {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        val isWide = LayoutMode.isWideScreen(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (isWide) {
                setPadding(dp(24), dp(22), dp(24), dp(28))
            } else {
                setPadding(dp(16), dp(14), dp(16), dp(28))
            }
        }

        // 1. 封面与音频快捷保存卡
        val mediaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@DownloadPickerActivity, Ds.colors.surface, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        mediaCard.addView(actionItemRow(
            title = "下载封面",
            subtitle = "将当前视频高清封面保存到相册",
            iconKind = KIND_IMAGE
        ) {
            val coverUrl = item.cover
            if (coverUrl.isBlank()) {
                Toast.makeText(this, "当前视频暂无封面", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "正在保存封面…", Toast.LENGTH_SHORT).show()
                ImagePreviewDialog.downloadImageToGallery(this, coverUrl) { msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        })
        mediaCard.addView(divider())
        mediaCard.addView(actionItemRow(
            title = "下载音频",
            subtitle = "提取纯音频并加入下载管理开始下载",
            iconKind = KIND_AUDIO
        ) {
            if (item.charge) {
                Toast.makeText(this, "充电专属视频暂不支持下载", Toast.LENGTH_SHORT).show()
                return@actionItemRow
            }
            DownloadManager.enqueueAudio(this, item)
            Toast.makeText(this, "已加入音频下载队列", Toast.LENGTH_SHORT).show()
        })

        // 2. 视频画质选择与下载卡
        val videoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@DownloadPickerActivity, Ds.colors.surface, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val qualityNameText = TextView(this).apply {
            text = selectedQuality?.let { qLabel(it) } ?: "自动"
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(Ds.colors.accent)
        }
        val qualityPickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(this@DownloadPickerActivity, Ds.colors.surfaceRaised, Ds.colors.lineSoft, Ds.Radius.control)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            isClickable = true
            Ds.bindPressFeedback(this, 0.98f)
        }
        qualityPickerRow.addView(TextView(this).apply {
            text = "视频画质"
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(Ds.colors.text)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        qualityPickerRow.addView(qualityNameText)
        qualityPickerRow.addView(Ds.chevronRightIcon(this, Ds.colors.muted),
            LinearLayout.LayoutParams(dp(14), dp(14)).apply { leftMargin = dp(6) })

        val downloadVideoBtn = TextView(this).apply {
            text = "下载视频 · ${selectedQuality?.label ?: "自动"}"
            textSize = 15f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            setTextColor(Ds.colors.onAccent)
            background = Ds.pill(this@DownloadPickerActivity, Ds.colors.accent)
            Ds.bindPressFeedback(this, 0.97f)
            setOnClickListener {
                val q = selectedQuality ?: return@setOnClickListener
                if (item.charge) {
                    Toast.makeText(this@DownloadPickerActivity, "充电专属视频暂不支持下载", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (q.needsVip && !BiliSessionStore.isBigVip()) {
                    Toast.makeText(this@DownloadPickerActivity, "${q.label} 需要大会员权限", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                DownloadManager.enqueueVideo(this@DownloadPickerActivity, item, q.qn, q.label)
                Toast.makeText(this@DownloadPickerActivity, "已加入视频下载：${q.label}", Toast.LENGTH_SHORT).show()
            }
        }

        qualityPickerRow.setOnClickListener {
            if (qualities.isEmpty()) return@setOnClickListener
            showQualitySheet(qualities, selectedQuality) { q ->
                selectedQuality = q
                qualityNameText.text = qLabel(q)
                downloadVideoBtn.text = "下载视频 · ${q.label}"
                if (::seasonQualityValue.isInitialized) seasonQualityValue.text = qLabel(q)
            }
        }

        videoCard.addView(qualityPickerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        videoCard.addView(downloadVideoBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(14)
        })

        if (isWide) {
            // 平板宽屏双栏：左侧 16:10 大封面信息预览卡，右侧「视频下载 + 快捷保存」完整控制台
            val splitRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            val leftCol = buildTabletVideoHeroCard()
            splitRow.addView(leftCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.44f).apply {
                rightMargin = dp(20)
            })
            val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            rightCol.addView(sectionLabel("视频下载", topMargin = 0))
            rightCol.addView(videoCard)
            rightCol.addView(sectionLabel("快捷保存", topMargin = dp(18)))
            rightCol.addView(mediaCard)
            splitRow.addView(rightCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.56f))
            content.addView(splitRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            content.addView(buildVideoInfoCard())
            content.addView(sectionLabel("快捷保存", topMargin = dp(20)))
            content.addView(mediaCard)
            content.addView(sectionLabel("视频下载", topMargin = dp(20)))
            content.addView(videoCard)
        }

        scroll.addView(content)
        return scroll
    }

    /** 平板宽屏单视频页左侧大封面预览信息卡。 */
    private fun buildTabletVideoHeroCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@DownloadPickerActivity, Ds.colors.surface, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(dp(16), dp(16), dp(16), dp(18))
        }
        val coverHost = FrameLayout(this).apply {
            setBackgroundColor(Ds.colors.cover)
            Ds.clipRounded(this, Ds.Radius.control)
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        coverHost.addView(img, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (item.duration > 0) {
            coverHost.addView(durationBadgeView(formatDuration(item.duration)), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(8); bottomMargin = dp(8)
            })
        }
        val coverUrl = item.cover
        if (coverUrl.isNotBlank()) {
            CoverLoader.load(img, coverUrl + "@640w_400h.webp")
            coverHost.isClickable = true
            coverHost.setOnClickListener { ImagePreviewDialog.show(this, listOf(coverUrl), 0) }
        }
        val previewW = ((resources.displayMetrics.widthPixels - dp(68)) * 0.44f - dp(32)).toInt().coerceAtLeast(dp(220))
        val previewH = (previewW * 9f / 16f).toInt().coerceIn(dp(150), dp(260))
        card.addView(coverHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, previewH))

        card.addView(TextView(this).apply {
            text = item.title
            textSize = 16.5f
            typeface = Ds.display(bold = true)
            setTextColor(Ds.colors.text)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        card.addView(TextView(this).apply {
            text = item.owner.ifBlank { "哔哩哔哩用户" }
            textSize = 13.5f
            typeface = Ds.body(bold = true)
            setTextColor(Ds.colors.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        val meta = buildList {
            if (item.duration > 0) add("时长 ${formatDuration(item.duration)}")
            if (item.publishedAt > 0L) add("发布 ${formatDate(item.publishedAt)}")
        }
        if (meta.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = meta.joinToString(" · ")
                textSize = 12f
                fontFeatureSettings = "tnum"
                setTextColor(Ds.colors.subtle)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
        }
        return card
    }

    private fun buildVideoInfoCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(this@DownloadPickerActivity, Ds.colors.surface, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val coverHost = FrameLayout(this).apply {
            setBackgroundColor(Ds.colors.cover)
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        coverHost.addView(img, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (item.duration > 0) {
            coverHost.addView(durationBadgeView(formatDuration(item.duration)), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(5); bottomMargin = dp(5)
            })
        }
        val coverUrl = item.cover
        if (coverUrl.isNotBlank()) CoverLoader.load(img, coverUrl + "@480w_300h.webp")
        card.addView(coverHost, LinearLayout.LayoutParams(dp(120), dp(74)))
        if (coverUrl.isNotBlank()) {
            coverHost.isClickable = true
            coverHost.setOnClickListener {
                ImagePreviewDialog.show(this, listOf(coverUrl), 0)
            }
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        info.addView(TextView(this).apply {
            text = item.title
            textSize = 15f
            typeface = Ds.display(bold = true)
            setTextColor(Ds.colors.text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        info.addView(TextView(this).apply {
            text = item.owner.ifBlank { "哔哩哔哩用户" }
            textSize = 12.5f
            setTextColor(Ds.colors.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        val meta = buildList {
            if (item.duration > 0) add(formatDuration(item.duration))
            if (item.publishedAt > 0L) add(formatDate(item.publishedAt))
        }
        if (meta.isNotEmpty()) {
            info.addView(TextView(this).apply {
                text = meta.joinToString(" · ")
                textSize = 11.5f
                fontFeatureSettings = "tnum"
                setTextColor(Ds.colors.subtle)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
        }
        card.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(12)
        })
        return card
    }

    private fun actionItemRow(
        title: String,
        subtitle: String,
        iconKind: Int,
        onClick: () -> Unit
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))

        val texts = LinearLayout(this@DownloadPickerActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(TextView(this@DownloadPickerActivity).apply {
            text = title
            textSize = 14.5f
            typeface = Ds.body(bold = true)
            setTextColor(Ds.colors.text)
        })
        texts.addView(TextView(this@DownloadPickerActivity).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Ds.colors.muted)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(12)
        })

        addView(ActionGlyph(this@DownloadPickerActivity, iconKind).apply {
            background = Ds.pill(this@DownloadPickerActivity, Ds.colors.accentSoft)
            isClickable = true
            contentDescription = if (iconKind == KIND_IMAGE) "保存封面到相册" else "下载音频"
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
    }

    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    /**
     * 右侧圆形图标：与播放页 ActionIconView 同批 1.6dp 圆角线条，仅在图形右下角加一个小下箭头表示「下载」。
     * KIND_IMAGE=相框+山形，KIND_AUDIO=音符。
     */
    private inner class ActionGlyph(context: Context, private val kind: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ds.colors.accent
            strokeWidth = dpf(1.5f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            // 图形整体上移，给底部下箭头留位
            val cy = height / 2f - dpf(1.5f)
            val s = dpf(1f)
            when (kind) {
                KIND_IMAGE -> {
                    canvas.drawRoundRect(
                        cx - 6f * s, cy - 4.6f * s, cx + 6f * s, cy + 4.6f * s,
                        1.5f * s, 1.5f * s, paint
                    )
                    val peaks = Path().apply {
                        moveTo(cx - 4.8f * s, cy + 2.2f * s)
                        lineTo(cx - 1.6f * s, cy - 1.4f * s)
                        lineTo(cx + 1.0f * s, cy + 1.4f * s)
                        lineTo(cx + 3.0f * s, cy - 0.4f * s)
                        lineTo(cx + 5.0f * s, cy + 2.6f * s)
                    }
                    canvas.drawPath(peaks, paint)
                }
                else -> {
                    // 音符：符头 + 符干 + 符尾横梁
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx - 3.2f * s, cy + 3.4f * s, 2.2f * s, paint)
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(cx - 1.0f * s, cy + 3.4f * s, cx - 1.0f * s, cy - 4.2f * s, paint)
                    canvas.drawLine(cx - 1.0f * s, cy - 4.2f * s, cx + 4.6f * s, cy - 5.4f * s, paint)
                    canvas.drawLine(cx + 4.6f * s, cy - 5.4f * s, cx + 4.6f * s, cy - 1.0f * s, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx + 4.6f * s, cy + 0.2f * s, 2.2f * s, paint)
                }
            }
            // 右下角小下箭头：箭头尾部短横，与图形本身区分开
            paint.style = Paint.Style.STROKE
            canvas.drawLine(cx + 4.6f * s, cy + 7.4f * s, cx + 4.6f * s, cy + 11.0f * s, paint)
            val arrow = Path().apply {
                moveTo(cx + 3.0f * s, cy + 9.6f * s)
                lineTo(cx + 4.6f * s, cy + 11.4f * s)
                lineTo(cx + 6.2f * s, cy + 9.6f * s)
            }
            canvas.drawPath(arrow, paint)
        }
    }

    // ── 合集下载页 ──────────────────────────────────────────

    private fun buildSeasonPage(season: UgcSeason): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 顶部设置与操作控制区（简洁紧凑卡片）
        val controlCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@DownloadPickerActivity, Ds.colors.surface, Ds.colors.lineSoft, Ds.Radius.card)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        // 第 1 行：合集标题 + 集数
        controlCard.addView(TextView(this).apply {
            text = season.title.ifBlank { item.title }
            textSize = 14.5f
            typeface = Ds.display(bold = true)
            setTextColor(Ds.colors.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        // 第 2 行：已选 xx/xx 集 | 排序胶囊 | 全选/取消全选按钮
        val topActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
        }
        seasonCountLabel = TextView(this).apply {
            textSize = 13f
            typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
            setTextColor(Ds.colors.text)
        }
        topActionRow.addView(seasonCountLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 排序按钮
        val sortBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = sortChipBackground(sortOrder)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
        }
        seasonSortChip = sortBtn
        seasonSortLabel = TextView(this).apply {
            text = sortLabel(sortOrder)
            textSize = 12f
            typeface = Ds.body(bold = true)
            setTextColor(sortTint(sortOrder))
        }
        val sortIcon = Ds.sortOrderIcon(this, sortOrder, sortTint(sortOrder)) as SortOrderIconView
        seasonSortIcon = sortIcon
        sortBtn.addView(sortIcon,
            LinearLayout.LayoutParams(dp(13), dp(13)).apply { rightMargin = dp(4) })
        sortBtn.addView(seasonSortLabel)
        sortBtn.setOnClickListener { showSeasonSortSheet() }
        topActionRow.addView(sortBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8)
        })

        seasonSelectAllBtn = TextView(this).apply {
            textSize = 12f
            typeface = Ds.body(bold = true)
            setTextColor(Ds.colors.accent)
            background = Ds.pill(this@DownloadPickerActivity, Ds.colors.accentSoft)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { toggleSeasonSelectAll() }
        }
        topActionRow.addView(seasonSelectAllBtn)
        controlCard.addView(topActionRow)

        controlCard.addView(divider(0, 0))

        // 第 3 行：画质选择（左侧） + 仅下载音频（右侧开关）
        val optionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        seasonQualityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            Ds.bindPressFeedback(this, 0.96f)
            setOnClickListener {
                if (audioOnlyInSeason || qualities.isEmpty()) return@setOnClickListener
                showQualitySheet(qualities, selectedQuality) { q ->
                    selectedQuality = q
                    seasonQualityValue.text = qLabel(q)
                }
            }
        }
        seasonQualityRow.addView(TextView(this).apply {
            text = "画质："
            textSize = 13f
            setTextColor(Ds.colors.muted)
        })
        seasonQualityValue = TextView(this).apply {
            text = selectedQuality?.let { qLabel(it) } ?: "自动"
            textSize = 13f
            typeface = Ds.body(bold = true)
            setTextColor(Ds.colors.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        seasonQualityRow.addView(seasonQualityValue, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        seasonQualityRow.addView(Ds.chevronRightIcon(this, Ds.colors.muted),
            LinearLayout.LayoutParams(dp(12), dp(12)).apply { leftMargin = dp(4); rightMargin = dp(12) })

        optionsRow.addView(seasonQualityRow, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        optionsRow.addView(TextView(this).apply {
            text = "仅音频"
            textSize = 13f
            typeface = Ds.body(bold = true)
            setTextColor(Ds.colors.text)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(6)
        })
        seasonAudioSwitch = Switch(this).apply {
            isChecked = audioOnlyInSeason
            buttonTintList = switchTint()
            trackTintList = switchTint()
            scaleX = 0.88f
            scaleY = 0.88f
            setOnCheckedChangeListener { _, checked ->
                audioOnlyInSeason = checked
                seasonQualityRow.alpha = if (checked) 0.4f else 1f
                refreshSeasonSummary()
            }
        }
        optionsRow.addView(seasonAudioSwitch)
        controlCard.addView(optionsRow)

        val isWide = LayoutMode.isWideScreen(this)
        val navH = runCatching {
            resources.getIdentifier("navigation_bar_height", "dimen", "android")
                .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: 0
        }.getOrDefault(0)

        seasonConfirmBtn = TextView(this).apply {
            textSize = 15f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            setTextColor(Ds.colors.onAccent)
            background = Ds.pill(this@DownloadPickerActivity, Ds.colors.accent)
            Ds.bindPressFeedback(this, 0.97f)
            setOnClickListener { enqueueSelectedSeasonEpisodes(season) }
        }

        // 合集分集列表（平板 2 列网格、手机单列，均支持 ViewHolder 高效复用）
        seasonListView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            clipToPadding = false
            setPadding(if (isWide) dp(16) else dp(14), dp(12), if (isWide) dp(20) else dp(14), dp(20) + navH)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setSelector(android.R.color.transparent)
        }
        seasonListView.adapter = SeasonEpisodeAdapter(cols = if (isWide) 2 else 1)

        if (isWide) {
            // 平板宽屏双栏：左侧 316dp 固定控制面板（含大封面摘要 + 全选/排序/画质/仅音频 + 确认下载主按钮），右侧 2 列分集网格
            root.orientation = LinearLayout.HORIZONTAL
            val leftPane = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Ds.colors.surface)
                setPadding(dp(18), dp(16), dp(18), dp(18) + navH)
            }
            val heroCoverHost = FrameLayout(this).apply {
                setBackgroundColor(Ds.colors.cover)
                Ds.clipRounded(this, Ds.Radius.control)
            }
            val heroCover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            heroCoverHost.addView(heroCover, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val cUrl = season.cover.ifBlank { item.cover }
            if (cUrl.isNotBlank()) CoverLoader.load(heroCover, cUrl + "@480w_300h.webp")
            leftPane.addView(heroCoverHost, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(148)).apply { bottomMargin = dp(12) })
            leftPane.addView(controlCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            leftPane.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            leftPane.addView(seasonConfirmBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(14) })

            root.addView(leftPane, LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.MATCH_PARENT))
            root.addView(View(this).apply { setBackgroundColor(Ds.colors.lineSoft) },
                LinearLayout.LayoutParams(Ds.hairline(this), LinearLayout.LayoutParams.MATCH_PARENT))
            root.addView(seasonListView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        } else {
            root.addView(controlCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(12)
            })
            root.addView(seasonListView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            val bottomBar = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Ds.colors.surface)
                setPadding(dp(16), dp(10), dp(16), dp(12) + navH)
            }
            bottomBar.addView(View(this).apply { setBackgroundColor(Ds.colors.lineSoft) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)).apply {
                    bottomMargin = dp(10)
                })
            bottomBar.addView(seasonConfirmBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
            root.addView(bottomBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        refreshSeasonEpisodesList()
        return root
    }

    private fun refreshSeasonEpisodesList() {
        val episodes = item.season?.episodes.orEmpty()
        val indexed = episodes.mapIndexed { idx, ep -> IndexedEpisode(idx, ep) }
        val sortedEps = DownloadManager.sortEpisodes(episodes, sortOrder)
        val byIdentity = indexed.groupBy { System.identityHashCode(it.episode) }
        val consumed = HashMap<Int, Int>()
        displayedEpisodes = sortedEps.map { ep ->
            val identity = System.identityHashCode(ep)
            val list = byIdentity[identity].orEmpty()
            val pos = consumed[identity] ?: 0
            consumed[identity] = pos + 1
            list.getOrElse(pos) { IndexedEpisode(0, ep) }
        }
        (seasonListView.adapter as? BaseAdapter)?.notifyDataSetChanged()
        refreshSeasonSummary()
    }

    private fun toggleSeasonSelectAll() {
        val downloadables = displayedEpisodes.filter { !it.episode.charge }
        val allSelected = downloadables.isNotEmpty() && downloadables.all { it.key in selectedEpisodes }
        if (allSelected) {
            downloadables.forEach { selectedEpisodes.remove(it.key) }
        } else {
            downloadables.forEach { selectedEpisodes.add(it.key) }
        }
        (seasonListView.adapter as? BaseAdapter)?.notifyDataSetChanged()
        refreshSeasonSummary()
    }

    private fun refreshSeasonSummary() {
        val total = item.season?.episodes?.size ?: 0
        val downloadables = displayedEpisodes.filter { !it.episode.charge }
        val selectedCount = downloadables.count { it.key in selectedEpisodes }
        seasonCountLabel.text = "已选 $selectedCount / $total 集"
        val allSelected = downloadables.isNotEmpty() && selectedCount == downloadables.size
        seasonSelectAllBtn.text = if (allSelected) "取消全选" else "全选"
        val modeLabel = if (audioOnlyInSeason) "音频" else "视频"
        seasonConfirmBtn.text = if (selectedCount == 0) "请选择要下载的分集" else "下载已选 $selectedCount 集（$modeLabel）"
        seasonConfirmBtn.alpha = if (selectedCount == 0) 0.5f else 1f
    }

    private fun enqueueSelectedSeasonEpisodes(season: UgcSeason) {
        val chosen = displayedEpisodes.filter { !it.episode.charge && it.key in selectedEpisodes }
        if (chosen.isEmpty()) {
            Toast.makeText(this, "请先选择要下载的分集", Toast.LENGTH_SHORT).show()
            return
        }
        val groupId = if (favoritePlayback != null) "fav-${season.id}" else "season-${season.id}"
        val groupTitle = season.title.ifBlank { item.title }
        val groupCover = season.cover.ifBlank { item.cover }
        val q = if (audioOnlyInSeason) null else selectedQuality
        if (q != null && q.needsVip && !BiliSessionStore.isBigVip()) {
            Toast.makeText(this, "${q.label} 需要大会员权限", Toast.LENGTH_SHORT).show()
            return
        }
        for (entry in chosen) {
            val ep = entry.episode
            val episodeNo = entry.originalIndex + 1
            // 收藏夹队列的 cid 可能为 0，先拉详情补全再入队
            if (ep.cid <= 0L && ep.bvid.isNotBlank()) {
                BiliApi.fetchDetail(ep.bvid, ep.aid) { detail, _, error ->
                    if (isFinishing || isDestroyed) return@fetchDetail
                    val cid = detail?.cid ?: 0L
                    if (cid <= 0L) {
                        Toast.makeText(this, "「${ep.title}」获取分集信息失败${error?.let { "：$it" }.orEmpty()}", Toast.LENGTH_SHORT).show()
                        return@fetchDetail
                    }
                    enqueueEpisode(ep.copy(cid = cid), episodeNo, groupId, groupTitle, groupCover, q)
                }
            } else {
                enqueueEpisode(ep, episodeNo, groupId, groupTitle, groupCover, q)
            }
        }
        val modeLabel = if (audioOnlyInSeason) "音频" else "视频"
        Toast.makeText(this, "已加入 ${chosen.size} 个${modeLabel}到下载队列", Toast.LENGTH_SHORT).show()
    }

    /** q 为 null 表示仅音频。 */
    private fun enqueueEpisode(
        ep: UgcEpisode,
        episodeNo: Int,
        groupId: String,
        groupTitle: String,
        groupCover: String,
        q: DownloadQuality?
    ) {
        val epItem = item.copy(
            bvid = ep.bvid, aid = ep.aid, cid = ep.cid,
            title = ep.title, duration = ep.duration,
            publishedAt = ep.publishedAt.takeIf { it > 0L } ?: item.publishedAt,
            charge = ep.charge,
            cover = ep.cover.ifBlank { item.cover }
        )
        if (q == null) {
            DownloadManager.enqueueAudio(this, epItem, groupId, groupTitle, groupCover, episodeNo)
        } else {
            DownloadManager.enqueueVideo(this, epItem, q.qn, q.label, groupId, groupTitle, groupCover, episodeNo)
        }
    }

    private fun showSeasonSortSheet() {
        val options = listOf(
            DownloadManager.SeasonSortOrder.DEFAULT to "默认排序",
            DownloadManager.SeasonSortOrder.OLDEST_FIRST to "发布时间从旧到新",
            DownloadManager.SeasonSortOrder.NEWEST_FIRST to "发布时间从新到旧"
        )
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.attributes?.gravity = Gravity.BOTTOM

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@DownloadPickerActivity, Ds.colors.surface, Ds.colors.lineSoft, Ds.Radius.dialog)
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }
        root.addView(TextView(this).apply {
            text = "合集分集排序"
            textSize = 16f
            typeface = Ds.display(bold = true)
            setTextColor(Ds.colors.text)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        options.forEach { (order, label) ->
            val selected = sortOrder == order
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(50)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = Ds.card(
                    this@DownloadPickerActivity,
                    if (selected) Ds.colors.accentSoft else Color.TRANSPARENT,
                    null,
                    Ds.Radius.control,
                    0
                )
                isClickable = true
                setOnClickListener {
                    sortOrder = order
                    seasonSortLabel.text = sortLabel(order)
                    seasonSortLabel.setTextColor(sortTint(order))
                    seasonSortIcon?.update(order, sortTint(order))
                    seasonSortChip.background = sortChipBackground(order)
                    refreshSeasonEpisodesList()
                    dialog.dismiss()
                }
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
                typeface = Ds.body(bold = selected)
                setTextColor(if (selected) Ds.colors.accent else Ds.colors.text)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) {
                row.addView(Ds.checkIcon(this, Ds.colors.accent), LinearLayout.LayoutParams(dp(16), dp(16)))
            }
            root.addView(row)
        }
        dialog.setContentView(root)
        dialog.show()
        LayoutMode.applyDialogWidth(dialog, this, maxTabletDp = 460, phoneMarginDp = 24)
    }

    private fun sortLabel(order: DownloadManager.SeasonSortOrder): String = order.seasonLabel()

    private fun sortTint(order: DownloadManager.SeasonSortOrder): Int =
        if (order == DownloadManager.SeasonSortOrder.DEFAULT) Ds.colors.muted else Ds.colors.accent

    private fun sortChipBackground(order: DownloadManager.SeasonSortOrder) =
        if (order == DownloadManager.SeasonSortOrder.DEFAULT) {
            Ds.pill(this, Ds.colors.surfaceRaised, Ds.colors.lineSoft)
        } else {
            Ds.pill(this, Ds.colors.accentSoft, Ds.colors.accent)
        }

    private inner class SeasonEpisodeAdapter(private val cols: Int = 1) : BaseAdapter() {
        override fun getCount(): Int = (displayedEpisodes.size + cols - 1) / cols
        override fun getItem(position: Int): IndexedEpisode = displayedEpisodes[position * cols]
        override fun getItemId(position: Int): Long = position.toLong()

        private fun createEpisodeCard(): Pair<LinearLayout, EpisodeHolder> {
            val card = LinearLayout(this@DownloadPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ds.card(
                    this@DownloadPickerActivity,
                    Ds.colors.surface,
                    Ds.colors.lineSoft,
                    Ds.Radius.card
                )
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            val thumbHost = FrameLayout(this@DownloadPickerActivity).apply {
                setBackgroundColor(Ds.colors.cover)
            }
            Ds.clipRounded(thumbHost, Ds.Radius.hairline)
            val thumb = ImageView(this@DownloadPickerActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val durationBadge = durationBadgeView("")
            thumbHost.addView(thumb, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            thumbHost.addView(durationBadge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(4); bottomMargin = dp(4)
            })
            card.addView(thumbHost, LinearLayout.LayoutParams(dp(96), dp(58)))

            val textCol = LinearLayout(this@DownloadPickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleTv = TextView(this@DownloadPickerActivity).apply {
                textSize = 13.5f
                typeface = Ds.body(bold = true)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            val metaTv = TextView(this@DownloadPickerActivity).apply {
                textSize = 11.5f
                fontFeatureSettings = "tnum"
                setTextColor(Ds.colors.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(titleTv)
            textCol.addView(metaTv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
            card.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10); rightMargin = dp(6)
            })

            val checkBox = CheckBox(this@DownloadPickerActivity).apply {
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(-android.R.attr.state_checked),
                        intArrayOf(android.R.attr.state_checked)
                    ),
                    intArrayOf(Ds.colors.muted, Ds.colors.accent)
                )
            }
            card.addView(checkBox, LinearLayout.LayoutParams(dp(34), dp(34)))
            return card to EpisodeHolder(card, thumb, durationBadge, titleTv, metaTv, checkBox)
        }

        private fun bindEpisodeHolder(holder: EpisodeHolder, entry: IndexedEpisode?) {
            if (entry == null) {
                holder.card.visibility = View.INVISIBLE
                holder.card.isClickable = false
                CoverLoader.clear(holder.thumb)
                return
            }
            holder.card.visibility = View.VISIBLE
            holder.card.isClickable = true
            val ep = entry.episode
            val isChecked = !ep.charge && entry.key in selectedEpisodes

            val coverUrl = ep.cover.ifBlank { item.cover }
            if (coverUrl.isNotBlank()) {
                CoverLoader.load(holder.thumb, coverUrl + "@320w_200h.webp")
            } else {
                CoverLoader.clear(holder.thumb)
            }
            if (ep.duration > 0) {
                holder.durationBadge.text = formatDuration(ep.duration)
                holder.durationBadge.visibility = View.VISIBLE
            } else {
                holder.durationBadge.visibility = View.GONE
            }

            holder.titleTv.text = ep.title
            holder.titleTv.setTextColor(if (ep.charge) Ds.colors.muted else Ds.colors.text)
            val pub = if (ep.publishedAt > 0L) formatDate(ep.publishedAt) else "P${entry.originalIndex + 1}"
            holder.metaTv.text = buildString {
                append("P${entry.originalIndex + 1}")
                if (ep.publishedAt > 0L) append(" · ").append(pub)
                if (ep.charge) append(" · 充电专属")
            }

            holder.checkBox.isEnabled = !ep.charge
            holder.checkBox.isChecked = isChecked
            holder.card.background = if (isChecked) {
                Ds.card(this@DownloadPickerActivity, Ds.colors.accentSoft, Ds.colors.accent, Ds.Radius.card)
            } else {
                Ds.card(this@DownloadPickerActivity, Ds.colors.surface, Ds.colors.lineSoft, Ds.Radius.card)
            }
            holder.card.setOnClickListener {
                if (ep.charge) {
                    Toast.makeText(this@DownloadPickerActivity, "充电专属分集暂不支持下载", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (entry.key in selectedEpisodes) selectedEpisodes.remove(entry.key)
                else selectedEpisodes.add(entry.key)
                notifyDataSetChanged()
                refreshSeasonSummary()
            }
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val holders: List<EpisodeHolder>
            if (convertView == null) {
                row = LinearLayout(this@DownloadPickerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(if (cols > 1) 10 else 8))
                }
                val list = ArrayList<EpisodeHolder>(cols)
                for (c in 0 until cols) {
                    val (card, holder) = createEpisodeCard()
                    row.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (c < cols - 1) rightMargin = dp(10)
                    })
                    list.add(holder)
                }
                holders = list
                row.tag = holders
            } else {
                row = convertView as LinearLayout
                @Suppress("UNCHECKED_CAST")
                holders = row.tag as List<EpisodeHolder>
            }
            val base = position * cols
            for (c in 0 until cols) {
                bindEpisodeHolder(holders[c], displayedEpisodes.getOrNull(base + c))
            }
            return row
        }
    }

    private class EpisodeHolder(
        val card: LinearLayout,
        val thumb: ImageView,
        val durationBadge: TextView,
        val titleTv: TextView,
        val metaTv: TextView,
        val checkBox: CheckBox
    )

    // ── 画质选择弹窗 & 辅助 ──────────────────────────────────

    private fun showQualitySheet(
        list: List<DownloadQuality>,
        current: DownloadQuality?,
        onPick: (DownloadQuality) -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.attributes?.gravity = Gravity.BOTTOM

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.card(this@DownloadPickerActivity, Ds.colors.surface, Ds.colors.lineSoft, Ds.Radius.dialog)
            setPadding(dp(18), dp(18), dp(18), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "选择画质"
            textSize = 16f
            typeface = Ds.display(bold = true)
            setTextColor(Ds.colors.text)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        list.forEach { q ->
            val selected = q == current
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(50)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = Ds.card(
                    this@DownloadPickerActivity,
                    if (selected) Ds.colors.accentSoft else Color.TRANSPARENT,
                    null,
                    Ds.Radius.control,
                    0
                )
                isClickable = true
                setOnClickListener {
                    onPick(q)
                    dialog.dismiss()
                }
            }
            row.addView(TextView(this).apply {
                text = qLabel(q)
                textSize = 14f
                typeface = Ds.body(bold = selected)
                setTextColor(if (selected) Ds.colors.accent else Ds.colors.text)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (q.needsVip) {
                row.addView(TextView(this).apply {
                    text = "大会员"
                    textSize = 10.5f
                    typeface = Ds.body(bold = true)
                    setTextColor(Ds.colors.accent)
                    background = Ds.pill(this@DownloadPickerActivity, Ds.colors.accentSoft)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                })
            }
            if (selected) {
                row.addView(Ds.checkIcon(this, Ds.colors.accent), LinearLayout.LayoutParams(dp(16), dp(16)))
            }
            root.addView(row)
        }
        dialog.setContentView(root)
        dialog.show()
        LayoutMode.applyDialogWidth(dialog, this, maxTabletDp = 460, phoneMarginDp = 24)
    }

    private fun durationBadgeView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 10f
        typeface = Ds.body(bold = true)
        fontFeatureSettings = "tnum"
        setTextColor(Color.WHITE)
        background = Ds.card(this@DownloadPickerActivity, 0xA8000000.toInt(), null, 4, 0)
        setPadding(dp(5), dp(1), dp(5), dp(1))
    }

    private fun sectionLabel(text: String, topMargin: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        typeface = Ds.body(bold = true)
        setTextColor(Ds.colors.muted)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = topMargin
            bottomMargin = dp(8)
        }
    }

    private fun divider(leftInset: Int = 0, rightInset: Int = 0): View = View(this).apply {
        setBackgroundColor(Ds.colors.lineSoft)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this@DownloadPickerActivity)
        ).apply {
            leftMargin = leftInset
            rightMargin = rightInset
        }
    }

    private fun switchTint(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_checked)
        ),
        intArrayOf(Ds.colors.line, Ds.colors.accent)
    )

    private fun qLabel(q: DownloadQuality): String =
        if (q.note.isBlank()) q.label else "${q.label} · ${q.note}"

    private fun formatDate(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(epochSeconds * 1000L))
    }

    private fun availableDownloadQualities(): List<DownloadQuality> {
        val all = BiliApi.downloadQualities()
        val accepted = acceptedQns.toSet()
        return if (accepted.isEmpty()) all else all.filter { it.qn in accepted }.ifEmpty { all }
    }

    companion object {
        private const val KIND_IMAGE = 0
        private const val KIND_AUDIO = 1
        const val EXTRA_ITEM_JSON = "item_json"
        const val EXTRA_ACCEPTED_QNS = "accepted_qns"

        fun itemToJson(item: VideoItem): String = JSONObject().apply {
            put("bvid", item.bvid)
            put("title", item.title)
            put("owner", item.owner)
            put("cover", item.cover)
            put("views", item.views)
            put("duration", item.duration)
            put("description", item.description)
            put("publishedAt", item.publishedAt)
            put("cid", item.cid)
            put("aid", item.aid)
            put("ownerMid", item.ownerMid)
            put("ownerAvatar", item.ownerAvatar)
            put("replyCount", item.replyCount)
            put("likes", item.likes)
            put("liked", item.liked)
            put("following", item.following)
            put("charge", item.charge)
            item.season?.let { s ->
                put("season", JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("cover", s.cover)
                    put("isMultiPage", s.isMultiPage)
                    val eps = JSONArray()
                    s.episodes.forEach { e ->
                        eps.put(JSONObject().apply {
                            put("bvid", e.bvid)
                            put("aid", e.aid)
                            put("cid", e.cid)
                            put("title", e.title)
                            put("duration", e.duration)
                            put("cover", e.cover)
                            put("publishedAt", e.publishedAt)
                            put("charge", e.charge)
                        })
                    }
                    put("episodes", eps)
                })
            }
        }.toString()

        private fun parseVideoItem(raw: String): VideoItem {
            val o = JSONObject(raw)
            val season = o.optJSONObject("season")?.let { s ->
                val epsArr = s.optJSONArray("episodes")
                val eps = if (epsArr == null) emptyList() else (0 until epsArr.length()).map { i ->
                    val e = epsArr.getJSONObject(i)
                    UgcEpisode(
                        bvid = e.optString("bvid"), aid = e.optLong("aid"), cid = e.optLong("cid"),
                        title = e.optString("title"), duration = e.optInt("duration"),
                        cover = e.optString("cover"),
                        publishedAt = e.optLong("publishedAt", 0L),
                        charge = e.optBoolean("charge")
                    )
                }
                UgcSeason(
                    id = s.optLong("id"), title = s.optString("title"), cover = s.optString("cover"),
                    episodes = eps, isMultiPage = s.optBoolean("isMultiPage")
                )
            }
            return VideoItem(
                bvid = o.optString("bvid"), title = o.optString("title"), owner = o.optString("owner"),
                cover = o.optString("cover"), views = o.optLong("views"), duration = o.optInt("duration"),
                description = o.optString("description"), publishedAt = o.optLong("publishedAt"),
                cid = o.optLong("cid"), aid = o.optLong("aid"), ownerMid = o.optLong("ownerMid"),
                ownerAvatar = o.optString("ownerAvatar"), replyCount = o.optLong("replyCount"),
                likes = o.optLong("likes"), liked = o.optBoolean("liked"),
                following = o.optBoolean("following"), season = season, charge = o.optBoolean("charge")
            )
        }
    }
}

package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 创建收藏夹：名称（必填）、简介、是否公开（默认公开）。
 * 创建成功后回调 RESULT_OK，由「我的收藏」刷新列表。
 */
class CreateFavoriteFolderActivity : Activity() {

    private lateinit var nameInput: EditText
    private lateinit var introInput: EditText
    private lateinit var privacySwitch: PillSwitch
    private lateinit var createBtn: TextView
    private var isPublic = true
    private var submitting = false
    /** 非 0 表示编辑模式。 */
    private var editMediaId = 0L
    /** 已上传到 B 站图床的封面 URL，空表示未选封面。 */
    private var coverUrl = ""
    private lateinit var coverPreview: android.widget.ImageView
    private lateinit var coverHintText: TextView
    private var uploadingCover = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Theme.applySystemBars(this)
        LayoutMode.applyScreenOrientation(this)
        window.setBackgroundDrawable(ColorDrawable(COLOR_BACKGROUND))
        editMediaId = intent.getLongExtra(EXTRA_EDIT_ID, 0L)
        isPublic = intent.getBooleanExtra(EXTRA_EDIT_PUBLIC, true)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val c = Ds.colors
        val root = FrameLayout(this).apply { setBackgroundColor(c.bg) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── 顶栏 ──
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(Ds.Space.lg), dp(8))
        }
        bar.addView(Ds.backIcon(this, c.text) { finish() },
            LinearLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(TextView(this).apply {
            text = if (editMediaId > 0L) "编辑收藏夹" else "创建收藏夹"
            textSize = 18f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
        })
        column.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(View(this).apply { setBackgroundColor(c.lineSoft) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)))

        // ── 可滚动表单 ──
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ds.Space.lg), dp(Ds.Space.lg), dp(Ds.Space.lg), dp(28))
        }

        // 封面：点击从图库选择，随后进入裁剪
        form.addView(sectionLabel("封面"), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val coverBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(this@CreateFavoriteFolderActivity, c.surface, c.lineSoft, Ds.Radius.card)
            setPadding(dp(12), dp(12), dp(14), dp(12))
            isClickable = true
            Ds.bindPressFeedback(this, 0.97f)
            setOnClickListener { pickCoverImage() }
        }
        val coverThumbHost = FrameLayout(this).apply {
            background = Ds.pill(this@CreateFavoriteFolderActivity, c.cover)
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        coverPreview = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        coverThumbHost.addView(coverPreview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val coverPlaceholder = CoverPlaceholderIcon(this, c.subtle)
        coverThumbHost.addView(coverPlaceholder, FrameLayout.LayoutParams(
            dp(22), dp(22), Gravity.CENTER))
        coverBox.addView(coverThumbHost, LinearLayout.LayoutParams(dp(96), dp(60)).apply {
            rightMargin = dp(12)
        })
        coverHintText = TextView(this).apply {
            text = "选择封面（可选，16:10）"
            textSize = 12.5f
            typeface = Ds.body()
            setTextColor(c.muted)
            includeFontPadding = false
            maxLines = 2
        }
        coverBox.addView(coverHintText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(coverBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // 名称（必填）
        form.addView(sectionLabel("名称（必填）"), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(22)
        })
        nameInput = EditText(this).apply {
            hint = "给收藏夹起个名字"
            textSize = 14.5f
            typeface = Ds.body()
            setTextColor(c.text)
            setHintTextColor(c.subtle)
            setSingleLine(true)
            includeFontPadding = false
            background = Ds.card(this@CreateFavoriteFolderActivity, c.surface, c.lineSoft, Ds.Radius.control)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = updateCreateState()
            })
        }
        form.addView(nameInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // 简介
        form.addView(sectionLabel("简介"), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        introInput = EditText(this).apply {
            hint = "简单描述这个收藏夹（可选）"
            textSize = 14f
            typeface = Ds.body()
            setTextColor(c.text)
            setHintTextColor(c.subtle)
            isSingleLine = false
            maxLines = 4
            gravity = Gravity.TOP
            includeFontPadding = false
            background = Ds.card(this@CreateFavoriteFolderActivity, c.surface, c.lineSoft, Ds.Radius.control)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        form.addView(introInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // 公开开关
        val privacyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, 0)
        }
        val privacyText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        privacyText.addView(TextView(this).apply {
            text = "公开收藏夹"
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(c.text)
            includeFontPadding = false
        })
        privacyText.addView(TextView(this).apply {
            text = "公开后其他人可以在你的主页看到"
            textSize = 11.5f
            typeface = Ds.body()
            setTextColor(c.muted)
            includeFontPadding = false
            setPadding(0, dp(4), 0, 0)
        })
        privacyRow.addView(privacyText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        privacySwitch = PillSwitch(this).apply {
            checked = true
            onCheckedChange = { on -> isPublic = on }
        }
        privacyRow.addView(privacySwitch, LinearLayout.LayoutParams(dp(44), dp(26)))
        form.addView(privacyRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        scroll.addView(
            LayoutMode.wrapCenteredForTablet(this, form, maxDp = 640),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        column.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // ── 右下角创建按钮 ──
        createBtn = TextView(this).apply {
            text = "创建"
            textSize = 14f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ds.pill(this@CreateFavoriteFolderActivity, c.accent)
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { submit() }
        }
        root.addView(createBtn, FrameLayout.LayoutParams(dp(88), dp(44), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(Ds.Space.lg)
            bottomMargin = dp(28)
        })
        if (editMediaId > 0L) {
            nameInput.setText(intent.getStringExtra(EXTRA_EDIT_TITLE).orEmpty())
            introInput.setText(intent.getStringExtra(EXTRA_EDIT_INTRO).orEmpty())
            privacySwitch.checked = isPublic
            createBtn.text = "保存"
        }
        updateCreateState()
        return root
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Ds.body(bold = true)
        setTextColor(COLOR_INK)
        includeFontPadding = false
    }

    private fun updateCreateState() {
        if (!::createBtn.isInitialized) return
        val ready = nameInput.text.toString().trim().isNotEmpty() && !submitting
        createBtn.isEnabled = ready
        createBtn.alpha = if (ready) 1f else 0.45f
    }

    private fun submit() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) return
        if (submitting) return
        submitting = true
        createBtn.text = "创建中…"
        updateCreateState()
        val intro = introInput.text.toString()
        if (editMediaId > 0L) {
            BiliApi.editFavoriteFolder(editMediaId, name, intro, isPublic, coverUrl, ::onSubmitResult)
        } else {
            BiliApi.createFavoriteFolder(name, intro, isPublic, coverUrl, ::onSubmitResult)
        }
    }

    private fun onSubmitResult(ok: Boolean, error: String?) {
        if (isFinishing || isDestroyed) return
        submitting = false
        createBtn.text = if (editMediaId > 0L) "保存" else "创建"
        updateCreateState()
        if (ok) {
            Toast.makeText(
                this,
                if (editMediaId > 0L) "已保存" else "收藏夹已创建",
                Toast.LENGTH_SHORT
            ).show()
            setResult(RESULT_OK)
            finish()
        } else {
            Toast.makeText(this, error ?: "操作失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 从系统相册选择图片。 */
    private fun pickCoverImage() {
        if (uploadingCover) return
        // 优先走相册（ACTION_PICK），不可用时回退到文档选择器
        val pick = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        val opened = runCatching {
            startActivityForResult(pick, REQUEST_PICK_IMAGE)
            true
        }.getOrDefault(false)
        if (opened) return
        runCatching {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                },
                REQUEST_PICK_IMAGE
            )
        }.onFailure {
            Toast.makeText(this, "无法打开相册", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_PICK_IMAGE -> {
                val uri = data?.data ?: return
                CoverCropActivity.openForResult(this, uri, REQUEST_CROP_IMAGE)
            }
            REQUEST_CROP_IMAGE -> {
                val path = data?.getStringExtra(CoverCropActivity.EXTRA_RESULT_PATH) ?: return
                uploadCroppedCover(path)
            }
        }
    }

    /** 把裁剪结果上传到 B 站图床，成功后回显。 */
    private fun uploadCroppedCover(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "裁剪结果丢失", Toast.LENGTH_SHORT).show()
            return
        }
        uploadingCover = true
        coverHintText.text = "正在上传封面…"
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null) {
            uploadingCover = false
            coverHintText.text = "选择封面（可选，16:10）"
            Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show()
            return
        }
        BiliApi.uploadCoverImage(bytes) { url, error ->
            if (isFinishing || isDestroyed) return@uploadCoverImage
            uploadingCover = false
            if (url.isNullOrBlank()) {
                coverHintText.text = "选择封面（可选，16:10）"
                Toast.makeText(this, error ?: "封面上传失败", Toast.LENGTH_SHORT).show()
                return@uploadCoverImage
            }
            coverUrl = url
            coverHintText.text = "封面已就绪，点击可重新选择"
            coverPreview.visibility = View.VISIBLE
            CoverLoader.load(coverPreview, url + "@200w_125h.webp")
            // 缓存文件用完即删
            runCatching { file.delete() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    /** 药丸开关：默认开，点击切换。 */
    private class PillSwitch(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val rect = RectF()
        private var animProgress = 1f

        var onCheckedChange: ((Boolean) -> Unit)? = null

        var checked: Boolean = true
            set(value) {
                if (field == value) return
                field = value
                animate().cancel()
                animateTo(if (value) 1f else 0f)
                onCheckedChange?.invoke(value)
            }

        init {
            isClickable = true
            setOnClickListener { checked = !checked }
        }

        private fun animateTo(target: Float) {
            val start = animProgress
            val startAt = android.os.SystemClock.uptimeMillis()
            postOnAnimation(object : Runnable {
                override fun run() {
                    val t = ((android.os.SystemClock.uptimeMillis() - startAt) / 160f).coerceIn(0f, 1f)
                    val eased = 1f - (1f - t) * (1f - t)
                    animProgress = start + (target - start) * eased
                    invalidate()
                    if (t < 1f) postOnAnimation(this)
                }
            })
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = height.toFloat()
            val w = width.toFloat()
            val radius = h / 2f
            val offColor = Ds.colors.lineSoft
            val onColor = Ds.colors.accent
            trackPaint.color = blend(offColor, onColor, animProgress)
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)

            val thumbR = radius - 3f * density
            val cx = thumbR + 3f * density + (w - 2f * (thumbR + 3f * density)) * animProgress
            canvas.drawCircle(cx, radius, thumbR, thumbPaint)
        }

        private fun blend(from: Int, to: Int, t: Float): Int {
            val a = Color.alpha(from) + ((Color.alpha(to) - Color.alpha(from)) * t).toInt()
            val r = Color.red(from) + ((Color.red(to) - Color.red(from)) * t).toInt()
            val g = Color.green(from) + ((Color.green(to) - Color.green(from)) * t).toInt()
            val b = Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * t).toInt()
            return Color.argb(a, r, g, b)
        }
    }

    /** 封面占位小图标。 */
    private class CoverPlaceholderIcon(context: Context, private val tint: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = tint
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = minOf(width, height).toFloat() * 0.78f
            val left = (width - s) / 2f
            val top = (height - s) / 2f
            rect.set(left, top + s * 0.12f, left + s, top + s)
            canvas.drawRoundRect(rect, s * 0.16f, s * 0.16f, paint)
            canvas.drawCircle(left + s * 0.34f, top + s * 0.42f, s * 0.07f, paint)
            val p = android.graphics.Path()
            p.moveTo(left + s * 0.16f, top + s * 0.86f)
            p.lineTo(left + s * 0.44f, top + s * 0.56f)
            p.lineTo(left + s * 0.68f, top + s * 0.86f)
            canvas.drawPath(p, paint)
        }
    }

    companion object {
        const val EXTRA_EDIT_ID = "edit_folder_id"
        const val EXTRA_EDIT_TITLE = "edit_folder_title"
        const val EXTRA_EDIT_INTRO = "edit_folder_intro"
        const val EXTRA_EDIT_PUBLIC = "edit_folder_public"
        private const val REQUEST_PICK_IMAGE = 4201
        private const val REQUEST_CROP_IMAGE = 4202

        fun open(context: Context) {
            context.startActivity(Intent(context, CreateFavoriteFolderActivity::class.java))
        }
    }
}

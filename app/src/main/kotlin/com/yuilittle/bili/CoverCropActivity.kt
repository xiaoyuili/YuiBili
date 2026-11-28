package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 收藏夹封面裁剪：固定 16:10 裁剪框，支持拖动与双指缩放，
 * 确认后把裁剪结果写入缓存目录并回传路径。
 */
class CoverCropActivity : Activity() {

    private lateinit var cropView: CropImageView
    private var decodeError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        LayoutMode.applyScreenOrientation(this)
        // 裁剪页整体黑底，状态栏用黑底白图标，避免刺眼
        Ds.applySystemBars(this, lightContent = true)
        window.navigationBarColor = Color.BLACK
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        val uriString = intent.getStringExtra(EXTRA_SOURCE_URI).orEmpty()
        if (uriString.isBlank()) { finish(); return }
        val bitmap = runCatching {
            contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeStream(input, null, opts)
            }
        }.getOrNull()
        if (bitmap == null) {
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        cropView = CropImageView(this).apply { setBitmap(bitmap) }
        root.addView(cropView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 顶栏：取消 + 提示
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        bar.addView(TextView(this).apply {
            text = "取消"
            textSize = 15f
            typeface = Ds.body(bold = true)
            setTextColor(Color.WHITE)
            includeFontPadding = false
            isClickable = true
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(56), dp(40)))
        bar.addView(TextView(this).apply {
            text = "拖动调整位置，双指缩放"
            textSize = 12.5f
            typeface = Ds.body()
            setTextColor(0xB3FFFFFF.toInt())
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        bar.addView(View(this), LinearLayout.LayoutParams(dp(56), dp(40)))
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP).apply {
            topMargin = dp(8)
        })

        // 底部确认
        val confirm = TextView(this).apply {
            text = "完成"
            textSize = 15f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ds.pill(this@CoverCropActivity, Ds.colors.accent)
            includeFontPadding = false
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { finishCrop() }
        }
        root.addView(confirm, FrameLayout.LayoutParams(dp(120), dp(44), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(36)
        })
        setContentView(root)
    }

    private fun finishCrop() {
        val result = runCatching { cropView.exportCropped() }.getOrNull()
        if (result == null) {
            Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(cacheDir, "fav_cover_${System.currentTimeMillis()}.jpg")
        val ok = runCatching {
            file.outputStream().use { out ->
                result.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            true
        }.getOrDefault(false)
        result.recycle()
        if (!ok) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, file.absolutePath))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 固定 16:10 裁剪框的图片视图：
     * 单指拖动平移，双指捏合缩放，缩放后自动约束在裁剪框内。
     */
    private class CropImageView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB3000000.toInt()
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = Color.WHITE
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * density
            color = 0x66FFFFFF
        }

        private var bitmap: Bitmap? = null
        private val matrix = Matrix()
        private val baseMatrix = Matrix()
        private var minScale = 1f
        private var currentScale = 1f
        private val frame = RectF()

        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        private var pointerCount = 0

        private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val next = (currentScale * factor).coerceIn(minScale, minScale * 6f)
                val applied = next / currentScale
                if (applied == 1f) return false
                currentScale = next
                matrix.postScale(applied, applied, detector.focusX, detector.focusY)
                constrain()
                invalidate()
                return true
            }
        })

        fun setBitmap(source: Bitmap) {
            bitmap = source
            if (width > 0 && height > 0) {
                updateFrame()
                resetMatrix()
            }
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateFrame()
            // 尺寸确定后才能算出裁剪框，此时再初始化矩阵
            if (bitmap != null) resetMatrix()
        }

        /** 裁剪框：水平居中、垂直居中，宽高比 16:10，留出上下操作空间。 */
        private fun updateFrame() {
            val availW = width - 32f * density
            val availH = height - 190f * density
            var fw = availW
            var fh = fw * 10f / 16f
            if (fh > availH) {
                fh = availH
                fw = fh * 16f / 10f
            }
            val left = (width - fw) / 2f
            val top = (height - fh) / 2f
            frame.set(left, top, left + fw, top + fh)
        }

        private fun resetMatrix() {
            val bmp = bitmap ?: return
            if (frame.width() <= 0f || frame.height() <= 0f) return
            baseMatrix.reset()
            // 等比铺满裁剪框
            val scale = maxOf(frame.width() / bmp.width, frame.height() / bmp.height)
            baseMatrix.setScale(scale, scale)
            baseMatrix.postTranslate(
                frame.centerX() - bmp.width * scale / 2f,
                frame.centerY() - bmp.height * scale / 2f
            )
            matrix.set(baseMatrix)
            minScale = 1f
            currentScale = 1f
            constrain()
            invalidate()
        }

        /** 保证图片始终覆盖裁剪框。 */
        private fun constrain() {
            val bmp = bitmap ?: return
            val values = FloatArray(9)
            matrix.getValues(values)
            val scale = values[Matrix.MSCALE_X]
            val transX = values[Matrix.MTRANS_X]
            val transY = values[Matrix.MTRANS_Y]
            val drawW = bmp.width * scale
            val drawH = bmp.height * scale

            var dx = 0f
            var dy = 0f
            if (drawW <= frame.width()) {
                dx = frame.centerX() - (transX + drawW / 2f)
            } else {
                if (transX > frame.left) dx = frame.left - transX
                if (transX + drawW < frame.right) dx = frame.right - (transX + drawW)
            }
            if (drawH <= frame.height()) {
                dy = frame.centerY() - (transY + drawH / 2f)
            } else {
                if (transY > frame.top) dy = frame.top - transY
                if (transY + drawH < frame.bottom) dy = frame.bottom - (transY + drawH)
            }
            if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    dragging = true
                    pointerCount = 1
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    pointerCount = event.pointerCount
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && dragging && pointerCount == 1) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        matrix.postTranslate(dx, dy)
                        constrain()
                        invalidate()
                        lastX = event.x
                        lastY = event.y
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    pointerCount = (event.pointerCount - 1).coerceAtLeast(1)
                    if (pointerCount == 1) {
                        val index = if (event.actionIndex == 0) 1 else 0
                        lastX = event.getX(index)
                        lastY = event.getY(index)
                        dragging = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bmp = bitmap ?: return
            canvas.drawBitmap(bmp, matrix, bitmapPaint)

            // 裁剪框外遮罩
            canvas.drawRect(0f, 0f, width.toFloat(), frame.top, maskPaint)
            canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), maskPaint)
            canvas.drawRect(0f, frame.top, frame.left, frame.bottom, maskPaint)
            canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, maskPaint)

            // 边框 + 三分线
            canvas.drawRect(frame, borderPaint)
            val thirdW = frame.width() / 3f
            val thirdH = frame.height() / 3f
            canvas.drawLine(frame.left + thirdW, frame.top, frame.left + thirdW, frame.bottom, gridPaint)
            canvas.drawLine(frame.left + thirdW * 2, frame.top, frame.left + thirdW * 2, frame.bottom, gridPaint)
            canvas.drawLine(frame.left, frame.top + thirdH, frame.right, frame.top + thirdH, gridPaint)
            canvas.drawLine(frame.left, frame.top + thirdH * 2, frame.right, frame.top + thirdH * 2, gridPaint)
        }

        /** 导出裁剪结果（宽 640 × 高 400，满足 B 站图床 >420 的要求）。 */
        fun exportCropped(): Bitmap? {
            val bmp = bitmap ?: return null
            if (frame.width() <= 0f) return null
            val outW = 640
            val outH = 400
            // 计算裁剪框在图片坐标系中的映射
            val inverse = Matrix()
            if (!matrix.invert(inverse)) return null
            val rect = RectF(frame)
            inverse.mapRect(rect)
            val left = rect.left.toInt().coerceIn(0, (bmp.width - 1).coerceAtLeast(1))
            val top = rect.top.toInt().coerceIn(0, (bmp.height - 1).coerceAtLeast(1))
            val right = rect.right.toInt().coerceIn(left + 1, bmp.width)
            val bottom = rect.bottom.toInt().coerceIn(top + 1, bmp.height)
            val srcW = right - left
            val srcH = bottom - top
            val src = Bitmap.createBitmap(bmp, left, top, srcW, srcH)
            val scaled = Bitmap.createScaledBitmap(src, outW, outH, true)
            if (scaled !== src) src.recycle()
            return scaled
        }
    }

    companion object {
        const val EXTRA_SOURCE_URI = "crop_source_uri"
        const val EXTRA_RESULT_PATH = "crop_result_path"

        fun openForResult(activity: Activity, uri: Uri, requestCode: Int) {
            activity.startActivityForResult(
                Intent(activity, CoverCropActivity::class.java)
                    .putExtra(EXTRA_SOURCE_URI, uri.toString()),
                requestCode
            )
        }
    }
}

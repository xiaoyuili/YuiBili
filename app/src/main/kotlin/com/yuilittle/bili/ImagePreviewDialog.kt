package com.yuilittle.bili

import android.animation.Animator
import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun shouldInstallPreviewPoster(boundUrl: String?, url: String, hasDrawable: Boolean): Boolean =
    boundUrl != url || !hasDrawable

/**
 * 公共全屏图片浏览器弹窗：
 * - 左右滑动切换多图
 * - 双击缩放 / 双指捏合缩放
 * - 超长竖图自动启用 `BitmapRegionDecoder` 1024px 分条解码，保持原图细节且消除掉帧
 * - 支持保存原图到系统相册（Pictures/YuiBili）
 */
internal object ImagePreviewDialog {

    fun show(
        activity: Activity,
        urls: List<String>,
        startIndex: Int,
        posterProvider: ((Int) -> Bitmap?)? = null,
        onFeedback: ((String) -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val images = urls.filter { it.isNotBlank() }.distinct().map(::originalCoverUrl)
        if (images.isEmpty()) return
        var current = startIndex.coerceIn(0, images.size - 1)
        var animating = false

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(activity).apply {
            setBackgroundColor(0xE6000000.toInt())
        }
        val slots = ArrayList<ZoomableImageView>(3).apply {
            repeat(3) { add(ZoomableImageView(activity)) }
        }
        fun rotateSlotWindow(direction: Int) {
            if (direction > 0) {
                slots.add(slots.removeAt(0))
            } else if (direction < 0) {
                slots.add(0, slots.removeAt(slots.lastIndex))
            }
        }

        val counter = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(activity.dp(14), activity.dp(6), activity.dp(14), activity.dp(6))
            background = rounded(0x66000000.toInt(), activity.dp(14))
        }
        val loadingBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(14), activity.dp(8), activity.dp(14), activity.dp(8))
            background = rounded(0x99000000.toInt(), activity.dp(18))
            visibility = View.GONE
        }
        val loadingSpinner = BrandSpinnerView(activity, COLOR_ROSE, Color.WHITE)
        val loadingPercent = TextView(activity).apply {
            text = "加载原图…"
            textSize = 12f
            typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val retryHint = TextView(activity).apply {
            text = "点击重试"
            textSize = 12f
            setTextColor(COLOR_ROSE)
            gravity = Gravity.CENTER
            setPadding(activity.dp(8), 0, 0, 0)
            visibility = View.GONE
        }
        loadingBox.addView(loadingSpinner, LinearLayout.LayoutParams(activity.dp(18), activity.dp(18)))
        loadingBox.addView(loadingPercent, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = activity.dp(8) })
        loadingBox.addView(retryHint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        lateinit var renderPage: () -> Unit
        loadingBox.setOnClickListener {
            if (retryHint.visibility == View.VISIBLE) {
                val raw = images.getOrNull(current) ?: return@setOnClickListener
                renderPage()
                CoverLoader.retry(slots[1], raw, original = true, allowAnimatedGif = true)
            }
        }

        slots.forEach { slot ->
            root.addView(slot, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            slot.visibility = View.GONE
            slot.onPagerDrag = { delta ->
                val w = root.width.toFloat().coerceAtLeast(1f)
                val limited = when {
                    current == 0 && delta > 0f -> delta * 0.28f
                    current == images.size - 1 && delta < 0f -> delta * 0.28f
                    else -> delta
                }
                slots.forEachIndexed { i, s -> s.translationX = (i - 1) * w + limited }
            }
            slot.onPagerRelease = { delta, velocityX ->
                if (!animating) {
                    val w = root.width.toFloat().coerceAtLeast(1f)
                    val next = when {
                        delta < -w * 0.10f || (velocityX < -500f && delta < 0f) -> (current + 1).coerceAtMost(images.size - 1)
                        delta > w * 0.10f || (velocityX > 500f && delta > 0f) -> (current - 1).coerceAtLeast(0)
                        else -> current
                    }
                    val target = when {
                        next > current -> -w
                        next < current -> w
                        else -> 0f
                    }
                    animating = true
                    val startDelta = when {
                        current == 0 && delta > 0f -> delta * 0.28f
                        current == images.size - 1 && delta < 0f -> delta * 0.28f
                        else -> delta
                    }
                    val anim = android.animation.ValueAnimator.ofFloat(startDelta, target)
                    anim.duration = 200
                    anim.interpolator = DecelerateInterpolator()
                    anim.addUpdateListener {
                        val v = it.animatedValue as Float
                        slots.forEachIndexed { i, s -> s.translationX = (i - 1) * w + v }
                    }
                    var settled = false
                    fun settle() {
                        if (settled) return
                        settled = true
                        if (next != current) {
                            val direction = if (next > current) 1 else -1
                            current = next
                            rotateSlotWindow(direction)
                        }
                        renderPage()
                        animating = false
                    }
                    anim.addListener(object : Animator.AnimatorListener {
                        override fun onAnimationStart(animation: Animator) {}
                        override fun onAnimationRepeat(animation: Animator) {}
                        override fun onAnimationCancel(animation: Animator) = settle()
                        override fun onAnimationEnd(animation: Animator) = settle()
                    })
                    anim.start()
                }
            }
        }

        root.addView(loadingBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = activity.dp(74) })

        var showLoadingDelay: Runnable? = null

        fun scheduleShowLoading(hasPoster: Boolean, percent: Int) {
            showLoadingDelay?.let(root::removeCallbacks)
            loadingSpinner.visibility = View.VISIBLE
            loadingSpinner.startSpinning()
            retryHint.visibility = View.GONE
            loadingPercent.text = if (percent > 0) "原图 $percent%" else "加载原图…"
            if (loadingBox.visibility == View.VISIBLE) return
            val delayMs = if (hasPoster) 120L else 150L
            val r = Runnable { loadingBox.visibility = View.VISIBLE }
            showLoadingDelay = r
            root.postDelayed(r, delayMs)
        }

        fun hideLoading() {
            showLoadingDelay?.let(root::removeCallbacks)
            showLoadingDelay = null
            loadingBox.visibility = View.GONE
        }

        fun loadNeighborThumbnail(slot: ZoomableImageView, imageIndex: Int) {
            val raw = images[imageIndex]
            slot.clearTileSource()
            val poster = posterProvider?.invoke(imageIndex) ?: CoverLoader.cachedThumbnail(raw)
            val hasPoster = slot.bindAsset(raw, poster, staticOnly = true)
            // 邻页仅加载轻量静态缩略图，不抢占当前图原图并发池
            CoverLoader.load(
                slot,
                raw,
                original = false,
                cropThumb = false,
                animate = false,
                thumbEdge = 480,
                priority = CoverLoader.PRIORITY_LOW,
                allowAnimatedGif = false,
                preservePlaceholder = hasPoster
            )
        }

        renderPage = render@{
            if (!dialog.isShowing) return@render
            val w = root.width.toFloat().coerceAtLeast(1f)
            hideLoading()
            // 先调度中央图（最高优先），再安排左右邻页轻量占位，防止左邻图抢占原图 2 线程 worker
            val centerSlot = slots[1]
            centerSlot.translationX = 0f
            if (current in images.indices) {
                centerSlot.visibility = View.VISIBLE
                val raw = images[current]
                val gif = resolveCoverKey(raw, original = true)?.gif == true
                val poster = posterProvider?.invoke(current) ?: CoverLoader.cachedThumbnail(raw)
                val hasPoster = centerSlot.bindAsset(raw, poster)
                CoverLoader.load(
                    centerSlot,
                    raw,
                    original = true,
                    cropThumb = false,
                    animate = !hasPoster,
                    priority = CoverLoader.PRIORITY_HIGH,
                    allowAnimatedGif = true,
                    preservePlaceholder = hasPoster,
                    onProgress = { percent ->
                        if (images.getOrNull(current) == raw) {
                            if (percent >= 100) {
                                hideLoading()
                                if (!gif) centerSlot.bindTileSource(raw)
                            } else {
                                scheduleShowLoading(hasPoster, percent)
                            }
                        }
                    },
                    onFailure = { _ ->
                        if (images.getOrNull(current) == raw) {
                            showLoadingDelay?.let(root::removeCallbacks)
                            loadingSpinner.visibility = View.GONE
                            loadingPercent.text = if (hasPoster) "原图加载失败" else "图片加载失败"
                            retryHint.visibility = View.VISIBLE
                            loadingBox.visibility = View.VISIBLE
                        }
                    }
                )
                centerSlot.invalidate()
                centerSlot.drawable?.invalidateSelf()
            } else {
                centerSlot.visibility = View.GONE
                centerSlot.clearTileSource()
                CoverLoader.clear(centerSlot)
            }

            listOf(0 to (current - 1), 2 to (current + 1)).forEach { (slotIdx, imageIndex) ->
                val slot = slots[slotIdx]
                slot.translationX = (slotIdx - 1) * w
                if (imageIndex in images.indices) {
                    slot.visibility = View.VISIBLE
                    loadNeighborThumbnail(slot, imageIndex)
                } else {
                    slot.visibility = View.GONE
                    slot.clearTileSource()
                    CoverLoader.clear(slot)
                }
            }
            counter.text = "${current + 1}/${images.size}"
            counter.visibility = if (images.size > 1) View.VISIBLE else View.GONE
        }

        root.addView(counter, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = activity.dp(28) })

        fun addAction(close: Boolean, action: () -> Unit) {
            val button = TextView(activity).apply {
                text = if (close) "×" else "存相册"
                textSize = if (close) 16f else 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                val padX = activity.dp(if (close) 10 else 14)
                val padY = activity.dp(if (close) 6 else 8)
                setPadding(padX, padY, padX, padY)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = rounded(0x66000000.toInt(), activity.dp(if (close) 16 else 18))
            }
            val hitArea = FrameLayout(activity).apply {
                minimumWidth = activity.dp(48)
                minimumHeight = activity.dp(48)
                contentDescription = if (close) "关闭图片预览" else "保存到相册"
                isFocusable = true
                addView(button, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                setOnClickListener { action() }
                Ds.bindPressFeedback(this, 0.96f)
            }
            val side = if (close) Gravity.START else Gravity.END
            val margin = activity.dp(16)
            root.addView(hitArea, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or side
            ).apply {
                topMargin = margin
                if (close) marginStart = margin else marginEnd = margin
            })
        }
        addAction(close = true) { dialog.dismiss() }
        addAction(close = false) { downloadImage(activity, images[current], onFeedback) }

        dialog.setOnDismissListener {
            hideLoading()
            slots.forEach(CoverLoader::clear)
        }
        dialog.setContentView(root)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0x00000000))
        dialog.show()
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(200).start()
        root.post { renderPage() }
    }

    fun downloadImageToGallery(
        activity: Activity,
        url: String,
        onFeedback: ((String) -> Unit)? = null
    ) {
        downloadImage(activity, originalCoverUrl(url), onFeedback)
    }

    private fun downloadImage(
        activity: Activity,
        url: String,
        onFeedback: ((String) -> Unit)?
    ) {
        if (url.isBlank() || activity.isFinishing || activity.isDestroyed) return
        if (CommentImageSavePermission.requestIfNeeded(activity, url)) return
        val notify: (String) -> Unit = { msg ->
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    if (onFeedback != null) onFeedback(msg)
                    else Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        Thread {
            try {
                val cached = CoverLoader.cachedOriginalFile(activity, url, includeGif = true)
                val extension = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty().substringAfterLast('.')
                    .lowercase(java.util.Locale.US)
                    .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") } ?: "jpg"
                val mimeType = when (extension) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> "image/jpeg"
                }
                val fileName = "yuibili_${System.currentTimeMillis()}.$extension"
                // 优先直接流式复制原图缓存，未命中则共享 OkHttp 连接池有界流式写入（不再 readBytes 整图进堆）
                val copyToOut: (java.io.OutputStream) -> Unit = { out ->
                    if (cached != null && cached.isFile && cached.length() > 0L) {
                        cached.inputStream().use { input -> input.copyTo(out, 32 * 1024) }
                    } else {
                        val req = BiliHttp.request(url).header("Accept", "image/*").build()
                        BiliHttp.mediaClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                            val body = resp.body ?: throw java.io.IOException("空响应体")
                            val maxBytes = 32L * 1024L * 1024L
                            if (body.contentLength() > maxBytes) throw java.io.IOException("图片过大")
                            body.byteStream().use { input ->
                                val buf = ByteArray(32 * 1024)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > maxBytes) throw java.io.IOException("图片超出大小上限")
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    }
                }
                val saved = if (Build.VERSION.SDK_INT >= 29) {
                    val resolver = activity.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YuiBili")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        try {
                            val output = resolver.openOutputStream(uri) ?: throw java.io.IOException("无法写入相册")
                            output.use(copyToOut)
                            values.clear()
                            values.put(MediaStore.Images.Media.IS_PENDING, 0)
                            check(resolver.update(uri, values, null, null) > 0) { "相册保存未完成" }
                            true
                        } catch (e: Exception) {
                            resolver.delete(uri, null, null)
                            throw e
                        }
                    } else false
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "YuiBili")
                    check(dir.isDirectory || dir.mkdirs()) { "无法创建图片目录" }
                    val file = File(dir, fileName)
                    try {
                        file.outputStream().buffered().use(copyToOut)
                    } catch (error: Exception) {
                        file.delete()
                        throw error
                    }
                    MediaScannerConnection.scanFile(activity.applicationContext, arrayOf(file.path), arrayOf(mimeType), null)
                    true
                }
                notify(if (saved) "已保存到相册（Pictures/YuiBili）" else "保存失败")
            } catch (error: Exception) {
                notify("下载失败：${error.message ?: "网络错误"}")
            }
        }.start()
    }

    private class ZoomableImageView(context: Context) : ImageView(context) {
        private val baseMatrix = Matrix()
        private val matrix = Matrix()
        private val matrixValues = FloatArray(9)
        private var minScale = 1f
        private var maxScale = 5f
        private var isLongVertical = false
        private val scroller = android.widget.OverScroller(context).apply {
            setFriction(ViewConfiguration.getScrollFriction() * 0.72f)
        }
        private var isAnimatingFling = false
        private val flingRunnable = object : Runnable {
            override fun run() {
                if (scroller.computeScrollOffset()) {
                    matrix.getValues(matrixValues)
                    matrixValues[Matrix.MTRANS_X] = scroller.currX.toFloat()
                    matrixValues[Matrix.MTRANS_Y] = scroller.currY.toFloat()
                    matrix.setValues(matrixValues)
                    clampMatrix()
                    imageMatrix = matrix
                    scheduleVisibleTiles()
                    invalidate()
                    postOnAnimation(this)
                } else {
                    isAnimatingFling = false
                }
            }
        }

        private var tileSourceUrl: String? = null
        private var tileRawWidth = 0
        private var tileRawHeight = 0
        private val tileStripHeight = 1024
        // 分块缓存按字节计量（上限 10 MiB），防止超宽长图 10 块累计突破内存预算
        private val tileCache = object : LruCache<Int, Bitmap>(10 * 1024 * 1024) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
            override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap?, newValue: Bitmap?) = Unit
        }
        private val tileMain = android.os.Handler(android.os.Looper.getMainLooper())
        private val tileWorker = ImageTileWorker<Bitmap>(
            open = ::openTileSource,
            deliver = { work -> tileMain.post(work); Unit },
            onSource = { width, height ->
                tileRawWidth = width
                tileRawHeight = height
                scheduleVisibleTiles()
                invalidate()
            },
            onTile = { index, bitmap ->
                if (bitmap != null) tileCache.put(index, bitmap)
                invalidate()
            }
        )
        private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val tileSrcRect = Rect()
        private val tileDstRect = RectF()

        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    stopFling()
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    var factor = detector.scaleFactor
                    val next = currentScale * factor
                    if (next > maxScale) factor = maxScale / currentScale
                    if (next < minScale) factor = minScale / currentScale
                    matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                    clampMatrix()
                    imageMatrix = matrix
                    scheduleVisibleTiles()
                    return true
                }
            }
        )

        private val tapDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    stopFling()
                    if (currentScale > minScale * 1.2f) resetToFit() else zoomTo(minScale * 2.5f, e.x, e.y)
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (scaleDetector.isInProgress) return false
                    if (canPanInside()) {
                        matrix.postTranslate(-distanceX, -distanceY)
                        clampMatrix()
                        imageMatrix = matrix
                        scheduleVisibleTiles()
                        return true
                    }
                    return false
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (scaleDetector.isInProgress || !canPanInside()) return false
                    val vw = width
                    val vh = height
                    matrix.getValues(matrixValues)
                    val curX = matrixValues[Matrix.MTRANS_X].toInt()
                    val curY = matrixValues[Matrix.MTRANS_Y].toInt()
                    val dw = (imageWidth() * matrixValues[Matrix.MSCALE_X]).toInt()
                    val dh = (imageHeight() * matrixValues[Matrix.MSCALE_Y]).toInt()
                    val minX = if (dw <= vw) (vw - dw) / 2 else vw - dw
                    val maxX = if (dw <= vw) (vw - dw) / 2 else 0
                    val minY = if (dh <= vh) (vh - dh) / 2 else vh - dh
                    val maxY = if (dh <= vh) (vh - dh) / 2 else 0
                    scroller.forceFinished(true)
                    scroller.fling(curX, curY, velocityX.toInt(), velocityY.toInt(), minX, maxX, minY, maxY)
                    isAnimatingFling = true
                    postOnAnimation(flingRunnable)
                    return true
                }
            }
        ).apply { setIsLongpressEnabled(false) }

        var onPagerDrag: ((Float) -> Unit)? = null
        var onPagerRelease: ((delta: Float, velocityX: Float) -> Unit)? = null
        private var pagerMode = 0
        private var downX = 0f
        private var downY = 0f
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var velocityTracker: VelocityTracker? = VelocityTracker.obtain()

        private val currentScale: Float
            get() {
                matrix.getValues(matrixValues)
                return matrixValues[Matrix.MSCALE_X]
            }

        init {
            scaleType = ScaleType.MATRIX
            isClickable = true
        }

        fun bindTileSource(rawUrl: String) {
            if (tileSourceUrl == rawUrl) {
                scheduleVisibleTiles()
                return
            }
            tileSourceUrl = rawUrl
            tileRawWidth = 0
            tileRawHeight = 0
            tileCache.evictAll()
            tileWorker.bind(rawUrl)
        }

        fun clearTileSource() {
            tileSourceUrl = null
            tileRawWidth = 0
            tileRawHeight = 0
            tileCache.evictAll()
            tileWorker.bind(null)
        }

        private fun openTileSource(rawUrl: String): ImageTileWorker.Source<Bitmap>? {
            val file = CoverLoader.cachedOriginalFile(context.applicationContext, rawUrl) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val rawWidth = bounds.outWidth
            val rawHeight = bounds.outHeight
            if (rawWidth <= 0 || rawHeight <= 2048 || rawHeight.toFloat() / rawWidth.toFloat() < 2f) return null
            val decoder = try {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.path)
                else BitmapRegionDecoder.newInstance(file.path, false)
            } catch (_: Exception) { null } ?: return null
            return object : ImageTileWorker.Source<Bitmap> {
                override val width = rawWidth
                override val height = rawHeight
                override fun decode(index: Int): Bitmap? {
                    val top = index * tileStripHeight
                    if (top !in 0 until rawHeight) return null
                    val bottom = ((index + 1) * tileStripHeight).coerceAtMost(rawHeight)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = imageTileSampleSize(rawWidth)
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    return decoder.decodeRegion(Rect(0, top, rawWidth, bottom), options)
                }
                override fun close() { if (!decoder.isRecycled) decoder.recycle() }
            }
        }

        private fun scheduleVisibleTiles() {
            val rawW = tileRawWidth
            val rawH = tileRawHeight
            val baseW = imageWidth()
            val baseH = imageHeight()
            if (rawW <= 0 || rawH <= 0 || baseW <= 0f || baseH <= 0f || width <= 0 || height <= 0) return
            matrix.getValues(matrixValues)
            val scaleX = matrixValues[Matrix.MSCALE_X]
            val scaleY = matrixValues[Matrix.MSCALE_Y]
            val transY = matrixValues[Matrix.MTRANS_Y]
            if (scaleX <= 0f || scaleY <= 0f) return

            val viewTopInBase = (-transY / scaleY).coerceIn(0f, baseH)
            val viewBottomInBase = ((height - transY) / scaleY).coerceIn(0f, baseH)
            val ratioY = rawH.toFloat() / baseH
            val rawTop = (viewTopInBase * ratioY).toInt().coerceIn(0, rawH)
            val rawBottom = (viewBottomInBase * ratioY).toInt().coerceIn(0, rawH)
            val centerTile = ((rawTop + rawBottom) / 2) / tileStripHeight
            val firstTile = ((rawTop / tileStripHeight) - 1).coerceAtLeast(0)
            val maxTile = ((rawH - 1) / tileStripHeight).coerceAtLeast(0)
            val lastTile = ((rawBottom / tileStripHeight) + 2).coerceAtMost(maxTile)
            val orderedIndices = (firstTile..lastTile)
                .filter { tileCache.get(it) == null }
                .sortedBy { kotlin.math.abs(it - centerTile) }
            tileWorker.request(orderedIndices)
        }

        override fun onDraw(canvas: Canvas) {
            val baseW = imageWidth()
            val baseH = imageHeight()
            if (tileRawWidth <= 0 || tileRawHeight <= 0 || baseW <= 0f || baseH <= 0f) {
                super.onDraw(canvas)
                return
            }
            matrix.getValues(matrixValues)
            val scaleX = matrixValues[Matrix.MSCALE_X]
            val scaleY = matrixValues[Matrix.MSCALE_Y]
            val transX = matrixValues[Matrix.MTRANS_X]
            val transY = matrixValues[Matrix.MTRANS_Y]
            val basePerRawY = baseH / tileRawHeight.toFloat()
            val screenW = baseW * scaleX
            val maxTile = ((tileRawHeight - 1) / tileStripHeight).coerceAtLeast(0)
            val viewTopRaw = ((-transY / scaleY) / basePerRawY).toInt().coerceIn(0, tileRawHeight)
            val viewBottomRaw = (((height - transY) / scaleY) / basePerRawY).toInt().coerceIn(0, tileRawHeight)
            val firstTile = (viewTopRaw / tileStripHeight).coerceAtLeast(0)
            val lastTile = (viewBottomRaw / tileStripHeight).coerceAtMost(maxTile)
            var allVisibleCached = true
            for (idx in firstTile..lastTile) {
                if (tileCache.get(idx) == null) {
                    allVisibleCached = false
                    break
                }
            }
            if (!allVisibleCached) super.onDraw(canvas)
            for (idx in firstTile..lastTile) {
                val tile = tileCache.get(idx) ?: continue
                val stripTop = idx * tileStripHeight
                val stripBottom = ((idx + 1) * tileStripHeight).coerceAtMost(tileRawHeight)
                val dstTop = transY + stripTop * basePerRawY * scaleY
                val dstBottom = transY + stripBottom * basePerRawY * scaleY
                tileSrcRect.set(0, 0, tile.width, tile.height)
                tileDstRect.set(transX, dstTop, transX + screenW, dstBottom)
                canvas.drawBitmap(tile, tileSrcRect, tileDstRect, tilePaint)
            }
        }

        private var boundAssetUrl: String? = null
        private var pendingResetOnNextDrawable = true

        fun bindAsset(rawUrl: String, poster: Bitmap?, staticOnly: Boolean = false): Boolean {
            val keepDrawable = drawable != null && !(staticOnly && drawable is Animatable)
            val installPoster = shouldInstallPreviewPoster(boundAssetUrl, rawUrl, keepDrawable)
            if (boundAssetUrl != rawUrl) {
                boundAssetUrl = rawUrl
                pendingResetOnNextDrawable = true
            }
            if (poster == null || poster.isRecycled) return false
            if (installPoster) setImageDrawable(BitmapDrawable(resources, poster))
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (drawable != null) resetToFit()
        }

        override fun setImageDrawable(drawable: Drawable?) {
            val prevW = imageWidth()
            val prevH = imageHeight()
            val prevScale = currentScale
            val hadUserZoom = !pendingResetOnNextDrawable && prevW > 0f && prevH > 0f && prevScale > minScale * 1.04f
            var normCenterX = 0.5f
            var normCenterY = 0.5f
            var zoomFactor = 1f
            if (hadUserZoom && width > 0 && height > 0) {
                matrix.getValues(matrixValues)
                val tx = matrixValues[Matrix.MTRANS_X]
                val ty = matrixValues[Matrix.MTRANS_Y]
                normCenterX = ((width * 0.5f - tx) / (prevW * prevScale)).coerceIn(0f, 1f)
                normCenterY = ((height * 0.5f - ty) / (prevH * prevScale)).coerceIn(0f, 1f)
                zoomFactor = (prevScale / minScale.coerceAtLeast(0.001f)).coerceIn(1f, 5f)
            }
            super.setImageDrawable(drawable)
            if (drawable != null) {
                resetToFit()
                pendingResetOnNextDrawable = false
                if (hadUserZoom && width > 0 && height > 0) {
                    val newW = imageWidth()
                    val newH = imageHeight()
                    if (newW > 0f && newH > 0f) {
                        val targetScale = (minScale * zoomFactor).coerceIn(minScale, maxScale)
                        val tx = width * 0.5f - normCenterX * newW * targetScale
                        val ty = height * 0.5f - normCenterY * newH * targetScale
                        matrix.reset()
                        matrix.postScale(targetScale, targetScale)
                        matrix.postTranslate(tx, ty)
                        clampMatrix()
                        imageMatrix = matrix
                    }
                }
            }
        }

        private fun imageWidth(): Float =
            (drawable as? BitmapDrawable)?.bitmap?.width?.toFloat()
                ?: drawable?.intrinsicWidth?.toFloat() ?: 0f

        private fun imageHeight(): Float =
            (drawable as? BitmapDrawable)?.bitmap?.height?.toFloat()
                ?: drawable?.intrinsicHeight?.toFloat() ?: 0f

        private fun canPanInside(): Boolean {
            if (currentScale > minScale * 1.05f) return true
            if (!isLongVertical || width == 0 || height == 0) return false
            matrix.getValues(matrixValues)
            val dh = imageHeight() * matrixValues[Matrix.MSCALE_Y]
            return dh > height + 2f
        }

        private fun stopFling() {
            isAnimatingFling = false
            removeCallbacks(flingRunnable)
            if (!scroller.isFinished) scroller.forceFinished(true)
        }

        private fun resetToFit() {
            stopFling()
            if (width == 0 || height == 0) {
                post { resetToFit() }
                return
            }
            val vw = width.toFloat()
            val vh = height.toFloat()
            val dw = imageWidth()
            val dh = imageHeight()
            if (dw <= 0f || dh <= 0f) return
            val fitWidth = vw / dw
            val longImage = dh / dw >= 2.0f && dh * fitWidth > vh * 1.15f
            isLongVertical = longImage
            val fit = if (longImage) fitWidth else minOf(fitWidth, vh / dh)
            minScale = fit
            maxScale = maxOf(fit * 5f, 2.5f)
            baseMatrix.reset()
            baseMatrix.postScale(fit, fit)
            val dx = (vw - dw * fit) / 2f
            val dy = if (longImage) 0f else (vh - dh * fit) / 2f
            baseMatrix.postTranslate(dx, dy)
            matrix.set(baseMatrix)
            imageMatrix = matrix
            scheduleVisibleTiles()
        }

        private fun zoomTo(target: Float, fx: Float, fy: Float) {
            val factor = target / currentScale
            matrix.postScale(factor, factor, fx, fy)
            clampMatrix()
            imageMatrix = matrix
            scheduleVisibleTiles()
        }

        override fun onDetachedFromWindow() {
            stopFling()
            clearTileSource()
            tileWorker.close()
            super.onDetachedFromWindow()
            velocityTracker?.recycle()
            velocityTracker = null
        }

        private fun clampMatrix() {
            val vw = width.toFloat().coerceAtLeast(1f)
            val vh = height.toFloat().coerceAtLeast(1f)
            matrix.getValues(matrixValues)
            val dw = imageWidth() * matrixValues[Matrix.MSCALE_X]
            val dh = imageHeight() * matrixValues[Matrix.MSCALE_Y]
            matrixValues[Matrix.MTRANS_X] = if (dw <= vw) (vw - dw) / 2f
            else matrixValues[Matrix.MTRANS_X].coerceIn(vw - dw, 0f)
            matrixValues[Matrix.MTRANS_Y] = if (dh <= vh) (vh - dh) / 2f
            else matrixValues[Matrix.MTRANS_Y].coerceIn(vh - dh, 0f)
            matrix.setValues(matrixValues)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
            tracker.addMovement(event)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) stopFling()
            if (!scaleDetector.isInProgress && currentScale <= minScale * 1.05f) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pagerMode = 0
                        downX = event.x
                        downY = event.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (pagerMode == 0) {
                            val dx = event.x - downX
                            val dy = event.y - downY
                            if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                                pagerMode = 1
                            } else if (Math.abs(dy) > touchSlop) {
                                pagerMode = 2
                            }
                        }
                        if (pagerMode == 1) {
                            onPagerDrag?.invoke(event.x - downX)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (pagerMode == 1) {
                            tracker.computeCurrentVelocity(1000)
                            onPagerRelease?.invoke(event.x - downX, tracker.xVelocity)
                            pagerMode = 0
                            return true
                        }
                        pagerMode = 0
                    }
                }
            }
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            return true
        }
    }
}

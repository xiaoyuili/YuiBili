package com.yuilittle.bili

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 原生 Canvas 高性能弹幕叠加层：
 * - 单 View 绘制，每条活动弹幕在准入时预光栅化为带描边离屏位图，onDraw 每帧仅做 `canvas.drawBitmap`
 *   （避免每帧 140 次 Skia 文字轮廓描边 + 填充，显著提升低端设备帧率）；
 * - 使用 Choreographer 的 VSYNC `frameTimeNanos` 做单调连续浮点时钟推进，切倍速（长按 2x 快进/松手恢复）
 *   只改变当前时刻起的速度斜率、绝不改写历史基准，彻底消除闪动与松手回退；
 * - 滚动（右→左 / 逆向）、顶部固定、底部固定独立轨道防碰撞调度。
 */
internal class DanmakuOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    @Suppress("DEPRECATION")
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        typeface = fillPaint.typeface
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glyphBounds = RectF()

    private var settings = DanmakuSettingsStore.Snapshot()
    private var isFullscreenMode = false

    // 当前活动分段数据（已按 progressMs 升序）
    private var segmentItems: List<DanmakuItem> = emptyList()
    private var currentSegmentIndex = 0
    private var nextEmitIndex = 0

    // 连续浮点媒体时钟（与 VSYNC 纳秒对齐，切倍速时不产生任何位置回退或跳变）
    private var playing = false
    private var playbackSpeed = 1f
    private var currentMediaMs = 0.0
    private var lastFrameNanos = 0L
    // 软校正速率偏置（用于把极小漂移在 300ms 内平滑吸收，绝不硬跳时钟）
    private var syncTargetMediaMs = 0.0
    private var syncTargetUptimeMs = 0L

    // 当前同屏活动弹幕
    private val activeScroll = ArrayList<ActiveDanmaku>(64)
    private val activeTop = ArrayList<ActiveDanmaku>(16)
    private val activeBottom = ArrayList<ActiveDanmaku>(16)
    private val activePool = ArrayDeque<ActiveDanmaku>(96)

    // 跨段去重小窗口（防 360000ms 边界重复发射）
    private val recentEmittedIds = LinkedHashSet<Long>(128)

    // 有界文本宽度测量缓存
    private val measureCache = object : LinkedHashMap<String, Float>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean =
            size > MAX_MEASURE_CACHE
    }

    // 有界弹幕预渲染位图缓存（仅缓存活动及最近使用，受总字节上限约束，避免反复描边光栅化）
    private var cachedBitmapBytes = 0
    private val bitmapCache = object : LinkedHashMap<String, CachedGlyph>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedGlyph>?): Boolean {
            if (size <= MAX_GLYPH_CACHE_ENTRIES && cachedBitmapBytes <= MAX_GLYPH_CACHE_BYTES) {
                return false
            }
            eldest?.value?.let { cachedBitmapBytes = (cachedBitmapBytes - it.bytes).coerceAtLeast(0) }
            return true
        }
    }

    private data class CachedGlyph(
        val bitmap: Bitmap,
        val baselineOffsetY: Float,
        val padX: Float,
        val textSizePx: Float,
        val bytes: Int
    )

    private var frameScheduled = false
    private var lastViewportW = 0
    private var lastViewportH = 0
    private var appliedFullscreen = false
    private var viewportScale = 1f

    // 每秒准入限流计数
    private var rateWindowSec = -1L
    private var rateWindowEmitted = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!playing || !settings.enabled || !isAttachedToWindow) return

            val dtMs = if (lastFrameNanos == 0L) {
                0.0
            } else {
                // 单帧时间步长限制在 0..50ms，避免后台唤醒或掉帧产生瞬移
                ((frameTimeNanos - lastFrameNanos) / 1_000_000.0).coerceIn(0.0, 50.0)
            }
            lastFrameNanos = frameTimeNanos

            // 按当前倍速连续推进浮点媒体时钟，不改写过去时间，彻底避免长按 2x 闪烁与松手回退
            var advanceMs = dtMs * playbackSpeed.toDouble()
            if (syncTargetUptimeMs > 0L) {
                val nowUptime = SystemClock.elapsedRealtime()
                val expectedPlayerMs = syncTargetMediaMs + (nowUptime - syncTargetUptimeMs) * playbackSpeed.toDouble()
                val err = expectedPlayerMs - (currentMediaMs + advanceMs)
                // 只在前向或极小正负误差下温柔追平（单帧最多修正 ±12% 速度，绝不倒走）
                if (abs(err) in 40.0..CLOCK_HARD_SYNC_MS.toDouble()) {
                    val correction = (err * 0.14).coerceIn(-advanceMs * 0.45, advanceMs * 0.65)
                    advanceMs = (advanceMs + correction).coerceAtLeast(0.0)
                }
            }
            currentMediaMs += advanceMs

            stepSimulation(currentMediaMs)
            invalidate()
            if (playing && settings.enabled && isAttachedToWindow) {
                scheduleFrame()
            }
        }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setFullscreenMode(fullscreen: Boolean) {
        if (isFullscreenMode == fullscreen) return
        isFullscreenMode = fullscreen
        invalidate()
    }

    fun applySettings(next: DanmakuSettingsStore.Snapshot) {
        val prev = settings
        settings = next
        if (!next.enabled) {
            clearActiveDanmaku()
            stopFrameLoop()
            visibility = GONE
            return
        }
        if (visibility != VISIBLE) visibility = VISIBLE

        val mediaMs = currentMediaMs

        // 类型 / 彩色屏蔽变化：立刻从屏幕上剔除被屏蔽项，其他弹幕保持不变
        if (prev.blockScroll != next.blockScroll ||
            prev.blockTop != next.blockTop ||
            prev.blockBottom != next.blockBottom ||
            prev.blockColor != next.blockColor
        ) {
            pruneBlockedActive(next)
        }

        val textScaleChanged = abs(prev.textScale - next.textScale) > 0.001f
        val speedChanged = abs(prev.speedScale - next.speedScale) > 0.001f
        val areaChanged = abs(prev.areaFraction - next.areaFraction) > 0.001f

        if (textScaleChanged) {
            measureCache.clear()
            clearGlyphCache()
            reflowActiveForTextScale(mediaMs)
        } else if (speedChanged) {
            rebaseScrollSpeed(mediaMs, next.speedScale)
        }

        if (areaChanged || textScaleChanged) {
            pruneOutOfAreaActive()
        }

        if (playing && isAttachedToWindow) scheduleFrame()
        invalidate()
    }

    fun setSegmentData(segmentIndex: Int, items: List<DanmakuItem>, currentPositionMs: Long) {
        currentSegmentIndex = segmentIndex
        segmentItems = items
        val startTarget = (currentPositionMs - LATE_WINDOW_MS).coerceAtLeast(0L).toInt()
        nextEmitIndex = lowerBound(items, startTarget)
        if (playing && settings.enabled && isAttachedToWindow) scheduleFrame()
    }

    fun syncClock(positionMs: Long, isPlaying: Boolean, speed: Float) {
        val nowUptime = SystemClock.elapsedRealtime()
        val safeSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f

        // 切倍速（如长按 2x 快进与松手恢复 1x）时，ExoPlayer 瞬时返回的 currentPosition 可能滞后 50~200ms；
        // 只更新播放速度和软同步目标，绝不把 currentMediaMs 硬拉回去，避免弹幕闪一下或倒退！
        playbackSpeed = safeSpeed
        syncTargetMediaMs = positionMs.toDouble()
        syncTargetUptimeMs = nowUptime

        val drift = positionMs.toDouble() - currentMediaMs
        if (!playing && activeScroll.isEmpty() && activeTop.isEmpty() && activeBottom.isEmpty()) {
            currentMediaMs = positionMs.toDouble()
        } else if (abs(drift) > CLOCK_HARD_SYNC_MS) {
            // 仅在真正发生大跨度跳变（外部 seek / 重播 / 分段切跳超过 1.2 秒）时才硬对齐
            currentMediaMs = positionMs.toDouble()
            if (drift < -1500.0) {
                clearActiveDanmaku()
                recentEmittedIds.clear()
                nextEmitIndex = lowerBound(segmentItems, positionMs.toInt())
            }
        }
        setPlaying(isPlaying)
    }

    fun setPlaying(isPlaying: Boolean) {
        if (playing == isPlaying) {
            if (playing && settings.enabled && isAttachedToWindow) scheduleFrame()
            return
        }
        playing = isPlaying
        lastFrameNanos = 0L
        if (playing && settings.enabled && isAttachedToWindow) {
            scheduleFrame()
        } else {
            stopFrameLoop()
            invalidate()
        }
    }

    fun onSeek(positionMs: Long) {
        currentMediaMs = positionMs.toDouble()
        syncTargetMediaMs = currentMediaMs
        syncTargetUptimeMs = SystemClock.elapsedRealtime()
        lastFrameNanos = 0L
        clearActiveDanmaku()
        recentEmittedIds.clear()
        rateWindowSec = -1L
        rateWindowEmitted = 0
        if (segmentItems.isNotEmpty()) {
            nextEmitIndex = lowerBound(segmentItems, positionMs.toInt())
        }
        invalidate()
    }

    fun clearForMediaChange() {
        playing = false
        segmentItems = emptyList()
        currentSegmentIndex = 0
        nextEmitIndex = 0
        currentMediaMs = 0.0
        syncTargetMediaMs = 0.0
        syncTargetUptimeMs = 0L
        lastFrameNanos = 0L
        recentEmittedIds.clear()
        rateWindowSec = -1L
        rateWindowEmitted = 0
        clearActiveDanmaku()
        clearGlyphCache()
        stopFrameLoop()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        if (playing && settings.enabled) scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        stopFrameLoop()
        super.onDetachedFromWindow()
    }

    private fun layoutActiveForViewport(checkCrowding: Boolean = false) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val changed = w != lastViewportW || h != lastViewportH || appliedFullscreen != isFullscreenMode
        if (!changed && !checkCrowding) return
        val topMargin = if (isFullscreenMode) dp(10f) else dp(6f)
        val bottomMargin = if (isFullscreenMode) dp(16f) else dp(8f)
        val naturalLine = naturalLineHeightPx()
        val occupied = 1 + maxOf(activeScroll.maxOfOrNull { it.lane } ?: -1,
            activeTop.maxOfOrNull { it.lane } ?: -1, activeBottom.maxOfOrNull { it.lane } ?: -1)
        val fit = viewportTextFit((h * settings.areaFraction - max(topMargin, bottomMargin)).coerceAtLeast(1f), naturalLine, occupied)
        // 收窄后只临时缩放仍在途的密集轨道；不丢弃它们，也不再往溢出轨道准入。
        if (!changed && (viewportScale == 1f || fit < 1f)) return
        val oldWidth = lastViewportW.takeIf { it > 0 } ?: w
        viewportScale = fit
        val line = computeLineHeightPx()
        val now = currentMediaMs
        val laneCounts = IntArray(occupied)
        for (d in activeScroll) laneCounts[d.lane]++
        fun place(list: ArrayList<ActiveDanmaku>, scrolling: Boolean, bottom: Boolean) {
            for (d in list) {
                val item = d.item ?: continue
                val newSize = resizedTextSize(d.laneSizePerPixel, computeTextSizePx(item.fontSize), w,
                    sharedLane = scrolling && laneCounts[d.lane] > 1)
                val newText = d.textWidth * (newSize / d.textSizePx)
                if (scrolling) {
                    val next = DanmakuTravel(d.currentX(now), d.speedPxPerMs).resized(oldWidth, d.textWidth, w, newText)
                    d.anchorX = next.left
                    d.speedPxPerMs = next.velocity
                    d.anchorMediaMs = now
                } else {
                    d.anchorX = (w - newText) * 0.5f
                }
                d.textWidth = newText
                d.textSizePx = newSize
                d.yBaseline = if (bottom) (h - bottomMargin - d.lane * line - line * 0.12f).coerceAtLeast(topMargin + newSize)
                    else topMargin + d.lane * line + newSize * 0.92f
            }
        }
        place(activeScroll, scrolling = true, bottom = false)
        place(activeTop, scrolling = false, bottom = false)
        place(activeBottom, scrolling = false, bottom = true)
        lastViewportW = w
        lastViewportH = h
        appliedFullscreen = isFullscreenMode
    }

    private fun scheduleFrame() {
        if (frameScheduled || !isAttachedToWindow) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        if (!frameScheduled) return
        frameScheduled = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun stepSimulation(mediaMs: Double) {
        val viewW = width
        val viewH = height
        if (viewW <= 0 || viewH <= 0) return

        layoutActiveForViewport()
        val before = activeScroll.size + activeTop.size + activeBottom.size
        sweepExpired(activeScroll, mediaMs, viewW)
        sweepExpired(activeTop, mediaMs, viewW)
        sweepExpired(activeBottom, mediaMs, viewW)
        if (viewportScale < 1f && before != activeScroll.size + activeTop.size + activeBottom.size) {
            layoutActiveForViewport(checkCrowding = true)
        }

        val list = segmentItems
        if (list.isEmpty() || nextEmitIndex >= list.size) return

        val mediaMsLong = mediaMs.toLong()
        val secBucket = mediaMsLong / 1000L
        if (secBucket != rateWindowSec) {
            rateWindowSec = secBucket
            rateWindowEmitted = 0
        }
        val maxPerSecond = if (isFullscreenMode) MAX_EMIT_PER_SEC_FULL else MAX_EMIT_PER_SEC_COMPACT
        val maxActive = if (isFullscreenMode) MAX_ACTIVE_FULL else MAX_ACTIVE_COMPACT

        var frameEmitted = 0
        while (nextEmitIndex < list.size) {
            val item = list[nextEmitIndex]
            if (item.progressMs > mediaMsLong) break
            nextEmitIndex++

            if (mediaMsLong - item.progressMs > LATE_WINDOW_MS) continue
            if (isBlockedBySettings(item, settings)) continue
            if (item.id > 0L && recentEmittedIds.contains(item.id)) continue

            val totalActive = activeScroll.size + activeTop.size + activeBottom.size
            if (totalActive >= maxActive || rateWindowEmitted >= maxPerSecond || frameEmitted >= MAX_EMIT_PER_FRAME) {
                continue
            }

            if (tryAdmit(item, mediaMs, viewW, viewH)) {
                frameEmitted++
                rateWindowEmitted++
                if (item.id > 0L) recordEmittedId(item.id)
            }
        }
    }

    private fun tryAdmit(item: DanmakuItem, mediaMs: Double, viewW: Int, viewH: Int): Boolean {
        val textSizePx = computeTextSizePx(item.fontSize)
        val lineHeight = computeLineHeightPx()
        val topMargin = if (isFullscreenMode) dp(10f) else dp(6f)
        val maxLanes = ((viewH * settings.areaFraction - topMargin) / naturalLineHeightPx()).toInt().coerceAtLeast(1)

        val textWidth = measureTextWidth(item.text, textSizePx)
        val isReverse = item.mode == DanmakuItem.MODE_SCROLL_REVERSE

        return when (item.mode) {
            DanmakuItem.MODE_TOP -> {
                val lane = findFixedLane(activeTop, maxLanes, mediaMs)
                if (lane < 0) return false
                val d = acquireActive()
                d.item = item
                d.lane = lane
                d.textSizePx = textSizePx
                d.textWidth = textWidth
                d.anchorMediaMs = mediaMs
                d.endMediaMs = mediaMs + FIXED_DURATION_MS
                d.anchorX = (viewW - textWidth) * 0.5f
                d.speedPxPerMs = 0f
                d.yBaseline = topMargin + lane * lineHeight + textSizePx * 0.92f
                d.reverse = false
                d.glyph = obtainGlyph(item, textSizePx, textWidth)
                activeTop.add(d)
                true
            }
            DanmakuItem.MODE_BOTTOM -> {
                val lane = findFixedLane(activeBottom, maxLanes, mediaMs)
                if (lane < 0) return false
                val bottomMargin = if (isFullscreenMode) dp(16f) else dp(8f)
                val d = acquireActive()
                d.item = item
                d.lane = lane
                d.textSizePx = textSizePx
                d.textWidth = textWidth
                d.anchorMediaMs = mediaMs
                d.endMediaMs = mediaMs + FIXED_DURATION_MS
                d.anchorX = (viewW - textWidth) * 0.5f
                d.speedPxPerMs = 0f
                d.yBaseline = (viewH - bottomMargin - lane * lineHeight - lineHeight * 0.12f)
                    .coerceAtLeast(topMargin + textSizePx)
                d.reverse = false
                d.glyph = obtainGlyph(item, textSizePx, textWidth)
                activeBottom.add(d)
                true
            }
            else -> {
                val durationMs = computeScrollDurationMs(viewW, textWidth, settings.speedScale)
                val totalDistance = viewW + textWidth
                val speed = totalDistance / durationMs.toFloat()
                val lane = findScrollLane(maxLanes, mediaMs, viewW, speed, isReverse)
                if (lane < 0) return false
                val d = acquireActive()
                d.item = item
                d.lane = lane
                d.textSizePx = textSizePx
                d.textWidth = textWidth
                d.anchorMediaMs = mediaMs
                d.endMediaMs = mediaMs + durationMs + 120.0
                d.anchorX = if (isReverse) -textWidth else viewW.toFloat()
                d.speedPxPerMs = if (isReverse) speed else -speed
                d.yBaseline = topMargin + lane * lineHeight + textSizePx * 0.92f
                d.reverse = isReverse
                d.glyph = obtainGlyph(item, textSizePx, textWidth)
                // 同轨间距在准入时已校验。这里只记录此刻的字号/窗宽，切窗不能累乘上一次缩放结果。
                for (other in activeScroll) if (other.lane == lane) other.laneSizePerPixel = other.textSizePx / viewW
                d.laneSizePerPixel = textSizePx / viewW
                activeScroll.add(d)
                true
            }
        }
    }

    /** 预光栅化单条带描边弹幕到紧凑 Bitmap；同文案/字号/颜色命中缓存零重建。 */
    private fun obtainGlyph(item: DanmakuItem, textSizePx: Float, textWidth: Float): CachedGlyph? {
        val sizeBucket = (textSizePx * 2f).toInt()
        val rgb = item.colorArgb and 0x00FFFFFF
        val key = "$sizeBucket|$rgb|${item.text}"
        bitmapCache[key]?.let { existing ->
            if (!existing.bitmap.isRecycled) return existing
            bitmapCache.remove(key)
        }
        val strokeW = max(dp(1.8f), textSizePx * 0.13f)
        val pad = ceil(strokeW * 0.8f + dp(1.5f))
        fillPaint.textSize = textSizePx
        val fm = fillPaint.fontMetrics
        val bmpW = ceil(textWidth + pad * 2f).toInt().coerceAtLeast(1)
        val bmpH = ceil((fm.descent - fm.ascent) + pad * 2f).toInt().coerceAtLeast(1)
        // 超宽异常长弹幕跳过离屏缓存，直接回退实时绘制，防大位图占内存
        if (bmpW > MAX_SINGLE_GLYPH_WIDTH || bmpH > MAX_SINGLE_GLYPH_HEIGHT) return null

        val bmp = try {
            Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        val c = Canvas(bmp)
        val baselineY = pad - fm.ascent
        strokePaint.textSize = textSizePx
        strokePaint.strokeWidth = strokeW
        strokePaint.color = if (isDarkColor(rgb)) 0xC8FFFFFF.toInt() else 0xC8000000.toInt()
        fillPaint.color = (0xFF shl 24) or rgb
        c.drawText(item.text, pad, baselineY, strokePaint)
        c.drawText(item.text, pad, baselineY, fillPaint)

        val bytes = bmp.allocationByteCount
        val glyph = CachedGlyph(bmp, baselineY, pad, textSizePx, bytes)
        bitmapCache[key] = glyph
        cachedBitmapBytes += bytes
        trimGlyphCacheIfNeeded()
        return glyph
    }

    private fun trimGlyphCacheIfNeeded() {
        if (bitmapCache.size <= MAX_GLYPH_CACHE_ENTRIES && cachedBitmapBytes <= MAX_GLYPH_CACHE_BYTES) return
        val iter = bitmapCache.entries.iterator()
        while (iter.hasNext() && (bitmapCache.size > MAX_GLYPH_CACHE_ENTRIES || cachedBitmapBytes > MAX_GLYPH_CACHE_BYTES)) {
            val evicted = iter.next()
            cachedBitmapBytes = (cachedBitmapBytes - evicted.value.bytes).coerceAtLeast(0)
            iter.remove()
        }
    }

    private fun clearGlyphCache() {
        bitmapCache.clear()
        cachedBitmapBytes = 0
    }

    private fun findFixedLane(list: ArrayList<ActiveDanmaku>, maxLanes: Int, mediaMs: Double): Int {
        for (lane in 0 until maxLanes) {
            var occupied = false
            for (i in 0 until list.size) {
                val cur = list[i]
                if (cur.lane == lane && cur.endMediaMs > mediaMs) {
                    occupied = true
                    break
                }
            }
            if (!occupied) return lane
        }
        return -1
    }

    private fun findScrollLane(
        maxLanes: Int,
        mediaMs: Double,
        viewW: Int,
        newSpeed: Float,
        reverse: Boolean
    ): Int {
        val gapPx = dp(18f)
        for (lane in 0 until maxLanes) {
            var collide = false
            for (i in 0 until activeScroll.size) {
                val cur = activeScroll[i]
                if (cur.lane != lane) continue
                if (cur.reverse != reverse) {
                    collide = true
                    break
                }
                val curX = cur.currentX(mediaMs)
                val curSpeedAbs = abs(cur.speedPxPerMs)
                if (!reverse) {
                    val tailX = curX + cur.textWidth
                    if (tailX + gapPx > viewW) {
                        collide = true
                        break
                    }
                    if (newSpeed > curSpeedAbs) {
                        val remainDist = curX + cur.textWidth
                        val curRemainMs = remainDist / max(curSpeedAbs, 0.001f)
                        val newHeadAtEnd = viewW - newSpeed * curRemainMs
                        if (newHeadAtEnd < gapPx) {
                            collide = true
                            break
                        }
                    }
                } else {
                    if (curX - gapPx < 0f) {
                        collide = true
                        break
                    }
                    if (newSpeed > curSpeedAbs) {
                        val remainDist = viewW - curX
                        val curRemainMs = remainDist / max(curSpeedAbs, 0.001f)
                        val newHeadAtEnd = newSpeed * curRemainMs
                        if (newHeadAtEnd > viewW - gapPx) {
                            collide = true
                            break
                        }
                    }
                }
            }
            if (!collide) return lane
        }
        return -1
    }

    private fun sweepExpired(list: ArrayList<ActiveDanmaku>, mediaMs: Double, viewW: Int) {
        var i = list.size - 1
        while (i >= 0) {
            val d = list[i]
            val expired = if (d.speedPxPerMs == 0f) {
                mediaMs >= d.endMediaMs
            } else {
                val x = d.currentX(mediaMs)
                if (d.reverse) x > viewW + 4f else x + d.textWidth < -4f
            }
            if (expired) {
                list.removeAt(i)
                recycleActive(d)
            }
            i--
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!settings.enabled) return
        layoutActiveForViewport()
        if (activeScroll.isEmpty() && activeTop.isEmpty() && activeBottom.isEmpty()) return

        val mediaMs = currentMediaMs
        val alphaInt = (settings.opacity * 255f).toInt().coerceIn(30, 255)
        val strokeAlpha = (alphaInt * 0.78f).toInt().coerceIn(24, 220)
        bitmapPaint.alpha = alphaInt

        drawList(canvas, activeScroll, mediaMs, alphaInt, strokeAlpha)
        drawList(canvas, activeTop, mediaMs, alphaInt, strokeAlpha)
        drawList(canvas, activeBottom, mediaMs, alphaInt, strokeAlpha)
    }

    private fun drawList(
        canvas: Canvas,
        list: ArrayList<ActiveDanmaku>,
        mediaMs: Double,
        alphaInt: Int,
        strokeAlpha: Int
    ) {
        for (i in 0 until list.size) {
            val d = list[i]
            val item = d.item ?: continue
            val x = d.currentX(mediaMs)
            val y = d.yBaseline
            val glyph = d.glyph
            if (glyph != null && !glyph.bitmap.isRecycled) {
                // 全屏动画/连续缩放期间复用在屏字形，不在主线程重新生成整屏位图。
                val scale = d.textSizePx / glyph.textSizePx
                val left = x - glyph.padX * scale
                val top = y - glyph.baselineOffsetY * scale
                glyphBounds.set(left, top, left + glyph.bitmap.width * scale, top + glyph.bitmap.height * scale)
                canvas.drawBitmap(glyph.bitmap, null, glyphBounds, bitmapPaint)
            } else {
                val size = d.textSizePx
                fillPaint.textSize = size
                strokePaint.textSize = size
                strokePaint.strokeWidth = max(dp(1.8f), size * 0.13f)
                val rgb = item.colorArgb and 0x00FFFFFF
                strokePaint.color = if (isDarkColor(rgb)) {
                    (strokeAlpha shl 24) or 0x00FFFFFF
                } else {
                    (strokeAlpha shl 24)
                }
                fillPaint.color = (alphaInt shl 24) or rgb
                canvas.drawText(item.text, x, y, strokePaint)
                canvas.drawText(item.text, x, y, fillPaint)
            }
        }
    }

    private fun isDarkColor(rgb: Int): Boolean {
        val r = (rgb ushr 16) and 0xFF
        val g = (rgb ushr 8) and 0xFF
        val b = rgb and 0xFF
        return (r * 299 + g * 587 + b * 114) < 55_000
    }

    private fun computeTextSizePx(rawFontSize: Int): Float {
        val fontFactor = when {
            rawFontSize <= 18 -> 0.84f
            rawFontSize >= 36 -> 1.22f
            else -> 1.0f
        }
        val baseSp = if (isFullscreenMode) 17.5f else 13.0f
        return baseSp * scaledDensity * settings.textScale * fontFactor * viewportScale
    }

    private fun computeLineHeightPx(): Float = naturalLineHeightPx() * viewportScale

    private fun naturalLineHeightPx(): Float {
        val baseSp = if (isFullscreenMode) 17.5f else 13.0f
        val basePx = baseSp * scaledDensity * settings.textScale
        return ceil(basePx * 1.28f)
    }

    private fun computeScrollDurationMs(viewW: Int, textWidth: Float, speedScale: Float): Long {
        val baseDuration = if (isFullscreenMode) 7_600f else 6_200f
        val lengthFactor = 1f + min(textWidth / max(viewW.toFloat(), 1f), 1.2f) * 0.28f
        return (baseDuration * lengthFactor / speedScale.coerceIn(0.6f, 1.6f)).toLong().coerceIn(3_000L, 14_000L)
    }

    private fun measureTextWidth(text: String, textSizePx: Float): Float {
        val sizeBucket = (textSizePx * 2f).toInt()
        val key = "$sizeBucket|$text"
        measureCache[key]?.let { return it }
        fillPaint.textSize = textSizePx
        val w = fillPaint.measureText(text)
        measureCache[key] = w
        return w
    }

    private fun rebaseScrollSpeed(mediaMs: Double, nextSpeedScale: Float) {
        val viewW = width.coerceAtLeast(1)
        for (i in 0 until activeScroll.size) {
            val d = activeScroll[i]
            val curX = d.currentX(mediaMs)
            val newDuration = computeScrollDurationMs(viewW, d.textWidth, nextSpeedScale)
            val newSpeed = (viewW + d.textWidth) / newDuration.toFloat()
            d.anchorX = curX
            d.anchorMediaMs = mediaMs
            d.speedPxPerMs = if (d.reverse) newSpeed else -newSpeed
            val remainDist = if (d.reverse) (viewW - curX) else (curX + d.textWidth)
            val remainMs = (remainDist / max(newSpeed, 0.001f)).toDouble().coerceAtLeast(0.0)
            d.endMediaMs = mediaMs + remainMs + 120.0
        }
    }

    private fun reflowActiveForTextScale(mediaMs: Double) {
        val viewW = width.coerceAtLeast(1)
        val viewH = height.coerceAtLeast(1)
        val lineHeight = computeLineHeightPx()
        val topMargin = if (isFullscreenMode) dp(10f) else dp(6f)
        val bottomMargin = if (isFullscreenMode) dp(16f) else dp(8f)

        for (i in 0 until activeScroll.size) {
            val d = activeScroll[i]
            val item = d.item ?: continue
            val curX = d.currentX(mediaMs)
            val newSize = computeTextSizePx(item.fontSize)
            val newWidth = measureTextWidth(item.text, newSize)
            val newDuration = computeScrollDurationMs(viewW, newWidth, settings.speedScale)
            val newSpeed = (viewW + newWidth) / newDuration.toFloat()
            d.textSizePx = newSize
            d.textWidth = newWidth
            d.laneSizePerPixel = newSize / viewW
            d.anchorX = curX
            d.anchorMediaMs = mediaMs
            d.speedPxPerMs = if (d.reverse) newSpeed else -newSpeed
            d.yBaseline = topMargin + d.lane * lineHeight + newSize * 0.92f
            val remainDist = if (d.reverse) (viewW - curX) else (curX + newWidth)
            val remainMs = (remainDist / max(newSpeed, 0.001f)).toDouble().coerceAtLeast(0.0)
            d.endMediaMs = mediaMs + remainMs + 120.0
            d.glyph = obtainGlyph(item, newSize, newWidth)
        }

        for (i in 0 until activeTop.size) {
            val d = activeTop[i]
            val item = d.item ?: continue
            val newSize = computeTextSizePx(item.fontSize)
            val newWidth = measureTextWidth(item.text, newSize)
            d.textSizePx = newSize
            d.textWidth = newWidth
            d.anchorX = (viewW - newWidth) * 0.5f
            d.yBaseline = topMargin + d.lane * lineHeight + newSize * 0.92f
            d.glyph = obtainGlyph(item, newSize, newWidth)
        }

        for (i in 0 until activeBottom.size) {
            val d = activeBottom[i]
            val item = d.item ?: continue
            val newSize = computeTextSizePx(item.fontSize)
            val newWidth = measureTextWidth(item.text, newSize)
            d.textSizePx = newSize
            d.textWidth = newWidth
            d.anchorX = (viewW - newWidth) * 0.5f
            d.yBaseline = (viewH - bottomMargin - d.lane * lineHeight - lineHeight * 0.12f)
                .coerceAtLeast(topMargin + newSize)
            d.glyph = obtainGlyph(item, newSize, newWidth)
        }
    }

    private fun pruneOutOfAreaActive() {
        val viewH = height
        if (viewH <= 0) return
        val lineHeight = computeLineHeightPx()
        val topMargin = if (isFullscreenMode) dp(10f) else dp(6f)
        val areaBottom = (viewH * settings.areaFraction).coerceAtLeast(topMargin + lineHeight)
        val maxLanes = ((areaBottom - topMargin) / lineHeight).toInt().coerceAtLeast(1)

        pruneExceedingLane(activeScroll, maxLanes)
        pruneExceedingLane(activeTop, maxLanes)
        pruneExceedingLane(activeBottom, maxLanes)
    }

    private fun pruneExceedingLane(list: ArrayList<ActiveDanmaku>, maxLanes: Int) {
        var i = list.size - 1
        while (i >= 0) {
            if (list[i].lane >= maxLanes) {
                recycleActive(list.removeAt(i))
            }
            i--
        }
    }

    private fun pruneBlockedActive(s: DanmakuSettingsStore.Snapshot) {
        pruneList(activeScroll, s)
        pruneList(activeTop, s)
        pruneList(activeBottom, s)
    }

    private fun pruneList(list: ArrayList<ActiveDanmaku>, s: DanmakuSettingsStore.Snapshot) {
        var i = list.size - 1
        while (i >= 0) {
            val item = list[i].item
            if (item == null || isBlockedBySettings(item, s)) {
                recycleActive(list.removeAt(i))
            }
            i--
        }
    }

    private fun isBlockedBySettings(item: DanmakuItem, s: DanmakuSettingsStore.Snapshot): Boolean {
        if (s.blockColor && item.isColorDanmaku) return true
        return when (item.mode) {
            DanmakuItem.MODE_TOP -> s.blockTop
            DanmakuItem.MODE_BOTTOM -> s.blockBottom
            DanmakuItem.MODE_SCROLL_NORMAL,
            DanmakuItem.MODE_SCROLL_ALT_2,
            DanmakuItem.MODE_SCROLL_ALT_3,
            DanmakuItem.MODE_SCROLL_REVERSE -> s.blockScroll
            else -> true
        }
    }

    private fun clearActiveDanmaku() {
        viewportScale = 1f
        for (i in 0 until activeScroll.size) recycleActive(activeScroll[i])
        for (i in 0 until activeTop.size) recycleActive(activeTop[i])
        for (i in 0 until activeBottom.size) recycleActive(activeBottom[i])
        activeScroll.clear()
        activeTop.clear()
        activeBottom.clear()
    }

    private fun acquireActive(): ActiveDanmaku = activePool.removeFirstOrNull() ?: ActiveDanmaku()

    private fun recycleActive(d: ActiveDanmaku) {
        d.item = null
        d.glyph = null
        if (activePool.size < 120) activePool.addLast(d)
    }

    private fun recordEmittedId(id: Long) {
        recentEmittedIds.add(id)
        if (recentEmittedIds.size > 240) {
            val iter = recentEmittedIds.iterator()
            repeat(60) {
                if (iter.hasNext()) {
                    iter.next()
                    iter.remove()
                }
            }
        }
    }

    private fun dp(v: Float): Float = v * density

    private class ActiveDanmaku {
        var item: DanmakuItem? = null
        var glyph: CachedGlyph? = null
        var lane: Int = 0
        var laneSizePerPixel = 0f
        var textSizePx: Float = 0f
        var textWidth: Float = 0f
        var anchorMediaMs: Double = 0.0
        var endMediaMs: Double = 0.0
        var anchorX: Float = 0f
        var speedPxPerMs: Float = 0f
        var yBaseline: Float = 0f
        var reverse: Boolean = false

        fun currentX(mediaMs: Double): Float =
            anchorX + speedPxPerMs * (mediaMs - anchorMediaMs).toFloat()
    }

    companion object {
        private const val FIXED_DURATION_MS = 4_000.0
        private const val LATE_WINDOW_MS = 400L
        private const val CLOCK_HARD_SYNC_MS = 1_200L
        private const val MAX_MEASURE_CACHE = 400
        private const val MAX_GLYPH_CACHE_ENTRIES = 96
        private const val MAX_GLYPH_CACHE_BYTES = 6 * 1024 * 1024
        private const val MAX_SINGLE_GLYPH_WIDTH = 1600
        private const val MAX_SINGLE_GLYPH_HEIGHT = 160
        private const val MAX_EMIT_PER_FRAME = 4
        private const val MAX_EMIT_PER_SEC_FULL = 12
        private const val MAX_EMIT_PER_SEC_COMPACT = 8
        private const val MAX_ACTIVE_FULL = 64
        private const val MAX_ACTIVE_COMPACT = 32

        internal fun lowerBound(items: List<DanmakuItem>, targetMs: Int): Int {
            var lo = 0
            var hi = items.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (items[mid].progressMs < targetMs) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}

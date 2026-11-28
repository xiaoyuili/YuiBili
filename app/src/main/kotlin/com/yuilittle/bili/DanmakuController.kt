package com.yuilittle.bili

import android.os.SystemClock

/**
 * 弹幕会话控制器（UGC / PGC 播放页共用）：
 * - 负责绑定当前媒体身份 (cid, aid, authEpoch)、按全局播放时钟调度当前段与临界预取；
 * - 处理 Seek、切集、同 cid 换画质、登录态变化、服务端关闭弹幕与暂停/恢复；
 * - 通过 generation 防串台，所有旧请求在切集或销毁时统一取消。
 */
internal class DanmakuController(
    private val view: DanmakuOverlayView
) {
    private var mediaGeneration = 0
    private var cid = 0L
    private var aid = 0L
    private var authEpoch = 0L
    private var referer = "https://www.bilibili.com/"
    private var localMode = false

    private var segmentDurationMs = DanmakuParser.DEFAULT_SEGMENT_MS
    private var knownTotalSegments = 0
    private var serverClosed = false
    private var lastKnownDurationMs = 0L
    private var lastPositionMs = 0L
    private var lastPlaybackSpeed = 1f
    private var isPlaying = false

    private var metaRequest: BiliRequest? = null
    private val segmentRequests = HashMap<Int, BiliRequest>()
    private val deliveredSegments = object : LinkedHashMap<Int, BiliDanmakuRepository.SegmentData>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BiliDanmakuRepository.SegmentData>?) = size > 2
    }
    private var metaRequested = false
    private var xmlWholePoolDelivered = false

    private var activeSegmentIndex = 0
    private var lastSeekAtMs = 0L
    private var lastEnabled = DanmakuSettingsStore.snapshot().enabled

    private val settingsListener: (DanmakuSettingsStore.Snapshot) -> Unit = { snapshot ->
        applySettings(snapshot)
    }

    init {
        DanmakuSettingsStore.addListener(settingsListener)
        view.applySettings(DanmakuSettingsStore.snapshot())
    }

    /**
     * 绑定媒体身份。若 cid 与 authEpoch 未变（如同 cid 换画质），保留已加载弹幕缓存不重置；
     * 若切集/切换 cid/登录态变更，则递增 generation、取消旧请求并清空画面。
     */
    fun bindMedia(
        cid: Long,
        aid: Long,
        referer: String,
        durationMs: Long = 0L,
        isLocalMode: Boolean = false,
        startPositionMs: Long = 0L
    ) {
        this.localMode = isLocalMode
        if (isLocalMode || cid <= 0L) {
            clearMedia()
            return
        }
        val epoch = BiliDanmakuRepository.currentAuthEpoch()
        if (this.cid == cid && this.authEpoch == epoch) {
            if (aid > 0L) this.aid = aid
            if (durationMs > 0L) this.lastKnownDurationMs = durationMs
            return
        }
        cancelAllRequests()
        mediaGeneration++
        this.cid = cid
        this.aid = aid
        this.authEpoch = epoch
        this.referer = referer.ifBlank { "https://www.bilibili.com/" }
        this.segmentDurationMs = DanmakuParser.DEFAULT_SEGMENT_MS
        this.knownTotalSegments = 0
        this.metaRequested = false
        this.serverClosed = false
        this.lastKnownDurationMs = durationMs.coerceAtLeast(0L)
        this.activeSegmentIndex = 0
        this.lastPositionMs = startPositionMs.coerceAtLeast(0L)
        this.isPlaying = false
        this.deliveredSegments.clear()
        this.xmlWholePoolDelivered = false
        view.clearForMediaChange()

        val settings = DanmakuSettingsStore.snapshot()
        view.applySettings(settings)
        if (!settings.enabled) return

        requestMetaIfNeeded()
        ensureSegmentsForPosition(lastPositionMs, lastKnownDurationMs, lastPlaybackSpeed)
    }

    private fun requestMetaIfNeeded() {
        if (metaRequested || cid <= 0L || serverClosed) return
        metaRequested = true
        val gen = mediaGeneration
        metaRequest = BiliDanmakuRepository.fetchViewMeta(cid, aid, authEpoch, referer) { meta ->
            if (gen != mediaGeneration) return@fetchViewMeta
            metaRequest = null
            if (meta == null) return@fetchViewMeta
            if (meta.isClosed) {
                serverClosed = true
                cancelAllRequests()
                view.clearForMediaChange()
                return@fetchViewMeta
            }
            if (meta.pageSizeMs in DanmakuParser.MIN_VALID_PAGE_SIZE_MS..DanmakuParser.MAX_VALID_PAGE_SIZE_MS) {
                segmentDurationMs = meta.pageSizeMs
            }
            if (meta.totalSegments > 0) {
                knownTotalSegments = meta.totalSegments
            }
        }
    }

    /** 播放页在 250ms 进度刷新与状态变化时调用（传入全局视频时钟，支持多段 DURL）。 */
    fun onPlaybackTick(
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        speed: Float
    ) {
        val safePos = positionMs.coerceAtLeast(0L)
        val safeSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        lastPositionMs = safePos
        if (durationMs > 0L) lastKnownDurationMs = durationMs
        lastPlaybackSpeed = safeSpeed
        isPlaying = playing

        if (localMode || cid <= 0L) {
            view.setPlaying(false)
            return
        }

        // 检测登录/退出切换
        val epoch = BiliDanmakuRepository.currentAuthEpoch()
        if (epoch != authEpoch) {
            bindMedia(cid, aid, referer, lastKnownDurationMs, startPositionMs = safePos)
            return
        }

        val settings = DanmakuSettingsStore.snapshot()
        if (!settings.enabled || serverClosed) {
            view.setPlaying(false)
            return
        }

        requestMetaIfNeeded()
        view.syncClock(safePos, playing, safeSpeed)
        ensureSegmentsForPosition(safePos, lastKnownDurationMs, safeSpeed)
    }

    /** 用户拖动进度条或手势 Seek 松手后调用。 */
    fun onSeeked(positionMs: Long) {
        val safePos = positionMs.coerceAtLeast(0L)
        lastPositionMs = safePos
        lastSeekAtMs = SystemClock.elapsedRealtime()
        view.onSeek(safePos)
        if (localMode || cid <= 0L || serverClosed || !DanmakuSettingsStore.snapshot().enabled) return

        val targetSeg = segmentIndexOf(safePos)
        // 取消不再需要的其他分段在途请求
        val it = segmentRequests.entries.iterator()
        while (it.hasNext()) {
            val (segIdx, req) = it.next()
            if (segIdx != targetSeg && segIdx != targetSeg + 1) {
                req.cancel()
                it.remove()
            }
        }
        activeSegmentIndex = 0
        ensureSegmentsForPosition(safePos, lastKnownDurationMs, lastPlaybackSpeed)
    }

    /** 切后台（onStop）：停止预取与在途请求，保留有界内存缓存，暂停渲染。 */
    fun freezeClock() {
        isPlaying = false
        view.setPlaying(false)
    }

    fun onStop() {
        cancelAllRequests()
        freezeClock()
    }

    /** 销毁页面（onDestroy）：解绑监听与所有引用。 */
    fun onDestroy() {
        DanmakuSettingsStore.removeListener(settingsListener)
        DanmakuSettingsStore.flushPersist()
        cancelAllRequests()
        deliveredSegments.clear()
        view.clearForMediaChange()
    }

    fun clearMedia() {
        cancelAllRequests()
        mediaGeneration++
        cid = 0L
        aid = 0L
        serverClosed = false
        activeSegmentIndex = 0
        lastPositionMs = 0L
        lastKnownDurationMs = 0L
        isPlaying = false
        deliveredSegments.clear()
        xmlWholePoolDelivered = false
        view.clearForMediaChange()
    }

    private fun applySettings(snapshot: DanmakuSettingsStore.Snapshot) {
        val turnedOn = !lastEnabled && snapshot.enabled
        lastEnabled = snapshot.enabled
        view.applySettings(snapshot)
        if (!snapshot.enabled) {
            cancelAllRequests()
            view.setPlaying(false)
            return
        }
        if (turnedOn && !localMode && cid > 0L && !serverClosed) {
            view.onSeek(lastPositionMs)
            view.syncClock(lastPositionMs, isPlaying, lastPlaybackSpeed)
            ensureSegmentsForPosition(lastPositionMs, lastKnownDurationMs, lastPlaybackSpeed)
        }
    }

    private fun ensureSegmentsForPosition(positionMs: Long, durationMs: Long, speed: Float) {
        if (localMode || cid <= 0L || serverClosed) return
        val segIdx = segmentIndexOf(positionMs)
        val total = resolveTotalSegments(durationMs)
        if (total > 0 && segIdx > total) return

        // 1. 若当前段已在本地持有或命中仓库缓存，立刻交付给渲染层
        if (xmlWholePoolDelivered) {
            // XML 整份池已交付，按时间二分查询即可，不重复拉分段
            return
        }
        if (activeSegmentIndex != segIdx) {
            val localSeg = deliveredSegments[segIdx]
                ?: BiliDanmakuRepository.getCachedSegment(cid, segIdx, authEpoch)?.also {
                    deliveredSegments[segIdx] = it
                }
            if (localSeg != null) {
                acceptSegment(localSeg)
                if (serverClosed || xmlWholePoolDelivered) return
            } else {
                requestSegmentIfNeeded(segIdx, isPrefetch = false, pinCurrent = segIdx)
            }
        }

        // 2. 临近段尾（默认 45s × 倍速，范围 [30s, 90s]）且存在下一段时预取
        val segEndMs = segIdx.toLong() * segmentDurationMs
        val prefetchLeadMs = (45_000f * speed).toLong().coerceIn(30_000L, 90_000L)
        val remainingInSeg = segEndMs - positionMs
        val nextSeg = segIdx + 1
        val hasNext = when {
            total > 0 -> nextSeg <= total
            durationMs > 0L -> segEndMs < durationMs - 1000L
            else -> false
        }
        if (hasNext && remainingInSeg <= prefetchLeadMs && !BiliDanmakuRepository.isInRiskCooldown()) {
            if (!deliveredSegments.containsKey(nextSeg) &&
                BiliDanmakuRepository.getCachedSegment(cid, nextSeg, authEpoch) == null
            ) {
                requestSegmentIfNeeded(nextSeg, isPrefetch = true, pinCurrent = segIdx)
            }
        }
    }

    private fun requestSegmentIfNeeded(segmentIndex: Int, isPrefetch: Boolean, pinCurrent: Int) {
        if (segmentRequests.containsKey(segmentIndex)) return
        if (BiliDanmakuRepository.isSegmentInFailureCooldown(cid, segmentIndex, authEpoch)) return
        if (BiliDanmakuRepository.isInRiskCooldown()) return

        val gen = mediaGeneration
        val reqCid = cid
        val reqEpoch = authEpoch
        val req = BiliDanmakuRepository.fetchSegment(
            cid = reqCid,
            aid = aid,
            segmentIndex = segmentIndex,
            authEpoch = reqEpoch,
            referer = referer,
            isPrefetch = isPrefetch,
            pinCurrentSegment = pinCurrent
        ) { outcome ->
            if (gen != mediaGeneration || reqCid != cid || reqEpoch != authEpoch) return@fetchSegment
            segmentRequests.remove(segmentIndex)
            when (outcome) {
                is BiliDanmakuRepository.FetchOutcome.Ready -> acceptSegment(outcome.segment)
                is BiliDanmakuRepository.FetchOutcome.Empty -> acceptSegment(outcome.segment)
                BiliDanmakuRepository.FetchOutcome.Closed -> {
                    serverClosed = true
                    cancelAllRequests()
                    view.clearForMediaChange()
                }
                is BiliDanmakuRepository.FetchOutcome.RiskLimited,
                is BiliDanmakuRepository.FetchOutcome.AuthOrPermission,
                is BiliDanmakuRepository.FetchOutcome.Failed -> {
                    // 静默降级：仓库层已记录冷却，绝不影响正常视频播放
                }
            }
        }
        segmentRequests[segmentIndex] = req
    }

    private fun acceptSegment(segment: BiliDanmakuRepository.SegmentData) {
        if (segment.closed) {
            serverClosed = true
            cancelAllRequests()
            view.clearForMediaChange()
            return
        }
        if (serverClosed || xmlWholePoolDelivered) return
        deliveredSegments[segment.segmentIndex] = segment
        if (segment.source == BiliDanmakuRepository.SourceKind.XML_FALLBACK) {
            xmlWholePoolDelivered = true
            segmentRequests.values.forEach { it.cancel() }
            segmentRequests.clear()
        } else if (segment.segmentIndex != segmentIndexOf(lastPositionMs)) return
        activeSegmentIndex = segment.segmentIndex
        // 晚到分段从当前播放位置继续，避免一口气倾泻几百条已过期弹幕
        view.setSegmentData(
            segmentIndex = segment.segmentIndex,
            items = segment.items,
            currentPositionMs = lastPositionMs
        )
    }

    private fun segmentIndexOf(positionMs: Long): Int {
        val dur = segmentDurationMs.coerceAtLeast(DanmakuParser.MIN_VALID_PAGE_SIZE_MS)
        // 避免视频正好停在整段边界（如 360000ms）时误拉下一段
        val effectivePos = if (lastKnownDurationMs > 0L && positionMs >= lastKnownDurationMs) {
            (lastKnownDurationMs - 1L).coerceAtLeast(0L)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        return (effectivePos / dur).toInt() + 1
    }

    private fun resolveTotalSegments(durationMs: Long): Int {
        val byDuration = if (durationMs > 0L) {
            (((durationMs - 1L).coerceAtLeast(0L)) / segmentDurationMs).toInt() + 1
        } else 0
        return when {
            knownTotalSegments > 0 && byDuration > 0 -> minOf(knownTotalSegments, byDuration)
            byDuration > 0 -> byDuration
            knownTotalSegments > 0 -> knownTotalSegments
            else -> 0
        }
    }

    private fun cancelAllRequests() {
        if (metaRequest != null) metaRequested = false
        metaRequest?.cancel()
        metaRequest = null
        segmentRequests.values.forEach { it.cancel() }
        segmentRequests.clear()
    }
}

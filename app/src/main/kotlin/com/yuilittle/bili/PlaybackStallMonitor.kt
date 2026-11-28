package com.yuilittle.bili

internal class PlaybackStallMonitor(
    private val timeoutMs: Long,
    private val maximumWaitMs: Long = timeoutMs * 3
) {
    private var startedAtMs = -1L
    private var progressedAtMs = -1L
    private var bufferedFrontierMs = 0L
    val started: Boolean get() = startedAtMs >= 0L

    fun reset() {
        startedAtMs = -1L
        progressedAtMs = -1L
        bufferedFrontierMs = 0L
    }

    fun observe(nowMs: Long, bufferedPositionMs: Long) {
        if (!started) {
            startedAtMs = nowMs
            progressedAtMs = nowMs
            bufferedFrontierMs = bufferedPositionMs
        } else if (bufferedPositionMs > bufferedFrontierMs) {
            bufferedFrontierMs = bufferedPositionMs
            progressedAtMs = nowMs
        }
    }

    fun remainingMs(nowMs: Long): Long = if (!started) timeoutMs else
        minOf(timeoutMs - (nowMs - progressedAtMs), maximumWaitMs - (nowMs - startedAtMs)).coerceAtLeast(0L)
}

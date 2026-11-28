package com.yuilittle.bili

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

internal class FallbackMediaReader(
    private val candidateCount: Int,
    private val connect: (index: Int, position: Long, length: Long) -> Source
) {
    interface Source {
        val length: Long
        fun read(buffer: ByteArray, offset: Int, length: Int): Int
        fun close()
    }

    private var source: Source? = null
    private var preferredIndex = 0
    private var currentIndex = 0
    private var startIndex = 0
    private var attempts = 0
    private var startPosition = 0L
    private var delivered = 0L
    private var totalLength = -1L
    @Volatile private var closed = true

    fun open(position: Long, length: Long): Long {
        close()
        closed = false
        startPosition = position
        delivered = 0L
        totalLength = length
        startIndex = preferredIndex
        attempts = 0
        source = connectNext()
        if (totalLength < 0L) totalLength = source!!.length
        return totalLength
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        ensureActive()
        if (totalLength >= 0L && delivered >= totalLength) return -1
        while (true) {
            val active = source ?: throw IOException("媒体源未打开")
            try {
                val wanted = if (totalLength < 0L) length else minOf(length.toLong(), totalLength - delivered).toInt()
                val count = active.read(buffer, offset, wanted)
                ensureActive()
                if (count < 0 && totalLength >= 0L && delivered < totalLength) throw EOFException("媒体响应未完整结束")
                if (count > 0) {
                    delivered += count
                    preferredIndex = currentIndex
                }
                return count
            } catch (error: IOException) {
                if (cancelled(error)) throw error
                closeSource()
                source = connectNext(error)
            }
        }
    }

    private fun connectNext(previous: IOException? = null): Source {
        var failure = previous
        while (attempts < candidateCount) {
            ensureActive()
            currentIndex = (startIndex + attempts++) % candidateCount
            try {
                val remaining = if (totalLength < 0L) -1L else totalLength - delivered
                val next = connect(currentIndex, startPosition + delivered, remaining)
                if (closed || Thread.currentThread().isInterrupted) {
                    runCatching { next.close() }
                    throw InterruptedIOException("媒体读取已取消")
                }
                return next
            } catch (error: IOException) {
                if (cancelled(error)) throw error
                failure = error
            }
        }
        throw failure ?: IOException("没有可用的媒体候选地址")
    }

    private fun ensureActive() {
        if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException("媒体读取已取消")
    }

    private fun cancelled(error: IOException): Boolean {
        if (closed || Thread.currentThread().isInterrupted) return true
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is InterruptedIOException && cause !is SocketTimeoutException) return true
            val next = cause.cause
            if (next === cause) break
            cause = next
        }
        return false
    }

    private fun closeSource() {
        val old = source
        source = null
        runCatching { old?.close() }
    }

    fun close() {
        closed = true
        closeSource()
    }
}

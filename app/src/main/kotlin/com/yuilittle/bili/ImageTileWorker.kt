package com.yuilittle.bili

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun imageTileSampleSize(width: Int): Int {
    var sample = 1
    while (width.toLong() / sample > 1440L) sample *= 2
    return sample
}

internal class ImageTileWorker<T>(
    private val open: (String) -> Source<T>?,
    private val deliver: (Runnable) -> Unit,
    private val onSource: (Int, Int) -> Unit,
    private val onTile: (Int, T?) -> Unit,
    private val executor: ExecutorService = ThreadPoolExecutor(
        1, 1, 10L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(1)
    ).apply { allowCoreThreadTimeOut(true) }
) {
    interface Source<T> {
        val width: Int
        val height: Int
        fun decode(index: Int): T?
        fun close()
    }

    private val lock = Any()
    private var generation = 0L
    private var key: String? = null
    private var closed = false
    private var scheduled = false
    private val pending = LinkedHashSet<Int>()
    private val inFlight = HashSet<Int>()
    private var awaitingDelivery = 0
    private var source: Source<T>? = null
    private var sourceGeneration = -1L

    fun bind(next: String?) = synchronized(lock) {
        if (closed) return@synchronized
        generation++
        key = next
        pending.clear()
        inFlight.clear()
        awaitingDelivery = 0
        scheduleLocked()
    }

    fun request(indices: List<Int>) = synchronized(lock) {
        if (closed || key == null) return@synchronized
        val wanted = indices.take(32).toSet()
        pending.filter { it !in wanted }.forEach { pending.remove(it); inFlight.remove(it) }
        for (index in indices.take(32)) if (inFlight.add(index)) pending.add(index)
        if (pending.isNotEmpty()) scheduleLocked()
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        generation++
        pending.clear()
        inFlight.clear()
        awaitingDelivery = 0
        scheduleLocked()
    }

    private fun scheduleLocked() {
        if (scheduled) return
        scheduled = true
        executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val action = synchronized(lock) {
                when {
                    closed -> Action(generation, null, -1, true)
                    sourceGeneration != generation -> Action(generation, key, -1, false)
                    pending.isNotEmpty() && awaitingDelivery < 2 -> {
                        awaitingDelivery++
                        Action(generation, key, pending.first().also(pending::remove), false)
                    }
                    else -> { scheduled = false; return }
                }
            }
            if (action.close) {
                releaseSource()
                executor.shutdown()
                return
            }
            if (action.generation != sourceGeneration) {
                releaseSource()
                val opened = try { action.key?.let(open) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                source = opened
                sourceGeneration = action.generation
                val current = synchronized(lock) { !closed && generation == action.generation }
                if (!current) { releaseSource(); continue }
                if (opened != null) deliver(Runnable {
                    if (isCurrent(action.generation)) onSource(opened.width, opened.height)
                })
            } else {
                val tile = try { source?.decode(action.index) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                deliver(Runnable {
                    val current = synchronized(lock) {
                        if (generation == action.generation && !closed) {
                            inFlight.remove(action.index)
                            awaitingDelivery--
                            if (pending.isNotEmpty()) scheduleLocked()
                            true
                        } else false
                    }
                    if (current) onTile(action.index, tile)
                })
            }
        }
    }

    private fun isCurrent(expected: Long): Boolean = synchronized(lock) { !closed && generation == expected }

    private fun releaseSource() {
        val old = source
        source = null
        runCatching { old?.close() }
    }

    private data class Action(val generation: Long, val key: String?, val index: Int, val close: Boolean)
}

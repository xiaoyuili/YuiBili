package com.yuilittle.bili

import java.io.File

internal data class EmoteFailure(val attempts: Int, val retryAtMs: Long)

internal fun nextEmoteFailure(previous: EmoteFailure?, nowMs: Long): EmoteFailure {
    val attempts = (previous?.attempts ?: 0) + 1
    return EmoteFailure(attempts, nowMs + if (attempts < 2) 5_000L else 60_000L)
}

internal class EmoteDiskCache(private val directory: File, private val limitBytes: Long = 16L * 1024 * 1024) {
    private var initialized = false
    private val lock = Any()
    private var bytesOnDisk = -1L
    private var lastSweepAtMs = 0L

    fun read(name: String, maxFileBytes: Long): ByteArray? = synchronized(lock) {
        prepare()
        val file = File(directory, name)
        if (!file.isFile || file.length() !in 1L..maxFileBytes) return@synchronized null
        val bytes = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(minOf(file.length(), maxFileBytes).toInt())
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxFileBytes) return@synchronized null
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        file.setLastModified(System.currentTimeMillis())
        bytes
    }

    fun write(name: String, bytes: ByteArray) = synchronized(lock) {
        prepare()
        val file = File(directory, name)
        val temporary = File.createTempFile("emote-", ".tmp", directory)
        try {
            temporary.writeBytes(bytes)
            val previous = file.length()
            if (!temporary.renameTo(file)) return@synchronized
            bytesOnDisk = (bytesOnDisk - previous).coerceAtLeast(0L) + file.length()
            if (bytesOnDisk > limitBytes) trim()
        } finally { temporary.delete() }
    }

    fun discard(name: String) = synchronized(lock) {
        val file = File(directory, name)
        val length = file.length()
        if (file.delete() && bytesOnDisk >= 0L) bytesOnDisk = (bytesOnDisk - length).coerceAtLeast(0L)
    }

    private fun prepare() {
        if (initialized && directory.isDirectory) {
            if (System.currentTimeMillis() - lastSweepAtMs >= 60_000L) trim()
            return
        }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建表情缓存目录" }
        trim()
        initialized = true
    }

    private fun trim() {
        lastSweepAtMs = System.currentTimeMillis()
        val entries = directory.listFiles().orEmpty()
        entries.filter { it.isFile && it.name.endsWith(".tmp") && lastSweepAtMs - it.lastModified() > 60_000L }.forEach { it.delete() }
        val files = entries.filter { it.isFile && !it.name.endsWith(".tmp") }
        bytesOnDisk = files.sumOf(File::length)
        if (bytesOnDisk <= limitBytes) return
        val target = limitBytes * 3 / 4
        for (file in files.sortedBy(File::lastModified)) {
            if (bytesOnDisk <= target) break
            val size = file.length()
            if (file.delete()) bytesOnDisk -= size
        }
    }
}

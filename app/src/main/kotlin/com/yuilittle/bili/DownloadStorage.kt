package com.yuilittle.bili

import java.io.File

/** 任务创建时固定；没有位置字段的旧记录始终使用原私有路径。 */
enum class DownloadDestination(val key: String) {
    PRIVATE("private"), LIBRARY("library");

    companion object {
        fun fromKey(key: String?) = if (key == LIBRARY.key) LIBRARY else PRIVATE
    }
}

internal enum class DownloadLayout { AUDIO, DASH, SEGMENTS }

/** 只有整次传输成功才记录各输入的长度，保存重试不需要重新取流。 */
internal data class DownloadStage(val layout: DownloadLayout, val lengths: List<Long>) {
    init {
        require(lengths.isNotEmpty() && lengths.all { it > 0L }) { "下载分片为空" }
        require(when (layout) {
            DownloadLayout.AUDIO -> lengths.size == 1
            DownloadLayout.DASH -> lengths.size in 1..2
            DownloadLayout.SEGMENTS -> true
        })
    }

    val outputCount: Int get() = if (layout == DownloadLayout.SEGMENTS) lengths.size else 1

    fun inputs(dir: File, id: Long): List<File> = when (layout) {
        DownloadLayout.AUDIO -> listOf(File(dir, "${id}_audio.m4s"))
        DownloadLayout.DASH -> listOf(File(dir, "${id}_video.m4s"), File(dir, "${id}_audio.m4s")).take(lengths.size)
        DownloadLayout.SEGMENTS -> lengths.indices.map { File(dir, "${id}_seg$it.mp4") }
    }

    fun isIntact(dir: File, id: Long): Boolean = inputs(dir, id).withIndex().all { (i, file) ->
        file.isFile && file.length() == lengths[i]
    }
}

internal object LibraryBatch {
    fun names(title: String, id: Long, token: String, count: Int, audio: Boolean): List<String> {
        val base = title.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_").trim(' ', '.').take(48).ifBlank { "YuiBili" }
        val ext = if (audio) "m4a" else "mp4"
        return List(count) { part -> "$base-yb${id.toString(36)}-$token-${part + 1}.$ext" }
    }

    fun validName(name: String, id: Long, audio: Boolean): Boolean = id > 0 &&
        File(name).name == name && '\\' !in name && name.none { it.isISOControl() } &&
        name.substringAfterLast("-yb${id.toString(36)}-", "").matches(
            if (audio) audioSuffix else videoSuffix)

    private val audioSuffix = Regex("[0-9a-f]{12}-[1-9][0-9]*\\.m4a")
    private val videoSuffix = Regex("[0-9a-f]{12}-[1-9][0-9]*\\.mp4")

    fun canAdopt(expected: List<Long>, actual: List<Long?>): Boolean =
        expected.isNotEmpty() && expected.size == actual.size &&
            expected.indices.all { expected[it] > 0L && expected[it] == actual[it] }
}

internal object DownloadPrivateFiles {
    private val segment = Regex("seg[0-9]+\\.mp4")
    private val image = Regex("(?:cover|comment)\\.[a-zA-Z0-9]{2,5}")

    fun belongsToTask(name: String, id: Long, includeCover: Boolean): Boolean {
        if (name == "$id.mpd") return true
        val prefix = "${id}_"
        if (!name.startsWith(prefix)) return false
        val tail = name.removePrefix(prefix)
        return tail == "audio.m4s" || tail == "video.m4s" || tail == "merged.mp4" ||
            segment.matches(tail) || (includeCover && image.matches(tail))
    }

    fun clean(dir: File, id: Long, includeCover: Boolean): Boolean {
        val files = dir.listFiles() ?: return false
        var ok = true
        for (file in files) {
            if (belongsToTask(file.name, id, includeCover) && file.isFile && !file.delete() && file.exists()) ok = false
        }
        return ok
    }
}

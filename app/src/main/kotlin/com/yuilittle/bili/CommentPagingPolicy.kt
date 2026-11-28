package com.yuilittle.bili

internal data class CommentPageCursor(val nextOffset: String?, val ended: Boolean?)
internal data class CommentCursorDecision(val nextOffset: String?, val hasMore: Boolean, val failureReason: String? = null)

internal data class CommentPageState(
    val offset: String? = null,
    val hasMore: Boolean = true,
    val failureReason: String? = null,
    val manualNext: Boolean = false
) {
    val failed: Boolean get() = failureReason != null

    fun fail(reason: String): CommentPageState = copy(failureReason = reason)

    fun retry(): CommentPageState = copy(hasMore = hasMore || failed, failureReason = null, manualNext = false)

    fun accept(
        nextOffset: String?,
        hasMore: Boolean,
        freshCount: Int,
        failureReason: String? = null
    ): CommentPageState = copy(
        offset = if (hasMore) nextOffset else offset,
        hasMore = hasMore,
        failureReason = failureReason,
        manualNext = hasMore && freshCount == 0
    )
}

internal fun encodeCommentOffsetJson(offset: String?): String =
    "{\"offset\":${offset?.let(::jsonString) ?: "\"\""}}"

internal fun quoteCursorForWbi(value: String): String = buildString(value.length) {
    value.forEach { char ->
        if (char in "!'()*" || char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
    }
}

private fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    for (char in value) when (char) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        in '\u0000'..'\u001F' -> append("\\u%04x".format(char.code))
        else -> append(char)
    }
    append('"')
}

internal fun decideCommentCursor(cursor: CommentPageCursor): CommentCursorDecision = when {
    cursor.ended == null -> CommentCursorDecision(null, false, "分页响应不完整")
    cursor.ended -> CommentCursorDecision(null, false)
    cursor.nextOffset.isNullOrBlank() -> CommentCursorDecision(null, false, "未返回下一页位置")
    else -> CommentCursorDecision(cursor.nextOffset, true)
}

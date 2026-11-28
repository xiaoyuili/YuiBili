package com.yuilittle.bili

internal object CommentMentionResolver {
    fun fromEntries(entries: Iterable<Pair<String, String>>): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        val ambiguous = HashSet<String>()
        for ((rawName, rawMid) in entries) {
            val name = rawName.removePrefix("@")
            val mid = rawMid.toLongOrNull()?.takeIf { it > 0L } ?: continue
            if (name.isBlank() || name in ambiguous) continue
            val previous = result[name]
            if (previous != null && previous != mid) {
                result.remove(name)
                ambiguous += name
            } else result[name] = mid
        }
        return result
    }

    data class Mention(val start: Int, val end: Int, val name: String, val mid: Long)

    fun find(text: String, members: Map<String, Long>): List<Mention> {
        if (members.isEmpty() || '@' !in text) return emptyList()
        val names = members.keys.sortedByDescending(String::length)
        val result = ArrayList<Mention>()
        var start = text.indexOf('@')
        while (start >= 0) {
            val name = names.firstOrNull { name ->
                val end = start + 1 + name.length
                text.regionMatches(start + 1, name, 0, name.length) &&
                    (end == text.length || text[end].isWhitespace() || text[end] in "：:，,。!！?？、;；@()（）[]【】")
            }
            if (name != null) result += Mention(start, start + 1 + name.length, name, members.getValue(name))
            start = text.indexOf('@', if (name != null) start + 1 + name.length else start + 1)
        }
        return result
    }
}

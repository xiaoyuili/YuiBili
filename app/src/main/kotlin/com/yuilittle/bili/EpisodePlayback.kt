package com.yuilittle.bili

import com.yuilittle.bili.DownloadManager.LocalPlayMode

/** 同 BV 的多 P 必须先匹配 cid；只有缺 cid 的条目才允许按稿件匹配。 */
internal fun List<UgcEpisode>.playingIndex(bvid: String, aid: Long, cid: Long): Int {
    if (cid > 0) indexOfFirst { it.cid == cid }.takeIf { it >= 0 }?.let { return it }
    return indexOfFirst {
        (cid <= 0 || it.cid <= 0) && ((bvid.isNotBlank() && bvid == it.bvid) || (aid > 0 && aid == it.aid))
    }
}

internal fun nextEpisodeIndex(size: Int, current: Int, mode: LocalPlayMode, automatic: Boolean): Int? {
    if (current !in 0 until size) return null
    return when {
        automatic && mode == LocalPlayMode.SINGLE_LOOP -> current
        mode == LocalPlayMode.SHUFFLE && size > 1 -> (current + kotlin.random.Random.nextInt(1, size)) % size
        mode == LocalPlayMode.LIST_LOOP -> (current + 1) % size
        current + 1 < size -> current + 1
        else -> null
    }
}

internal fun seasonForEpisode(previous: UgcSeason?, received: UgcSeason?, bvid: String, aid: Long, cid: Long): UgcSeason? {
    // 跨稿件切集时，新详情可能只返回本 BV 的多 P/单条节选；此时应保持发起连播的合集。
    if (previous != null && previous.episodes.playingIndex(bvid, aid, cid) >= 0 &&
        (received == null || received.id != previous.id || received.episodes.size < previous.episodes.size)) return previous
    return received
}

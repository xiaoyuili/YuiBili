package com.yuilittle.bili

import java.lang.ref.WeakReference

internal data class CommentLikeKey(val epoch: Long, val type: Int, val oid: String, val rpid: Long)
internal data class CommentLikeState(val liked: Boolean, val count: Long, val pending: Boolean = false)

internal class CommentLikeLedger(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(var state: CommentLikeState, var confirmedUntil: Long, var operation: Long, var observedAt: Long, var ackAtNanos: Long = 0L)
    private val entries = LinkedHashMap<CommentLikeKey, Entry>()
    private var operation = 0L

    fun read(key: CommentLikeKey, server: CommentLikeState, observedAt: Long = System.nanoTime()): CommentLikeState {
        val old = entries[key]
        if (old != null && (old.state.pending || old.confirmedUntil > now() || observedAt <= maxOf(old.observedAt, old.ackAtNanos))) return old.state
        entries[key] = Entry(server.copy(pending = false), 0L, 0L, observedAt)
        trim()
        return server.copy(pending = false)
    }

    fun begin(key: CommentLikeKey, server: CommentLikeState): Pair<Long, CommentLikeState>? {
        val previous = entries[key]?.state ?: read(key, server)
        if (previous.pending) return null
        val op = ++operation
        val observedAt = entries[key]?.observedAt ?: 0L
        entries[key] = Entry(CommentLikeState(!previous.liked, (previous.count + if (previous.liked) -1 else 1).coerceAtLeast(0), true), Long.MAX_VALUE, op, observedAt)
        return op to previous
    }

    fun finish(key: CommentLikeKey, op: Long, previous: CommentLikeState, ok: Boolean): CommentLikeState? {
        val entry = entries[key]?.takeIf { it.operation == op } ?: return null
        entry.state = if (ok) entry.state.copy(pending = false) else previous.copy(pending = false)
        entry.confirmedUntil = now() + 15_000L
        entry.ackAtNanos = System.nanoTime()
        return entry.state
    }

    fun current(key: CommentLikeKey): CommentLikeState? = entries[key]?.state
    fun clearOtherSessions(epoch: Long) { entries.keys.removeAll { it.epoch != epoch } }
    private fun trim() {
        if (entries.size <= 512) return
        val stale = entries.filterValues { !it.state.pending }.keys.take(entries.size - 512)
        stale.forEach(entries::remove)
    }
}

internal object CommentLikes {
    fun interface Listener { fun changed(state: CommentLikeState) }
    private val ledger = CommentLikeLedger()
    private val listeners = HashMap<CommentLikeKey, MutableList<WeakReference<Listener>>>()

    fun read(key: CommentLikeKey, comment: VideoComment): CommentLikeState {
        val epoch = BiliSessionStore.snapshot().epoch
        ledger.clearOtherSessions(epoch)
        listeners.keys.removeAll { it.epoch != epoch }
        if (key.epoch != epoch) return CommentLikeState(false, comment.likes)
        return ledger.read(key, CommentLikeState(comment.liked, comment.likes), comment.requestedAtNanos)
    }

    fun subscribe(key: CommentLikeKey, listener: Listener) {
        val list = listeners.getOrPut(key) { ArrayList() }
        list.removeAll { it.get() == null || it.get() === listener }
        list += WeakReference(listener)
        ledger.current(key)?.let(listener::changed)
    }

    fun unsubscribe(key: CommentLikeKey, listener: Listener) {
        listeners[key]?.let { list ->
            list.removeAll { it.get() == null || it.get() === listener }
            if (list.isEmpty()) listeners.remove(key)
        }
    }

    fun toggle(key: CommentLikeKey, comment: VideoComment, onError: (String) -> Unit) {
        val session = BiliSessionStore.snapshot()
        if (key.epoch != session.epoch) { onError("账号已切换，请刷新评论"); return }
        if (!BiliSessionStore.isLoggedIn(session.cookie)) { onError("请先登录后再点赞评论"); return }
        val (operation, previous) = ledger.begin(key, CommentLikeState(comment.liked, comment.likes)) ?: return
        notify(key)
        val target = ledger.current(key)!!.liked
        BiliApi.postCommentLike(key.oid, key.type, key.rpid, target, session) { ok, error ->
            if (!BiliSessionStore.isCurrent(session)) { ledger.clearOtherSessions(BiliSessionStore.snapshot().epoch); return@postCommentLike }
            ledger.finish(key, operation, previous, ok)
            notify(key)
            if (!ok) onError(error ?: "评论点赞失败，请稍后再试")
        }
    }

    private fun notify(key: CommentLikeKey) {
        val state = ledger.current(key) ?: return
        listeners[key]?.toList()?.forEach { it.get()?.changed(state) }
    }
}

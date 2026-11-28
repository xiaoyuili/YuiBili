package com.yuilittle.bili

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 弹幕显示设置（进程内共享 + SharedPreferences 持久化）。
 * 两个播放页（UGC / PGC）复用同一份设置；滑块高频拖动时即时更新内存态，落盘防抖合并。
 */
internal object DanmakuSettingsStore {

    private const val PREFS = "bili_danmaku"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_TEXT_SCALE = "text_scale"
    private const val KEY_SPEED_SCALE = "speed_scale"
    private const val KEY_AREA_FRACTION = "area_fraction"
    private const val KEY_BLOCK_SCROLL = "block_scroll"
    private const val KEY_BLOCK_TOP = "block_top"
    private const val KEY_BLOCK_BOTTOM = "block_bottom"
    private const val KEY_BLOCK_COLOR = "block_color"

    private const val PERSIST_DEBOUNCE_MS = 200L

    data class Snapshot(
        val enabled: Boolean = true,
        val opacity: Float = 0.85f,
        val textScale: Float = 1.0f,
        val speedScale: Float = 1.0f,
        val areaFraction: Float = 0.50f,
        val blockScroll: Boolean = false,
        val blockTop: Boolean = false,
        val blockBottom: Boolean = false,
        val blockColor: Boolean = false
    ) {
        fun sanitized(): Snapshot = copy(
            opacity = sanitizeFloat(opacity, 0.85f, 0.15f, 1.0f),
            textScale = sanitizeFloat(textScale, 1.0f, 0.6f, 1.6f),
            speedScale = sanitizeFloat(speedScale, 1.0f, 0.6f, 1.6f),
            areaFraction = snapAreaFraction(areaFraction)
        )
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var current: Snapshot = Snapshot()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    private val persistRunnable = Runnable {
        val ctx = appContext ?: return@Runnable
        val s = current
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, s.enabled)
            .putFloat(KEY_OPACITY, s.opacity)
            .putFloat(KEY_TEXT_SCALE, s.textScale)
            .putFloat(KEY_SPEED_SCALE, s.speedScale)
            .putFloat(KEY_AREA_FRACTION, s.areaFraction)
            .putBoolean(KEY_BLOCK_SCROLL, s.blockScroll)
            .putBoolean(KEY_BLOCK_TOP, s.blockTop)
            .putBoolean(KEY_BLOCK_BOTTOM, s.blockBottom)
            .putBoolean(KEY_BLOCK_COLOR, s.blockColor)
            .apply()
    }

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        current = Snapshot(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            opacity = prefs.getFloat(KEY_OPACITY, 0.85f),
            textScale = prefs.getFloat(KEY_TEXT_SCALE, 1.0f),
            speedScale = prefs.getFloat(KEY_SPEED_SCALE, 1.0f),
            areaFraction = prefs.getFloat(KEY_AREA_FRACTION, 0.50f),
            blockScroll = prefs.getBoolean(KEY_BLOCK_SCROLL, false),
            blockTop = prefs.getBoolean(KEY_BLOCK_TOP, false),
            blockBottom = prefs.getBoolean(KEY_BLOCK_BOTTOM, false),
            blockColor = prefs.getBoolean(KEY_BLOCK_COLOR, false)
        ).sanitized()
    }

    fun snapshot(): Snapshot = current

    fun update(debouncePersist: Boolean = false, transform: (Snapshot) -> Snapshot): Snapshot {
        val next = transform(current).sanitized()
        if (next == current) return current
        current = next
        schedulePersist(debouncePersist)
        dispatchChanged(next)
        return next
    }

    fun setEnabled(enabled: Boolean): Snapshot = update(debouncePersist = false) {
        it.copy(enabled = enabled)
    }

    fun toggleEnabled(): Snapshot = update(debouncePersist = false) {
        it.copy(enabled = !it.enabled)
    }

    fun resetToDefault(): Snapshot = update(debouncePersist = false) {
        Snapshot(enabled = it.enabled)
    }

    fun flushPersist() {
        mainHandler.removeCallbacks(persistRunnable)
        persistRunnable.run()
    }

    fun addListener(listener: (Snapshot) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    private fun schedulePersist(debounce: Boolean) {
        if (appContext == null) return
        mainHandler.removeCallbacks(persistRunnable)
        if (debounce) {
            mainHandler.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MS)
        } else {
            persistRunnable.run()
        }
    }

    private fun dispatchChanged(snapshot: Snapshot) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it(snapshot) }
        } else {
            mainHandler.post { listeners.forEach { it(snapshot) } }
        }
    }

    internal fun sanitizeFloat(value: Float, fallback: Float, min: Float, max: Float): Float {
        if (!value.isFinite()) return fallback
        return value.coerceIn(min, max)
    }

    internal fun snapAreaFraction(raw: Float): Float {
        if (!raw.isFinite()) return 0.50f
        return when {
            raw < 0.375f -> 0.25f
            raw < 0.625f -> 0.50f
            raw < 0.875f -> 0.75f
            else -> 1.00f
        }
    }

}

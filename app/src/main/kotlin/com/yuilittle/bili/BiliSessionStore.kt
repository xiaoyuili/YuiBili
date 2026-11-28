package com.yuilittle.bili

import android.content.Context
import android.os.SystemClock

/**
 * Local Bilibili session holder.
 *
 * Cookies are only kept in the app's private preferences after the user
 * completes the official Bilibili login page. They are never logged or sent
 * anywhere except Bilibili endpoints used by this app.
 */
object BiliSessionStore {
    private const val PREFS = "bili_session"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_VIP_STATUS = "vip_status"
    private const val KEY_VIP_TYPE = "vip_type"
    private const val KEY_PROFILE_UID = "profile_uid"
    private const val KEY_PROFILE_UNAME = "profile_uname"
    private const val KEY_PROFILE_FACE = "profile_face"
    private const val KEY_PROFILE_COINS = "profile_coins"
    private const val KEY_PROFILE_LEVEL = "profile_level"
    private const val KEY_PROFILE_FOLLOWING = "profile_following"
    private const val KEY_PROFILE_FOLLOWER = "profile_follower"
    private const val KEY_PROFILE_SAVED_AT = "profile_saved_at"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedCookie: String? = null

    @Volatile
    private var sessionEpoch = 0L

    class SessionSnapshot internal constructor(val epoch: Long, internal val cookie: String)

    @Synchronized
    fun snapshot(): SessionSnapshot = SessionSnapshot(sessionEpoch, cachedCookie.orEmpty())

    @Synchronized
    fun isCurrent(snapshot: SessionSnapshot): Boolean =
        snapshot.epoch == sessionEpoch && snapshot.cookie == cachedCookie.orEmpty()

    @Volatile
    private var vipStatus = 0

    @Volatile
    private var vipType = 0

    /** Last successfully loaded profile card (process-local). Survives night-mode recreate. */
    @Volatile
    private var lastProfile: BiliLoginApi.UserInfo? = null

    @Volatile
    private var lastProfileAt: Long = 0L

    @Volatile
    private var profilePreloadInFlight = false

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val application = context.applicationContext
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        vipStatus = prefs.getInt(KEY_VIP_STATUS, 0)
        vipType = prefs.getInt(KEY_VIP_TYPE, 0)
        cachedCookie = prefs.getString(KEY_COOKIE, "").orEmpty()
        val uid = prefs.getLong(KEY_PROFILE_UID, 0L)
        if (uid > 0L && isLoggedIn(cachedCookie.orEmpty())) {
            val profile = BiliLoginApi.UserInfo(
                uid = uid,
                uname = prefs.getString(KEY_PROFILE_UNAME, "").orEmpty(),
                face = prefs.getString(KEY_PROFILE_FACE, "").orEmpty(),
                coins = prefs.getFloat(KEY_PROFILE_COINS, 0f).toDouble(),
                vipStatus = vipStatus,
                vipType = vipType,
                level = prefs.getInt(KEY_PROFILE_LEVEL, 0),
                following = prefs.getInt(KEY_PROFILE_FOLLOWING, 0),
                follower = prefs.getInt(KEY_PROFILE_FOLLOWER, 0)
            )
            lastProfile = profile
            lastProfileAt = prefs.getLong(KEY_PROFILE_SAVED_AT, 0L)
            if (profile.face.isNotBlank()) {
                CoverLoader.prefetch(
                    application,
                    profile.face + "@128w_128h.webp",
                    cropThumb = false,
                    thumbEdge = 128,
                    priority = CoverLoader.PRIORITY_HIGH
                )
            }
        }
        appContext = application
    }

    fun cookie(): String = cachedCookie.orEmpty()

    fun isLoggedIn(): Boolean = isLoggedIn(cookie())

    internal fun isLoggedIn(cookie: String): Boolean {
        return cookie.split(';')
            .asSequence()
            .map { it.trim() }
            .any { it.startsWith("SESSDATA=") && it.substringAfter('=', "").isNotBlank() }
    }

    /** True when the account has an active big-vip membership (vipType >= 1). */
    fun isBigVip(): Boolean = vipStatus == 1 && vipType >= 1


    fun cacheProfile(info: BiliLoginApi.UserInfo) {
        lastProfile = info
        val now = System.currentTimeMillis()
        lastProfileAt = now
        setVip(info.vipStatus, info.vipType)
        val context = appContext
        if (context != null && info.face.isNotBlank()) {
            CoverLoader.prefetch(
                context,
                info.face + "@128w_128h.webp",
                cropThumb = false,
                thumbEdge = 128,
                priority = CoverLoader.PRIORITY_HIGH
            )
        }
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putLong(KEY_PROFILE_UID, info.uid)
            ?.putString(KEY_PROFILE_UNAME, info.uname)
            ?.putString(KEY_PROFILE_FACE, info.face)
            ?.putFloat(KEY_PROFILE_COINS, info.coins.toFloat())
            ?.putInt(KEY_PROFILE_LEVEL, info.level)
            ?.putInt(KEY_PROFILE_FOLLOWING, info.following)
            ?.putInt(KEY_PROFILE_FOLLOWER, info.follower)
            ?.putLong(KEY_PROFILE_SAVED_AT, now)
            ?.apply()
    }

    fun cachedProfile(): BiliLoginApi.UserInfo? = lastProfile

    fun cachedProfileAt(): Long = lastProfileAt

    fun clearProfileCache() {
        lastProfile = null
        lastProfileAt = 0L
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove(KEY_PROFILE_UID)
            ?.remove(KEY_PROFILE_UNAME)
            ?.remove(KEY_PROFILE_FACE)
            ?.remove(KEY_PROFILE_COINS)
            ?.remove(KEY_PROFILE_LEVEL)
            ?.remove(KEY_PROFILE_FOLLOWING)
            ?.remove(KEY_PROFILE_FOLLOWER)
            ?.remove(KEY_PROFILE_SAVED_AT)
            ?.apply()
    }

    /** 首页启动时在后台静默预热一次用户档案，确保切入「我的」页面时零等待、无整卡刷新感。 */
    fun preloadProfileIfNeeded(context: Context, onUpdated: ((BiliLoginApi.UserInfo) -> Unit)? = null) {
        init(context)
        val session = cookie()
        if (!isLoggedIn(session)) return
        synchronized(this) {
            if (profilePreloadInFlight) return
            profilePreloadInFlight = true
        }
        BiliLoginApi.fetchProfile(
            cookie = session,
            onBaseReady = { base ->
                // nav 一返回立即预热头像，不等 nav/stat 或 relation/stat 回退
                if (session == cookie() && base.face.isNotBlank()) {
                    CoverLoader.prefetch(
                        context.applicationContext,
                        base.face + "@128w_128h.webp",
                        cropThumb = false,
                        thumbEdge = 128,
                        priority = CoverLoader.PRIORITY_HIGH
                    )
                }
            }
        ) { info, _ ->
            synchronized(this) { profilePreloadInFlight = false }
            if (info != null && session == cookie()) {
                cacheProfile(info)
                onUpdated?.invoke(info)
            }
        }
    }

    /** Cache the membership state fetched from the nav endpoint. */
    @Synchronized
    fun setVip(status: Int, type: Int) {
        vipCheckedAt = SystemClock.elapsedRealtime()
        if (vipStatus == status && vipType == type) return
        vipStatus = status
        vipType = type
        val context = appContext ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VIP_STATUS, status)
            .putInt(KEY_VIP_TYPE, type)
            .apply()
    }

    private var vipCheckedAt = -VIP_REFRESH_MS
    private var vipCheck: Any? = null

    @Synchronized
    fun refreshVipInfo() {
        val session = cookie()
        if (!isLoggedIn(session) || vipCheck != null ||
            SystemClock.elapsedRealtime() - vipCheckedAt < VIP_REFRESH_MS
        ) return
        val check = Any()
        vipCheck = check
        BiliLoginApi.fetchVipStatus(session) { status, type ->
            synchronized(this) {
                if (vipCheck !== check) return@synchronized
                vipCheck = null
                if (session == cookie() && status != null) setVip(status, type)
            }
        }
    }

    /** Save only the cookie pairs useful for a normal Bilibili session. */
    @Synchronized
    fun saveCookie(context: Context, rawCookie: String): Boolean {
        init(context)
        val normalized = normalize(rawCookie)
        if (normalized.isBlank()) return false
        if (cachedCookie != normalized) {
            sessionEpoch++
            clearProfileCache()
            resetVip()
        }
        cachedCookie = normalized
        appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COOKIE, normalized)
            .apply()
        val loggedIn = isLoggedIn()
        if (loggedIn) refreshVipInfo() else setVip(0, 0)
        return loggedIn
    }

    @Synchronized
    fun clear(context: Context) {
        clearProfileCache()
        init(context)
        if (!cachedCookie.isNullOrBlank()) sessionEpoch++
        cachedCookie = ""
        resetVip()
        appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_COOKIE)
            .apply()
    }

    private fun resetVip() {
        setVip(0, 0)
        vipCheck = null
        vipCheckedAt = -VIP_REFRESH_MS
    }

    private const val VIP_REFRESH_MS = 5 * 60_000L

    private fun normalize(rawCookie: String): String {
        val allowed = setOf(
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5",
            "sid", "buvid3", "buvid4", "b_nut", "b_lsid", "CURRENT_FNVAL",
            "CURRENT_QUALITY", "CURRENT_PID", "CURRENT_QUALITY"
        )
        val seen = HashSet<String>()
        return rawCookie.split(';')
            .asSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
            .filter { (name, value) -> name in allowed && value.isNotBlank() }
            .filter { seen.add(it.first) }
            .joinToString("; ") { (name, value) -> "$name=$value" }
    }
}

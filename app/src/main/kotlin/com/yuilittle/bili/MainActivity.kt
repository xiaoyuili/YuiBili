package com.yuilittle.bili

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max

/** Extra flag: when set, MainActivity switches to the account ("我的") page. */
const val EXTRA_SWITCH_TO_ACCOUNT = "main_switch_to_account"

class MainActivity : Activity() {
    private lateinit var pageHost: FrameLayout
    private var bottomNavigation: FrostedBottomNavigation? = null
    private var tabletRail: TabletRailNav? = null
    private var tabletStage: TabletVerticalStage? = null
    private val pages = arrayOfNulls<View>(2)
    private var currentPage = HOME
    private var currentTabletDest = TabletRailNav.DEST_HOME
    private var refreshOnEntry = true
    private var restoredHomeState: Bundle? = null
    private var resumed = false
    private var reducedMotion = false
    private var builtAsTablet = false
    private var themeTransitionRunning = false
    private var themeRevealAnimator: ValueAnimator? = null
    private var themeRevealCleanup: (() -> Unit)? = null
    private data class TabletState(val session: String, val searches: Map<Int, TabletSearchPageView.Snapshot>,
        val favorites: TabletFavoritesPageView.Snapshot?, val history: TabletHistoryPageView.Snapshot?)
    private var retainedTabletState: TabletState? = null
    private val restoredSearches = HashMap<Int, TabletSearchPageView.Snapshot>()
    private var restoredFavorites: TabletFavoritesPageView.Snapshot? = null
    private var restoredHistory: TabletHistoryPageView.Snapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reducedMotion = MotionTokens.isReduced(this)
        Theme.init(this)
        builtAsTablet = LayoutMode.isTablet
        LayoutMode.applyScreenOrientation(this)
        // Paint the window with the current theme colour BEFORE any content so
        // recreate() after night-mode toggle never flashes the light styles.xml
        // windowBackground (#FFFCFA) for one frame.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(COLOR_BACKGROUND))
        // Suppress the default activity cross-fade that fights the circular reveal.
        if (Theme.peekCircularTransition() || Theme.hasTabletFade()) {
            overridePendingTransition(0, 0)
        }
        BiliSessionStore.init(this)
        (lastNonConfigurationInstance as? TabletState)?.takeIf { builtAsTablet && it.session == BiliSessionStore.cookie() }?.let {
            restoredSearches.putAll(it.searches)
            restoredFavorites = it.favorites
            restoredHistory = it.history
        }
        // A new task/activity entry refreshes the feeds; state restoration (such as
        // rotation or process recreation with saved state) keeps the visible feed.
        refreshOnEntry = savedInstanceState == null
        restoredHomeState = savedInstanceState?.getBundle(STATE_HOME) ?: savedInstanceState
        currentPage = savedInstanceState?.getInt(STATE_CURRENT_PAGE, HOME)?.coerceIn(HOME, PROFILE)
            ?: destinationFor(intent) ?: HOME
        currentTabletDest = savedInstanceState?.getInt(STATE_TABLET_DEST, TabletRailNav.DEST_HOME)
            ?.coerceIn(0, TabletRailNav.DEST_COUNT - 1)
            ?: if (destinationFor(intent) == PROFILE) TabletRailNav.DEST_PROFILE else TabletRailNav.DEST_HOME
        configureSystemBars()

        val root = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
        }

        if (builtAsTablet) {
            // 搜索复用右侧舞台，侧栏只保留主要目的地和日夜切换。
            val shell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(COLOR_BACKGROUND)
            }
            val rail = TabletRailNav(
                context = this,
                onSelectDestination = { dest -> showTabletDestination(dest, animate = true) },
                onToggleTheme = { _, dark -> toggleDarkModeFromRail(dark) }
            )
            tabletRail = rail
            val stage = TabletVerticalStage(this)
            tabletStage = stage
            pageHost = stage

            shell.addView(
                rail,
                LinearLayout.LayoutParams(
                    dp(TabletRailNav.RAIL_WIDTH_DP),
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
            shell.addView(
                View(this).apply { setBackgroundColor(Ds.colors.lineSoft) },
                LinearLayout.LayoutParams(Ds.hairline(this), LinearLayout.LayoutParams.MATCH_PARENT)
            )
            shell.addView(
                stage,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            )
            root.addView(
                shell,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            if (BiliSessionStore.isLoggedIn()) {
                BiliSessionStore.preloadProfileIfNeeded(this) { info ->
                    runOnUiThread {
                        (tabletStage?.getPage(TabletRailNav.DEST_PROFILE) as? AccountPageView)?.syncCachedProfile(info)
                    }
                }
            }
            showTabletDestination(currentTabletDest, animate = false)
            setContentView(root)
            playPendingThemeTransition(root)
            requestNotificationPermissionIfNeeded()
            showDisclaimerIfNeeded()
            root.postDelayed({
                if (!isFinishing && !isDestroyed) BiliPlayUrl.prewarm()
            }, 650L)
            return
        }

        // 手机端：保持原左右滑动 RootPager + 悬浮底部胶囊导航
        val pager = RootPager(this)
        pageHost = pager
        pager.setOnPageSelectedListener { index ->
            currentPage = index
            if (!pager.isTransitioning()) updateActivePage()
            bottomNavigation?.setSelectedIndex(index)
        }
        pager.setOnPageRevealListener { index ->
            if (index == HOME) (pages[HOME] as? HomeFeedView)?.prepareVisible()
        }
        pager.setOnPageScrollListener { progress ->
            bottomNavigation?.setScrollProgress(progress)
        }
        root.addView(
            pageHost,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val dock = FrostedBottomNavigation(this).apply {
            setOnDestinationSelectedListener { destination -> showPage(destination) }
            // 已在首页时再点「首页」：快速平滑回顶（不再切页）
            setOnDestinationReselectedListener { destination ->
                if (destination == HOME && currentPage == HOME) {
                    (pages[HOME] as? HomeFeedView)?.scrollToTop()
                }
            }
        }
        bottomNavigation = dock
        val dockWidth = minOf(dp(208), resources.displayMetrics.widthPixels - dp(48))
        root.addView(
            dock,
            FrameLayout.LayoutParams(
                dockWidth,
                dp(62),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(12)
            }
        )

        if (BiliSessionStore.isLoggedIn()) {
            BiliSessionStore.preloadProfileIfNeeded(this) { info ->
                runOnUiThread {
                    (pages[PROFILE] as? AccountPageView)?.syncCachedProfile(info)
                }
            }
        }
        for (index in pages.indices) obtainPage(index)
        pager.showPage(currentPage, animate = false)
        setContentView(root)
        playPendingThemeTransition(root)
        requestNotificationPermissionIfNeeded()
        showDisclaimerIfNeeded()
        // 冷启动空闲阶段预热 WBI 密钥与匿名 buvid（合并单飞），首屏稳定后点开视频或搜索无需再临时握手
        root.postDelayed({
            if (!isFinishing && !isDestroyed) BiliPlayUrl.prewarm()
        }, 650L)
        bottomNavigation?.setSelectedIndex(currentPage)
        root.post {
            bottomNavigation?.alpha = 1f
            bottomNavigation?.translationY = 0f
        }
    }

    private fun showTabletDestination(dest: Int, animate: Boolean) {
        themeRevealCleanup?.invoke()
        val stage = tabletStage ?: return
        currentTabletDest = dest.coerceIn(0, TabletRailNav.DEST_COUNT - 1)
        tabletRail?.setSelectedDestination(currentTabletDest)
        stage.showPage(currentTabletDest, { createTabletPage(currentTabletDest) }, animate = animate)
        updateActivePage()
    }

    private fun createTabletPage(dest: Int): View = when (dest) {
        TabletRailNav.DEST_HOME -> HomeFeedView(this, refreshOnEntry, restoredHomeState,
            onSearch = { showTabletDestination(TabletRailNav.DEST_SEARCH, animate = true) }).also {
            restoredHomeState = null
            pages[HOME] = it
        }
        TabletRailNav.DEST_SEARCH, TabletRailNav.DEST_HISTORY_SEARCH, TabletRailNav.DEST_FAVORITES_SEARCH -> {
            val scope = tabletSearchScope(dest)!!
            TabletSearchPageView(this, scope, restoredSearches.remove(dest)) {
                showTabletDestination(scope.parentDestination, animate = true)
            }
        }
        TabletRailNav.DEST_DOWNLOADS -> DownloadPageView(this, initialTab = DownloadPageView.TAB_VIDEO, onClose = null)
        TabletRailNav.DEST_FAVORITES -> TabletFavoritesPageView(this, restoredFavorites.also { restoredFavorites = null }) {
            showTabletDestination(TabletRailNav.DEST_FAVORITES_SEARCH, animate = true)
        }
        TabletRailNav.DEST_HISTORY -> TabletHistoryPageView(this, restoredHistory.also { restoredHistory = null }) {
            showTabletDestination(TabletRailNav.DEST_HISTORY_SEARCH, animate = true)
        }
        else -> AccountPageView(this).also { pages[PROFILE] = it }
    }

    private fun toggleDarkModeFromRail(next: Boolean) {
        if (!resumed || isFinishing || isDestroyed || themeTransitionRunning || next == Theme.isDark) return
        retainedTabletState = snapshotTabletState()
        themeTransitionRunning = true
        tabletRail?.setThemeTransitionLocked(true)
        if (!MotionTokens.isReduced(this)) {
            var snapshot: Bitmap? = null
            try {
                val content = window.decorView.findViewById<View>(android.R.id.content) ?: window.decorView
                if (content.width > 0 && content.height > 0) {
                    snapshot = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                    tabletRail?.setThemeSymbolsHidden(true)
                    content.draw(Canvas(snapshot))
                    Theme.prepareTabletFade(snapshot, Theme.isDark)
                    snapshot = null
                }
            } catch (_: Exception) {
            } catch (_: OutOfMemoryError) {
            } finally {
                snapshot?.recycle()
                tabletRail?.setThemeSymbolsHidden(false)
            }
        }
        Theme.setDark(this, next)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        recreate()
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (builtAsTablet && event.actionMasked == android.view.MotionEvent.ACTION_DOWN) themeRevealCleanup?.invoke()
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateActivePage()
    }

    override fun onPause() {
        if (builtAsTablet && (!themeTransitionRunning || themeRevealCleanup != null)) retainedTabletState = snapshotTabletState()
        resumed = false
        themeRevealCleanup?.invoke()
        updateActivePage()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        destinationFor(intent)?.let { dest ->
            if (builtAsTablet) {
                showTabletDestination(
                    if (dest == PROFILE) TabletRailNav.DEST_PROFILE else TabletRailNav.DEST_HOME,
                    animate = true
                )
            } else {
                showPage(dest)
            }
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (builtAsTablet) {
            val curView = tabletStage?.getPage(currentTabletDest)
            if (curView is TabletSearchPageView) {
                if (curView.handleBack()) return
                showTabletDestination(tabletSearchScope(currentTabletDest)!!.parentDestination, animate = true)
                return
            }
            if (curView is DownloadPageView && curView.handleBack()) return
            if (currentTabletDest != TabletRailNav.DEST_HOME) {
                showTabletDestination(TabletRailNav.DEST_HOME, animate = true)
                return
            }
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        themeRevealCleanup?.invoke()
        for (dest in restoredSearchDestinations) (tabletStage?.getPage(dest) as? TabletSearchPageView)?.destroy()
        super.onDestroy()
    }

    private fun destinationFor(intent: Intent): Int? = when {
        intent.getBooleanExtra(EXTRA_SWITCH_TO_ACCOUNT, false) -> PROFILE
        else -> null
    }

    private fun updateActivePage() {
        if (builtAsTablet) {
            (tabletStage?.getPage(TabletRailNav.DEST_HOME) as? HomeFeedView)
                ?.setActive(resumed && currentTabletDest == TabletRailNav.DEST_HOME)
            (tabletStage?.getPage(TabletRailNav.DEST_PROFILE) as? AccountPageView)
                ?.setActive(resumed && currentTabletDest == TabletRailNav.DEST_PROFILE)
            (tabletStage?.getPage(TabletRailNav.DEST_DOWNLOADS) as? DownloadPageView)
                ?.setActive(resumed && currentTabletDest == TabletRailNav.DEST_DOWNLOADS)
            (tabletStage?.getPage(TabletRailNav.DEST_HISTORY) as? TabletHistoryPageView)
                ?.setActive(resumed && currentTabletDest == TabletRailNav.DEST_HISTORY)
            for (dest in restoredSearchDestinations) {
                (tabletStage?.getPage(dest) as? TabletSearchPageView)?.setActive(resumed && currentTabletDest == dest)
            }
            if (resumed) {
                when (currentTabletDest) {
                    TabletRailNav.DEST_FAVORITES ->
                        (tabletStage?.getPage(TabletRailNav.DEST_FAVORITES) as? TabletFavoritesPageView)?.onPageActivated()
                }
            }
            return
        }
        (pages[HOME] as? HomeFeedView)?.setActive(resumed && currentPage == HOME)
        (pages[PROFILE] as? AccountPageView)?.setActive(resumed && currentPage == PROFILE)
    }

    /** Android 13+ 需要运行时授予通知权限，后台下载进度通知依赖它。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4201)
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** First launch: show the disclaimer dialog; "取消" exits the app. */
    private fun showDisclaimerIfNeeded() {        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)) return

        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = Ds.card(this@MainActivity, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.dialog)
        }
        content.addView(TextView(this).apply {
            text = "免责声明"
            textSize = 18f
            typeface = Ds.display(bold = true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_INK)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(TextView(this).apply {
            text = "该应用用于持久化的所有数据均仅明文存储于用户本地!\n" +
                "因使用本项目而产生的任何后果均由用户个人承担,与开发者无关,概不负责。\n" +
                "\"哔哩哔哩\"及\"Bilibili\"名称、LOGO及相关图形是上海幻电信息科技有限公司的注册商标或商标。\n" +
                "本项目与哔哩哔哩及其关联公司无任何关联、合作、授权或背书等关系。"
            textSize = 13f
            typeface = Ds.body()
            setLineSpacing(dp(6).toFloat(), 1f)
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(16), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val agreeBox = CheckBox(this).apply {
            text = "我已阅读免责声明，继续使用"
            textSize = 13f
            typeface = Ds.body()
            setTextColor(COLOR_INK)
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(agreeBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        val cancelButton = TextView(this).apply {
            text = "取消"
            textSize = 14f
            typeface = Ds.body()
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            background = Ds.pill(this@MainActivity, COLOR_SURFACE, Ds.colors.lineSoft)
            isClickable = true
        }
        val confirmButton = TextView(this).apply {
            text = "确定"
            textSize = 14f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(COLOR_ON_ROSE)
            background = Ds.pill(this@MainActivity, COLOR_ROSE)
            isClickable = true
            isEnabled = false
            alpha = 0.4f
        }
        agreeBox.setOnCheckedChangeListener { _, checked ->
            confirmButton.isEnabled = checked
            confirmButton.alpha = if (checked) 1f else 0.4f
        }
        cancelButton.setOnClickListener {
            dialog.dismiss()
            finishAffinity()
        }
        confirmButton.setOnClickListener {
            prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
            dialog.dismiss()
        }
        buttonRow.addView(cancelButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        buttonRow.addView(confirmButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            leftMargin = dp(12)
        })
        content.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(content)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            minOf(resources.displayMetrics.widthPixels - dp(48), dp(420)),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun snapshotTabletState(): TabletState = TabletState(BiliSessionStore.cookie(),
        restoredSearches.toMutableMap().apply {
            for (dest in restoredSearchDestinations) {
                (tabletStage?.getPage(dest) as? TabletSearchPageView)?.let { put(dest, it.snapshot()) }
            }
        }, (tabletStage?.getPage(TabletRailNav.DEST_FAVORITES) as? TabletFavoritesPageView)?.snapshot() ?: restoredFavorites,
        (tabletStage?.getPage(TabletRailNav.DEST_HISTORY) as? TabletHistoryPageView)?.snapshot() ?: restoredHistory)

    override fun onRetainNonConfigurationInstance(): Any? = if (builtAsTablet) retainedTabletState ?: snapshotTabletState() else null

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_CURRENT_PAGE, currentPage)
        outState.putInt(STATE_TABLET_DEST, currentTabletDest)
        val home = (if (builtAsTablet) tabletStage?.getPage(TabletRailNav.DEST_HOME) else pages[HOME]) as? HomeFeedView
        val homeState = if (home == null) restoredHomeState else Bundle().also { home.saveState(it) }
        if (homeState != null) outState.putBundle(STATE_HOME, homeState)
        super.onSaveInstanceState(outState)
    }

    private fun obtainPage(index: Int): View {
        pages[index]?.let { return it }
        val page = createRootPage(index).apply {
            contentDescription = pageDescription(index)
        }
        (pageHost as RootPager).addPage(page)
        pages[index] = page
        return page
    }

    private fun createRootPage(index: Int): View = when (index) {
        HOME -> HomeFeedView(this, refreshOnEntry, restoredHomeState).also { restoredHomeState = null }
        else -> AccountPageView(this)
    }

    /** 根页面切换：底栏点击与左右滑动走同一个滑动容器。 */
    private fun showPage(destination: Int) {
        if (destination !in pages.indices) return
        if (destination == currentPage) {
            bottomNavigation?.setSelectedIndex(destination)
            tabletRail?.setSelectedDestination(destination)
            return
        }
        if (destination == HOME) (pages[HOME] as? HomeFeedView)?.prepareVisible()
        val previous = currentPage
        currentPage = destination
        bottomNavigation?.setSelectedIndex(destination)
        tabletRail?.setSelectedDestination(destination)
        (pageHost as? RootPager)?.showPage(destination, animate = !reducedMotion)
        if ((pageHost as? RootPager)?.isTransitioning() != true) {
            updateActivePage()
        }
        if (previous == destination) return
    }

    private fun configureSystemBars() {
        Theme.applySystemBars(this)
    }

    /**
     * Circular theme hand-off after night-mode toggle:
     * - expand=true  (light→dark): old light screenshot is punched open from the
     *   switch, revealing the new dark content underneath (night spreads out).
     * - expand=false (dark→light): old dark screenshot is clipped to a shrinking
     *   circle that collapses into the switch (night is sucked back in).
     */
    private fun playPendingThemeTransition(root: FrameLayout) {
        Theme.consumeTabletFade()?.let { pending ->
            if (builtAsTablet) playTabletThemeFade(root, pending) else pending.bitmap.recycle()
            return
        }
        val pending = Theme.consumeCircularTransition() ?: return
        val (bitmap, center, expand) = pending
        if (reducedMotion) {
            bitmap.recycle()
            return
        }
        themeTransitionRunning = true
        tabletRail?.setThemeTransitionLocked(true)
        val cx = center.first
        val cy = center.second
        // Smooth theme switch: subtle fade-out of the previous window snapshot
        // when reduced motion is enabled, or fast snappy circular reveal from
        // the toggle switch.
        val overlay = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val path = Path()
            private val destination = RectF()
            var radius = 0f
            var modeExpand = true

            override fun onDraw(canvas: Canvas) {
                if (width <= 0 || height <= 0 || bitmap.isRecycled) return
                destination.set(0f, 0f, width.toFloat(), height.toFloat())
                path.rewind()
                path.addCircle(cx, cy, radius.coerceAtLeast(0f), Path.Direction.CW)
                val save = canvas.save()
                if (modeExpand) {
                    if (Build.VERSION.SDK_INT >= 26) canvas.clipOutPath(path)
                    else {
                        @Suppress("DEPRECATION")
                        canvas.clipPath(path, android.graphics.Region.Op.DIFFERENCE)
                    }
                } else canvas.clipPath(path)
                canvas.drawBitmap(bitmap, null, destination, paint)
                canvas.restoreToCount(save)
            }
        }
        overlay.modeExpand = expand
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        var layoutListener: View.OnLayoutChangeListener? = null
        var cleaned = false
        fun cleanup() {
            if (cleaned) return
            cleaned = true
            layoutListener?.let(overlay::removeOnLayoutChangeListener)
            layoutListener = null
            themeRevealAnimator?.let {
                it.removeAllListeners()
                it.removeAllUpdateListeners()
                it.cancel()
            }
            themeRevealAnimator = null
            root.removeView(overlay)
            if (!bitmap.isRecycled) bitmap.recycle()
            themeTransitionRunning = false
            themeRevealCleanup = null
            tabletRail?.setThemeTransitionLocked(false)
        }
        themeRevealCleanup = ::cleanup
        fun startAnim(maxR: Float) {
            if (cleaned || isFinishing || isDestroyed) { cleanup(); return }
            val start = if (expand) 0f else maxR
            val end = if (expand) maxR else 0f
            overlay.radius = start
            themeRevealAnimator = ValueAnimator.ofFloat(start, end).apply {
                duration = 380L
                interpolator = MotionTokens.easeOut
                addUpdateListener {
                    overlay.radius = it.animatedValue as Float
                    overlay.invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = cleanup()
                    override fun onAnimationCancel(animation: Animator) = cleanup()
                })
            }
            themeRevealAnimator?.start()
        }
        if (overlay.width > 0 && overlay.height > 0) {
            val maxR = maxRadius(cx, cy, overlay.width, overlay.height)
            startAnim(maxR)
        } else {
            layoutListener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    overlay.removeOnLayoutChangeListener(this)
                    layoutListener = null
                    startAnim(maxRadius(cx, cy, overlay.width, overlay.height))
                }
            }
            overlay.addOnLayoutChangeListener(layoutListener)
        }
    }

    private fun playTabletThemeFade(root: FrameLayout, pending: Theme.TabletFade) {
        val bitmap = pending.bitmap
        if (reducedMotion) { bitmap.recycle(); return }
        val rail = tabletRail ?: run { bitmap.recycle(); return }
        val from = Ds.palette(pending.fromDark)
        val to = Ds.colors
        themeTransitionRunning = true
        rail.setThemeTransitionLocked(true)
        rail.setThemeSymbolsHidden(true)
        val overlay = object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            var progress = 0f
            override fun onDraw(canvas: Canvas) {
                if (bitmap.isRecycled) return
                paint.alpha = (255 * (1f - progress)).toInt()
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                rail.drawThemeTransition(canvas, root, pending.fromDark, progress)
            }
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        fun setBarForeground(dark: Boolean) {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            val status = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            flags = if (dark) flags and status.inv() else flags or status
            if (Build.VERSION.SDK_INT >= 26) {
                flags = if (dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                    else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
        var barDark = pending.fromDark
        setBarForeground(barDark)
        window.statusBarColor = from.bg
        window.navigationBarColor = from.bg
        var listener: View.OnLayoutChangeListener? = null
        var cleaned = false
        fun cleanup() {
            if (cleaned) return
            cleaned = true
            listener?.let(root::removeOnLayoutChangeListener)
            listener = null
            themeRevealAnimator?.let {
                it.removeAllListeners()
                it.removeAllUpdateListeners()
                it.cancel()
            }
            themeRevealAnimator = null
            root.removeView(overlay)
            if (!bitmap.isRecycled) bitmap.recycle()
            rail.setThemeSymbolsHidden(false)
            rail.setThemeTransitionLocked(false)
            configureSystemBars()
            themeTransitionRunning = false
            themeRevealCleanup = null
        }
        themeRevealCleanup = ::cleanup
        fun start() {
            if (cleaned) return
            if (isFinishing || isDestroyed || root.width != bitmap.width || root.height != bitmap.height) {
                cleanup()
                return
            }
            themeRevealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener {
                    val p = it.animatedValue as Float
                    overlay.progress = p
                    window.statusBarColor = themeBlendColor(from.bg, to.bg, p)
                    window.navigationBarColor = themeBlendColor(from.bg, to.bg, p)
                    val background = window.statusBarColor
                    val dark = (Color.red(background) * .299f + Color.green(background) * .587f + Color.blue(background) * .114f) < 145f
                    if (dark != barDark) { barDark = dark; setBarForeground(dark) }
                    overlay.invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = cleanup()
                    override fun onAnimationCancel(animation: Animator) = cleanup()
                })
            }
            themeRevealAnimator?.start()
        }
        listener = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            if (cleaned || r <= l || b <= t) return@OnLayoutChangeListener
            if (r - l != bitmap.width || b - t != bitmap.height) cleanup()
            else if (themeRevealAnimator == null) start()
        }
        root.addOnLayoutChangeListener(listener)
        if (root.width > 0 && root.height > 0) start()
    }

    private fun maxRadius(cx: Float, cy: Float, w: Int, h: Int): Float {
        val corners = listOf(
            hypot(cx.toDouble(), cy.toDouble()),
            hypot((w - cx).toDouble(), cy.toDouble()),
            hypot(cx.toDouble(), (h - cy).toDouble()),
            hypot((w - cx).toDouble(), (h - cy).toDouble())
        )
        return (corners.maxOrNull() ?: 1.0).toFloat() + dp(24).toFloat()
    }

    private fun pageDescription(index: Int): String = when (index) {
        HOME -> "首页"
        else -> "我的"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val HOME = 0
        const val PROFILE = 1
        const val STATE_CURRENT_PAGE = "main_current_page"
        const val STATE_TABLET_DEST = "main_tablet_dest"
        const val STATE_HOME = "main_home_state"
        const val PREFS_NAME = "yuibili_settings"
        const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
        val restoredSearchDestinations = intArrayOf(TabletRailNav.DEST_SEARCH, TabletRailNav.DEST_HISTORY_SEARCH, TabletRailNav.DEST_FAVORITES_SEARCH)
    }
}

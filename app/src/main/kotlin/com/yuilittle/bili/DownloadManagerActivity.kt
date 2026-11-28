package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 下载管理：从「我的」进入的独立页面。
 * `DownloadPageView` 自带顶栏、返回键、存储统计、视频/音频双标签横滑与批量/左滑删除。
 */
class DownloadManagerActivity : Activity() {

    private lateinit var page: DownloadPageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Ds.applySystemBars(this)
        PlayerSettingsStore.applyHomeOrientation(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Ds.colors.bg))
        DownloadManager.init(this)

        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, DownloadPageView.TAB_VIDEO)
        page = DownloadPageView(
            context = this,
            initialTab = initialTab,
            onClose = { handleBack() }
        )
        setContentView(page)
    }

    override fun onResume() {
        super.onResume()
        page.refreshNow()
    }

    private fun handleBack() {
        if (page.handleBack()) return
        close()
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left_soft, R.anim.slide_out_right)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    companion object {
        const val EXTRA_INITIAL_TAB = "initial_tab"

        /** 与历史、收藏等二级页一致：从右侧滑入，返回时向右退出。 */
        fun open(context: Context, initialTab: Int = DownloadPageView.TAB_VIDEO) {
            context.startActivity(Intent(context, DownloadManagerActivity::class.java).apply {
                putExtra(EXTRA_INITIAL_TAB, initialTab)
            })
            (context as? Activity)?.overridePendingTransition(
                R.anim.slide_in_right, R.anim.slide_out_left_soft
            )
        }
    }
}

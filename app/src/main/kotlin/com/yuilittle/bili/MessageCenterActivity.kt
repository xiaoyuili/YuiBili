package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 手机端消息中心壳页面。平板横屏时消息中心作为主舞台的一级页面直接内嵌，
 * 不走这个 Activity；两边共用 [MessageCenterPageView]。
 */
class MessageCenterActivity : Activity() {

    private lateinit var pageView: MessageCenterPageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Theme.applySystemBars(this)
        PlayerSettingsStore.applyHomeOrientation(this)
        pageView = MessageCenterPageView(this, showBack = true, onBack = { finish() })
        setContentView(pageView)
    }

    override fun onDestroy() {
        if (::pageView.isInitialized) pageView.destroy()
        super.onDestroy()
    }

    companion object {
        internal fun open(context: Context) {
            context.startActivity(Intent(context, MessageCenterActivity::class.java))
        }
    }
}

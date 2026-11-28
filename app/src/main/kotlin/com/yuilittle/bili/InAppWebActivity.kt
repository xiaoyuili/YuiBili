package com.yuilittle.bili

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 应用内网页浏览：评论区链接在此打开，不跳出到外部浏览器。
 * 顶部为返回 + 只读网址框（内含复制）+ 系统浏览器图标。
 */
class InAppWebActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var urlText: TextView
    private lateinit var progressBar: View
    private lateinit var errorLayer: LinearLayout
    private var currentUrl: String = ""
    private var desktopUa = false
    private var withBiliLogin = false
    private var replyAnchor = ""
    private val replyAnchorTask = Runnable { scrollToReplyAnchor() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Ds.applySystemBars(this)
        PlayerSettingsStore.applyHomeOrientation(this)
        window.setBackgroundDrawable(ColorDrawable(Ds.colors.bg))

        val target = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!CommentLinkParser.isSafeUrl(target)) {
            Toast.makeText(this, "不支持的链接类型", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        desktopUa = intent.getBooleanExtra(EXTRA_DESKTOP_UA, false)
        withBiliLogin = intent.getBooleanExtra(EXTRA_BILI_LOGIN, false)
        replyAnchor = Regex("#reply(\\d+)").find(target)?.groupValues?.getOrNull(1).orEmpty()
        currentUrl = target
        setContentView(buildUi())
        applyWebPreferences(target)
        webView.loadUrl(target)
    }

    /** 桌面 UA + 站内登录态：消息中心跳去网页版回复时使用。 */
    private fun applyWebPreferences(url: String) {
        if (desktopUa) {
            webView.settings.userAgentString = DESKTOP_USER_AGENT
        }
        if (!withBiliLogin) return
        val cookie = BiliSessionStore.cookie()
        if (cookie.isBlank()) return
        runCatching {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            manager.setAcceptThirdPartyCookies(webView, true)
            val origin = Uri.parse(url).let { uri ->
                if (uri.host.isNullOrBlank()) null else "${uri.scheme}://${uri.host}"
            }
            val hosts = if (origin != null) BILI_COOKIE_HOSTS + origin else BILI_COOKIE_HOSTS
            // setCookie 只接受单个 Set-Cookie，逐条写入。
            cookie.split(';')
                .map { it.trim() }
                .filter { it.contains('=') }
                .forEach { pair ->
                    // 网页端登录态的 cookie 是 Domain=.bilibili.com 的，必须按域下发，
                    // 否则 api.vc.bilibili.com / api.bilibili.com 等接口域收不到 SESSDATA，
                    // 消息中心就会「加载失败」。
                    manager.setCookie(BILI_COOKIE_ANCHOR, "$pair; Domain=.bilibili.com; Path=/")
                    // 域属性万一被旧内核忽略，再按主机逐条补一遍。
                    hosts.forEach { manager.setCookie(it, pair) }
                }
            manager.flush()
        }
    }

    private fun buildUi(): View {
        val c = Ds.colors
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.bg)
        }

        // ── 顶栏：返回 + 网址框（内嵌复制）+ 系统浏览器 ──
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(10), dp(6))
            setBackgroundColor(c.bg)
        }
        topBar.addView(Ds.backIcon(this, c.text) { finish() },
            LinearLayout.LayoutParams(dp(40), dp(40)))

        val urlBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(this@InAppWebActivity, c.surfaceRaised, c.lineSoft, Ds.Radius.control)
            setPadding(dp(12), 0, dp(2), 0)
        }
        urlText = TextView(this).apply {
            text = currentUrl
            textSize = 13f
            setTextColor(c.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            fontFeatureSettings = "tnum"
        }
        urlBox.addView(urlText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        urlBox.addView(CopyIcon(this, c.muted) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", currentUrl))
            Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(32), dp(32)))

        topBar.addView(urlBox, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
            leftMargin = dp(6); rightMargin = dp(6)
        })
        topBar.addView(ExternalBrowserIcon(this, c.text) { openInBrowser() },
            LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(View(this).apply { setBackgroundColor(c.lineSoft) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(this)))

        // ── 加载进度条 ──
        progressBar = View(this).apply {
            setBackgroundColor(c.accent)
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(0, dp(2)))

        // ── WebView + 错误层 ──
        val host = FrameLayout(this)
        webView = WebView(this).apply {
            setBackgroundColor(c.bg)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val scheme = request.url.scheme?.lowercase().orEmpty()
                    // 只放行 http/https，其余（intent/file/javascript 等）一律拦截
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrBlank()) updateUrl(url)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank()) updateUrl(url)
                    if (replyAnchor.isNotBlank()) {
                        webView.removeCallbacks(replyAnchorTask)
                        // B 站评论区是异步渲染的，等 DOM 落地后再定位一次。
                        webView.postDelayed(replyAnchorTask, 1200L)
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    // 只有主文档失败才切错误态，子资源失败忽略
                    if (request.isForMainFrame) showError()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (newProgress >= 100) {
                        progressBar.visibility = View.GONE
                    } else {
                        progressBar.visibility = View.VISIBLE
                        val total = root.width.coerceAtLeast(1)
                        progressBar.layoutParams = LinearLayout.LayoutParams(
                            (total * newProgress / 100).coerceAtLeast(0), dp(2))
                    }
                }
            }
        }
        host.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        errorLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(c.bg)
            visibility = View.GONE
        }
        errorLayer.addView(TextView(this).apply {
            text = "网页加载失败"
            textSize = 15f
            typeface = Ds.display(bold = true)
            setTextColor(c.text)
        })
        errorLayer.addView(TextView(this).apply {
            text = "请检查网络后重试"
            textSize = 12.5f
            setTextColor(c.muted)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        errorLayer.addView(TextView(this).apply {
            text = "重新加载"
            textSize = 13.5f
            typeface = Ds.body(bold = true)
            setTextColor(c.accent)
            background = Ds.pill(this@InAppWebActivity, c.accentSoft)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            isClickable = true
            Ds.bindPressFeedback(this, 0.95f)
            setOnClickListener {
                errorLayer.visibility = View.GONE
                webView.reload()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        host.addView(errorLayer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.addView(host, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    /** 评论锚点兜底：SPA 异步渲染时 #reply 未必自动滚到目标，用 JS 找到对应楼层并高亮。 */
    private fun scrollToReplyAnchor() {
        if (replyAnchor.isBlank()) return
        val js = """
            (function(){
              var id='$replyAnchor';
              if(!id) return false;
              var nodes=document.querySelectorAll('[data-rpid="'+id+'"],[data-reply-id="'+id+'"],[id="reply-'+id+'"]');
              for (var i=0;i<nodes.length;i++){
                var n=nodes[i];
                if(!n.getBoundingClientRect) continue;
                n.scrollIntoView({block:'center'});
                n.style.outline='2px solid #fb7299';
                n.style.outlineOffset='2px';
                return true;
              }
              var reply=document.getElementById('comment')||document.querySelector('.reply-area,.comment-area');
              if(reply) reply.scrollIntoView({block:'start'});
              return false;
            })()
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun updateUrl(url: String) {
        currentUrl = url
        urlText.text = url
    }

    private fun showError() {
        errorLayer.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
    }

    private fun openInBrowser() {
        if (!CommentLinkParser.isSafeUrl(currentUrl)) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
        }.onFailure {
            Toast.makeText(this, "未找到可用浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeCallbacks(replyAnchorTask)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 优先网页后退，退无可退才关闭页面
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    /** 网址框内的复制图标。 */
    private class CopyIcon(
        context: Context,
        private val tint: Int,
        onClick: () -> Unit
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            strokeJoin = Paint.Join.ROUND
            color = tint
        }
        private val rect = android.graphics.RectF()

        init {
            isClickable = true
            Ds.bindPressFeedback(this, 0.90f)
            setOnClickListener { onClick() }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = minOf(width, height).toFloat()
            val left = (width - s) / 2f + s * 0.20f
            val top = (height - s) / 2f + s * 0.20f
            val w = s * 0.44f
            rect.set(left, top, left + w, top + w)
            canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, paint)
            rect.offset(s * 0.16f, s * 0.16f)
            canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, paint)
        }
    }

    /** 调用系统浏览器图标。 */
    private class ExternalBrowserIcon(
        context: Context,
        private val tint: Int,
        onClick: () -> Unit
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = tint
        }
        private val path = Path()
        private val rect = android.graphics.RectF()

        init {
            isClickable = true
            Ds.bindPressFeedback(this, 0.90f)
            setOnClickListener { onClick() }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * 0.30f
            // 地球经纬
            canvas.drawCircle(cx, cy, r, paint)
            canvas.drawLine(cx - r, cy, cx + r, cy, paint)
            rect.set(cx - r * 0.5f, cy - r, cx + r * 0.5f, cy + r)
            canvas.drawOval(rect, paint)
            // 右上外链箭头
            path.rewind()
            path.moveTo(cx + r * 0.7f, cy - r * 1.5f)
            path.lineTo(cx + r * 1.5f, cy - r * 1.5f)
            path.lineTo(cx + r * 1.5f, cy - r * 0.7f)
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        private const val EXTRA_URL = "in_app_web_url"
        private const val EXTRA_DESKTOP_UA = "in_app_web_desktop_ua"
        private const val EXTRA_BILI_LOGIN = "in_app_web_bili_login"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        private val BILI_COOKIE_HOSTS = listOf(
            "https://www.bilibili.com",
            "https://message.bilibili.com",
            "https://space.bilibili.com",
            "https://t.bilibili.com",
            "https://live.bilibili.com",
            "https://account.bilibili.com",
            "https://search.bilibili.com",
            "https://bilibili.com",
            "https://api.bilibili.com",
            "https://api.vc.bilibili.com"
        )

        /** 下发 Domain=.bilibili.com 的锚点域。 */
        private const val BILI_COOKIE_ANCHOR = "https://www.bilibili.com"

        fun open(
            context: Context,
            url: String,
            desktopUa: Boolean = false,
            withBiliLogin: Boolean = false
        ) {
            if (!CommentLinkParser.isSafeUrl(url)) return
            context.startActivity(Intent(context, InAppWebActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_DESKTOP_UA, desktopUa)
                putExtra(EXTRA_BILI_LOGIN, withBiliLogin)
            })
        }
    }
}

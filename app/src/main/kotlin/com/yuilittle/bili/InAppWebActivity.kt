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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        Ds.applySystemBars(this)
        LayoutMode.applyScreenOrientation(this)
        window.setBackgroundDrawable(ColorDrawable(Ds.colors.bg))

        val target = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!CommentLinkParser.isSafeUrl(target)) {
            Toast.makeText(this, "不支持的链接类型", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUrl = target
        setContentView(buildUi())
        webView.loadUrl(target)
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

        fun open(context: Context, url: String) {
            if (!CommentLinkParser.isSafeUrl(url)) return
            context.startActivity(Intent(context, InAppWebActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
            })
        }
    }
}

package com.universalbrain.ai.feature.cloning

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Host for one isolated WEB clone instance, in its own OS process. Isolation
 * layers (best available wins):
 *  1. Multi-profile WebView (WebView 116+): a named Profile per instance with
 *     its own cookie store and data directory — real per-instance sessions.
 *  2. Per-process WebView data-directory suffix (API 28+).
 * Fresh-start mode clears cookies/storage before loading the start URL so the
 * site's sign-in page shows on every launch.
 */
abstract class WebInstanceActivity : Activity() {

    private lateinit var instanceId: String
    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar
    private var profileCookieManager: CookieManager? = null

    private fun safeProfileName(): String =
        "uba_" + instanceId.replace("[^A-Za-z0-9_-]".toRegex(), "_").take(40)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instanceId = intent.getStringExtra(CloneRepository.EXTRA_INSTANCE_ID) ?: ""
        val name = intent.getStringExtra(CloneRepository.EXTRA_INSTANCE_NAME) ?: "Instance"
        val startUrl = intent.getStringExtra(CloneRepository.EXTRA_START_URL)
        val freshStart = intent.getBooleanExtra(CloneRepository.EXTRA_FRESH_START, false)
        if (instanceId.isBlank()) {
            finish()
            return
        }

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF14171C.toInt())
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(8), px(10), px(8))
        }
        val title = TextView(this).apply {
            text = name + if (freshStart) " · fresh session" else ""
            textSize = 15f
            setTextColor(0xFFEDF1F5.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(this).apply {
            text = "Close"
            textSize = 12f
            setOnClickListener { finish() }
        }
        top.addView(title)
        top.addView(close)

        val urlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), 0, px(10), px(6))
        }
        urlInput = EditText(this).apply {
            hint = "https://..."
            maxLines = 1
            setTextColor(0xFFEDF1F5.toInt())
            setHintTextColor(0xFF6B7683.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val go = Button(this).apply {
            text = "Go"
            setOnClickListener { loadInput() }
        }
        urlBar.addView(urlInput)
        urlBar.addView(go)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3))
            visibility = ViewGroup.GONE
        }

        webView = createWebView()
        webView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        root.addView(top)
        root.addView(urlBar)
        root.addView(progressBar)
        root.addView(webView)
        setContentView(root)

        if (freshStart) clearSessionStorage()
        configureWebView()
        if (!startUrl.isNullOrBlank()) {
            urlInput.setText(startUrl)
            webView.loadUrl(startUrl)
        }
    }

    /** Multi-profile WebView when supported; per-process suffix as fallback. */
    private fun createWebView(): WebView {
        val multiProfile = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        }.getOrDefault(false)
        if (!multiProfile && Build.VERSION.SDK_INT >= 28) {
            // Legacy path: one WebView data directory per slot process. Must be
            // set before the first WebView exists in this process.
            runCatching { WebView.setDataDirectorySuffix(safeProfileName()) }
        }
        val view = WebView(this)
        if (multiProfile) {
            runCatching {
                WebViewCompat.setProfile(view, safeProfileName())
                profileCookieManager = WebViewCompat.getProfile(view).cookieManager
            }
        }
        return view
    }

    private fun clearSessionStorage() {
        runCatching {
            (profileCookieManager ?: CookieManager.getInstance()).run {
                removeAllCookies(null)
                removeSessionCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearFormData()
            webView.clearHistory()
        }
    }

    @Suppress("DEPRECATION")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= 26) {
                setSafeBrowsingEnabled(true)
            }
        }
        webView.setBackgroundColor(0xFF14171C.toInt())
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                urlInput.setText(url)
                progressBar.visibility = ViewGroup.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Toast.makeText(
                        this@WebInstanceActivity,
                        "Load failed: ${error?.description ?: "network error"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.visibility = if (newProgress in 1..99) ViewGroup.VISIBLE else ViewGroup.GONE
                progressBar.progress = newProgress
            }
        }
    }

    private fun loadInput() {
        val raw = urlInput.text.toString().trim()
        if (raw.isEmpty()) return
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains('.') && !raw.contains(' ') -> "https://$raw"
            else -> "https://duckduckgo.com/?q=" + android.net.Uri.encode(raw)
        }
        webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        // Reset the slot process so the next instance using it starts fresh.
        android.os.Handler(mainLooper).postDelayed({ Process.killProcess(Process.myPid()) }, 250)
        super.onDestroy()
    }
}

class WebInstanceActivity0 : WebInstanceActivity()
class WebInstanceActivity1 : WebInstanceActivity()

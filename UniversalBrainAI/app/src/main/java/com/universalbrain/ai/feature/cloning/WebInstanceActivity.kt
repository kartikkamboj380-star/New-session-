package com.universalbrain.ai.feature.cloning

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Host for one isolated WEB clone instance, in its own OS process. Each slot
 * process sets a unique WebView data-directory suffix, so concurrent WEB
 * instances have genuinely separate storage.
 */
abstract class WebInstanceActivity : Activity() {

    private lateinit var instanceId: String
    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instanceId = intent.getStringExtra(CloneRepository.EXTRA_INSTANCE_ID) ?: ""
        val name = intent.getStringExtra(CloneRepository.EXTRA_INSTANCE_NAME) ?: "Instance"
        if (instanceId.isBlank()) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WebView.setDataDirectorySuffix("uba_" + instanceId.replace("[^A-Za-z0-9_-]".toRegex(), "_").take(40))
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
            text = name
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

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        root.addView(top)
        root.addView(urlBar)
        root.addView(progressBar)
        root.addView(webView)
        setContentView(root)

        configureWebView()
        val startUrl = intent.getStringExtra(EXTRA_START_URL)
        if (!startUrl.isNullOrBlank()) {
            urlInput.setText(startUrl)
            webView.loadUrl(startUrl)
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

    companion object {
        const val EXTRA_START_URL = "extra.start_url"
    }
}

class WebInstanceActivity0 : WebInstanceActivity()
class WebInstanceActivity1 : WebInstanceActivity()

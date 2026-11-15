package com.multispace.manager.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.multispace.manager.MultiSpaceApp
import com.multispace.manager.core.SessionPaths
import com.multispace.manager.core.SessionStatus
import com.multispace.manager.data.db.TabEntity
import com.multispace.manager.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Host activity for one isolated WEB session, running in its own OS process.
 *
 * Why a dedicated process: Android only supports per-process WebView data
 * directories. One process = one WebView data directory suffix = one isolated
 * web session. Four slot subclasses exist in the manifest, so up to four web
 * sessions can be isolated simultaneously; a fifth open attempt is refused
 * with an explicit message instead of silently sharing storage.
 *
 * On Android < 9 (API < 28) setDataDirectorySuffix does not exist; the slot
 * then warns that full storage isolation is unavailable on that OS version.
 */
abstract class WebSlotActivity : Activity() {

    private lateinit var sessionId: String
    private lateinit var sessionName: String

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var tabBarLayout: LinearLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView

    private val webViews = LinkedHashMap<String, WebView>()
    private val tabs = LinkedHashMap<String, TabEntity>()
    private var activeTabId: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        sessionName = intent.getStringExtra(EXTRA_SESSION_NAME) ?: "Session"
        if (sessionId.isBlank()) {
            Toast.makeText(this, "Invalid session id", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // MUST run before any WebView is created in this process.
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            WebView.setDataDirectorySuffix("ms_" + SessionPaths.sanitize(sessionId))
        }

        broadcastStatus(SessionStatus.ACTIVE)
        buildUi()
        loadTabsFromDatabase()
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0F14.toInt())
        }

        // ---- top bar: session name + close ----
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(8), px(12), px(8))
        }
        val title = TextView(this).apply {
            text = sessionName
            textSize = 16f
            setTextColor(0xFFF2F6FA.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeButton = Button(this).apply {
            text = "Close session"
            textSize = 12f
            setOnClickListener {
                ioScope.launch {
                    MultiSpaceApp.instance?.container?.sessionManager?.close(sessionId)
                }
                finish()
            }
        }
        topBar.addView(title)
        topBar.addView(closeButton)

        // ---- URL bar ----
        val urlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), 0, px(12), px(8))
        }
        urlInput = EditText(this).apply {
            hint = "https://…"
            maxLines = 1
            setTextColor(0xFFE6ECF2.toInt())
            setHintTextColor(0xFF6B7683.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val goButton = Button(this).apply {
            text = "Go"
            setOnClickListener { loadFromUrlBar() }
        }
        urlBar.addView(urlInput)
        urlBar.addView(goButton)

        // ---- status line (session status + isolation note) ----
        statusView = TextView(this).apply {
            text = buildStatusLine()
            textSize = 11f
            setTextColor(0xFF2DE2C5.toInt())
            setPadding(px(12), 0, px(12), px(4))
        }

        // ---- tab strip ----
        val tabStripScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        tabBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(8), 0, px(8), 0)
        }
        tabStripScroll.addView(tabBarLayout)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(3)
            )
            visibility = View.GONE
        }

        webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(topBar)
        root.addView(urlBar)
        root.addView(statusView)
        root.addView(tabStripScroll)
        root.addView(progressBar)
        root.addView(webContainer)
        setContentView(root)
    }

    private fun buildStatusLine(): String {
        val isolated = android.os.Build.VERSION.SDK_INT >= 28
        val suffixNote = if (isolated) {
            "Isolated storage: ON (own WebView data directory, own process)"
        } else {
            "Isolated storage: LIMITED (Android < 9 — shared WebView data dir)"
        }
        return "${statusViewTag()} · $suffixNote"
    }

    private fun statusViewTag(): String = "Session ACTIVE"

    @Suppress("DEPRECATION")
    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                setSafeBrowsingEnabled(true)
            }
        }
        webView.setBackgroundColor(0xFF0B0F14.toInt())

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                val title = view.title?.takeIf { it.isNotBlank() } ?: url
                updateTabMeta(title, url)
                urlInput.setText(url)
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest?, error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Toast.makeText(
                        this@WebSlotActivity,
                        "Load failed: ${error?.description ?: "network error"}. Note: cleartext HTTP is blocked by MultiSpace.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
            }
        }
        webView.setDownloadListener(downloadListener())
    }

    private fun downloadListener() = DownloadListener { url, _, contentDisposition, mimeType, _ ->
        ioScope.launch {
            val saved = runCatching {
                com.multispace.manager.web.SessionFileDownloader.download(
                    url, contentDisposition, mimeType,
                    MultiSpaceApp.instance?.container?.sessionManager?.downloadsDir(sessionId)
                        ?: cacheDir
                )
            }.getOrNull()
            runOnUiThread {
                Toast.makeText(
                    this@WebSlotActivity,
                    if (saved != null) "Saved to session downloads: ${saved.name}"
                    else "Download failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadTabsFromDatabase() {
        val container = MultiSpaceApp.instance?.container ?: return finish()
        ioScope.launch {
            val dao = container.db.tabDao()
            val persisted = dao.getForSession(sessionId)
            runOnUiThread {
                if (persisted.isEmpty()) {
                    createNewTab(SessionManager.NEW_TAB_URL)
                } else {
                    persisted.forEach { tabs[it.id] = it }
                    activeTabId = persisted.firstOrNull { it.isActive }?.id
                        ?: persisted.first().id.also { markActive(it) }
                    // Recreate every persisted tab lazily; attach active one now.
                    persisted.forEach { ensureWebView(it) }
                    tabs.forEach { (id, _) -> if (id != activeTabId) detach(id) }
                    activeTabId?.let { attach(it) }
                    rebuildTabStrip()
                    syncUrlBar()
                }
            }
        }
    }

    private fun markActive(tabId: String) {
        val container = MultiSpaceApp.instance?.container ?: return
        ioScope.launch {
            container.db.tabDao().setActiveFlags(sessionId, false)
            container.db.tabDao().markActive(tabId)
        }
    }

    private fun updateTabMeta(title: String, url: String) {
        val tab = activeTabId?.let { tabs[it] } ?: return
        val updated = tab.copy(title = title, url = url)
        tabs[tab.id] = updated
        val container = MultiSpaceApp.instance?.container ?: return
        ioScope.launch { container.db.tabDao().updatePage(tab.id, title, url) }
        rebuildTabStrip()
    }

    private fun createNewTab(url: String) {
        val container = MultiSpaceApp.instance?.container ?: return
        val tab = TabEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            title = "New Tab",
            url = url,
            isActive = false,
            position = tabs.size,
            createdAt = System.currentTimeMillis()
        )
        tabs[tab.id] = tab
        ioScope.launch {
            container.db.tabDao().setActiveFlags(sessionId, false)
            container.db.tabDao().insert(tab.copy(isActive = true))
        }
        activeTabId = tab.id
        ensureWebView(tab)
        attach(tab.id)
        rebuildTabStrip()
        syncUrlBar()
    }

    private fun ensureWebView(tab: TabEntity): WebView {
        webViews[tab.id]?.let { return it }
        val webView = WebView(this)
        configureWebView(webView)
        webViews[tab.id] = webView
        if (tab.url.isNotBlank() && tab.url != "about:blank") {
            webView.loadUrl(tab.url)
        }
        return webView
    }

    private fun attach(tabId: String) {
        val webView = webViews[tabId] ?: return
        if (webView.parent === webContainer) return
        webContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun detach(tabId: String) {
        val webView = webViews[tabId] ?: return
        if (webView.parent === webContainer) {
            webContainer.removeView(webView)
        }
    }

    private fun switchTo(tabId: String) {
        if (!tabs.containsKey(tabId)) return
        activeTabId?.let { detach(it) }
        activeTabId = tabId
        ensureWebView(tabs[tabId]!!)
        attach(tabId)
        markActive(tabId)
        rebuildTabStrip()
        syncUrlBar()
    }

    private fun closeTab(tabId: String) {
        if (tabs.size <= 1) {
            Toast.makeText(this, "Last tab: use 'Close session' instead", Toast.LENGTH_SHORT).show()
            return
        }
        val index = tabs.keys.indexOf(tabId)
        tabs.remove(tabId)
        webViews.remove(tabId)?.destroy()
        val container = MultiSpaceApp.instance?.container ?: return
        ioScope.launch { container.db.tabDao().delete(tabId) }

        if (activeTabId == tabId) {
            val next = tabs.keys.toList()[index.coerceAtMost(tabs.size - 1)]
            activeTabId = next
            ensureWebView(tabs[next]!!)
            attach(next)
            markActive(next)
        }
        rebuildTabStrip()
        syncUrlBar()
    }

    private fun rebuildTabStrip() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        tabBarLayout.removeAllViews()
        tabs.values.sortedBy { it.position }.forEach { tab ->
            val active = tab.id == activeTabId
            val btn = Button(this).apply {
                text = (if (active) "● " else "○ ") + tab.title.take(14)
                textSize = 11f
                isAllCaps = false
                setTextColor(if (active) 0xFF2DE2C5.toInt() else 0xFF9AA7B4.toInt())
                setBackgroundColor(if (active) 0xFF1A2330.toInt() else 0xFF121821.toInt())
                setPadding(px(10), px(4), px(10), px(4))
                setOnClickListener { switchTo(tab.id) }
                setOnLongClickListener {
                    closeTab(tab.id)
                    true
                }
            }
            tabBarLayout.addView(
                btn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = px(6) }
            )
        }
        val newTabButton = Button(this).apply {
            text = "+ New Tab"
            textSize = 11f
            isAllCaps = false
            setTextColor(0xFF7C5CFF.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { createNewTab(SessionManager.NEW_TAB_URL) }
        }
        tabBarLayout.addView(newTabButton)
    }

    private fun syncUrlBar() {
        activeTabId?.let { id ->
            tabs[id]?.let { urlInput.setText(it.url.takeIf { u -> u != "about:blank" } ?: "") }
        }
    }

    private fun loadFromUrlBar() {
        val raw = urlInput.text.toString().trim()
        if (raw.isEmpty()) return
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains('.') && !raw.contains(' ') -> "https://$raw"
            else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(raw, "UTF-8")
        }
        val tab = activeTabId?.let { tabs[it] } ?: return
        tabs[tab.id] = tab.copy(url = url)
        val webView = ensureWebView(tabs[tab.id]!!)
        attach(tab.id)
        webView.loadUrl(url)
    }

    private fun broadcastStatus(status: SessionStatus) {
        val intent = Intent(SessionStatusReceiver.ACTION_SESSION_STATUS)
            .setPackage(packageName)
            .putExtra(SessionStatusReceiver.EXTRA_SESSION_ID, sessionId)
            .putExtra(SessionStatusReceiver.EXTRA_STATUS, status.name)
        sendBroadcast(intent)
    }

    override fun onBackPressed() {
        val webView = activeTabId?.let { webViews[it] }
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        webViews.values.forEach { it.destroy() }
        webViews.clear()
        broadcastStatus(SessionStatus.IDLE)
        // Reset this slot's process so the next session using it starts with a
        // fresh WebView data directory suffix. The snapshot is persisted first.
        ioScope.launch {
            kotlinx.coroutines.delay(250)
            runCatching {
                val container = MultiSpaceApp.instance?.container
                val session = container?.db?.sessionDao()?.get(sessionId)
                session?.let { container.sessionManager.persistSnapshot(it) }
            }
            Process.killProcess(Process.myPid())
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra.session_id"
        const val EXTRA_SESSION_NAME = "extra.session_name"
        const val EXTRA_SLOT_INDEX = "extra.slot_index"
    }
}

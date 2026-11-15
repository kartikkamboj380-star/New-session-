package com.multispace.manager.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multispace.manager.AppContainer
import com.multispace.manager.apk.ApkInstaller
import com.multispace.manager.core.SessionKind
import com.multispace.manager.core.SessionStatus
import com.multispace.manager.core.SessionRecoveryPolicy
import com.multispace.manager.data.db.AppEntity
import com.multispace.manager.data.db.SessionEntity
import com.multispace.manager.data.db.TabEntity
import com.multispace.manager.service.SystemMonitor
import com.multispace.manager.web.SessionStatusReceiver
import com.multispace.manager.web.WebSlotActivity
import com.multispace.manager.web.WebSlotActivity0
import com.multispace.manager.web.WebSlotActivity1
import com.multispace.manager.web.WebSlotActivity2
import com.multispace.manager.web.WebSlotActivity3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class AppUiState(
    val apps: List<AppEntity> = emptyList(),
    val sessions: List<SessionEntity> = emptyList(),
    val tabs: List<TabEntity> = emptyList(),
    val stats: SystemMonitor.Stats? = null,
    val serviceRunning: Boolean = false,
    val importing: Boolean = false,
    val importMessage: String? = null,
    val sessionMessage: String? = null,
    val selectedWebSessionId: String? = null
)

class MultiSpaceViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val importing = MutableStateFlow(false)
    private val importMessage = MutableStateFlow<String?>(null)
    private val sessionMessage = MutableStateFlow<String?>(null)
    private val selectedWebSessionId = MutableStateFlow<String?>(null)

    private val meta = combine(importing, importMessage, sessionMessage, selectedWebSessionId) {
        imp, impMsg, sesMsg, sel ->
        MetaUi(imp, impMsg, sesMsg, sel)
    }

    private data class MetaUi(
        val importing: Boolean,
        val importMessage: String?,
        val sessionMessage: String?,
        val selected: String?
    )

    private val serviceUi = combine(
        container.serviceStats, container.serviceRunning
    ) { stats, running -> stats to running }

    val uiState: StateFlow<AppUiState> = combine(
        container.db.appDao().observeAll(),
        container.db.sessionDao().observeAll(),
        container.db.tabDao().observeAll(),
        serviceUi,
        meta
    ) { apps, sessions, tabs, (stats, running), metaUi ->
        AppUiState(
            apps = apps,
            sessions = sessions,
            tabs = tabs,
            stats = stats,
            serviceRunning = running,
            importing = metaUi.importing,
            importMessage = metaUi.importMessage,
            sessionMessage = metaUi.sessionMessage,
            selectedWebSessionId = metaUi.selected
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    init {
        viewModelScope.launch {
            container.sessionManager.recoverSessions(processAlive = ::anySlotProcessAlive)
        }
        // Foreground dashboard sampling: mirrors the service interval policy
        // (slower on low-RAM devices) so vitals update even when the
        // background manager is stopped. When the service runs, it drives
        // the same flow — the dashboard never invents numbers.
        viewModelScope.launch {
            while (true) {
                val stats = container.systemMonitor.sample()
                if (!container.serviceRunning.value) {
                    container.serviceStats.value = stats
                }
                val plan = com.multispace.manager.core.MonitorPolicy.plan(
                    isLowRamDevice = container.isLowRamDevice,
                    availRamRatio = stats.ramAvailRatio,
                    screenOn = true
                )
                kotlinx.coroutines.delay(plan.intervalMs)
            }
        }
    }

    /**
     * Liveness check used by session recovery. ActivityManager only reports
     * this app's own processes; slot processes do not carry the session id,
     * so when ANY slot process is alive we keep persisted ACTIVE sessions
     * active (conservative, honest) instead of guessing per session.
     */
    private fun anySlotProcessAlive(@Suppress("UNUSED_PARAMETER") sessionId: String): Boolean {
        val am = container.appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val procs = runCatching { am.runningAppProcesses?.map { it.processName } ?: emptyList() }
            .getOrDefault(emptyList())
        return procs.any { it.contains(":web_slot_") }
    }

    // ---- APK import -------------------------------------------------------

    fun importApk(uri: Uri) {
        if (importing.value) return
        importing.value = true
        importMessage.value = null
        viewModelScope.launch {
            when (val result = container.apkImporter.import(uri)) {
                is com.multispace.manager.apk.ApkImporter.ImportResult.Success ->
                    importMessage.value =
                        "Imported ${result.app.label} v${result.app.versionName} (${result.app.packageName})"
                is com.multispace.manager.apk.ApkImporter.ImportResult.Failure ->
                    importMessage.value = result.reason
            }
            importing.value = false
        }
    }

    fun dismissImportMessage() {
        importMessage.value = null
    }

    fun deleteApp(appId: String) {
        viewModelScope.launch { container.apkImporter.delete(appId) }
    }

    // ---- Sessions ---------------------------------------------------------

    fun createSession(
        name: String,
        kind: SessionKind,
        packageName: String?,
        initialUrl: String?
    ) {
        viewModelScope.launch {
            val entity = container.sessionManager.createSession(name, kind, packageName, initialUrl)
            sessionMessage.value = "Session \"${entity.name}\" created (${entity.id.take(8)}…)"
            if (kind == SessionKind.WEB) {
                openWebSession(entity.id)
            }
        }
    }

    /** Opens an isolated WEB session in its own process slot. */
    fun openWebSession(sessionId: String) {
        val slot = container.webSlots.acquire(sessionId)
        if (slot == null) {
            sessionMessage.value =
                "All ${container.webSlots.capacity()} isolation slots are busy. Close a web session first — sessions are never run without isolation."
            return
        }
        selectedWebSessionId.value = sessionId
        viewModelScope.launch {
            val session = container.db.sessionDao().get(sessionId) ?: return@launch
            val activityClass = slotActivity(slot)
            val intent = Intent(container.appContext, activityClass)
                .putExtra(WebSlotActivity.EXTRA_SESSION_ID, sessionId)
                .putExtra(WebSlotActivity.EXTRA_SESSION_NAME, session.name)
                .putExtra(WebSlotActivity.EXTRA_SLOT_INDEX, slot)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { container.appContext.startActivity(intent) }
                .onFailure {
                    container.webSlots.release(sessionId)
                    sessionMessage.value = "Could not open session: ${it.message}"
                }
        }
    }

    /**
     * APP session launch. The app must be installed on the device (MultiSpace
     * offers the platform install flow). Launching uses the OS launcher —
     * there is no in-process APK virtualization, by design.
     */
    fun openAppSession(session: SessionEntity) {
        viewModelScope.launch {
            val pm = container.appContext.packageManager
            val pkg = session.packageName ?: return@launch
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { container.appContext.startActivity(launchIntent) }
                    .onFailure { sessionMessage.value = "Launch failed: ${it.message}" }
            } else {
                val app = container.db.appDao().getByPackage(pkg)
                if (app != null) {
                    sessionMessage.value = "Installing ${app.label} via Android PackageInstaller…"
                    val result = ApkInstaller.install(
                        container.appContext,
                        File(app.apkPath),
                        app.label
                    )
                    if (result.isFailure) {
                        sessionMessage.value =
                            "Install could not start: ${result.exceptionOrNull()?.message}"
                    }
                } else {
                    sessionMessage.value = "Import the APK first, then open this session."
                }
            }
        }
    }

    fun closeSession(sessionId: String) {
        viewModelScope.launch {
            container.sessionManager.close(sessionId)
            container.webSlots.release(sessionId)
        }
    }

    fun restartSession(sessionId: String) {
        viewModelScope.launch {
            val session = container.db.sessionDao().get(sessionId) ?: return@launch
            val kind = runCatching { SessionKind.valueOf(session.kind) }
                .getOrDefault(SessionKind.WEB)
            container.sessionManager.close(sessionId)
            container.sessionManager.restart(sessionId)
            when (kind) {
                SessionKind.WEB -> openWebSession(sessionId)
                SessionKind.APP -> openAppSession(
                    session.copy(status = SessionStatus.ACTIVE.name)
                )
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            container.sessionManager.delete(sessionId)
            container.webSlots.release(sessionId)
            if (selectedWebSessionId.value == sessionId) selectedWebSessionId.value = null
        }
    }

    fun selectWebSessionTab(sessionId: String) {
        selectedWebSessionId.value = sessionId
        val session = uiState.value.sessions.firstOrNull { it.id == sessionId }
        when (runCatching { SessionKind.valueOf(session?.kind ?: "WEB") }.getOrDefault(SessionKind.WEB)) {
            SessionKind.WEB -> openWebSession(sessionId)
            SessionKind.APP -> session?.let { openAppSession(it) }
        }
    }

    /** Single entry point for the Sessions screen "Open" action. */
    fun openSession(session: SessionEntity) {
        selectWebSessionTab(session.id)
    }

    /** Installs an imported APK through the platform PackageInstaller. */
    fun installApp(app: AppEntity) {
        viewModelScope.launch {
            sessionMessage.value = "Installing ${app.label} via Android PackageInstaller…"
            val result = ApkInstaller.install(container.appContext, File(app.apkPath), app.label)
            if (result.isFailure) {
                sessionMessage.value =
                    "Install could not start: ${result.exceptionOrNull()?.message ?: "unknown error"}. Check 'Install unknown apps' permission for MultiSpace."
            }
        }
    }

    fun dismissSessionMessage() {
        sessionMessage.value = null
    }

    private fun slotActivity(slot: Int): Class<out WebSlotActivity> = when (slot % 4) {
        0 -> WebSlotActivity0::class.java
        1 -> WebSlotActivity1::class.java
        2 -> WebSlotActivity2::class.java
        else -> WebSlotActivity3::class.java
    }

    // ---- Background manager ----------------------------------------------

    fun startBackgroundManager() = container.startBackgroundManager()

    fun stopBackgroundManager() = container.stopBackgroundManager()

    // ---- Session status broadcast helper (used by UI to force refresh) ----

    fun statusActionName(): String = SessionStatusReceiver.ACTION_SESSION_STATUS
}

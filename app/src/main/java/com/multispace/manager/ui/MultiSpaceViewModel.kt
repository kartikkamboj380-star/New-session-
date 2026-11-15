package com.multispace.manager.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multispace.manager.AppContainer
import com.multispace.manager.apk.ApkInstaller
import com.multispace.manager.apk.InstallEvent
import com.multispace.manager.apk.InstallPermissionNeededException
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
    val installMessage: String? = null,
    val installSuggestUninstall: Boolean = false,
    val installPackageName: String? = null,
    val sessionMessage: String? = null,
    val selectedWebSessionId: String? = null
)

class MultiSpaceViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val importing = MutableStateFlow(false)
    private val importMessage = MutableStateFlow<String?>(null)
    private val installMessage = MutableStateFlow<String?>(null)
    private val installSuggestUninstall = MutableStateFlow(false)
    private val installPackageName = MutableStateFlow<String?>(null)
    private val sessionMessage = MutableStateFlow<String?>(null)
    private val selectedWebSessionId = MutableStateFlow<String?>(null)

    private val installUi = combine(
        installMessage, installSuggestUninstall, installPackageName
    ) { msg, suggest, pkg -> Triple(msg, suggest, pkg) }

    private val meta = combine(
        importing, importMessage, installUi, sessionMessage, selectedWebSessionId
    ) { imp, impMsg, install, sesMsg, sel ->
        MetaUi(imp, impMsg, install.first, install.second, install.third, sesMsg, sel)
    }

    private data class MetaUi(
        val importing: Boolean,
        val importMessage: String?,
        val installMessage: String?,
        val installSuggestUninstall: Boolean,
        val installPackageName: String?,
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
            installMessage = metaUi.installMessage,
            installSuggestUninstall = metaUi.installSuggestUninstall,
            installPackageName = metaUi.installPackageName,
            sessionMessage = metaUi.sessionMessage,
            selectedWebSessionId = metaUi.selected
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    init {
        viewModelScope.launch {
            container.sessionManager.recoverSessions(processAlive = ::anySlotProcessAlive)
        }
        // Install results from the platform PackageInstaller (broadcasts arrive
        // from the receiver in any process/state): show the exact platform
        // message in-app and auto-launch the app on success.
        viewModelScope.launch {
            container.installEvent.collect { event ->
                if (event == null) return@collect
                installMessage.value = event.message
                installSuggestUninstall.value = event.suggestUninstall
                installPackageName.value = event.packageName
                if (event.kind == InstallEvent.Kind.SUCCESS) {
                    installSuggestUninstall.value = false
                    event.packageName?.let { launchInstalledApp(it) }
                    // OBB best-effort placement + honest note when blocked.
                    val appId = event.appId
                    if (appId != null && event.packageName != null) {
                        val app = container.db.appDao().get(appId)
                        if (app != null) {
                            container.apkImporter.placeObbFiles(app, event.packageName!!)
                                ?.let { note -> installMessage.value = event.message + "\n" + note }
                        }
                    }
                }
                container.installEvent.value = null
            }
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
                    startInstall(app)
                } else {
                    sessionMessage.value = "Import the APK first, then open this session."
                }
            }
        }
    }

    /**
     * Runs the platform PackageInstaller flow. For XAPK/APKS imports the base
     * APK plus every split APK go into ONE install session (the supported
     * way); OBB notes are surfaced after the install result.
     */
    private fun startInstall(app: AppEntity) {
        installMessage.value = "Installing ${app.label} via Android PackageInstaller…"
        val bundle = container.apkImporter.loadBundleInfo(app)
        val files = buildList {
            add(File(app.apkPath))
            bundle?.splitApkPaths?.forEach { add(File(it)) }
        }
        val result = ApkInstaller.install(
            container.appContext,
            files,
            app.label,
            app.packageName,
            app.id
        )
        if (result.isFailure) {
            handleInstallFailure(result.exceptionOrNull())
        }
    }

    private fun handleInstallFailure(error: Throwable?) {
        if (error is InstallPermissionNeededException) {
            openInstallPermissionSettings()
            installMessage.value =
                "MultiSpace needs the 'Install unknown apps' permission. The Settings page opened — enable it, then tap Open again."
        } else {
            installMessage.value =
                "Install could not start: ${error?.message ?: "unknown error"}."
        }
    }

    /** Deep-links to the per-app "Install unknown apps" settings page. */
    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:com.multispace.manager")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { container.appContext.startActivity(intent) }
            .onFailure {
                installMessage.value =
                    "Open Settings → Apps → MultiSpace → 'Install unknown apps' → Allow, then try again."
            }
    }

    /** Launches an installed app by package (used after successful install). */
    private fun launchInstalledApp(packageName: String) {
        val pm = container.appContext.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { container.appContext.startActivity(launchIntent) }
                .onFailure {
                    installMessage.value = "Installed. Open it from the session card (Open)."
                }
        } else {
            installMessage.value =
                "Installed. The app has no launcher screen — open it from your app drawer."
        }
    }

    fun dismissInstallMessage() {
        installMessage.value = null
        installSuggestUninstall.value = false
    }

    /** Opens the system uninstall dialog for an app that blocks an update. */
    fun uninstallExistingApp(packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { container.appContext.startActivity(intent) }
            .onFailure {
                installMessage.value = "Could not open uninstaller for $packageName — remove the app manually, then install again."
            }
        dismissInstallMessage()
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
            startInstall(app)
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

package com.universalbrain.ai.feature.dashboard

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.universalbrain.ai.AppContainer
import com.universalbrain.ai.UbaApp
import com.universalbrain.ai.core.database.Account
import com.universalbrain.ai.core.database.AccountSession
import com.universalbrain.ai.core.database.AiMessage
import com.universalbrain.ai.core.database.AutomationRule
import com.universalbrain.ai.core.database.CloneInstance
import com.universalbrain.ai.core.database.Earning
import com.universalbrain.ai.core.database.ImportedApp
import com.universalbrain.ai.core.database.MiningWorker
import com.universalbrain.ai.feature.aiagent.AIBrain
import com.universalbrain.ai.feature.aiagent.Conversations
import com.universalbrain.ai.feature.aiagent.MemoryManager
import com.universalbrain.ai.feature.cloning.CloneRepository
import com.universalbrain.ai.integrations.llm.LlmClient
import com.universalbrain.ai.work.MiningPollWorker
import com.universalbrain.ai.work.RuleEvalWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val Context.settingsStore by preferencesDataStore(name = "uba_settings")

data class ChatMessageUi(
    val id: String,
    val role: String,
    val text: String,
    val tool: String?,
    val pending: Boolean = false
)

data class PendingConfirmation(val planId: String, val preview: String)

/**
 * Central VM exposing real flows for every module + all user actions.
 */
class UbaViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val settings = container.appContext.settingsStore

    // ---- settings-backed state ----
    val onboarded: StateFlow<Boolean> = settings.data.map { it[KEY_ONBOARDED] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val adsConsent: StateFlow<Boolean> = settings.data.map { it[KEY_ADS_CONSENT] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val llmBaseUrl = MutableStateFlow("")
    private val llmModel = MutableStateFlow("")
    private val llmKeySet = MutableStateFlow(false)

    // ---- databases ----
    val importedApps = container.db.appDao().observeAll()
    val instances = container.db.cloneDao().observeAll()
    val accounts = container.db.accountDao().observeAll()
    val workers = container.db.miningDao().observeWorkers()
    val earnings = container.db.miningDao().observeEarnings(200)
    val rules = container.db.automationDao().observeRules()
    val notifications = container.db.opsDao().observeNotifications(100)
    val logs = container.db.opsDao().observeLogs(200)
    val tasks = container.db.opsDao().observeTasks(50)
    val totalHashrate = container.db.miningDao().observeTotalHashrate()
    val memoryCount = container.memoryManager.observeMemoryCount()
    val unreadCount = container.db.opsDao().observeUnreadCount()

    val vitals = MutableStateFlow<com.universalbrain.ai.core.monitor.DeviceMonitor.Vitals?>(null)
    val engineRunning = container.engineRunning
    val miningPaused = container.miningRepository.paused
    val network = container.networkMonitor.state

    // ---- scanning / import ----
    data class ScannerState(
        val apps: List<CloneRepository.InstalledAppInfo> = emptyList(),
        val selected: Set<String> = emptySet(),
        val query: String = "",
        val filter: String = "ALL", // ALL / USER / SYSTEM
        val importing: Boolean = false,
        val importedPackages: Set<String> = emptySet()
    )

    val scanner = MutableStateFlow(ScannerState())

    // ---- chat ----
    val chat: Flow<List<AiMessage>> = container.db.aiDao().observeConversation(Conversations.DEFAULT)
    val aiBusy = MutableStateFlow(false)
    val aiTyping = MutableStateFlow(false)
    val toolStatus = MutableStateFlow<String?>(null)
    val pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)

    val brain = AIBrain(container, container.memoryManager)

    val clonePolicy: com.universalbrain.ai.domain.cloning.ClonePolicy.Decision by lazy {
        container.cloneRepository.policy(container.deviceMonitor.totalRamMb(), container.isLowRamDevice)
    }

    init {
        viewModelScope.launch {
            while (true) {
                vitals.value = container.deviceMonitor.sample()
                delayCompat(5000)
            }
        }
        viewModelScope.launch { seedBuiltInRules() }
        viewModelScope.launch {
            val cfg = loadLlmConfig()
            llmBaseUrl.value = cfg.first
            llmModel.value = cfg.second
            llmKeySet.value = cfg.third != null
        }
        RuleEvalWorker.schedule(container.appContext)
    }

    private suspend fun delayCompat(ms: Long) = kotlinx.coroutines.delay(ms)

    private suspend fun seedBuiltInRules() {
        if (container.db.automationDao().count() == 0) {
            container.db.automationDao().insertAll(
                listOf(
                    AutomationRule(
                        id = UUID.randomUUID().toString(),
                        name = "Network lost alert",
                        conditionType = "NETWORK_LOST", conditionValue = "1",
                        actionType = "NOTIFY", actionValue = "",
                        enabled = true, lastTriggeredAt = null
                    ),
                    AutomationRule(
                        id = UUID.randomUUID().toString(),
                        name = "Network restored alert",
                        conditionType = "NETWORK_RESTORED", conditionValue = "1",
                        actionType = "NOTIFY", actionValue = "",
                        enabled = true, lastTriggeredAt = null
                    ),
                    AutomationRule(
                        id = UUID.randomUUID().toString(),
                        name = "High temperature alert",
                        conditionType = "BATTERY_TEMP_OVER", conditionValue = "43",
                        actionType = "NOTIFY", actionValue = "",
                        enabled = true, lastTriggeredAt = null
                    ),
                    AutomationRule(
                        id = UUID.randomUUID().toString(),
                        name = "Worker offline alert",
                        conditionType = "WORKER_OFFLINE", conditionValue = "1",
                        actionType = "NOTIFY", actionValue = "",
                        enabled = true, lastTriggeredAt = null
                    )
                )
            )
        }
    }

    // ---- scanning & import ----

    fun refreshScanner() {
        viewModelScope.launch {
            scanner.value = scanner.value.copy(
                apps = container.cloneRepository.installedApps(),
                importedPackages = importedApps.first().map { it.packageName }.toSet()
            )
        }
    }

    fun setScannerQuery(query: String) {
        scanner.value = scanner.value.copy(query = query)
    }

    fun setScannerFilter(filter: String) {
        scanner.value = scanner.value.copy(filter = filter)
    }

    fun toggleSelect(packageName: String) {
        val current = scanner.value.selected.toMutableSet()
        if (!current.add(packageName)) current.remove(packageName)
        scanner.value = scanner.value.copy(selected = current)
    }

    fun importSelected() {
        val state = scanner.value
        if (state.selected.isEmpty() || state.importing) return
        viewModelScope.launch {
            scanner.value = state.copy(importing = true)
            val now = System.currentTimeMillis()
            val toImport = state.apps.filter { it.packageName in state.selected }
            val entities = toImport.map { app ->
                ImportedApp(
                    id = UUID.randomUUID().toString(),
                    packageName = app.packageName,
                    label = app.label,
                    versionName = app.versionName,
                    versionCode = app.versionCode,
                    isSystem = app.isSystem,
                    compatibility = compatibilityOf(app),
                    importedAt = now
                )
            }
            container.db.appDao().insertAll(entities)
            container.db.opsDao().insertLog(
                com.universalbrain.ai.core.database.ActivityLog(
                    id = UUID.randomUUID().toString(), at = now, module = "IMPORT",
                    action = "IMPORTED", detail = "${entities.size} app(s)"
                )
            )
            scanner.value = scanner.value.copy(selected = emptySet(), importing = false)
            refreshScanner()
        }
    }

    private fun compatibilityOf(app: CloneRepository.InstalledAppInfo): String = when {
        app.isSystem -> "LIMITED (system app)"
        container.cloneRepository.installedApps().none { it.packageName == app.packageName } -> "UNKNOWN"
        else -> "SUPPORTED"
    }

    fun removeImported(appId: String) {
        viewModelScope.launch {
            container.db.appDao().delete(appId)
            refreshScanner()
        }
    }

    // ---- instances ----

    fun createInstance(appPackage: String, appLabel: String, kind: String, name: String?, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val current = container.db.cloneDao().count()
            if (!com.universalbrain.ai.domain.cloning.ClonePolicy.canCreate(current, clonePolicy)) {
                onResult("Instance cap reached (${clonePolicy.maxInstances}) for this device: ${clonePolicy.reason}")
                return@launch
            }
            val instance = container.cloneRepository.create(appPackage, appLabel, kind, name)
            onResult(null)
            log("CLONES", "CREATED", instance.name)
        }
    }

    fun bulkCreate(appPackage: String, appLabel: String, kind: String, count: Int, onResult: (String) -> Unit) {
        viewModelScope.launch {
            var created = 0
            var blocked: String? = null
            repeat(count) {
                val current = container.db.cloneDao().count()
                if (!com.universalbrain.ai.domain.cloning.ClonePolicy.canCreate(current, clonePolicy)) {
                    blocked = "Cap reached (${clonePolicy.maxInstances}): ${clonePolicy.reason}"
                    return@repeat
                }
                container.cloneRepository.create(appPackage, appLabel, kind, null)
                created++
            }
            onResult(if (blocked != null) "Created $created. $blocked" else "Created $created instance(s).")
        }
    }

    fun renameInstance(id: String, newName: String) {
        viewModelScope.launch { container.cloneRepository.rename(id, newName) }
    }

    fun deleteInstance(id: String) {
        viewModelScope.launch { container.cloneRepository.delete(id) }
    }

    fun launchInstance(id: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val instance = container.db.cloneDao().get(id) ?: return@launch
            val error = container.cloneRepository.launch(instance)
            onResult(error)
        }
    }

    fun assignAccount(instanceId: String, accountId: String?) {
        viewModelScope.launch { container.db.cloneDao().assignAccount(instanceId, accountId) }
    }

    suspend fun instance(id: String): CloneInstance? = container.db.cloneDao().get(id)

    // ---- accounts ----

    fun addAccount(label: String, group: String, username: String, secret: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            if (label.isBlank() || username.isBlank()) {
                onResult("Label and username are required.")
                return@launch
            }
            val id = UUID.randomUUID().toString()
            val secretRef = "acct_$id"
            container.secureStore.put(secretRef, secret)
            container.db.accountDao().insert(
                Account(
                    id = id, label = label, groupName = group.ifBlank { "Default" },
                    username = username, secretRef = secretRef, status = "ACTIVE",
                    instanceId = null, createdAt = System.currentTimeMillis(),
                    lastActiveAt = System.currentTimeMillis()
                )
            )
            container.db.accountDao().insertSession(
                AccountSession(
                    id = UUID.randomUUID().toString(), accountId = id,
                    startedAt = System.currentTimeMillis(), endedAt = null,
                    status = "OPEN", note = "Account registered"
                )
            )
            onResult(null)
        }
    }

    fun deleteAccount(id: String) {
        viewModelScope.launch {
            val account = container.db.accountDao().get(id)
            account?.let { container.secureStore.remove(it.secretRef) }
            container.db.accountDao().delete(id)
        }
    }

    fun setAccountStatus(id: String, status: String) {
        viewModelScope.launch {
            container.db.accountDao().setStatus(id, status, System.currentTimeMillis())
            if (status == "DISCONNECTED") {
                container.db.accountDao().closeOpenSessions(id, System.currentTimeMillis(), "CLOSED")
            }
        }
    }

    suspend fun account(id: String): Account? = container.db.accountDao().get(id)

    suspend fun accountSessions(id: String) = container.db.accountDao().sessions(id)

    suspend fun revealSecret(accountId: String): String? {
        val account = container.db.accountDao().get(accountId) ?: return null
        return container.secureStore.get(account.secretRef)
    }

    // ---- chat ----

    fun sendChat(text: String) {
        if (text.isBlank() || aiBusy.value) return
        viewModelScope.launch {
            aiBusy.value = true
            val now = System.currentTimeMillis()
            container.db.aiDao().insertMessage(
                AiMessage(UUID.randomUUID().toString(), Conversations.DEFAULT, "USER", text, null, now)
            )
            var llmTried = false
            if (llmBaseUrl.value.isNotBlank() && llmKeySet.value) {
                llmTried = true
                aiTyping.value = true
                val history = chat.first().map { it.role.lowercase() to it.text }.takeLast(10)
                val reply = container.llmClient.complete(
                    LlmClient.Config(llmBaseUrl.value, container.secureStore.get("llm_api_key") ?: "", llmModel.value.ifBlank { "gpt-4o-mini" }),
                    "You are MASTER BRAIN, the assistant inside the Universal Brain AI Android app. Answer briefly.",
                    history
                )
                aiTyping.value = false
                if (reply != null) {
                    container.db.aiDao().insertMessage(
                        AiMessage(UUID.randomUUID().toString(), Conversations.DEFAULT, "ASSISTANT", reply, "LLM", System.currentTimeMillis())
                    )
                    aiBusy.value = false
                    return@launch
                }
                container.db.aiDao().insertMessage(
                    AiMessage(UUID.randomUUID().toString(), Conversations.DEFAULT, "ASSISTANT",
                        "(LLM bridge failed — falling back to the local brain.)", "LLM", System.currentTimeMillis())
                )
            }
            brain.handle(text).collect { event ->
                when (event) {
                    is AIBrain.AgentEvent.Thinking -> aiTyping.value = true
                    is AIBrain.AgentEvent.ToolStarted -> toolStatus.value = "Running tool: ${event.tool}"
                    is AIBrain.AgentEvent.ToolDone -> toolStatus.value = null
                    is AIBrain.AgentEvent.NeedsConfirmation ->
                        pendingConfirmation.value = PendingConfirmation(event.planId, event.preview)
                    is AIBrain.AgentEvent.Final -> {
                        aiTyping.value = false
                        toolStatus.value = null
                        container.db.aiDao().insertMessage(
                            AiMessage(UUID.randomUUID().toString(), Conversations.DEFAULT, "ASSISTANT", event.text, null, System.currentTimeMillis())
                        )
                    }
                }
            }
            aiBusy.value = false
            if (!llmTried) Unit
        }
    }

    fun confirmPlan(planId: String) {
        val plan = brain.pendingPlan(planId) ?: return
        pendingConfirmation.value = null
        brain.clearPending(planId)
        viewModelScope.launch {
            aiBusy.value = true
            aiTyping.value = true
            val final = brain.executeApproved(plan)
            aiTyping.value = false
            container.db.aiDao().insertMessage(
                AiMessage(UUID.randomUUID().toString(), Conversations.DEFAULT, "ASSISTANT", final, null, System.currentTimeMillis())
            )
            aiBusy.value = false
        }
    }

    fun cancelPlan(planId: String) {
        brain.clearPending(planId)
        pendingConfirmation.value = null
        viewModelScope.launch {
            container.db.aiDao().insertMessage(
                AiMessage(UUID.randomUUID().toString(), Conversations.DEFAULT, "ASSISTANT", "Cancelled.", null, System.currentTimeMillis())
            )
        }
    }

    // ---- mining ----

    fun addWorker(name: String, apiUrl: String, wallet: String, coin: String, intervalMin: Int, onResult: (String?) -> Unit) {
        if (apiUrl.isBlank() || wallet.isBlank()) {
            onResult("Pool API URL and wallet are required.")
            return
        }
        viewModelScope.launch {
            container.miningRepository.addWorker(name, apiUrl, wallet, coin, intervalMin)
            MiningPollWorker.schedule(container.appContext, intervalMin)
            onResult(null)
        }
    }

    fun deleteWorker(id: String) {
        viewModelScope.launch { container.miningRepository.deleteWorker(id) }
    }

    fun pollNow(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val ok = container.miningRepository.pollAllWorkers()
            val total = container.db.miningDao().allWorkers().size
            onResult("Polled $total worker(s): $ok succeeded${if (ok < total) " — failures are shown on each worker card" else ""}.")
        }
    }

    fun setMiningPaused(paused: Boolean) {
        viewModelScope.launch { container.miningRepository.setPaused(paused) }
    }

    fun setBaseInterval(minutes: Int) {
        viewModelScope.launch {
            container.miningRepository.setBaseInterval(minutes)
            MiningPollWorker.schedule(container.appContext, minutes)
        }
    }

    // ---- automation ----

    fun toggleRule(id: String, enabled: Boolean) {
        viewModelScope.launch { container.db.automationDao().setEnabled(id, enabled) }
    }

    fun addRule(name: String, condition: String, value: String, action: String, onResult: (String?) -> Unit) {
        if (name.isBlank()) {
            onResult("Rule name is required.")
            return
        }
        viewModelScope.launch {
            container.db.automationDao().insert(
                AutomationRule(
                    id = UUID.randomUUID().toString(), name = name,
                    conditionType = condition, conditionValue = value,
                    actionType = action, actionValue = "", enabled = true, lastTriggeredAt = null
                )
            )
            onResult(null)
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch { container.db.automationDao().delete(id) }
    }

    // ---- engine ----

    fun startEngine() {
        viewModelScope.launch {
            container.startEngine()
            log("ENGINE", "STARTED", null)
        }
    }

    fun stopEngine() {
        viewModelScope.launch {
            container.stopEngine()
            log("ENGINE", "STOPPED", null)
        }
    }

    fun pauseEngine() = container.appContext.startService(
        android.content.Intent(container.appContext, com.universalbrain.ai.service.BrainService::class.java)
            .setAction(com.universalbrain.ai.service.BrainService.ACTION_PAUSE)
    )

    fun resumeEngine() = container.appContext.startService(
        android.content.Intent(container.appContext, com.universalbrain.ai.service.BrainService::class.java)
            .setAction(com.universalbrain.ai.service.BrainService.ACTION_RESUME)
    )

    // ---- notifications / logs ----

    fun markNotificationsRead() {
        viewModelScope.launch { container.db.opsDao().markAllRead() }
    }

    fun clearNotifications() {
        viewModelScope.launch { container.db.opsDao().clearNotifications() }
    }

    // ---- settings ----

    fun setOnboarded() {
        viewModelScope.launch { settings.edit { it[KEY_ONBOARDED] = true } }
    }

    fun setAdsConsent(consent: Boolean) {
        viewModelScope.launch { settings.edit { it[KEY_ADS_CONSENT] = consent } }
    }

    private suspend fun loadLlmConfig(): Triple<String, String, String?> {
        val prefs = settings.data.first()
        return Triple(
            prefs[KEY_LLM_URL] ?: "",
            prefs[KEY_LLM_MODEL] ?: "",
            container.secureStore.get("llm_api_key")
        )
    }

    fun saveLlmConfig(baseUrl: String, model: String, apiKey: String?, onResult: (String) -> Unit) {
        viewModelScope.launch {
            settings.edit {
                it[KEY_LLM_URL] = baseUrl.trim()
                it[KEY_LLM_MODEL] = model.trim()
            }
            if (!apiKey.isNullOrBlank()) {
                container.secureStore.put("llm_api_key", apiKey)
            }
            llmBaseUrl.value = baseUrl.trim()
            llmModel.value = model.trim()
            llmKeySet.value = container.secureStore.get("llm_api_key") != null
            onResult("LLM bridge saved${if (baseUrl.isBlank()) " (disabled — local brain active)" else ""}.")
        }
    }

    fun llmStatus(): Flow<Triple<String, String, Boolean>> = combine(llmBaseUrl, llmModel, llmKeySet) { url, model, keySet ->
        Triple(url, model, keySet)
    }

    fun wipeSecureData(onResult: (String) -> Unit) {
        viewModelScope.launch {
            container.secureStore.clearAll()
            onResult("All stored secrets wiped (account passwords, LLM key).")
        }
    }

    private suspend fun log(module: String, action: String, detail: String?) {
        container.db.opsDao().insertLog(
            com.universalbrain.ai.core.database.ActivityLog(
                id = UUID.randomUUID().toString(), at = System.currentTimeMillis(),
                module = module, action = action, detail = detail
            )
        )
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = UbaViewModel(container) as T
    }

    companion object {
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        private val KEY_ADS_CONSENT = booleanPreferencesKey("ads_consent")
        private val KEY_LLM_URL = stringPreferencesKey("llm_base_url")
        private val KEY_LLM_MODEL = stringPreferencesKey("llm_model")
    }
}

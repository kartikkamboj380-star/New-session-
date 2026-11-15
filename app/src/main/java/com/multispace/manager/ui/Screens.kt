package com.multispace.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multispace.manager.core.BatteryReading
import com.multispace.manager.core.SessionKind
import com.multispace.manager.core.SessionStatus
import com.multispace.manager.data.db.AppEntity
import com.multispace.manager.data.db.SessionEntity
import com.multispace.manager.service.SystemMonitor

@Composable
fun DashboardScreen(state: AppUiState, profileCapability: String, lowRamDevice: Boolean) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SectionHeader("Device vitals")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val stats = state.stats
                StatCard(
                    title = "RAM",
                    value = stats?.let { "${it.ramUsedPct.toInt()}%" } ?: "—",
                    detail = stats?.let { "${it.ramAvailMb} / ${it.ramTotalMb} MB free" } ?: "service off",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "CPU",
                    value = stats?.cpuPct?.let { "${it.toInt()}%" } ?: "N/A",
                    detail = if (stats?.cpuPct == null) "sealed /proc/stat" else "since last sample",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Battery",
                    value = stats?.let { "${"%.1f".format(it.batteryTempC)}°" } ?: "—",
                    detail = stats?.let {
                        BatteryReading.label(it.batteryTempC) + if (it.batteryCharging) " · charging" else ""
                    } ?: "service off",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            SectionHeader("Background status")
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .width(10.dp)
                                .height(10.dp)
                                .background(
                                    if (state.serviceRunning) StatusGreen else StatusAmber,
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        Text(
                            if (state.serviceRunning) "Background manager RUNNING"
                            else "Background manager STOPPED",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        if (state.serviceRunning) {
                            "Foreground service active: session snapshots persist while you use other apps; sampling slows with the screen off."
                        } else {
                            "Start it to keep session state snapshots and monitoring running in the background."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        item {
            SectionHeader("Active sessions")
            val active = state.sessions.filter { it.status == SessionStatus.ACTIVE.name }
            if (active.isEmpty()) {
                Text(
                    "No active sessions. Create one from the Sessions tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            } else {
                Column {
                    active.forEach { session ->
                        Text(
                            "● ${session.name} — ${session.kind}",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            color = StatusGreen
                        )
                    }
                }
            }
        }
        item {
            SectionHeader("Device capabilities (honest limits)")
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        if (lowRamDevice) "Low-RAM device (Android Go class): sampling is throttled to protect memory."
                        else "Standard RAM device.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        profileCapability,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        "Note: a normal Android app cannot run another app's APK inside itself with a separate identity. MultiSpace uses the supported levels (device install + isolated profiles, per-process WebView sessions) and never fakes execution.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun AppsScreen(
    state: AppUiState,
    onImport: () -> Unit,
    onInstall: (AppEntity) -> Unit,
    onDelete: (AppEntity) -> Unit,
    onDismissImportMessage: () -> Unit,
    onDismissInstallMessage: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SectionHeader("Imported apps")
            Button(
                onClick = onImport,
                enabled = !state.importing,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(if (state.importing) "Importing…" else "Import App (APK)")
            }
            if (state.importing) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp).width(28.dp).height(28.dp)
                )
            }
        }
        items(state.apps, key = { it.id }) { app ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(app)
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "v${app.versionName} (${app.versionCode}) · ${app.sizeBytes / 1024} KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { onInstall(app) }) {
                            Text("Install on device")
                        }
                        TextButton(onClick = { onDelete(app) }) {
                            Text("Remove", color = StatusRed)
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
        }
    }
    state.importMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissImportMessage,
            confirmButton = { TextButton(onClick = onDismissImportMessage) { Text("OK") } },
            title = { Text(if (message.startsWith("Imported")) "Import complete" else "Import problem") },
            text = { Text(message) }
        )
    }
    // Live status of the platform PackageInstaller flow (pending confirmation,
    // success incl. auto-launch, or the exact platform failure message).
    state.installMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissInstallMessage,
            confirmButton = { TextButton(onClick = onDismissInstallMessage) { Text("OK") } },
            title = { Text("Install") },
            text = { Text(message) }
        )
    }
}

@Composable
fun SessionsScreen(
    state: AppUiState,
    onCreateSession: (String, SessionKind, String?, String?) -> Unit,
    onOpenSession: (SessionEntity) -> Unit,
    onCloseSession: (String) -> Unit,
    onRestartSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDismissSessionMessage: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text("New Session")
        }
        SectionHeader("All sessions (${state.sessions.size})")
        if (state.sessions.isEmpty()) {
            Text(
                "No sessions yet. Tap 'New Session' — WEB sessions get an isolated per-process WebView; APP sessions launch installed imported apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        LazyColumn {
            items(state.sessions, key = { it.id }) { session ->
                SessionCard(
                    session = session,
                    onOpen = { onOpenSession(session) },
                    onClose = { onCloseSession(session.id) },
                    onRestart = { onRestartSession(session.id) },
                    onDelete = { onDeleteSession(session.id) }
                )
            }
        }
    }
    if (showCreateDialog) {
        NewSessionDialog(
            apps = state.apps,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, kind, pkg, url ->
                showCreateDialog = false
                onCreateSession(name, kind, pkg, url)
            }
        )
    }
    state.sessionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissSessionMessage,
            confirmButton = { TextButton(onClick = onDismissSessionMessage) { Text("OK") } },
            title = { Text("Sessions") },
            text = { Text(message) }
        )
    }
}

@Composable
fun NewSessionDialog(
    apps: List<AppEntity>,
    onDismiss: () -> Unit,
    onCreate: (String, SessionKind, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(SessionKind.WEB) }
    var url by remember { mutableStateOf("") }
    var selectedPackage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Session") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Session name") },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = kind == SessionKind.WEB, onClick = { kind = SessionKind.WEB })
                    Text("WEB (isolated WebView)", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = kind == SessionKind.APP, onClick = { kind = SessionKind.APP })
                    Text("APP (installed app launch)", fontSize = 13.sp)
                }
                if (kind == SessionKind.WEB) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Start URL (optional)") },
                        singleLine = true
                    )
                    Text(
                        "Each WEB session runs in its own Android process with its own WebView data directory (up to 4 concurrent).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    if (apps.isEmpty()) {
                        Text(
                            "No imported apps yet — import an APK first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusAmber
                        )
                    } else {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            apps.take(4).forEach { app ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selectedPackage == app.packageName,
                                        onClick = { selectedPackage = app.packageName }
                                    )
                                    Text("${app.label} (${app.packageName})", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(name, kind, selectedPackage, url)
                },
                enabled = name.isNotBlank() && (kind == SessionKind.WEB || selectedPackage != null)
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SystemScreen(
    state: AppUiState,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    slotUsage: Pair<Int, Int>
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SectionHeader("Background manager")
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onStartService, enabled = !state.serviceRunning) {
                    Text("Start")
                }
                Button(onClick = onStopService, enabled = state.serviceRunning) {
                    Text("Stop")
                }
            }
            Text(
                "Foreground service (specialUse): persistent notification, periodic session snapshots, battery/CPU/RAM sampling. User-controlled start/stop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        item {
            SectionHeader("Resource usage")
            state.stats?.let { stats ->
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    UsageRow(
                        label = "RAM used",
                        fraction = stats.ramUsedPct / 100f,
                        valueText = "${stats.ramUsedPct.toInt()}% (${stats.ramTotalMb - stats.ramAvailMb}/${stats.ramTotalMb} MB)",
                        color = if (stats.lowMemory || stats.ramAvailRatio < 0.15f) StatusRed else AccentPurple
                    )
                    UsageRow(
                        label = "CPU" + if (stats.cpuPct == null) " (unavailable)" else "",
                        fraction = (stats.cpuPct ?: 0f) / 100f,
                        valueText = stats.cpuPct?.let { "${it.toInt()}%" } ?: "N/A",
                        color = StatusAmber
                    )
                    UsageRow(
                        label = "Battery temperature",
                        fraction = (stats.batteryTempC / 60f).coerceIn(0f, 1f),
                        valueText = "${"%.1f".format(stats.batteryTempC)}°C — ${BatteryReading.label(stats.batteryTempC)}",
                        color = when {
                            stats.batteryTempC >= 43f -> StatusRed
                            stats.batteryTempC >= 35f -> StatusAmber
                            else -> StatusGreen
                        }
                    )
                }
            } ?: Text(
                "Start the background manager to sample live stats. The dashboard samples on its own while the app is open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        item {
            SectionHeader("Isolation slots")
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "WEB session isolation slots: ${slotUsage.first} / ${slotUsage.second} in use",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Each slot is a separate Android process with a dedicated WebView data directory. When all slots are busy, new WEB sessions wait — they are never silently run without isolation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

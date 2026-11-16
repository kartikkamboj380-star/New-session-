package com.universalbrain.ai.feature.automation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.universalbrain.ai.Routes
import com.universalbrain.ai.feature.dashboard.UbaViewModel
import com.universalbrain.ai.feature.shell.ErrorRed
import com.universalbrain.ai.feature.shell.SectionHeader
import com.universalbrain.ai.feature.shell.SimpleCard
import com.universalbrain.ai.feature.shell.StatCard
import com.universalbrain.ai.feature.shell.StatusDot
import com.universalbrain.ai.feature.shell.statusColor

@Composable
fun AutomationScreen(vm: UbaViewModel, navController: NavHostController) {
    val rules by vm.rules.collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Automation Center")
        Button(onClick = { showAdd = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("New rule")
        }
        Text(
            "The engine evaluates rules against live device vitals, network state and worker status, then executes approved actions.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(rules, key = { it.id }) { rule ->
                SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "WHEN ${rule.conditionType} (${rule.conditionValue}) THEN ${rule.actionType}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            rule.lastTriggeredAt?.let {
                                Text(
                                    "Last triggered: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(checked = rule.enabled, onCheckedChange = { vm.toggleRule(rule.id, it) })
                        TextButton(onClick = { vm.deleteRule(rule.id) }) {
                            Text("Delete", color = ErrorRed)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    if (showAdd) {
        AddRuleDialog(onDismiss = { showAdd = false }, onAdd = { name, condition, value, action ->
            showAdd = false
            vm.addRule(name, condition, value, action) { error -> message = error }
        })
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Automation") },
            text = { Text(it) }
        )
    }
}

@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("BATTERY_TEMP_OVER") }
    var value by remember { mutableStateOf("43") }
    var action by remember { mutableStateOf("NOTIFY") }
    val conditions = listOf(
        "BATTERY_TEMP_OVER", "BATTERY_LEVEL_UNDER", "RAM_AVAIL_UNDER",
        "NETWORK_LOST", "NETWORK_RESTORED", "WORKER_OFFLINE"
    )
    val actions = listOf("NOTIFY", "PAUSE_MINING", "RESUME_MINING", "START_SERVICE", "STOP_SERVICE")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New automation rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Rule name") }, singleLine = true)
                Text("Condition", style = MaterialTheme.typography.labelSmall)
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    conditions.forEach { c ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { condition = c }
                        ) {
                            androidx.compose.material3.RadioButton(selected = condition == c, onClick = { condition = c })
                            Text(c, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Threshold / count") }, singleLine = true)
                Text("Action", style = MaterialTheme.typography.labelSmall)
                Column {
                    actions.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { action = a }
                        ) {
                            androidx.compose.material3.RadioButton(selected = action == a, onClick = { action = a })
                            Text(a, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, condition, value, action) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun BackgroundOpsScreen(vm: UbaViewModel, navController: NavHostController) {
    val engine by vm.engineRunning.collectAsState()
    val tasks by vm.tasks.collectAsState(initial = emptyList())
    val paused by vm.miningPaused.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Background Operations")
        Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.startEngine() }, enabled = !engine) { Text("Start") }
            Button(onClick = { vm.stopEngine() }, enabled = engine) { Text("Stop") }
            TextButton(onClick = { if (engine) vm.pauseEngine() }, enabled = engine && !paused) { Text("Pause") }
            TextButton(onClick = { if (engine) vm.resumeEngine() }, enabled = engine && paused) { Text("Resume") }
        }
        Text(
            if (engine) "Foreground service active: vitals sampling, rule evaluation, worker polling. Intervals double on low battery/heat."
            else "Engine stopped. WorkManager keeps periodic mining polls and rule checks alive.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
        SectionHeader("Tasks")
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(tasks, key = { it.id }) { task ->
                SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(statusColor(task.status))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(task.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${task.type} · started ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(task.startedAt))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(task.status, color = statusColor(task.status), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun DeviceMonitorScreen(vm: UbaViewModel, navController: NavHostController) {
    val vitals by vm.vitals.collectAsState()
    val network by vm.network.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Device Monitor")
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("RAM", vitals?.let { "${it.ramUsedPct.toInt()}%" } ?: "—",
                vitals?.let { "${it.ramAvailMb}/${it.ramTotalMb} MB free" } ?: "sampling", Modifier.weight(1f))
            StatCard("CPU", vitals?.cpuPct?.let { "${it.toInt()}%" } ?: "N/A",
                if (vitals?.cpuPct == null) "sealed /proc/stat" else "live", Modifier.weight(1f), com.universalbrain.ai.feature.shell.AccentBlue)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Battery", vitals?.let { "${it.batteryLevelPct}%" } ?: "—",
                vitals?.let { "${it.batteryTempC}C ${if (it.batteryCharging) "charging" else ""}" } ?: "", Modifier.weight(1f))
            StatCard("Storage", vitals?.let { "${"%.1f".format(it.storageFreeGb)} GB" } ?: "—",
                "free (app scope)", Modifier.weight(1f), com.universalbrain.ai.feature.shell.AccentBlue)
        }
        SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (network.connected) com.universalbrain.ai.feature.shell.AccentGreen else ErrorRed)
                Text(
                    if (network.connected) "  Network connected (${if (network.wifi) "WiFi" else "cellular"}${if (network.metered) ", metered" else ""})"
                    else "  Network OFFLINE",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Text(
            "Sampling adapts to device class: low-RAM devices poll at half the rate. Values come from ActivityManager, /proc/stat and the battery broadcast — nothing is simulated.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
}

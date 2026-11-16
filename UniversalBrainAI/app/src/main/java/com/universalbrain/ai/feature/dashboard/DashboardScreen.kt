package com.universalbrain.ai.feature.dashboard

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.universalbrain.ai.Routes
import com.universalbrain.ai.domain.mining.SmartOptimizer
import com.universalbrain.ai.feature.shell.AccentBlue
import com.universalbrain.ai.feature.shell.AccentGreen
import com.universalbrain.ai.feature.shell.SectionHeader
import com.universalbrain.ai.feature.shell.SimpleCard
import com.universalbrain.ai.feature.shell.StatCard
import com.universalbrain.ai.feature.shell.StatusDot
import com.universalbrain.ai.feature.shell.statusColor

@Composable
fun DashboardScreen(vm: UbaViewModel, navController: NavHostController) {
    val apps by vm.importedApps.collectAsState(initial = emptyList())
    val instances by vm.instances.collectAsState(initial = emptyList())
    val accounts by vm.accounts.collectAsState(initial = emptyList())
    val workers by vm.workers.collectAsState(initial = emptyList())
    val hashrate by vm.totalHashrate.collectAsState(initial = null)
    val vitals by vm.vitals.collectAsState()
    val engine by vm.engineRunning.collectAsState()
    val network by vm.network.collectAsState()
    val tasks by vm.tasks.collectAsState(initial = emptyList())
    val memoryCount by vm.memoryCount.collectAsState(initial = 0)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "UNIVERSAL BRAIN",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text("AI agent status: ${if (engine) "ONLINE" else "LOCAL (engine off)"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (engine) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SimpleCard {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        StatusDot(if (network.connected) AccentGreen else com.universalbrain.ai.feature.shell.ErrorRed)
                        Text(
                            if (network.connected) "  Online" else "  Offline",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
        item {
            SectionHeader("Platform")
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Apps", "${apps.size}", "imported", Modifier.weight(1f), AccentBlue)
                StatCard("Instances", "${instances.size}", "${instances.count { it.status == "ACTIVE" }} active", Modifier.weight(1f))
                StatCard("Accounts", "${accounts.size}", "${accounts.count { it.status == "ACTIVE" }} active", Modifier.weight(1f), AccentBlue)
            }
        }
        item {
            SectionHeader("Mining")
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Workers", "${workers.size}", "${workers.count { it.status == "ONLINE" }} online", Modifier.weight(1f))
                StatCard(
                    "Hashrate",
                    hashrate?.takeIf { it > 0 }?.let { SmartOptimizer.formatHashrate(it) } ?: "N/A",
                    "from pool APIs",
                    Modifier.weight(1f), AccentBlue
                )
                StatCard("AI memory", "$memoryCount", "facts stored", Modifier.weight(1f))
            }
        }
        item {
            SectionHeader("Device vitals (live)")
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("RAM", vitals?.let { "${it.ramUsedPct.toInt()}%" } ?: "—",
                    vitals?.let { "${it.ramAvailMb}/${it.ramTotalMb} MB" } ?: "sampling", Modifier.weight(1f), AccentBlue)
                StatCard("CPU", vitals?.cpuPct?.let { "${it.toInt()}%" } ?: "N/A",
                    if (vitals?.cpuPct == null) "sealed /proc/stat" else "live", Modifier.weight(1f))
                StatCard("Battery", vitals?.let { "${it.batteryLevelPct}%" } ?: "—",
                    vitals?.let { "${it.batteryTempC}C" } ?: "", Modifier.weight(1f), AccentBlue)
            }
        }
        item {
            SectionHeader("Engine & tasks")
            SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    StatusDot(if (engine) AccentGreen else com.universalbrain.ai.feature.shell.WarnAmber)
                    Text(
                        if (engine) "  Background engine RUNNING" else "  Background engine STOPPED",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "Running background tasks: ${tasks.count { it.status == "RUNNING" }} · Network: ${if (network.connected) "connected" else "offline"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SectionHeader("Open modules")
        }
        items(moduleLinks) { link ->
            SimpleCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { navController.navigate(link.first) }
            ) {
                Text(link.second, fontWeight = FontWeight.SemiBold, color = AccentBlue)
                Text(link.third, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

private val moduleLinks = listOf(
    Triple(Routes.SCANNER, "Installed App Scanner", "Scan device apps"),
    Triple(Routes.ACCOUNTS, "Account Manager", "Accounts, groups, sessions"),
    Triple(Routes.BRAIN, "Master AI Brain", "Agent tools & memory"),
    Triple(Routes.OPTIMIZER, "Mining Optimizer", "Smart recommendations"),
    Triple(Routes.ADS, "Advertising Dashboard", "Rewards & consent"),
    Triple(Routes.AUTOMATION, "Automation Center", "Rules engine"),
    Triple(Routes.BACKGROUND, "Background Operations", "Engine control"),
    Triple(Routes.MONITOR, "Device Monitor", "Live vitals"),
    Triple(Routes.EARNINGS, "Earnings Analytics", "Tracked earnings"),
    Triple(Routes.HISTORY, "Activity History", "Audit trail"),
    Triple(Routes.NOTIFICATIONS, "Notifications", "Alerts inbox"),
    Triple(Routes.SECURITY, "Security Settings", "Keystore & secrets"),
    Triple(Routes.SETTINGS, "General Settings", "LLM bridge & intervals"),
    Triple(Routes.MODULES, "All modules", "Full module grid")
)

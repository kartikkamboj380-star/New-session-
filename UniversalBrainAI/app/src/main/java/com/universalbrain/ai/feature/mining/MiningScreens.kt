package com.universalbrain.ai.feature.mining

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.universalbrain.ai.feature.shell.WarnAmber
import com.universalbrain.ai.feature.shell.statusColor

@Composable
fun MiningDashboardScreen(vm: UbaViewModel, navController: NavHostController) {
    val workers by vm.workers.collectAsState(initial = emptyList())
    val hashrate by vm.totalHashrate.collectAsState(initial = null)
    val paused by vm.miningPaused.collectAsState(initial = false)
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Mining Dashboard")
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Workers", "${workers.size}", "${workers.count { it.status == "ONLINE" }} online", Modifier.weight(1f))
            StatCard(
                "Hashrate",
                hashrate?.takeIf { it > 0 }?.let { com.universalbrain.ai.domain.mining.SmartOptimizer.formatHashrate(it) } ?: "N/A",
                "sum of workers",
                Modifier.weight(1f), com.universalbrain.ai.feature.shell.AccentBlue
            )
        }
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { navController.navigate(Routes.MINING_CONFIG) }) { Text("Configuration") }
            Button(onClick = { navController.navigate(Routes.OPTIMIZER) }) { Text("Optimizer") }
            Button(onClick = { vm.pollNow { result -> message = result } }) { Text("Poll now") }
        }
        if (paused) {
            Text(
                "Monitoring PAUSED (thermal/battery rule or manual). Resume in Background Operations.",
                color = WarnAmber,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (workers.isEmpty()) {
                item {
                    Text(
                        "No workers configured. Open Configuration and add your pool API URL + wallet — stats come from the documented pool APIs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(workers, key = { it.id }) { worker ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(statusColor(worker.status))
                            Text("  ${worker.name}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(worker.status, color = statusColor(worker.status), style = MaterialTheme.typography.labelSmall)
                        }
                        Text("${worker.coin} · wallet ${worker.wallet.take(10)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column {
                                Text("Hashrate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (worker.lastHashrate > 0) com.universalbrain.ai.domain.mining.SmartOptimizer.formatHashrate(worker.lastHashrate) else "N/A",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Column {
                                Text("Workers online", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${worker.lastWorkersOnline}", fontWeight = FontWeight.SemiBold)
                            }
                            Column {
                                Text("Unpaid", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (worker.lastUnpaid > 0) "%.8f".format(worker.lastUnpaid) else "N/A",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        worker.lastError?.let {
                            Text("Error: $it", style = MaterialTheme.typography.labelSmall, color = ErrorRed)
                        }
                        Text(
                            if (worker.lastSyncAt > 0) "Last sync: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(worker.lastSyncAt))}"
                            else "Never synced",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Mining") },
            text = { Text(it) }
        )
    }
}

@Composable
fun MiningConfigScreen(vm: UbaViewModel, navController: NavHostController) {
    val workers by vm.workers.collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var apiUrl by remember { mutableStateOf("") }
    var wallet by remember { mutableStateOf("") }
    var coin by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("10") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Mining Configuration")
        SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Add worker", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Worker name") }, singleLine = true)
            OutlinedTextField(
                value = apiUrl, onValueChange = { apiUrl = it },
                label = { Text("Pool stats API URL (may contain {wallet})") }, singleLine = true
            )
            OutlinedTextField(value = wallet, onValueChange = { wallet = it }, label = { Text("Wallet address") }, singleLine = true)
            OutlinedTextField(value = coin, onValueChange = { coin = it }, label = { Text("Coin symbol") }, singleLine = true)
            OutlinedTextField(value = interval, onValueChange = { interval = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("Poll interval (minutes)") }, singleLine = true)
            Button(onClick = {
                vm.addWorker(name, apiUrl, wallet, coin, interval.toIntOrNull() ?: 10) { error ->
                    if (error == null) {
                        name = ""; apiUrl = ""; wallet = ""; coin = ""
                        message = "Worker added. Poll it from the dashboard."
                    } else message = error
                }
            }) { Text("Add worker") }
            Text(
                "Honest scope: Universal Brain monitors and optimizes EXTERNAL workers via documented pool APIs. " +
                    "It does not mine on this device — phone CPUs cannot mine profitably and sustained load damages hardware.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionHeader("Configured workers")
        workers.forEach { worker ->
            SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(worker.name, fontWeight = FontWeight.SemiBold)
                        Text(worker.poolApiUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.deleteWorker(worker.id) }) {
                        Text("Delete", color = ErrorRed)
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Mining configuration") },
            text = { Text(it) }
        )
    }
}

@Composable
fun OptimizerScreen(vm: UbaViewModel, navController: NavHostController) {
    val vitals by vm.vitals.collectAsState()
    val workers by vm.workers.collectAsState(initial = emptyList())
    val network by vm.network.collectAsState()
    var results by remember { mutableStateOf<List<com.universalbrain.ai.domain.mining.SmartOptimizer.Recommendation>?>(null) }

    LaunchedEffect(Unit) {
        if (vitals == null) {
            kotlinx.coroutines.delay(800)
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Smart Mining Optimizer")
        Button(
            onClick = {
                val currentVitals = vitals
                if (currentVitals != null) {
                    results = com.universalbrain.ai.domain.mining.SmartOptimizer.analyze(
                        com.universalbrain.ai.domain.mining.SmartOptimizer.OptimizerInput(
                            vitals = currentVitals,
                            networkConnected = network.connected,
                            workerCount = workers.size,
                            onlineWorkers = workers.count { it.status == "ONLINE" },
                            pollIntervalMin = 10,
                            totalHashrate = workers.sumOf { it.lastHashrate }
                        )
                    )
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp),
            enabled = vitals != null
        ) { Text("Analyze now") }
        Text(
            "Analyzes live CPU, RAM, temperature, battery, network stability and worker performance.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
        results?.forEach { recommendation ->
            SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        recommendation.priority,
                        color = when (recommendation.priority) {
                            "HIGH" -> ErrorRed
                            "MEDIUM" -> WarnAmber
                            else -> com.universalbrain.ai.feature.shell.AccentGreen
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text("  ${recommendation.title}", fontWeight = FontWeight.SemiBold)
                }
                Text(recommendation.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

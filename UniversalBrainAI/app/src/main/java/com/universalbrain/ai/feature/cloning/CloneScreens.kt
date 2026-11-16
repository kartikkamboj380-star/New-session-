package com.universalbrain.ai.feature.cloning

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.universalbrain.ai.core.database.CloneInstance
import com.universalbrain.ai.feature.dashboard.UbaViewModel
import com.universalbrain.ai.feature.shell.SectionHeader
import com.universalbrain.ai.feature.shell.SimpleCard
import com.universalbrain.ai.feature.shell.StatCard
import com.universalbrain.ai.feature.shell.StatusDot
import com.universalbrain.ai.feature.shell.statusColor

@Composable
fun CloneManagerScreen(vm: UbaViewModel, navController: NavHostController) {
    val apps by vm.importedApps.collectAsState(initial = emptyList())
    val instances by vm.instances.collectAsState(initial = emptyList())
    var showCreate by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Multi-Clone Manager")
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Instances", "${instances.size}", "cap: ${vm.clonePolicy.maxInstances}", Modifier.weight(1f))
            StatCard("Active", "${instances.count { it.status == "ACTIVE" }}", "running now", Modifier.weight(1f), com.universalbrain.ai.feature.shell.AccentBlue)
        }
        Text(
            vm.clonePolicy.reason + " - each WEB clone runs in its own process with separate storage.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Button(
            onClick = { if (apps.isNotEmpty()) showCreate = true },
            enabled = apps.isNotEmpty(),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        ) { Text("Create instance") }
        if (apps.isEmpty()) {
            Text(
                "Import apps first (Apps tab -> Scanner).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(instances, key = { it.id }) { instance ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { navController.navigate("instance/${instance.id}") },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(statusColor(instance.status))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(instance.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${instance.kind} · ${instance.appLabel} · #${"%02d".format(instance.index)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            instance.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor(instance.status)
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showCreate) {
        CreateInstanceDialog(
            apps = apps,
            onDismiss = { showCreate = false },
            onCreate = { pkg, label, kind, count, url, fresh ->
                showCreate = false
                vm.bulkCreate(pkg, label, kind, count, url, fresh) { result -> message = result }
            }
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Instances") },
            text = { Text(it) }
        )
    }
}

@Composable
private fun CreateInstanceDialog(
    apps: List<com.universalbrain.ai.core.database.ImportedApp>,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, Int, String, Boolean) -> Unit
) {
    var selectedPackage by remember { mutableStateOf(apps.firstOrNull()?.packageName ?: "") }
    var kind by remember { mutableStateOf("APP") }
    var count by remember { mutableStateOf("1") }
    var startUrl by remember { mutableStateOf("") }
    var freshStart by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create instance(s)") },
        text = {
            Column {
                Text("App", style = MaterialTheme.typography.labelSmall)
                apps.take(6).forEach { app ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedPackage == app.packageName,
                            onClick = { selectedPackage = app.packageName }
                        )
                        Text("${app.label}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    RadioButton(selected = kind == "APP", onClick = { kind = "APP" })
                    Text("APP (launch installed app)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = kind == "WEB", onClick = { kind = "WEB" })
                    Text("WEB (isolated session + sign-in page)", style = MaterialTheme.typography.bodySmall)
                }
                if (kind == "WEB") {
                    OutlinedTextField(
                        value = startUrl,
                        onValueChange = { startUrl = it },
                        label = { Text("Website URL (e.g. https://ctpool.com)") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(
                            checked = freshStart,
                            onCheckedChange = { freshStart = it }
                        )
                        Text(
                            "  Fresh session each launch (always opens sign-in page)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "Each WEB instance gets its own cookie store and data directory — separate accounts per instance.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "APP instances launch the real installed app, so the app's own stored session is used (one session per device). For independent sign-ins per clone, use WEB and paste the app's website URL.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = count,
                    onValueChange = { count = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("How many (bulk)") },
                    singleLine = true,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(
                    selectedPackage,
                    apps.firstOrNull { it.packageName == selectedPackage }?.label ?: "App",
                    kind,
                    count.toIntOrNull() ?: 1,
                    startUrl.trim(),
                    if (kind == "WEB") freshStart else false
                )
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun WebSessionConfig(vm: UbaViewModel, current: CloneInstance, onMessage: (String) -> Unit) {
    var url by remember(current.id) { mutableStateOf(current.startUrl) }
    var fresh by remember(current.id) { mutableStateOf(current.freshStart) }
    SectionHeader("WEB session (sign-in)")
    SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Start URL (e.g. https://ctpool.com)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            androidx.compose.material3.Switch(checked = fresh, onCheckedChange = { fresh = it })
            Text(
                "  Fresh session on every launch (sign-in page)",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(onClick = { vm.setInstanceUrl(current.id, url, fresh) { msg -> onMessage(msg) } }) {
            Text("Save session settings")
        }
    }
}

@Composable
fun InstanceDetailsScreen(vm: UbaViewModel, navController: NavHostController, instanceId: String) {
    var instance by remember { mutableStateOf<CloneInstance?>(null) }
    var renameTo by remember { mutableStateOf("") }
    var showRename by remember { mutableStateOf(false) }
    var configMessage by remember { mutableStateOf<String?>(null) }
    val accounts by vm.accounts.collectAsState(initial = emptyList())

    androidx.compose.runtime.LaunchedEffect(instanceId) {
        instance = vm.instance(instanceId)
    }

    val current = instance
    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Instance details")
        if (current == null) {
            Text("Loading…", modifier = Modifier.padding(16.dp))
        } else {
            SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor(current.status))
                    Text("  ${current.name}", fontWeight = FontWeight.Bold)
                }
                Text("App: ${current.appLabel} (${current.appPackage})", style = MaterialTheme.typography.bodySmall)
                Text("Kind: ${current.kind} · Index: #${"%02d".format(current.index)}", style = MaterialTheme.typography.bodySmall)
                Text("Status: ${current.status}", style = MaterialTheme.typography.bodySmall, color = statusColor(current.status))
                Text("Data dir: ${current.dataDirPath}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Assigned account: " + (accounts.firstOrNull { it.id == current.accountId }?.label ?: "none"),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.launchInstance(current.id) { } }) { Text("Launch") }
                TextButton(onClick = { showRename = true }) { Text("Rename") }
                TextButton(onClick = {
                    vm.deleteInstance(current.id)
                    navController.popBackStack()
                }) { Text("Delete", color = com.universalbrain.ai.feature.shell.ErrorRed) }
            }
            if (current.kind == "WEB") {
                WebSessionConfig(vm, current) { msg -> configMessage = msg }
                TextButton(onClick = {
                    vm.resetInstanceData(current.id) { msg -> configMessage = msg }
                }) { Text("Reset instance data (wipes session files)", color = com.universalbrain.ai.feature.shell.WarnAmber) }
            } else {
                SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("APP instance — session note", fontWeight = FontWeight.SemiBold)
                    Text(
                        "This instance launches the real installed app, so the app uses its own stored session " +
                            "(Android does not allow duplicating another app's private data). " +
                            "Each launch restarts the app from its start screen, but the sign-in state is the app's own. " +
                            "For one session PER clone, create a WEB instance with the app's website URL — those have " +
                            "fully isolated cookie stores. On Xiaomi you can also use MIUI's built-in Dual apps (Settings → Apps → Dual apps).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            SectionHeader("Assign account")
            if (accounts.isEmpty()) {
                Text("No accounts yet — add some in the Account Manager.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                accounts.forEach { account ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.assignAccount(current.id, account.id) }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        RadioButton(selected = current.accountId == account.id, onClick = {
                            vm.assignAccount(current.id, account.id)
                        })
                        Text("${account.label} (${account.groupName})", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showRename && current != null) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename instance") },
            text = {
                OutlinedTextField(value = renameTo, onValueChange = { renameTo = it }, label = { Text("New name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameTo.isNotBlank()) vm.renameInstance(current.id, renameTo)
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }

    configMessage?.let {
        AlertDialog(
            onDismissRequest = { configMessage = null },
            confirmButton = { TextButton(onClick = { configMessage = null }) { Text("OK") } },
            title = { Text("Instance session") },
            text = { Text(it) }
        )
    }
}

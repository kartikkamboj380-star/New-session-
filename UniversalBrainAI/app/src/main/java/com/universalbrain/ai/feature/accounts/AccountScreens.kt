package com.universalbrain.ai.feature.accounts

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.universalbrain.ai.core.database.Account
import com.universalbrain.ai.core.database.AccountSession
import com.universalbrain.ai.feature.dashboard.UbaViewModel
import com.universalbrain.ai.feature.shell.SectionHeader
import com.universalbrain.ai.feature.shell.SimpleCard
import com.universalbrain.ai.feature.shell.StatusDot
import com.universalbrain.ai.feature.shell.statusColor

@Composable
fun AccountsScreen(vm: UbaViewModel, navController: NavHostController) {
    val accounts by vm.accounts.collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var groupFilter by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val groups = accounts.map { it.groupName }.distinct()
    val filtered = if (groupFilter == null) accounts else accounts.filter { it.groupName == groupFilter }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Account Manager")
        Button(onClick = { showAdd = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Add account")
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = groupFilter == null, onClick = { groupFilter = null }, label = { Text("All") })
            groups.forEach { group ->
                FilterChip(selected = groupFilter == group, onClick = { groupFilter = group }, label = { Text(group) })
            }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        "No accounts yet. Add one — its secret is stored encrypted with the Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(filtered, key = { it.id }) { account ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { navController.navigate("account/${account.id}") },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(statusColor(account.status))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(account.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                "@${account.username} · ${account.groupName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(account.status, style = MaterialTheme.typography.labelSmall, color = statusColor(account.status))
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showAdd) {
        AddAccountDialog(onDismiss = { showAdd = false }, onAdd = { label, group, username, secret ->
            showAdd = false
            vm.addAccount(label, group, username, secret) { error -> message = error }
        })
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Accounts") },
            text = { Text(it) }
        )
    }
}

@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onAdd: (String, String, String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("Default") }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label (e.g. Work 1)") }, singleLine = true)
                OutlinedTextField(value = group, onValueChange = { group = it }, label = { Text("Group") }, singleLine = true)
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username / handle") }, singleLine = true)
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it },
                    label = { Text("Password / token (Keystore-encrypted)") }, singleLine = true
                )
                Text(
                    "Secrets never touch the Room database in plain text.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(label, group, username, secret) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AccountDetailsScreen(vm: UbaViewModel, navController: NavHostController, accountId: String) {
    val accountState = produceState<Account?>(initialValue = null, accountId) {
        value = vm.account(accountId)
    }
    val sessionsState = produceState<List<AccountSession>>(initialValue = emptyList(), accountId) {
        value = vm.accountSessions(accountId)
    }
    val account = accountState.value

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Account details")
        if (account == null) {
            Text("Loading…", modifier = Modifier.padding(16.dp))
        } else {
            SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor(account.status))
                    Text("  ${account.label}", fontWeight = FontWeight.Bold)
                }
                Text("Username: @${account.username}", style = MaterialTheme.typography.bodySmall)
                Text("Group: ${account.groupName}", style = MaterialTheme.typography.bodySmall)
                Text("Status: ${account.status}", style = MaterialTheme.typography.bodySmall, color = statusColor(account.status))
            }
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.setAccountStatus(account.id, "ACTIVE") }) { Text("Mark active") }
                TextButton(onClick = { vm.setAccountStatus(account.id, "DISCONNECTED") }) {
                    Text("Mark disconnected", color = com.universalbrain.ai.feature.shell.WarnAmber)
                }
                TextButton(onClick = {
                    vm.deleteAccount(account.id)
                    navController.popBackStack()
                }) { Text("Delete", color = com.universalbrain.ai.feature.shell.ErrorRed) }
            }
            SectionHeader("Session history")
            if (sessionsState.value.isEmpty()) {
                Text("No sessions recorded.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            LazyColumn {
                items(sessionsState.value, key = { it.id }) { session ->
                    SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("Session ${session.id.take(8)}…", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Started: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(session.startedAt))}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            "Ended: ${session.endedAt?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "open"} · ${session.status}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        session.note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

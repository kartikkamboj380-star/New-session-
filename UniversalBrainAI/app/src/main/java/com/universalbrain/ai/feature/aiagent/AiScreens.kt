package com.universalbrain.ai.feature.aiagent

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.universalbrain.ai.Routes
import com.universalbrain.ai.domain.ai.ToolRegistry
import com.universalbrain.ai.feature.dashboard.PendingConfirmation
import com.universalbrain.ai.feature.dashboard.UbaViewModel
import com.universalbrain.ai.feature.shell.SectionHeader
import com.universalbrain.ai.feature.shell.SimpleCard
import com.universalbrain.ai.feature.shell.StatCard
import com.universalbrain.ai.feature.shell.StatusDot
import kotlinx.coroutines.launch

@Composable
fun BrainScreen(vm: UbaViewModel, navController: NavHostController) {
    val memoryCount by vm.memoryCount.collectAsState(initial = 0)
    val engine by vm.engineRunning.collectAsState()
    val tools = ToolRegistry.tools

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Master AI Brain")
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Agent", if (engine) "ONLINE" else "LOCAL", "tools + memory", Modifier.weight(1f))
            StatCard("Tools", "${tools.size}", "registered", Modifier.weight(1f), com.universalbrain.ai.feature.shell.AccentBlue)
            StatCard("Memory", "$memoryCount", "facts", Modifier.weight(1f))
        }
        Text(
            "The agent is a transparent, deterministic local brain (parser -> intents -> tools). " +
                "An optional LLM bridge can be configured in Settings. Actions that change state always ask first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
        Button(
            onClick = { navController.navigate(Routes.CHAT) },
            modifier = Modifier.padding(horizontal = 16.dp)
        ) { Text("Open AI Chat") }
        SectionHeader("Tool registry (permissions)")
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(tools, key = { it.name }) { tool ->
                val permissionColor = if (tool.permission == ToolRegistry.Permission.READ) {
                    com.universalbrain.ai.feature.shell.AccentGreen
                } else if (tool.permission == ToolRegistry.Permission.EXECUTE) {
                    com.universalbrain.ai.feature.shell.WarnAmber
                } else {
                    com.universalbrain.ai.feature.shell.ErrorRed
                }
                SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(permissionColor)
                        Text(
                            "  ${tool.name} · ${tool.permission}",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(tool.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun ChatScreen(vm: UbaViewModel, navController: NavHostController) {
    val messages by vm.chat.collectAsState(initial = emptyList())
    val busy by vm.aiBusy.collectAsState()
    val typing by vm.aiTyping.collectAsState()
    val toolStatus by vm.toolStatus.collectAsState()
    val confirmation by vm.pendingConfirmation.collectAsState()
    var input by remember { mutableStateOf("") }
    var search by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) input = spoken
        }
    }

    val visible = if (search && searchQuery.isNotBlank()) {
        messages.filter { it.text.contains(searchQuery, ignoreCase = true) }
    } else messages

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AI Chat", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            IconButton(onClick = { search = !search }) {
                Icon(Icons.Filled.Search, contentDescription = "Search messages")
            }
        }
        if (search) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search in messages") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true
            )
        }
        LazyColumn(modifier = Modifier.weight(1f), reverseLayout = false) {
            items(visible, key = { it.id }) { message ->
                val isUser = message.role == "USER"
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                            .widthIn(max = 320.dp)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            if (message.tool != null) {
                                Text("[${message.tool}]", style = MaterialTheme.typography.labelSmall, color = com.universalbrain.ai.feature.shell.AccentBlue)
                            }
                            Text(message.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (typing) {
                item {
                    Text(
                        "MASTER BRAIN is thinking…",
                        style = MaterialTheme.typography.labelSmall,
                        color = com.universalbrain.ai.feature.shell.AccentGreen,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
            toolStatus?.let { status ->
                item {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = com.universalbrain.ai.feature.shell.AccentBlue,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        confirmation?.let { pending ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Confirm execution", fontWeight = FontWeight.SemiBold, color = com.universalbrain.ai.feature.shell.WarnAmber)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.confirmPlan(pending.planId) }) { Text("Approve") }
                        TextButton(onClick = { vm.cancelPlan(pending.planId) }) { Text("Cancel") }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Command MASTER BRAIN")
                }
                runCatching { voiceLauncher.launch(intent) }
            }) {
                Icon(Icons.Filled.Mic, contentDescription = "Voice input")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("e.g. Show all my accounts") },
                singleLine = true,
                enabled = !busy
            )
            IconButton(
                onClick = {
                    val text = input
                    input = ""
                    vm.sendChat(text)
                },
                enabled = !busy && input.isNotBlank()
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }
        }
        QuickCommandsRow { command ->
            vm.sendChat(command)
        }
    }
}

@Composable
private fun QuickCommandsRow(onCommand: (String) -> Unit) {
    val quick = listOf(
        "Show all my accounts",
        "Check mining performance",
        "Show today's earnings",
        "Optimize the mining configuration",
        "Generate a complete performance report"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        quick.forEach { command ->
            TextButton(onClick = { onCommand(command) }) {
                Text(command, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

package com.universalbrain.ai.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.universalbrain.ai.feature.dashboard.UbaViewModel
import com.universalbrain.ai.feature.shell.ErrorRed
import com.universalbrain.ai.feature.shell.SectionHeader
import com.universalbrain.ai.feature.shell.SimpleCard
import com.universalbrain.ai.feature.shell.WarnAmber

@Composable
fun SecuritySettingsScreen(vm: UbaViewModel, navController: NavHostController) {
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Security Settings")
        SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Secret storage", fontWeight = FontWeight.SemiBold)
            Text(
                "Account passwords, tokens and the LLM API key are encrypted with an AES-256/GCM key generated inside the Android Keystore. The key is non-exportable; ciphertext lives in app-private preferences. The Room database never stores secrets in plain text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("AI tool permissions", fontWeight = FontWeight.SemiBold)
            Text(
                "Every agent tool declares a permission level (READ / EXECUTE / WRITE). WRITE and EXECUTE tools require explicit user confirmation in the chat before running. All tool executions are recorded in Activity History.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(
            onClick = { vm.wipeSecureData { result -> message = result } },
            modifier = Modifier.padding(16.dp)
        ) { Text("Wipe all stored secrets", color = ErrorRed) }
        Text(
            "This removes account secrets and the LLM key from secure storage. Account rows in the library remain but become unrecoverable until re-added.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(40.dp))
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Security") },
            text = { Text(it) }
        )
    }
}

@Composable
fun GeneralSettingsScreen(vm: UbaViewModel, navController: NavHostController) {
    val llm by vm.llmStatus().collectAsState(initial = Triple("", "", false))
    var baseUrl by remember(llm.first) { mutableStateOf(llm.first) }
    var model by remember(llm.second) { mutableStateOf(llm.second) }
    var apiKey by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("LLM bridge (optional)")
        SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Remote model configuration", fontWeight = FontWeight.SemiBold)
            Text(
                "The local brain always works offline. Optionally connect an OpenAI-compatible endpoint; when configured, chat first tries the remote model and falls back to the local brain on failure. The API key is stored in the Keystore-backed secure store.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it },
                label = { Text("Base URL (e.g. https://api.openai.com/v1)") },
                singleLine = true
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it },
                label = { Text("Model (e.g. gpt-4o-mini)") },
                singleLine = true
            )
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("API key" + if (llm.third) " (stored — leave blank to keep)" else "") },
                singleLine = true
            )
            Button(onClick = {
                vm.saveLlmConfig(baseUrl, model, apiKey.ifBlank { null }) { result -> message = result }
            }) { Text("Save LLM bridge") }
        }
        SectionHeader("About")
        SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Universal Brain AI v1.0.0", fontWeight = FontWeight.SemiBold)
            Text(
                "Honest scope: app import = real PackageManager scan; clones = per-process WEB isolation + installed-app launches; mining = documented pool API monitoring + optimizer; ads module = consent + reward ledger (SDK keys required for live ads); AI = deterministic local tools + optional LLM bridge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(40.dp))
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Settings") },
            text = { Text(it) }
        )
    }
}

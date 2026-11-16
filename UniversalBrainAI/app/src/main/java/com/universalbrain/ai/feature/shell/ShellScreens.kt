package com.universalbrain.ai.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.universalbrain.ai.Routes
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1400)
        onDone()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "UNIVERSAL BRAIN AI",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = AccentGreen
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "App manager · Multi-clone · Autonomous agent",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Welcome to UNIVERSAL BRAIN AI",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(16.dp))
        onboardingCard("1 · Import apps", "Scan apps installed on this device and register them in your library with compatibility status.")
        onboardingCard("2 · Multi-clone", "Create isolated instances. WEB clones run in dedicated processes with separate storage; the count adapts to your device RAM.")
        onboardingCard("3 · Accounts", "Manage multiple accounts with Keystore-encrypted secrets, groups and session history.")
        onboardingCard("4 · MASTER BRAIN", "Command everything in natural language. The agent uses audited tools with explicit permissions — no hidden actions.")
        onboardingCard("5 · Mining monitoring", "Track external mining workers through documented pool APIs, with a smart optimizer. On-device mining is not performed — that would be dishonest and destructive.")
        Spacer(Modifier.height(20.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Get started")
        }
    }
}

@Composable
private fun onboardingCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = AccentGreen)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class ModuleEntry(val route: String, val title: String, val subtitle: String)

@Composable
fun ModulesGridScreen(navController: NavHostController) {
    val modules = listOf(
        ModuleEntry(Routes.SCANNER, "Installed App Scanner", "Scan & select device apps"),
        ModuleEntry(Routes.IMPORT, "App Import", "Register selected apps"),
        ModuleEntry(Routes.LIBRARY, "Application Library", "Imported apps"),
        ModuleEntry(Routes.CLONES, "Multi-Clone Manager", "Instances dashboard"),
        ModuleEntry(Routes.ACCOUNTS, "Account Manager", "Accounts & sessions"),
        ModuleEntry(Routes.BRAIN, "Master AI Brain", "Agent status & tools"),
        ModuleEntry(Routes.CHAT, "AI Chat", "Natural-language control"),
        ModuleEntry(Routes.MINING, "Mining Dashboard", "Workers & hashrate"),
        ModuleEntry(Routes.MINING_CONFIG, "Mining Configuration", "Workers & polling"),
        ModuleEntry(Routes.OPTIMIZER, "Mining Optimizer", "Smart recommendations"),
        ModuleEntry(Routes.ADS, "Advertising Dashboard", "Rewards & consent"),
        ModuleEntry(Routes.AUTOMATION, "Automation Center", "Rules engine"),
        ModuleEntry(Routes.BACKGROUND, "Background Operations", "Engine control"),
        ModuleEntry(Routes.MONITOR, "Device Monitor", "Live vitals"),
        ModuleEntry(Routes.EARNINGS, "Earnings Analytics", "Tracked earnings"),
        ModuleEntry(Routes.HISTORY, "Activity History", "Everything that happened"),
        ModuleEntry(Routes.NOTIFICATIONS, "Notifications", "Alerts inbox"),
        ModuleEntry(Routes.SECURITY, "Security Settings", "Keystore & secrets"),
        ModuleEntry(Routes.SETTINGS, "General Settings", "Engine & LLM config")
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "All modules",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { navController.popBackStack() }) { Text("Close") }
            }
        }
        items(modules) { module ->
            Card(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .clickable { navController.navigate(module.route) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(module.title, fontWeight = FontWeight.SemiBold, color = AccentBlue)
                    Spacer(Modifier.height(4.dp))
                    Text(module.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

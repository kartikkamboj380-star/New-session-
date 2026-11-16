package com.universalbrain.ai.feature.appimport

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.universalbrain.ai.Routes
import com.universalbrain.ai.feature.dashboard.UbaViewModel
import com.universalbrain.ai.feature.shell.SectionHeader
import com.universalbrain.ai.feature.shell.SimpleCard
import com.universalbrain.ai.feature.shell.StatCard
import com.universalbrain.ai.feature.shell.statusColor

@Composable
fun ScannerScreen(vm: UbaViewModel, navController: NavHostController) {
    val state by vm.scanner.collectAsState()
    LaunchedEffect(Unit) { vm.refreshScanner() }

    val filtered = state.apps.filter { app ->
        val matchesQuery = app.label.contains(state.query, ignoreCase = true) ||
            app.packageName.contains(state.query, ignoreCase = true)
        val matchesFilter = when (state.filter) {
            "USER" -> !app.isSystem
            "SYSTEM" -> app.isSystem
            else -> true
        }
        matchesQuery && matchesFilter
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Installed App Scanner")
        OutlinedTextField(
            value = state.query,
            onValueChange = { vm.setScannerQuery(it) },
            label = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true
        )
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = state.filter == "ALL", onClick = { vm.setScannerFilter("ALL") }, label = { Text("All") })
            FilterChip(selected = state.filter == "USER", onClick = { vm.setScannerFilter("USER") }, label = { Text("User apps") })
            FilterChip(selected = state.filter == "SYSTEM", onClick = { vm.setScannerFilter("SYSTEM") }, label = { Text("System") })
        }
        Text(
            "${filtered.size} apps found · ${state.selected.size} selected",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filtered, key = { it.packageName }) { app ->
                val selected = app.packageName in state.selected
                val imported = app.packageName in state.importedPackages
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { vm.toggleSelect(app.packageName) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = selected, onCheckedChange = { vm.toggleSelect(app.packageName) })
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold)
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "v${app.versionName} (${app.versionCode})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (imported) "IMPORTED" else "NOT IMPORTED",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (imported) com.universalbrain.ai.feature.shell.AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (app.isSystem) "LIMITED" else "SUPPORTED",
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor(if (app.isSystem) "LIMITED" else "SUPPORTED")
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        Button(
            onClick = {
                vm.importSelected()
                navController.navigate(Routes.IMPORT)
            },
            enabled = state.selected.isNotEmpty() && !state.importing,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Text(if (state.importing) "Importing…" else "Import selected (${state.selected.size})")
        }
    }
}

@Composable
fun AppImportScreen(vm: UbaViewModel, navController: NavHostController) {
    val state by vm.scanner.collectAsState()
    LaunchedEffect(Unit) { vm.refreshScanner() }
    val queued = state.apps.filter { it.packageName in state.selected }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("App Import")
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Queued", "${queued.size}", "selected", Modifier.weight(1f))
            StatCard("Imported", "${state.importedPackages.size}", "in library", Modifier.weight(1f), com.universalbrain.ai.feature.shell.AccentBlue)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (queued.isEmpty()) {
                item {
                    Text(
                        "Nothing queued. Open the scanner and select apps to import.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(queued, key = { it.packageName }) { app ->
                SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(app.label, fontWeight = FontWeight.SemiBold)
                    Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Status: REGISTERED · Compatibility: ${if (app.isSystem) "LIMITED (system app)" else "SUPPORTED"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(if (app.isSystem) "LIMITED" else "SUPPORTED")
                    )
                }
            }
            item {
                TextButton(
                    onClick = { navController.navigate(Routes.LIBRARY) },
                    modifier = Modifier.padding(16.dp)
                ) { Text("Open Application Library ->") }
            }
        }
    }
}

@Composable
fun LibraryScreen(vm: UbaViewModel, navController: NavHostController) {
    val apps by vm.importedApps.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Application Library")
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (apps.isEmpty()) {
                item {
                    Text(
                        "Library is empty. Use the Home tab -> Installed App Scanner to import apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(apps, key = { it.id }) { app ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                app.label.take(1).uppercase(),
                                color = com.universalbrain.ai.feature.shell.AccentBlue,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold)
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "v${app.versionName} (${app.versionCode}) · ${app.compatibility}",
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor(app.compatibility)
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Button(onClick = { navController.navigate(Routes.CLONES) }) { Text("Clone") }
                            TextButton(onClick = { vm.removeImported(app.id) }) {
                                Text("Remove", color = com.universalbrain.ai.feature.shell.ErrorRed)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

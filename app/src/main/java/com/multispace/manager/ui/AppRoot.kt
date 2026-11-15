package com.multispace.manager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import com.multispace.manager.AppContainer
import com.multispace.manager.core.SessionStatus

private enum class Screen(val label: String) {
    DASHBOARD("Dashboard"),
    APPS("Apps"),
    SESSIONS("Sessions"),
    SYSTEM("System")
}

@Composable
fun AppRoot(vm: MultiSpaceViewModel, container: AppContainer) {
    val state by vm.uiState.collectAsState()
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    var showNewSessionDialog by remember { mutableStateOf(false) }

    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.importApk(it) }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // ---------- Header ----------
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "MultiSpace",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Box(modifier = Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (state.serviceRunning) StatusGreen else AccentPurple,
                            CircleShape
                        )
                )
                Text(
                    if (state.serviceRunning) "  BG active" else "  BG off",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------- Browser-style session tab strip ----------
        SessionTabStrip(
            state = state,
            onSelect = { vm.selectWebSessionTab(it) },
            onClose = { vm.closeSession(it) },
            onNew = { showNewSessionDialog = true }
        )

        // ---------- Content ----------
        Box(modifier = Modifier.weight(1f)) {
            when (screen) {
                Screen.DASHBOARD -> DashboardScreen(
                    state = state,
                    profileCapability = container.profileIntegration.describeCapability(),
                    lowRamDevice = container.isLowRamDevice
                )
                Screen.APPS -> AppsScreen(
                    state = state,
                    onImport = {
                        apkPicker.launch(
                            arrayOf(
                                "application/vnd.android.package-archive",
                                "application/octet-stream",
                                "application/zip",
                                "*/*"
                            )
                        )
                    },
                    onInstall = { vm.installApp(it) },
                    onDelete = { vm.deleteApp(it.id) },
                    onDismissImportMessage = { vm.dismissImportMessage() },
                    onDismissInstallMessage = { vm.dismissInstallMessage() },
                    onUninstallExisting = { vm.uninstallExistingApp(it) }
                )
                Screen.SESSIONS -> SessionsScreen(
                    state = state,
                    onCreateSession = { name, kind, pkg, url -> vm.createSession(name, kind, pkg, url) },
                    onOpenSession = { vm.openSession(it) },
                    onCloseSession = { vm.closeSession(it) },
                    onRestartSession = { vm.restartSession(it) },
                    onDeleteSession = { vm.deleteSession(it) },
                    onDismissSessionMessage = { vm.dismissSessionMessage() }
                )
                Screen.SYSTEM -> SystemScreen(
                    state = state,
                    onStartService = { vm.startBackgroundManager() },
                    onStopService = { vm.stopBackgroundManager() },
                    slotUsage = container.webSlots.activeCount() to container.webSlots.capacity()
                )
            }
        }

        // ---------- Bottom navigation ----------
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            Screen.entries.forEach { entry ->
                NavigationBarItem(
                    selected = screen == entry,
                    onClick = { screen = entry },
                    icon = {
                        Icon(
                            imageVector = when (entry) {
                                Screen.DASHBOARD -> Icons.Filled.Home
                                Screen.APPS -> Icons.Filled.AccountBox
                                Screen.SESSIONS -> Icons.Filled.List
                                Screen.SYSTEM -> Icons.Filled.Settings
                            },
                            contentDescription = entry.label
                        )
                    },
                    label = { Text(entry.label) }
                )
            }
        }
    }

    if (showNewSessionDialog) {
        NewSessionDialog(
            apps = state.apps,
            onDismiss = { showNewSessionDialog = false },
            onCreate = { name, kind, pkg, url ->
                showNewSessionDialog = false
                vm.createSession(name, kind, pkg, url)
            }
        )
    }
}

/**
 * Browser-style tab bar: one tab per session, active-tab indicator (colored
 * underline + filled status dot), per-tab close, and a "+" new-tab button.
 */
@Composable
private fun SessionTabStrip(
    state: AppUiState,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit
) {
    val selected = state.selectedWebSessionId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        state.sessions.forEach { session ->
            val isSelected = session.id == selected
            val active = session.status == SessionStatus.ACTIVE.name
            Column(
                modifier = Modifier
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    )
                    .clickable { onSelect(session.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(statusColor(session.status), CircleShape)
                    )
                    Text(
                        text = session.name.take(12),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 6.dp, end = 6.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onClose(session.id) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close tab ${session.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                // Active-tab indicator underline (green when running).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(2.dp)
                        .background(
                            if (isSelected) (if (active) StatusGreen else AccentPurple)
                            else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .clickable { onNew() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "New session tab",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

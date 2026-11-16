package com.universalbrain.ai.feature.analytics

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.universalbrain.ai.feature.dashboard.UbaViewModel
import com.universalbrain.ai.feature.shell.ErrorRed
import com.universalbrain.ai.feature.shell.SectionHeader
import com.universalbrain.ai.feature.shell.SimpleCard
import com.universalbrain.ai.feature.shell.StatCard
import com.universalbrain.ai.feature.shell.statusColor

@Composable
fun EarningsScreen(vm: UbaViewModel, navController: NavHostController) {
    val earnings by vm.earnings.collectAsState(initial = emptyList())
    val grouped = earnings.groupBy { java.text.DateFormat.getDateInstance().format(java.util.Date(it.at)) }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Earnings Analytics")
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Records", "${earnings.size}", "estimated from pools", Modifier.weight(1f))
            StatCard(
                "Total",
                if (earnings.isEmpty()) "N/A"
                else "%.8f".format(earnings.sumOf { it.amount }) + " ${earnings.first().currency}",
                "all time",
                Modifier.weight(1f), com.universalbrain.ai.feature.shell.AccentBlue
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (earnings.isEmpty()) {
                item {
                    Text(
                        "No earnings yet. Earnings are ESTIMATED from the pool's reported reward rate each time a worker is polled — add workers in Mining Configuration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            grouped.entries.forEach { (day, list) ->
                item(key = "day_$day") {
                    Text(
                        day,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = com.universalbrain.ai.feature.shell.AccentGreen,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                items(list, key = { it.id }) { earning ->
                    SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "%.8f ${earning.currency}".format(earning.amount),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${earning.source} · ${java.text.DateFormat.getTimeInstance().format(java.util.Date(earning.at))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun HistoryScreen(vm: UbaViewModel, navController: NavHostController) {
    val logs by vm.logs.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Activity History")
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (logs.isEmpty()) {
                item {
                    Text(
                        "No activity yet. Every import, clone action, AI tool run and automation trigger is logged here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(logs, key = { it.id }) { log ->
                SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${log.module} · ${log.action}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                            Text(
                                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(log.at)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            log.detail?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun NotificationsScreen(vm: UbaViewModel, navController: NavHostController) {
    val notifications by vm.notifications.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Notifications",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.markNotificationsRead() }) { Text("Mark read") }
            TextButton(onClick = { vm.clearNotifications() }) { Text("Clear", color = ErrorRed) }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (notifications.isEmpty()) {
                item {
                    Text(
                        "No notifications. Automation alerts (network changes, temperature, offline workers) appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(notifications, key = { it.id }) { item ->
                SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                            Text(item.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.at)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!item.read) {
                            com.universalbrain.ai.feature.shell.StatusDot(com.universalbrain.ai.feature.shell.AccentBlue)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

package com.universalbrain.ai.integrations.ads

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.universalbrain.ai.feature.dashboard.UbaViewModel
import com.universalbrain.ai.feature.shell.SectionHeader
import com.universalbrain.ai.feature.shell.SimpleCard

/**
 * Advertising module: consent management + reward ledger (reward records are
 * appended when a supported ad/reward integration is configured; without SDK
 * keys the ledger stays empty and the UI says so — no fake rewards).
 */
@Composable
fun AdsScreen(vm: UbaViewModel, navController: NavHostController) {
    val consent by vm.adsConsent.collectAsState()
    val earnings by vm.earnings.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Advertising Dashboard")
        SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Personalized ads consent", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Required before any ad SDK may be initialized. Currently no ad SDK is bundled; this consent is stored and honored when an integration is added.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = consent, onCheckedChange = { vm.setAdsConsent(it) })
            }
        }
        Text(
            "Integration status: NO AD SDK CONNECTED. Live advertisements require adding a supported SDK (e.g. AdMob) with real application IDs — this app will not fake ad views or rewards.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
        SectionHeader("Reward ledger")
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (earnings.isEmpty()) {
                item {
                    Text(
                        "No rewards recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(earnings.filter { it.source.contains("AD") || it.source.contains("REWARD") }, key = { it.id }) { reward ->
                SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                    Text("%.8f ${reward.currency}".format(reward.amount), fontWeight = FontWeight.SemiBold)
                    Text(reward.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

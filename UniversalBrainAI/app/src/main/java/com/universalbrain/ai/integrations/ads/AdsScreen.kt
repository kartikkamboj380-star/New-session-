package com.universalbrain.ai.integrations.ads

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
import com.universalbrain.ai.domain.ads.AdsEngine
import com.universalbrain.ai.feature.dashboard.UbaViewModel
import com.universalbrain.ai.feature.shell.SectionHeader
import com.universalbrain.ai.feature.shell.SimpleCard
import com.universalbrain.ai.feature.shell.StatCard

/**
 * Advertising module: consent management, reward points, daily cap, streak
 * and the reward ledger. Honest model: this build integrates no ad SDK, so
 * no real rewarded ads can be served yet — points appear ONLY when a real
 * SDK callback fires (UbaViewModel.recordAdReward). Nothing is simulated.
 */
@Composable
fun AdsScreen(vm: UbaViewModel, navController: NavHostController) {
    val consent by vm.adsConsent.collectAsState()
    val balance by vm.adBalance.collectAsState(initial = 0)
    val today by vm.adsToday.collectAsState()
    val streak by vm.adsStreak.collectAsState()
    val rewards by vm.adRewards.collectAsState(initial = emptyList())

    val state = AdsEngine.state(balance, today, streak, consent, providerConfigured = false)

    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader("Advertising Dashboard")
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Points", "$balance", "lifetime", Modifier.weight(1f))
            StatCard("Today", "$today/${state.cap}", "daily cap", Modifier.weight(1f))
            StatCard("Streak", "$streak", "day(s)", Modifier.weight(1f))
        }
        SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Personalized ads consent", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Required before any ad SDK may be initialized. Your choice is stored and honored when an integration is added.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = consent, onCheckedChange = { vm.setAdsConsent(it) })
            }
        }
        SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Rewarded ads readiness", fontWeight = FontWeight.SemiBold)
            Text(
                state.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Honest status: this build has NO ad SDK bundled. When a supported provider (e.g. AdMob) is integrated, its real \"user earned reward\" callback writes the ledger here — this app will not fake ad views or points. The AI can then schedule sessions up to ${AdsEngine.DAILY_CAP}/day and the MASTER BRAIN tracks them with streak bonuses.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionHeader("Reward ledger")
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (rewards.isEmpty()) {
                item {
                    Text(
                        "No rewards recorded yet (waiting for a real ad SDK integration).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(rewards, key = { it.id }) { reward ->
                SimpleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
                    Text("${reward.points} pt(s) · ${reward.kind}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${reward.provider} · " + java.text.DateFormat.getDateTimeInstance()
                            .format(java.util.Date(reward.at)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

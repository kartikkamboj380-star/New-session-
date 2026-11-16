package com.universalbrain.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.universalbrain.ai.core.design.UniversalBrainTheme
import com.universalbrain.ai.feature.accounts.AccountDetailsScreen
import com.universalbrain.ai.feature.accounts.AccountsScreen
import com.universalbrain.ai.feature.aiagent.BrainScreen
import com.universalbrain.ai.feature.aiagent.ChatScreen
import com.universalbrain.ai.feature.analytics.EarningsScreen
import com.universalbrain.ai.feature.analytics.HistoryScreen
import com.universalbrain.ai.feature.analytics.NotificationsScreen
import com.universalbrain.ai.feature.automation.AutomationScreen
import com.universalbrain.ai.feature.automation.BackgroundOpsScreen
import com.universalbrain.ai.feature.automation.DeviceMonitorScreen
import com.universalbrain.ai.feature.cloning.CloneManagerScreen
import com.universalbrain.ai.feature.cloning.InstanceDetailsScreen
import com.universalbrain.ai.feature.dashboard.DashboardScreen
import com.universalbrain.ai.feature.dashboard.UbaViewModel
import com.universalbrain.ai.feature.mining.MiningConfigScreen
import com.universalbrain.ai.feature.mining.MiningDashboardScreen
import com.universalbrain.ai.feature.mining.OptimizerScreen
import com.universalbrain.ai.feature.appimport.AppImportScreen
import com.universalbrain.ai.feature.appimport.LibraryScreen
import com.universalbrain.ai.feature.appimport.ScannerScreen
import com.universalbrain.ai.feature.settings.GeneralSettingsScreen
import com.universalbrain.ai.feature.settings.SecuritySettingsScreen
import com.universalbrain.ai.feature.shell.ModulesGridScreen
import com.universalbrain.ai.feature.shell.OnboardingScreen
import com.universalbrain.ai.feature.shell.SplashScreen
import com.universalbrain.ai.integrations.ads.AdsScreen
import com.universalbrain.ai.service.BrainService

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BrainService.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val container = (application as UbaApp).container
        setContent {
            UniversalBrainTheme {
                val vm: UbaViewModel = viewModel(factory = UbaViewModel.Factory(container))
                AppRoot(vm)
            }
        }
    }
}

object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SCANNER = "scanner"
    const val IMPORT = "import"
    const val LIBRARY = "library"
    const val CLONES = "clones"
    const val INSTANCE = "instance/{id}"
    const val ACCOUNTS = "accounts"
    const val ACCOUNT = "account/{id}"
    const val BRAIN = "brain"
    const val CHAT = "chat"
    const val MINING = "mining"
    const val MINING_CONFIG = "mining_config"
    const val OPTIMIZER = "optimizer"
    const val ADS = "ads"
    const val AUTOMATION = "automation"
    const val BACKGROUND = "background"
    const val MONITOR = "monitor"
    const val EARNINGS = "earnings"
    const val HISTORY = "history"
    const val NOTIFICATIONS = "notifications"
    const val SECURITY = "security"
    const val SETTINGS = "settings"
    const val MODULES = "modules"
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun AppRoot(vm: UbaViewModel) {
    val navController = rememberNavController()
    val onboarded by vm.onboarded.collectAsState()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val tabs = listOf(
        Tab(Routes.HOME, "Home") { Icon(Icons.Filled.Home, contentDescription = "Home") },
        Tab(Routes.LIBRARY, "Apps") { Icon(Icons.Filled.Apps, contentDescription = "Apps") },
        Tab(Routes.CLONES, "Clones") { Icon(Icons.Filled.Tag, contentDescription = "Clones") },
        Tab(Routes.CHAT, "Brain") { Icon(Icons.Filled.Chat, contentDescription = "Brain") },
        Tab(Routes.MINING, "Mining") { Icon(Icons.Filled.Memory, contentDescription = "Mining") }
    )

    val startRoute = if (onboarded) Routes.HOME else Routes.SPLASH
    val showBottomBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = tab.icon,
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.SPLASH) { SplashScreen(onDone = {
                navController.navigate(Routes.ONBOARDING) { popUpTo(Routes.SPLASH) { inclusive = true } }
            }) }
            composable(Routes.ONBOARDING) { OnboardingScreen(onDone = {
                vm.setOnboarded()
                navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            }) }
            composable(Routes.HOME) { DashboardScreen(vm, navController) }
            composable(Routes.SCANNER) { ScannerScreen(vm, navController) }
            composable(Routes.IMPORT) { AppImportScreen(vm, navController) }
            composable(Routes.LIBRARY) { LibraryScreen(vm, navController) }
            composable(Routes.CLONES) { CloneManagerScreen(vm, navController) }
            composable(Routes.INSTANCE) { entry ->
                InstanceDetailsScreen(vm, navController, entry.arguments?.getString("id") ?: "")
            }
            composable(Routes.ACCOUNTS) { AccountsScreen(vm, navController) }
            composable(Routes.ACCOUNT) { entry ->
                AccountDetailsScreen(vm, navController, entry.arguments?.getString("id") ?: "")
            }
            composable(Routes.BRAIN) { BrainScreen(vm, navController) }
            composable(Routes.CHAT) { ChatScreen(vm, navController) }
            composable(Routes.MINING) { MiningDashboardScreen(vm, navController) }
            composable(Routes.MINING_CONFIG) { MiningConfigScreen(vm, navController) }
            composable(Routes.OPTIMIZER) { OptimizerScreen(vm, navController) }
            composable(Routes.ADS) { AdsScreen(vm, navController) }
            composable(Routes.AUTOMATION) { AutomationScreen(vm, navController) }
            composable(Routes.BACKGROUND) { BackgroundOpsScreen(vm, navController) }
            composable(Routes.MONITOR) { DeviceMonitorScreen(vm, navController) }
            composable(Routes.EARNINGS) { EarningsScreen(vm, navController) }
            composable(Routes.HISTORY) { HistoryScreen(vm, navController) }
            composable(Routes.NOTIFICATIONS) { NotificationsScreen(vm, navController) }
            composable(Routes.SECURITY) { SecuritySettingsScreen(vm, navController) }
            composable(Routes.SETTINGS) { GeneralSettingsScreen(vm, navController) }
            composable(Routes.MODULES) { ModulesGridScreen(navController) }
        }
    }
}

package com.scamkill.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.scamkill.app.ui.screens.MessageLogScreen
import com.scamkill.app.ui.screens.SettingsScreen

object Routes {
    const val MESSAGES = "messages"
    const val SETTINGS = "settings"
}

@Composable
fun ScamKillNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.MESSAGES) {
        composable(Routes.MESSAGES) {
            MessageLogScreen(
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

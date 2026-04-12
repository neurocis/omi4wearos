package com.omi4wos.wear.presentation

import androidx.compose.runtime.Composable
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.omi4wos.wear.presentation.screens.AboutScreen
import com.omi4wos.wear.presentation.screens.HomeScreen
import com.omi4wos.wear.presentation.theme.Omi4wosTheme

@Composable
fun WearApp() {
    Omi4wosTheme {
        val navController = rememberSwipeDismissableNavController()
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = "home"
        ) {
            composable("home") {
                HomeScreen(onAboutClick = { navController.navigate("about") })
            }
            composable("about") {
                AboutScreen()
            }
        }
    }
}

package ro.newsfilter.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import ro.newsfilter.app.ui.screens.bookmarks.BookmarksScreen
import ro.newsfilter.app.ui.screens.detail.DetailScreen
import ro.newsfilter.app.ui.screens.feed.FeedScreen
import ro.newsfilter.app.ui.screens.onboarding.OnboardingScreen
import ro.newsfilter.app.ui.screens.settings.SettingsScreen

/**
 * Graful de navigare al aplicației.
 *
 * Decizie tehnică: Folosim Navigation Compose cu NavHost.
 * startDestination se decide dinamic: dacă onboarding-ul nu e completat,
 * pornim cu OnboardingScreen, altfel cu FeedScreen.
 *
 * DetailScreen primește articleId ca argument din rută.
 */
@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // === Onboarding ===
        composable(route = Routes.Onboarding.route) {
            OnboardingScreen(
                onOnboardingComplete = {
                    navController.navigate(Routes.Feed.route) {
                        popUpTo(Routes.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        // === Feed "Pentru tine" ===
        composable(route = Routes.Feed.route) {
            FeedScreen(
                onArticleClick = { articleId ->
                    navController.navigate(Routes.Detail.createRoute(articleId))
                }
            )
        }

        // === Articole salvate ===
        composable(route = Routes.Bookmarks.route) {
            BookmarksScreen(
                onArticleClick = { articleId ->
                    navController.navigate(Routes.Detail.createRoute(articleId))
                }
            )
        }

        // === Setări ===
        composable(route = Routes.Settings.route) {
            SettingsScreen()
        }

        // === Detaliu articol ===
        composable(
            route = Routes.Detail.route,
            arguments = listOf(
                navArgument("articleId") { type = NavType.StringType }
            )
        ) {
            DetailScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}


package ro.newsfilter.app.ui.navigation

/**
 * Definirea rutelor de navigare ca sealed class.
 *
 * Decizie tehnică: Sealed class oferă type-safety la compilare și
 * previne erori de string în rute. Fiecare rută expune un `route` string
 * folosit de NavHost. DetailScreen primește articleId ca argument.
 */
sealed class Routes(val route: String) {
    data object Onboarding : Routes("onboarding")
    data object Feed : Routes("feed")
    data object Bookmarks : Routes("bookmarks")
    data object Settings : Routes("settings")
    data object Detail : Routes("detail/{articleId}") {
        fun createRoute(articleId: String): String = "detail/$articleId"
    }
}


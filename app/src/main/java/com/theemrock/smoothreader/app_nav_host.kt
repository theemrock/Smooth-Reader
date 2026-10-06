package com.theemrock.smoothreader

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument



@Composable
fun AppNavHost() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenBook = { bookId -> nav.navigate(resumeRoute(bookId)) }
            )
        }

        composable(Routes.SETTINGS) {
            PlaceholderScreen(title = "Ayarlar (yakında)") { nav.popBackStack() }
        }

        composable(
            route = "${Routes.RESUME}/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
            ResumeScreen(
                bookId = bookId,
                onBack = { nav.popBackStack() },
                onRead = { id -> nav.navigate(readerRoute(id, ReadingMode.READ)) },
                onListen = { id -> nav.navigate(readerRoute(id, ReadingMode.LISTEN)) }
            )
        }

        composable(
            route = "${Routes.READER}/{bookId}?mode={mode}",
            arguments = listOf(
                navArgument("bookId") { type = NavType.StringType },
                navArgument("mode") {
                    type = NavType.StringType; defaultValue = ReadingMode.READ.name
                }
            )
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
            val modeStr = backStackEntry.arguments?.getString("mode").orEmpty()
            val mode = runCatching { ReadingMode.valueOf(modeStr) }.getOrElse { ReadingMode.READ }

            ReaderScreen(
                bookId = bookId,
                mode = mode,
                onBack = {
                    // Reader'dan çıkınca direkt Home'a dön
                    nav.popBackStack(Routes.HOME, inclusive = false)
                }
            )

        }
    }
}

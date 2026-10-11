package com.theemrock.smoothreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument



@Composable
internal fun AppNavHost(
    playbackOpenRequest: PlaybackOpenRequest? = null,
    onPlaybackOpenConsumed: () -> Unit = {}
) {
    val nav = rememberNavController()

    LaunchedEffect(playbackOpenRequest) {
        val request = playbackOpenRequest ?: return@LaunchedEffect
        val current = nav.currentBackStackEntry
        val currentBookId = current?.arguments?.getString("bookId")
        val alreadyShowingBook =
            current?.destination?.route?.startsWith("${Routes.READER}/") == true &&
                currentBookId == request.bookId
        if (!alreadyShowingBook) {
            nav.navigate(readerRoute(request.bookId, request.mode)) {
                launchSingleTop = true
            }
        }
        onPlaybackOpenConsumed()
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                onOpenBook = { bookId -> nav.navigate(resumeRoute(bookId)) }
            )
        }

        composable(
            route = "${Routes.RESUME}/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
            ResumeScreen(
                 bookId = bookId,
    onBack = { nav.popBackStack() },
    onContinue = { id ->
        nav.navigate(readerRoute(id, ReadingMode.READ))
    }
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

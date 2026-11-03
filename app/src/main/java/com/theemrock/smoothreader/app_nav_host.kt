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
                onOpenBook = { bookId -> nav.navigate(resumeRoute(bookId)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenHighlights = { nav.navigate(Routes.HIGHLIGHTS) }
            )
        }

        composable(
            route = "${Routes.RSVP}/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { backStackEntry ->
            RsvpRouteScreen(
                bookId = backStackEntry.arguments?.getString("bookId").orEmpty(),
                onBack = { nav.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }

        composable(Routes.HIGHLIGHTS) {
            HighlightsScreen(
                onBack = { nav.popBackStack() },
                onGoTo = { highlight ->
                    nav.navigate(readerRoute(highlight.bookId, ReadingMode.READ, highlight.startOffset))
                }
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
    },
    onQuickRead = { id ->
        nav.navigate(rsvpRoute(id))
    }
)
        }

        composable(
            route = "${Routes.READER}/{bookId}?mode={mode}&hl={hl}",
            arguments = listOf(
                navArgument("bookId") { type = NavType.StringType },
                navArgument("mode") {
                    type = NavType.StringType; defaultValue = ReadingMode.READ.name
                },
                navArgument("hl") {
                    type = NavType.IntType; defaultValue = -1
                }
            )
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
            val modeStr = backStackEntry.arguments?.getString("mode").orEmpty()
            val mode = runCatching { ReadingMode.valueOf(modeStr) }.getOrElse { ReadingMode.READ }
            val hl = backStackEntry.arguments?.getInt("hl") ?: -1

            ReaderScreen(
                bookId = bookId,
                mode = mode,
                jumpOffset = hl.takeIf { it >= 0 },
                onBack = {
                    // Reader'dan çıkınca direkt Home'a dön
                    nav.popBackStack(Routes.HOME, inclusive = false)
                }
            )

        }
    }
}

package com.theemrock.smoothreader

object Routes {
    const val HOME = "home"
    const val RESUME = "resume" // resume/{bookId}
    const val READER = "reader" // reader/{bookId}?mode=READ|LISTEN
}

fun resumeRoute(bookId: String) = "${Routes.RESUME}/$bookId"
fun readerRoute(bookId: String, mode: ReadingMode) = "${Routes.READER}/$bookId?mode=${mode.name}"

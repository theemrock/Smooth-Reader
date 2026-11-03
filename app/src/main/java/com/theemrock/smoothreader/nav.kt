package com.theemrock.smoothreader

object Routes {
    const val HOME = "home"
    const val RESUME = "resume" // resume/{bookId}
    const val READER = "reader" // reader/{bookId}?mode=READ|LISTEN
    const val SETTINGS = "settings"
    const val HIGHLIGHTS = "highlights"
    const val RSVP = "rsvp" // rsvp/{bookId}, opened from resume screen "Hızlı Oku"
}

fun rsvpRoute(bookId: String) = "${Routes.RSVP}/$bookId"

fun resumeRoute(bookId: String) = "${Routes.RESUME}/$bookId"
/** [jumpOffset] >= 0 opens the reader at a highlight without moving the saved progress. */
fun readerRoute(bookId: String, mode: ReadingMode, jumpOffset: Int = -1) =
    "${Routes.READER}/$bookId?mode=${mode.name}&hl=$jumpOffset"

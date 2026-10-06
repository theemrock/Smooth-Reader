package com.theemrock.smoothreader

object ParsedCache {
    private val map = mutableMapOf<String, ParsedBook>()

    fun get(bookId: String): ParsedBook? = map[bookId]

    fun put(parsed: ParsedBook) {
        map[parsed.bookId] = parsed
    }

    fun remove(bookId: String) {
        map.remove(bookId)
    }

    fun clear() {
        map.clear()
    }

    suspend fun getOrParse(
        bookId: String,
        parse: suspend () -> ParseResult,
        acceptResult: () -> Boolean = { true }
    ): ParseResult {
        get(bookId)?.let { return ParseResult.Success(it) }
        val result = parse()
        if (result is ParseResult.Success &&
            result.parsed.bookId == bookId &&
            acceptResult()
        ) {
            put(result.parsed)
        }
        return result
    }
}

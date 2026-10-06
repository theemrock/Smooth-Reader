package com.theemrock.smoothreader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PaginationTicket(val content: Long, val layout: Long)

internal class PaginationEpoch {
    private var current = PaginationTicket(-1, 0)
    fun next(content: Long): PaginationTicket = PaginationTicket(content, current.layout + 1).also { current = it }
    fun accepts(ticket: PaginationTicket): Boolean = current == ticket
}

/** Main-thread confined, with a suspending measurement callback supplied by Compose.
 * Retains at most six bounded source blocks; global counts contain no page text.
 */
internal class WindowPaginator(
    val source: ReadingSource,
    val ticket: PaginationTicket,
    anchor: ReadingPosition,
    private val blockChars: Int = 8192
) {
    init { require(blockChars > 0) }
    internal class Block(val start: Int, val end: Int, val text: String) {
        val pages = mutableListOf<ReadingPage>()
        var cursor = 0
        var complete = false
    }

    private val nominalBlockCount = if (source.length == 0) 0 else (source.length - 1) / blockChars + 1
    val blockCount = if (nominalBlockCount > 1 && boundary(nominalBlockCount - 1) == source.length)
        nominalBlockCount - 1 else nominalBlockCount
    private val counts = IntArray(blockCount) { -1 }
    private val cache = LinkedHashMap<Int, Block>()
    var focus = blockAt(anchor)
    var demand: Int? = null
    var legacyPage: Int? = null
    val cachedBlockCount get() = cache.size
    val totalPages: Int get() = if (counts.all { it >= 0 }) counts.sum() else 0

    private fun boundary(index: Int): Int {
        if (index <= 0) return 0
        val nominal = (index.toLong() * blockChars).coerceAtMost(source.length.toLong()).toInt()
        if (nominal == source.length) return nominal
        val lookahead = minOf(80, (blockChars / 2).coerceAtLeast(1))
        val nearby = source.slice(nominal, (nominal + lookahead).coerceAtMost(source.length))
        val whitespace = nearby.indexOfFirst { it.isWhitespace() }
        if (whitespace >= 0) return nominal + whitespace + 1
        // Never start between the two UTF-16 units of a surrogate pair.
        return if (nearby.firstOrNull()?.isLowSurrogate() == true) nominal + 1 else nominal
    }

    fun blockAt(position: ReadingPosition): Int {
        if (blockCount == 0) return 0
        val offset = source.offsetOf(position).coerceAtMost(source.length - 1)
        var index = (offset / blockChars).coerceAtMost(blockCount - 1)
        while (index > 0 && offset < boundary(index)) index--
        return index
    }

    fun block(index: Int): Block? = cache[index]

    fun nextBlock(): Int? {
        demand?.takeIf { it in counts.indices && cache[it]?.complete != true }?.let { return it }
        // A legacy ordinal is resolved by counting from the real book beginning only.
        legacyPage?.takeIf { it >= 0 && locateGlobal(it) == null }?.let {
            counts.indexOfFirst { count -> count < 0 }.takeIf { index -> index >= 0 }?.let { index -> return index }
        }
        val nearby = listOf(focus, focus + 1, focus - 1, focus + 2, focus - 2)
        nearby.firstOrNull { it in counts.indices && cache[it]?.complete != true }?.let { return it }
        return counts.indexOfFirst { it < 0 }.takeIf { it >= 0 }
    }

    fun globalNumber(block: Int, local: Int): Int? {
        if (block !in counts.indices || (0 until block).any { counts[it] < 0 }) return null
        return (0 until block).sumOf { counts[it] } + local + 1
    }

    /** Zero-based global index. Only fully counted prefixes may supply a block offset. */
    fun locateGlobal(index: Int): Pair<Int, Int>? {
        if (index < 0) return null
        var remaining = index
        for (block in counts.indices) {
            val count = counts[block]
            if (count < 0) {
                return if (remaining < (cache[block]?.pages?.size ?: 0)) block to remaining else null
            }
            if (remaining < count) return block to remaining
            remaining -= count
        }
        return null
    }

    private fun obtain(index: Int): Block {
        cache[index]?.let { return it }
        if (cache.size >= 6) {
            val victim = cache.keys.firstOrNull { it != demand && it !in (focus - 2)..(focus + 2) }
                ?: cache.keys.first { it != focus && it != demand }
            cache.remove(victim)
        }
        val start = boundary(index)
        val end = boundary(index + 1)
        return Block(start, end, source.slice(start, end)).also { cache[index] = it }
    }

    /** Exactly one page per step. The caller can reprioritize after every page. */
    suspend fun step(index: Int, fits: suspend (String) -> Boolean) {
        require(index in counts.indices)
        val block = obtain(index)
        if (block.complete) return
        val text = block.text
        var start = block.cursor
        while (start < text.length && text[start].isWhitespace()) start++
        if (start == text.length) {
            block.complete = true
            counts[index] = block.pages.size
            return
        }
        currentCoroutineContext().ensureActive()
        val remaining = text.length - start
        var best = 0
        var upper = minOf(256, remaining)
        while (true) {
            val fitsCandidate = fits(text.substring(start, start + upper))
            currentCoroutineContext().ensureActive()
            if (!fitsCandidate) break
            best = upper
            if (upper == remaining) break
            upper = minOf(upper * 2, remaining)
        }
        if (best < upper) {
            var low = best + 1
            var high = upper - 1
            while (low <= high) {
                currentCoroutineContext().ensureActive()
                val middle = (low + high) / 2
                val fitsCandidate = fits(text.substring(start, start + middle))
                currentCoroutineContext().ensureActive()
                if (fitsCandidate) { best = middle; low = middle + 1 } else high = middle - 1
            }
        }
        var length = best.coerceAtLeast(1)
        if (length < remaining) {
            val lastSpace = text.substring(start, start + length).indexOfLast { it.isWhitespace() }
            if (lastSpace > 0 && length - lastSpace < 80) length = lastSpace
            if (text[start + length].isLowSurrogate() && text[start + length - 1].isHighSurrogate()) {
                length = if (length > 1) length - 1 else 2
            }
        }
        val end = start + length
        val page = source.page(block.start + start, block.start + end, text.substring(start, end).trimEnd())
        currentCoroutineContext().ensureActive()
        block.cursor = end
        if (page.text.isNotBlank()) block.pages += page
        while (block.cursor < text.length && text[block.cursor].isWhitespace()) block.cursor++
        if (block.cursor == text.length) {
            block.complete = true
            counts[index] = block.pages.size
        }
    }
}

package com.theemrock.smoothreader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProgressJsonTest {
    @Test fun canonicalRoundTripPreservesUtf16OffsetAndMetadata() {
        val p = ReadingProgress("book", ReadingPosition(12, 37), 90, 123456L)
        assertEquals(p, ProgressJson.decode(ProgressJson.upsert("", p))["book"])
    }

    @Test fun oldParagraphRecordHasZeroOffset() {
        val json = """{"old":{"bookId":"old","paragraphIndex":7,"totalParagraphs":20,"updatedAt":42}}"""
        val p = ProgressJson.decode(json, legacy = true)["old"]!!
        assertEquals(ReadingPosition(7, 0), p.position)
        assertEquals(20, p.totalParagraphs)
        assertTrue(ProgressJson.decode(json).isEmpty()) // Not yet canonical.
    }

    @Test fun unrelatedBooksAndHashCollisionsStayIndependent() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val first = ReadingProgress("Aa", ReadingPosition(2, 3), 10, 1)
        val second = ReadingProgress("BB", ReadingPosition(5, 6), 30, 2)
        val json = ProgressJson.upsert(ProgressJson.upsert("", first), second)
        val updated = ProgressJson.decode(ProgressJson.upsert(json, first.copy(position = ReadingPosition(3, 4))))
        assertEquals(second, updated["BB"])
        assertEquals(ReadingPosition(3, 4), updated["Aa"]!!.position)
    }

    @Test fun malformedRecordDoesNotHideOrDeleteOtherBooks() {
        val p = ReadingProgress("good", ReadingPosition(2, 4))
        val raw = JSONObject(ProgressJson.upsert("", p)).put("broken", "retain me").toString()
        assertEquals(p, ProgressJson.decode(raw)["good"])
        val changed = ProgressJson.upsert(raw, p.copy(totalParagraphs = 50))
        assertEquals("retain me", JSONObject(changed).getString("broken"))
        assertEquals(ReadingPosition(2, 4), ProgressJson.decode(changed)["good"]!!.position)
    }

    @Test fun missingOrInvalidPositionIsNotDefaultedToZero() {
        val raw = """{"a":{"bookId":"a","position":{"paragraphIndex":"bad","characterOffset":0}},"b":{"bookId":"b","position":{"paragraphIndex":4}}}"""
        assertTrue(ProgressJson.decode(raw).isEmpty())
    }

    @Test fun malformedRootCannotBeOverwrittenByFallback() {
        assertTrue(ProgressJson.decode("broken").isEmpty())
        assertThrows(Exception::class.java) { ProgressJson.upsert("broken", ReadingProgress("a")) }
    }

    @Test fun migrationIsIdempotentAndNeverOverwritesExistingCanonical() {
        val saved = ReadingProgress("a", ReadingPosition(8, 9), 50, 100)
        val raw = ProgressJson.upsert("", saved, onlyIfAbsent = true)
        val retried = ProgressJson.upsert(raw, ReadingProgress("a"), onlyIfAbsent = true)
        assertEquals(saved, ProgressJson.decode(retried)["a"])
    }

    @Test fun corruptExistingCanonicalIsNotReplacedByMigration() {
        assertThrows(Exception::class.java) {
            ProgressJson.upsert("""{"a":"broken"}""", ReadingProgress("a"), onlyIfAbsent = true)
        }
    }

    @Test fun percentRetainsParagraphBasedBehavior() {
        assertEquals(50, ReadingProgress("a", ReadingPosition(5, 12), 11).percent())
        assertEquals(100, ReadingProgress("a", ReadingPosition(10, 0), 11).percent())
        assertEquals(0, ReadingProgress("a").percent())
    }
}

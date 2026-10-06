package com.theemrock.smoothreader

import org.json.JSONObject

internal object ProgressJson {
    fun decode(json: String, legacy: Boolean = false): Map<String, ReadingProgress> {
        if (json.isBlank()) return emptyMap()
        val root = try { JSONObject(json) } catch (_: Exception) { return emptyMap() }
        return buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                try {
                    val entry = root.getJSONObject(id)
                    require(entry.getString("bookId") == id)
                    val position = if (legacy) {
                        ReadingPosition(entry.getInt("paragraphIndex"), 0)
                    } else {
                        val p = entry.getJSONObject("position")
                        ReadingPosition(p.getInt("paragraphIndex"), p.getInt("characterOffset"))
                    }
                    put(id, ReadingProgress(id, position,
                        entry.optInt("totalParagraphs", 0), entry.optLong("updatedAt", 0)))
                } catch (_: Exception) {
                    // A broken entry does not hide other books; raw data is left untouched.
                }
            }
        }
    }

    fun upsert(json: String, progress: ReadingProgress, onlyIfAbsent: Boolean = false): String {
        // Fail closed on a broken root rather than replacing all existing records.
        val root = if (json.isBlank()) JSONObject() else JSONObject(json)
        if (onlyIfAbsent && root.has(progress.bookId)) {
            require(decode(json).containsKey(progress.bookId)) { "Mevcut konum kaydı okunamadı." }
            return root.toString()
        }
        val entry = JSONObject()
            .put("bookId", progress.bookId)
            .put("position", JSONObject()
                .put("paragraphIndex", progress.position.paragraphIndex)
                .put("characterOffset", progress.position.characterOffset))
            .put("totalParagraphs", progress.totalParagraphs)
            .put("updatedAt", progress.updatedAt)
        root.put(progress.bookId, entry)
        return root.toString()
    }
}

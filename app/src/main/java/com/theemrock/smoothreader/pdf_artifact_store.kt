package com.theemrock.smoothreader

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PdfArtifactFingerprint(
    val bookId: String,
    val sourceUri: String,
    val sourceSize: Long,
    val sourceLastModified: Long,
    val prefixHash: String,
    val engineVersion: String,
    val normalizationVersion: Int
)

data class PdfCanonicalArtifact(
    val fingerprint: PdfArtifactFingerprint,
    val capabilities: PdfDocumentCapabilities,
    val paragraphs: List<String>,
    val sourceMap: List<PdfSourceMapEntry>
)

class PdfArtifactStore(internal val directory: File) {
    internal fun fileFor(bookId: String): File =
        File(directory, bookId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    fun read(expected: PdfArtifactFingerprint): PdfCanonicalArtifact? {
        val file = fileFor(expected.bookId)
        if (!file.isFile) return null
        return runCatching { decode(file.readText(Charsets.UTF_8)) }
            .getOrNull()
            ?.takeIf { it.fingerprint == expected }
    }

    suspend fun write(
        artifact: PdfCanonicalArtifact,
        beforePublish: suspend () -> Unit = { currentCoroutineContext().ensureActive() }
    ) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val finalFile = fileFor(artifact.fingerprint.bookId)
        val temporary = File(directory, finalFile.name + ".${System.nanoTime()}.tmp")
        try {
            temporary.writeText(encode(artifact), Charsets.UTF_8)
            beforePublish()
            currentCoroutineContext().ensureActive()
            try {
                Files.move(
                    temporary.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun encode(artifact: PdfCanonicalArtifact): String = JSONObject().apply {
        put("fingerprint", encodeFingerprint(artifact.fingerprint))
        put("decision", artifact.capabilities.decision.name)
        put("pages", JSONArray().apply {
            artifact.capabilities.pages.forEach { page ->
                put(JSONObject().apply {
                    put("index", page.pageIndex)
                    put("width", page.width.toDouble())
                    put("height", page.height.toDouble())
                    put("rotation", page.rotation)
                    put("state", page.textState.name)
                    put("reason", page.reason ?: JSONObject.NULL)
                })
            }
        })
        put("paragraphs", JSONArray(artifact.paragraphs))
        put("sourceMap", JSONArray().apply {
            artifact.sourceMap.forEach { entry ->
                put(JSONObject().apply {
                    put("page", entry.pageIndex)
                    put("line", entry.sourceLineIndex)
                    put("left", entry.sourceBounds.left.toDouble())
                    put("top", entry.sourceBounds.top.toDouble())
                    put("right", entry.sourceBounds.right.toDouble())
                    put("bottom", entry.sourceBounds.bottom.toDouble())
                    put("paragraph", entry.paragraphIndex)
                    put("start", entry.canonicalStart)
                    put("end", entry.canonicalEnd)
                })
            }
        })
    }.toString()

    private fun encodeFingerprint(value: PdfArtifactFingerprint) = JSONObject().apply {
        put("bookId", value.bookId)
        put("sourceUri", value.sourceUri)
        put("sourceSize", value.sourceSize)
        put("sourceLastModified", value.sourceLastModified)
        put("prefixHash", value.prefixHash)
        put("engineVersion", value.engineVersion)
        put("normalizationVersion", value.normalizationVersion)
    }

    private fun decode(raw: String): PdfCanonicalArtifact {
        val root = JSONObject(raw)
        val fingerprintObject = root.getJSONObject("fingerprint")
        val fingerprint = PdfArtifactFingerprint(
            fingerprintObject.getString("bookId"),
            fingerprintObject.getString("sourceUri"),
            fingerprintObject.getLong("sourceSize"),
            fingerprintObject.getLong("sourceLastModified"),
            fingerprintObject.getString("prefixHash"),
            fingerprintObject.getString("engineVersion"),
            fingerprintObject.getInt("normalizationVersion")
        )
        val pageArray = root.getJSONArray("pages")
        val pages = buildList {
            for (index in 0 until pageArray.length()) {
                val page = pageArray.getJSONObject(index)
                add(PdfPageCapability(
                    page.getInt("index"),
                    page.getDouble("width").toFloat(),
                    page.getDouble("height").toFloat(),
                    page.getInt("rotation"),
                    PdfPageTextState.valueOf(page.getString("state")),
                    page.optString("reason").takeIf { it.isNotBlank() && it != "null" }
                ))
            }
        }
        val paragraphArray = root.getJSONArray("paragraphs")
        val paragraphs = List(paragraphArray.length()) { paragraphArray.getString(it) }
        val mapArray = root.getJSONArray("sourceMap")
        val sourceMap = buildList {
            for (index in 0 until mapArray.length()) {
                val entry = mapArray.getJSONObject(index)
                add(PdfSourceMapEntry(
                    entry.getInt("page"),
                    entry.getInt("line"),
                    PdfBounds(
                        entry.getDouble("left").toFloat(),
                        entry.getDouble("top").toFloat(),
                        entry.getDouble("right").toFloat(),
                        entry.getDouble("bottom").toFloat()
                    ),
                    entry.getInt("paragraph"),
                    entry.getInt("start"),
                    entry.getInt("end")
                ))
            }
        }
        return PdfCanonicalArtifact(
            fingerprint,
            PdfDocumentCapabilities(pages, PdfReflowDecision.valueOf(root.getString("decision"))),
            paragraphs,
            sourceMap
        )
    }
}

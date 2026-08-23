package com.smile.kiosk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Thin Android-Context-dependent wrapper around the cache directory + a
// small JSON index file. All actual decision-making (diff/eviction logic)
// lives in MediaCacheSync, which is plain Kotlin and unit-testable; this
// class is just I/O.
class MediaCacheStore(context: Context) {
    private val cacheDir = File(context.filesDir, "media_cache").apply { mkdirs() }
    private val indexFile = File(cacheDir, "index.json")

    fun load(): List<CachedMediaEntry> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(indexFile.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                CachedMediaEntry(
                    mediaItemId = o.getString("media_item_id"),
                    mediaRecipientId = o.getString("media_recipient_id"),
                    mediaType = o.getString("media_type"),
                    localFileName = o.getString("local_file_name"),
                    fileSizeBytes = o.getLong("file_size_bytes"),
                    sortOrder = o.getLong("sort_order"),
                    delivered = o.optBoolean("delivered", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(entries: List<CachedMediaEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("media_item_id", entry.mediaItemId)
                    put("media_recipient_id", entry.mediaRecipientId)
                    put("media_type", entry.mediaType)
                    put("local_file_name", entry.localFileName)
                    put("file_size_bytes", entry.fileSizeBytes)
                    put("sort_order", entry.sortOrder)
                    put("delivered", entry.delivered)
                },
            )
        }
        indexFile.writeText(array.toString())
    }

    fun fileFor(entry: CachedMediaEntry): File = File(cacheDir, entry.localFileName)

    fun fileNameFor(item: MediaItem): String {
        val extension = if (item.mediaType == "video") "mp4" else "jpg"
        return "${item.mediaItemId}.$extension"
    }

    fun deleteFile(entry: CachedMediaEntry) {
        runCatching { fileFor(entry).delete() }
    }

    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}

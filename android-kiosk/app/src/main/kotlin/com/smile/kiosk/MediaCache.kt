package com.smile.kiosk

// One row of the local offline-cache index. Deliberately a plain data class
// (no Room/database dependency) persisted as a small JSON array by
// MediaCacheStore -- the dataset size here (a family's worth of photos/videos
// per tablet) doesn't need indexed SQL queries, and this avoids pulling in
// Room's KSP/annotation-processor version-matching risk for a fictional
// mid-2026 Kotlin toolchain.
data class CachedMediaEntry(
    val mediaItemId: String,
    val mediaRecipientId: String,
    val mediaType: String,
    val localFileName: String,
    val fileSizeBytes: Long,
    val sortOrder: Long,
    val delivered: Boolean,
)

object MediaCacheSync {
    data class DiffResult(
        val toDownload: List<MediaItem>,
        val toDelete: List<CachedMediaEntry>,
    )

    // Pure function: given what the server says should be on this device and
    // what's already cached locally, decide what to fetch and what to evict.
    // No I/O here so it's trivially unit-testable.
    fun diff(remote: List<MediaItem>, local: List<CachedMediaEntry>): DiffResult {
        val localIds = local.map { it.mediaItemId }.toSet()
        val remoteIds = remote.map { it.mediaItemId }.toSet()

        val toDownload = remote.filter { it.mediaItemId !in localIds }
        val toDelete = local.filter { it.mediaItemId !in remoteIds }

        return DiffResult(toDownload, toDelete)
    }

    // Which cached entries to evict to get back under a byte budget, oldest
    // (by sort_order) first. Returns entries to remove, in eviction order.
    fun entriesToEvictForCap(cached: List<CachedMediaEntry>, maxBytes: Long): List<CachedMediaEntry> {
        var total = cached.sumOf { it.fileSizeBytes }
        if (total <= maxBytes) return emptyList()

        val sortedOldestFirst = cached.sortedBy { it.sortOrder }
        val toEvict = mutableListOf<CachedMediaEntry>()
        for (entry in sortedOldestFirst) {
            if (total <= maxBytes) break
            toEvict += entry
            total -= entry.fileSizeBytes
        }
        return toEvict
    }
}

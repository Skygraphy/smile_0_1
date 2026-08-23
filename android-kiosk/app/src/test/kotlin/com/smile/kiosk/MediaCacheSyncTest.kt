package com.smile.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCacheSyncTest {

    private fun mediaItem(id: String) = MediaItem(
        mediaRecipientId = "recipient-$id",
        mediaItemId = id,
        mediaType = "photo",
        displayUrl = "https://example.invalid/$id.jpg",
    )

    private fun cachedEntry(id: String, sortOrder: Long = 0, sizeBytes: Long = 100) = CachedMediaEntry(
        mediaItemId = id,
        mediaRecipientId = "recipient-$id",
        mediaType = "photo",
        localFileName = "$id.jpg",
        fileSizeBytes = sizeBytes,
        sortOrder = sortOrder,
        delivered = true,
    )

    @Test
    fun `diff downloads items missing locally`() {
        val remote = listOf(mediaItem("a"), mediaItem("b"))
        val local = listOf(cachedEntry("a"))

        val result = MediaCacheSync.diff(remote, local)

        assertEquals(listOf(mediaItem("b")), result.toDownload)
        assertEquals(emptyList<CachedMediaEntry>(), result.toDelete)
    }

    @Test
    fun `diff deletes items no longer assigned remotely`() {
        val remote = listOf(mediaItem("a"))
        val local = listOf(cachedEntry("a"), cachedEntry("b"))

        val result = MediaCacheSync.diff(remote, local)

        assertEquals(emptyList<MediaItem>(), result.toDownload)
        assertEquals(listOf(cachedEntry("b")), result.toDelete)
    }

    @Test
    fun `diff is a no-op when local already matches remote`() {
        val remote = listOf(mediaItem("a"), mediaItem("b"))
        val local = listOf(cachedEntry("a"), cachedEntry("b"))

        val result = MediaCacheSync.diff(remote, local)

        assertEquals(emptyList<MediaItem>(), result.toDownload)
        assertEquals(emptyList<CachedMediaEntry>(), result.toDelete)
    }

    @Test
    fun `entriesToEvictForCap returns nothing when under budget`() {
        val cached = listOf(cachedEntry("a", sortOrder = 0, sizeBytes = 100), cachedEntry("b", sortOrder = 1, sizeBytes = 100))

        val result = MediaCacheSync.entriesToEvictForCap(cached, maxBytes = 1_000)

        assertEquals(emptyList<CachedMediaEntry>(), result)
    }

    @Test
    fun `entriesToEvictForCap evicts oldest-by-sort-order first until under budget`() {
        val oldest = cachedEntry("old", sortOrder = 0, sizeBytes = 100)
        val middle = cachedEntry("mid", sortOrder = 1, sizeBytes = 100)
        val newest = cachedEntry("new", sortOrder = 2, sizeBytes = 100)
        val cached = listOf(newest, oldest, middle) // deliberately unsorted input

        val result = MediaCacheSync.entriesToEvictForCap(cached, maxBytes = 150)

        // Total is 300, cap is 150 -- must evict oldest first (100), then
        // still over budget (200 > 150), evict next-oldest too (100).
        assertEquals(listOf(oldest, middle), result)
    }
}

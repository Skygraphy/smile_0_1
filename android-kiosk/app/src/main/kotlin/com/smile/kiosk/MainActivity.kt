package com.smile.kiosk

import android.app.admin.DevicePolicyManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Phase 3+5a: pairing + fully offline-capable slideshow + lockdown. As soon
// as this app is Device Owner, applyLockdown() runs unconditionally on every
// start (even before pairing) -- there is deliberately no settings UI and no
// manual "activate lock" affordance, matching the plan's requirement that
// the device can't be fiddled with. The only way out of lock task is a
// future remote command (reset_to_policy), never something reachable
// on-device.
//
// Rendering NEVER touches the network: advanceSlideshow() only ever reads
// from the local MediaCacheStore. syncMedia() is the only thing that talks
// to Supabase, and it runs on a timer independent of what's on screen.
class MainActivity : ComponentActivity() {

    private lateinit var credentials: DeviceCredentialsStore
    private lateinit var dpm: DevicePolicyManager
    private lateinit var root: LinearLayout
    private lateinit var cacheStore: MediaCacheStore

    private val handler = Handler(Looper.getMainLooper())
    private var cachedItems: List<CachedMediaEntry> = emptyList()
    private var slideshowIndex = 0
    private var slideshowIntervalMs = 8000L
    private var maxLocalCacheGb: Double? = null
    private val advanceRunnable = Runnable { advanceSlideshow() }

    // The app stays foregrounded permanently in kiosk mode, so a simple
    // in-activity repeating fetch is enough to notice new photos -- no need
    // for WorkManager here (its minimum periodic interval is 15 minutes
    // anyway, too slow for "a photo shows up soon after it's sent"). The
    // FCM wake path described in the plan can shorten this further later;
    // this poll is the reliability fallback regardless.
    private val mediaSyncIntervalMs = 2 * 60 * 1000L
    private val mediaSyncRunnable = Runnable { syncMedia() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentials = DeviceCredentialsStore(this)
        dpm = getSystemService(DevicePolicyManager::class.java)
        cacheStore = MediaCacheStore(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        setContentView(root)

        KioskLockdown.apply(this)
        ComplianceWorker.schedule(applicationContext)

        if (credentials.isProvisioned()) {
            showSlideshowFromLocalCache()
        } else {
            showPairingScreen()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(advanceRunnable)
        handler.removeCallbacks(mediaSyncRunnable)
    }

    // ---- Pairing ----

    private fun showPairingScreen() {
        root.removeAllViews()

        val statusText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            text = buildString {
                appendLine("Package: $packageName")
                appendLine("Device Owner: ${if (dpm.isDeviceOwnerApp(packageName)) "JA" else "NEIN"}")
            }
        }
        root.addView(statusText)

        val codeInput = EditText(this).apply {
            hint = "Pairing-Code"
        }
        root.addView(codeInput)

        val errorText = TextView(this).apply {
            setTextColor(android.graphics.Color.RED)
        }
        root.addView(errorText)

        val pairButton = Button(this).apply {
            text = "Koppeln"
            setOnClickListener {
                val code = codeInput.text.toString().trim()
                if (code.isEmpty()) return@setOnClickListener
                errorText.text = "Verbinde..."
                lifecycleScope.launch {
                    try {
                        val (deviceId, tenantId, deviceToken) = SupabaseApi.claimDeviceProvisioning(code)
                        credentials.deviceId = deviceId
                        credentials.tenantId = tenantId
                        credentials.deviceToken = deviceToken
                        showSlideshowFromLocalCache()
                    } catch (e: Exception) {
                        errorText.text = "Fehler: ${e.message}"
                    }
                }
            }
        }
        root.addView(pairButton)
    }

    // ---- Slideshow (offline-first: render from cache, sync in the background) ----

    private lateinit var imageView: ImageView
    private lateinit var videoView: VideoView

    private fun showSlideshowFromLocalCache() {
        root.removeAllViews()
        root.setPadding(0, 0, 0, 0)

        imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        videoView = VideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
        }
        root.addView(imageView)
        root.addView(videoView)

        // Render whatever's already on disk immediately -- no network wait.
        cachedItems = cacheStore.load().sortedBy { it.sortOrder }
        advanceSlideshow()

        // Then refresh from the server in the background; this is the only
        // thing in the whole slideshow path that touches the network.
        syncMedia()
    }

    private fun scheduleMediaSync() {
        handler.removeCallbacks(mediaSyncRunnable)
        handler.postDelayed(mediaSyncRunnable, mediaSyncIntervalMs)
    }

    private fun syncMedia() {
        lifecycleScope.launch {
            try {
                val token = credentials.deviceToken ?: return@launch
                val batch = SupabaseApi.getMediaBatch(token)
                slideshowIntervalMs = batch.policy.slideshowIntervalSeconds * 1000L
                maxLocalCacheGb = batch.policy.maxLocalCacheGb

                val remoteItems = batch.items.filter { !it.displayUrl.isNullOrEmpty() }
                var local = cacheStore.load()
                val diff = MediaCacheSync.diff(remoteItems, local)

                // Evict anything the server no longer assigns to this device.
                diff.toDelete.forEach { entry -> cacheStore.deleteFile(entry) }
                local = local.filterNot { entry -> diff.toDelete.any { it.mediaItemId == entry.mediaItemId } }

                // Download anything new. One at a time on purpose -- this is
                // a background loop, not something a person is waiting on,
                // and it keeps memory/bandwidth bounded on modest tablets.
                val newlyDelivered = mutableListOf<CachedMediaEntry>()
                for (item in diff.toDownload) {
                    val displayUrl = item.displayUrl ?: continue
                    val fileName = cacheStore.fileNameFor(item)
                    val destFile = cacheStore.fileFor(
                        CachedMediaEntry(item.mediaItemId, item.mediaRecipientId, item.mediaType, fileName, 0, 0, false),
                    )
                    try {
                        val bytes = SupabaseApi.downloadToFile(displayUrl, destFile)
                        val entry = CachedMediaEntry(
                            mediaItemId = item.mediaItemId,
                            mediaRecipientId = item.mediaRecipientId,
                            mediaType = item.mediaType,
                            localFileName = fileName,
                            fileSizeBytes = bytes,
                            sortOrder = remoteItems.indexOf(item).toLong(),
                            delivered = false,
                        )
                        local = local + entry
                        newlyDelivered += entry
                    } catch (_: Exception) {
                        // Leave it for the next sync cycle to retry.
                    }
                }

                // Fix up sort order to match the server's ordering exactly.
                local = remoteItems.mapNotNull { remote -> local.find { it.mediaItemId == remote.mediaItemId } }
                    .mapIndexed { index, entry -> entry.copy(sortOrder = index.toLong()) }

                val capGb = maxLocalCacheGb
                if (capGb != null) {
                    val maxBytes = (capGb * 1_000_000_000L).toLong()
                    val toEvict = MediaCacheSync.entriesToEvictForCap(local, maxBytes)
                    toEvict.forEach { entry -> cacheStore.deleteFile(entry) }
                    local = local.filterNot { entry -> toEvict.any { it.mediaItemId == entry.mediaItemId } }
                }

                cacheStore.save(local)
                cachedItems = local.sortedBy { it.sortOrder }

                newlyDelivered.forEach { entry ->
                    runCatching { SupabaseApi.markDelivered(token, entry.mediaRecipientId) }
                }
                if (newlyDelivered.isNotEmpty()) {
                    cacheStore.save(local.map { if (it in newlyDelivered) it.copy(delivered = true) else it })
                }
            } catch (_: Exception) {
                // Fallback poll; a failed refresh just tries again next cycle.
                // Whatever's already cached keeps displaying regardless.
            } finally {
                scheduleMediaSync()
            }
        }
    }

    private fun advanceSlideshow() {
        handler.removeCallbacks(advanceRunnable)
        videoView.setOnCompletionListener(null)
        videoView.setOnErrorListener(null)

        if (cachedItems.isEmpty()) {
            handler.postDelayed(advanceRunnable, slideshowIntervalMs)
            return
        }

        val entry = cachedItems[slideshowIndex % cachedItems.size]
        slideshowIndex++
        val file = cacheStore.fileFor(entry)

        if (!file.exists()) {
            // Stale index entry (e.g. cleared cache mid-download); skip fast.
            handler.postDelayed(advanceRunnable, 200)
            return
        }

        markViewedBestEffort(entry)

        if (entry.mediaType == "video") {
            imageView.visibility = View.GONE
            videoView.visibility = View.VISIBLE
            videoView.setVideoURI(Uri.fromFile(file))
            videoView.setOnCompletionListener { advanceSlideshow() }
            videoView.setOnErrorListener { _, _, _ -> advanceSlideshow(); true }
            videoView.start()
        } else {
            videoView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
                }
                if (bitmap != null) imageView.setImageBitmap(bitmap)
                handler.postDelayed(advanceRunnable, slideshowIntervalMs)
            }
        }
    }

    private fun markViewedBestEffort(entry: CachedMediaEntry) {
        val token = credentials.deviceToken ?: return
        lifecycleScope.launch {
            runCatching { SupabaseApi.markViewed(token, entry.mediaRecipientId) }
        }
    }
}

package com.smile.kiosk

import android.app.admin.DevicePolicyManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.util.Log
import android.view.MotionEvent
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
// Rendering NEVER touches the network: renderCurrent() only ever reads
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
    // "slideshow" (default, timer-driven) or "manual" (two tap zones,
    // left/right, no auto-advance) -- entirely policy-driven, never a
    // setting the person at the tablet can reach.
    private var displayMode = "slideshow"
    private val advanceRunnable = Runnable { slideshowIndex++; renderCurrent() }

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

        val pendingPairingCode = credentials.pendingPairingCode
        if (credentials.isProvisioned()) {
            showSlideshowFromLocalCache()
        } else if (!pendingPairingCode.isNullOrBlank()) {
            // Arrived here via QR provisioning (KioskDeviceAdminReceiver) --
            // consume the code immediately so a later manual re-pair (e.g.
            // after a reset_to_policy wipe) never accidentally replays it.
            credentials.pendingPairingCode = null
            showPairingScreen(prefillCode = pendingPairingCode, autoSubmit = true)
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

    private fun showPairingScreen(prefillCode: String? = null, autoSubmit: Boolean = false) {
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
            if (prefillCode != null) setText(prefillCode)
        }
        root.addView(codeInput)

        val errorText = TextView(this).apply {
            setTextColor(android.graphics.Color.RED)
        }
        root.addView(errorText)

        fun submitCode(code: String) {
            if (code.isEmpty()) return
            errorText.setTextColor(android.graphics.Color.RED)
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

        val pairButton = Button(this).apply {
            text = "Koppeln"
            setOnClickListener { submitCode(codeInput.text.toString().trim()) }
        }
        root.addView(pairButton)

        // QR provisioning handed us a code directly -- connect right away
        // instead of making someone re-type what a machine already knows.
        // If it turns out to be stale/invalid, the error above still lands
        // on this same screen with the code pre-filled for manual retry.
        if (autoSubmit && prefillCode != null) submitCode(prefillCode)
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
        setupTapNavigation()

        // Render whatever's already on disk immediately -- no network wait.
        cachedItems = cacheStore.load().sortedBy { it.sortOrder }
        renderCurrent()

        // Then refresh from the server in the background; this is the only
        // thing in the whole slideshow path that touches the network.
        syncMedia()
    }

    // Active only in manual mode (checked on every touch so a policy change
    // takes effect immediately without recreating the listener). Right half
    // advances, left half goes back; no gestures, no icons to interpret.
    private fun setupTapNavigation() {
        root.setOnTouchListener { view, event ->
            // Returning false on ACTION_DOWN opts out of the whole gesture --
            // Android then never delivers the matching ACTION_UP to this
            // listener at all. So ACTION_DOWN must be claimed (return true)
            // whenever we're interested in the eventual ACTION_UP; only the
            // UP itself actually advances the slideshow.
            if (displayMode != "manual") return@setOnTouchListener false
            if (event.action == MotionEvent.ACTION_UP) {
                slideshowIndex += if (event.x > view.width / 2f) 1 else -1
                renderCurrent()
            }
            true
        }
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
                val modeChanged = batch.policy.displayMode != displayMode
                displayMode = batch.policy.displayMode
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

                // A mode switch changes whether a timer should be running at
                // all -- re-render now instead of waiting for whatever timer
                // state happened to be left over from the previous mode.
                if (modeChanged) renderCurrent()

                newlyDelivered.forEach { entry ->
                    runCatching { SupabaseApi.markDelivered(token, entry.mediaRecipientId) }
                }
                if (newlyDelivered.isNotEmpty()) {
                    cacheStore.save(local.map { if (it in newlyDelivered) it.copy(delivered = true) else it })
                }
            } catch (e: Exception) {
                // Fallback poll; a failed refresh just tries again next cycle.
                // Whatever's already cached keeps displaying regardless.
                Log.w("MainActivityDebug", "sync failed", e)
            } finally {
                scheduleMediaSync()
            }
        }
    }

    // The single rendering entry point for both display modes. In slideshow
    // mode it schedules its own next tick (interval for photos, "on
    // completion" for videos); in manual mode it renders exactly once and
    // waits for a tap (setupTapNavigation) to call it again -- no timer ever
    // runs while displayMode == "manual".
    private fun renderCurrent() {
        handler.removeCallbacks(advanceRunnable)
        videoView.setOnCompletionListener(null)
        videoView.setOnErrorListener(null)

        if (cachedItems.isEmpty()) {
            if (displayMode != "manual") handler.postDelayed(advanceRunnable, slideshowIntervalMs)
            return
        }

        val size = cachedItems.size
        slideshowIndex = ((slideshowIndex % size) + size) % size
        val entry = cachedItems[slideshowIndex]
        val file = cacheStore.fileFor(entry)

        if (!file.exists()) {
            // Stale index entry (e.g. cleared cache mid-download); skip fast.
            slideshowIndex++
            if (displayMode == "manual") renderCurrent() else handler.postDelayed(advanceRunnable, 200)
            return
        }

        markViewedBestEffort(entry)

        if (entry.mediaType == "video") {
            imageView.visibility = View.GONE
            videoView.visibility = View.VISIBLE
            videoView.setVideoURI(Uri.fromFile(file))
            if (displayMode == "manual") {
                // Nothing to advance to automatically -- loop in place so the
                // screen keeps showing motion instead of going dark/frozen
                // while waiting for the next tap.
                videoView.setOnCompletionListener { it.start() }
            } else {
                videoView.setOnCompletionListener { slideshowIndex++; renderCurrent() }
                videoView.setOnErrorListener { _, _, _ -> slideshowIndex++; renderCurrent(); true }
            }
            videoView.start()
        } else {
            videoView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
                }
                if (bitmap != null) imageView.setImageBitmap(bitmap)
                if (displayMode != "manual") handler.postDelayed(advanceRunnable, slideshowIntervalMs)
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

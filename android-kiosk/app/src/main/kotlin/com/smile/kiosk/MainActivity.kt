package com.smile.kiosk

import android.app.admin.DevicePolicyManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

// Phase 3: pairing + slideshow + full lockdown. As soon as this app is
// Device Owner, applyLockdown() runs unconditionally on every start (even
// before pairing) -- there is deliberately no settings UI and no manual
// "activate lock" affordance, matching the plan's requirement that the
// device can't be fiddled with. The only way out of lock task is a future
// remote command (reset_to_policy), never something reachable on-device.
class MainActivity : ComponentActivity() {

    private lateinit var credentials: DeviceCredentialsStore
    private lateinit var dpm: DevicePolicyManager
    private lateinit var root: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var slideshowItems: List<MediaItem> = emptyList()
    private var slideshowIndex = 0
    private var slideshowIntervalMs = 8000L
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

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        setContentView(root)

        KioskLockdown.apply(this)
        scheduleComplianceWorker()

        if (credentials.isProvisioned()) {
            loadAndShowSlideshow()
        } else {
            showPairingScreen()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(advanceRunnable)
        handler.removeCallbacks(mediaSyncRunnable)
    }

    private fun scheduleComplianceWorker() {
        ComplianceWorker.schedule(applicationContext)
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
                        loadAndShowSlideshow()
                    } catch (e: Exception) {
                        errorText.text = "Fehler: ${e.message}"
                    }
                }
            }
        }
        root.addView(pairButton)
    }

    // ---- Slideshow ----

    private fun loadAndShowSlideshow() {
        root.removeAllViews()
        val loadingText = TextView(this).apply {
            text = "Lade Medien..."
            gravity = Gravity.CENTER
        }
        root.addView(loadingText)

        lifecycleScope.launch {
            try {
                val batch = fetchMediaBatch()
                slideshowItems = batch.items.filter { !it.displayUrl.isNullOrEmpty() }
                slideshowIntervalMs = batch.policy.slideshowIntervalSeconds * 1000L
                slideshowIndex = 0
                showSlideshowView()
                advanceSlideshow()
                scheduleMediaSync()
            } catch (e: Exception) {
                loadingText.text = "Fehler beim Laden: ${e.message}"
            }
        }
    }

    private suspend fun fetchMediaBatch(): MediaBatch {
        val token = credentials.deviceToken ?: throw IllegalStateException("Kein Device-Token")
        return SupabaseApi.getMediaBatch(token)
    }

    private fun scheduleMediaSync() {
        handler.removeCallbacks(mediaSyncRunnable)
        handler.postDelayed(mediaSyncRunnable, mediaSyncIntervalMs)
    }

    private fun syncMedia() {
        lifecycleScope.launch {
            try {
                val batch = fetchMediaBatch()
                slideshowItems = batch.items.filter { !it.displayUrl.isNullOrEmpty() }
                slideshowIntervalMs = batch.policy.slideshowIntervalSeconds * 1000L
                // Deliberately don't reset slideshowIndex or interrupt the
                // current image -- new items just join the rotation next
                // time advanceSlideshow() picks an index.
            } catch (_: Exception) {
                // Fallback poll; a failed refresh just tries again next cycle.
            } finally {
                scheduleMediaSync()
            }
        }
    }

    private lateinit var imageView: ImageView

    private fun showSlideshowView() {
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
        root.addView(imageView)
    }

    private fun advanceSlideshow() {
        handler.removeCallbacks(advanceRunnable)
        if (slideshowItems.isEmpty()) {
            handler.postDelayed(advanceRunnable, slideshowIntervalMs)
            return
        }
        val item = slideshowItems[slideshowIndex % slideshowItems.size]
        slideshowIndex++

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    URL(item.displayUrl).openStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            }
            handler.postDelayed(advanceRunnable, slideshowIntervalMs)
        }
    }
}

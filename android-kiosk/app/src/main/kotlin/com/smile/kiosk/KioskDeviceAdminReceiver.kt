package com.smile.kiosk

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

// Device Owner setup can happen two ways: `adb shell dpm set-device-owner`
// (what every hardware test so far has used) or real QR provisioning, which
// this receiver's onProfileProvisioningComplete is the entry point for.
// Either way the app ends up Device Owner identically -- KioskLockdown
// doesn't care which path got it there.
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        // Key inside the QR payload's PROVISIONING_ADMIN_EXTRAS_BUNDLE that
        // carries the pairing code, so a person never has to type it in --
        // see the QR-generator script for the exact JSON this pairs with.
        const val EXTRA_PAIRING_CODE = "com.smile.kiosk.PAIRING_CODE"

        // Shared with PolicyComplianceActivity -- on Android 12+ that
        // activity (ACTION_ADMIN_POLICY_COMPLIANCE) is the real end-of-flow
        // hook, but the extras carrying our pairing code arrive the same way
        // either place, so both read it identically.
        fun extractPairingCode(intent: Intent): String? {
            val adminExtras: Bundle? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, Bundle::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
            }
            // Falls back to a top-level intent extra of the same key -- the
            // real QR/zero-touch flow always nests it inside the admin
            // extras bundle, but a plain `am start` (used for adb-driven
            // testing without a physical QR scan) can only set top-level
            // string extras.
            return adminExtras?.getString(EXTRA_PAIRING_CODE) ?: intent.getStringExtra(EXTRA_PAIRING_CODE)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("KioskDeviceAdmin", "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("KioskDeviceAdmin", "Device admin disabled")
    }

    // Fires once, right after Android finishes silently installing this app
    // and setting it as Device Owner from a QR scan. Device-owner
    // provisioning does NOT auto-launch anything afterward (unlike the
    // profile-owner/work-profile flow) -- we have to start the activity
    // ourselves, which doubles as the moment to hand off the pairing code.
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i("KioskDeviceAdmin", "QR provisioning complete")

        // On Android 12+, PolicyComplianceActivity (ACTION_ADMIN_POLICY_COMPLIANCE)
        // is the real, *required* end-of-flow hook and normally handles this
        // already -- this stays as a fallback for older OS versions / OEM
        // variants that skip straight to this classic callback instead.
        val pairingCode = extractPairingCode(intent)
        if (!pairingCode.isNullOrBlank()) {
            DeviceCredentialsStore(context).pendingPairingCode = pairingCode
        } else {
            Log.w("KioskDeviceAdmin", "QR provisioning completed without a pairing code in admin extras")
        }

        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

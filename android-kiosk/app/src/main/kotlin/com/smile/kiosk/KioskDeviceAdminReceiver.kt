package com.smile.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// Minimal for now -- Phase 3 thin slice just needs this to exist so the app
// can become Device Owner at all. onProfileProvisioningComplete (the real
// QR-provisioning entry point) is wired up once the provisioning flow itself
// is built; for now we test Device Owner status via `adb shell dpm
// set-device-owner`, which doesn't go through this callback.
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("KioskDeviceAdmin", "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("KioskDeviceAdmin", "Device admin disabled")
    }
}

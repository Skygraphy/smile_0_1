package com.smile.kiosk

import android.content.Context

// TODO(Phase 3 hardening): switch to EncryptedSharedPreferences (Android
// Keystore-backed) before this ever ships on a real deployed tablet -- the
// plan calls for the device JWT to survive a stolen/rooted device without
// being trivially readable. Plain SharedPreferences is fine for this dev
// slice on a controlled test tablet.
class DeviceCredentialsStore(context: Context) {
    private val prefs = context.getSharedPreferences("device_credentials", Context.MODE_PRIVATE)

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(value) = prefs.edit().putString("device_id", value).apply()

    var tenantId: String?
        get() = prefs.getString("tenant_id", null)
        set(value) = prefs.edit().putString("tenant_id", value).apply()

    var deviceToken: String?
        get() = prefs.getString("device_token", null)
        set(value) = prefs.edit().putString("device_token", value).apply()

    fun isProvisioned(): Boolean = deviceToken != null

    fun clear() = prefs.edit().clear().apply()
}

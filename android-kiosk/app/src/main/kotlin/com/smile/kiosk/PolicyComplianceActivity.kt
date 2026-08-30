package com.smile.kiosk

import android.app.Activity
import android.content.Intent
import android.os.Bundle

// The other Android-12+-required handler alongside
// GetProvisioningModeActivity (DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE)
// -- this is the real end-of-provisioning hook on modern Android, not
// KioskDeviceAdminReceiver.onProfileProvisioningComplete (which is kept as a
// fallback for older/OEM variants that still use the classic callback
// instead). Same pairing-code extras arrive here, so it does the same
// hand-off.
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pairingCode = KioskDeviceAdminReceiver.extractPairingCode(intent)
        if (!pairingCode.isNullOrBlank()) {
            DeviceCredentialsStore(this).pendingPairingCode = pairingCode
        }

        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}

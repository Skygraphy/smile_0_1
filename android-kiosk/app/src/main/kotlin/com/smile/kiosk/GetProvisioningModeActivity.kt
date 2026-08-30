package com.smile.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

// Required since Android 12: Google's own docs are explicit that
// provisioning FAILS outright ("Gerät kann nicht eingerichtet werden", no
// further detail shown) if a DPC doesn't handle
// DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -- confirmed the hard way
// across several failed hardware attempts before finding this. Without it,
// Android has nothing to ask this activity, shows an ambiguous "does this
// device belong to your organization?" screen to the person instead, and
// then fails anyway since the *other* required handler
// (PolicyComplianceActivity) is also missing.
//
// A kiosk is always a fully organization-managed device, never a personal
// device with a work profile bolted on, so this always answers the same way
// without ever asking.
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().apply {
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
            )
        }
        setResult(RESULT_OK, result)
        finish()
    }
}

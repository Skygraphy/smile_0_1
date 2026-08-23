package com.smile.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager

data class LockdownStatus(val isDeviceOwner: Boolean, val isLockTaskActive: Boolean, val driftDetails: List<String>)

// Shared between MainActivity (applies lockdown on every start) and
// ComplianceWorker (re-applies it if the periodic check finds drift).
// startLockTask() specifically requires an Activity (it's an Activity API,
// not available from a bare Context), so it's only actually invoked when
// called from MainActivity; the Worker can fix everything else remotely.
object KioskLockdown {

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, KioskDeviceAdminReceiver::class.java)

    fun apply(activity: Activity) {
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(activity.packageName)) return
        val admin = adminComponent(activity)

        applyPolicies(activity, dpm, admin)

        val activityManager = activity.getSystemService(ActivityManager::class.java)
        if (activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            activity.startLockTask()
        }
    }

    // The subset of lockdown that doesn't require an Activity -- usable from
    // the background compliance worker to repair everything except actually
    // (re-)entering lock task, which needs the activity to be in the foreground.
    fun applyPolicies(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)

        listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_ADD_USER,
        ).forEach { restriction -> dpm.addUserRestriction(admin, restriction) }

        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, homeFilter, ComponentName(context, MainActivity::class.java))
    }

    // Read-only compliance check -- never mutates anything, just reports.
    fun checkStatus(context: Context): LockdownStatus {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val isLockTaskActive = activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

        val drift = mutableListOf<String>()
        if (!isDeviceOwner) {
            drift += "not_device_owner"
        } else {
            if (!isLockTaskActive) drift += "lock_task_not_active"
            val um = context.getSystemService(UserManager::class.java)
            listOf(
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_ADD_USER,
            ).forEach { restriction ->
                if (!um.hasUserRestriction(restriction)) drift += "missing_restriction:$restriction"
            }
        }
        return LockdownStatus(isDeviceOwner, isLockTaskActive, drift)
    }
}

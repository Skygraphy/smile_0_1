package com.smile.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.provider.Settings
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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
        hideSystemBars(activity)

        val activityManager = activity.getSystemService(ActivityManager::class.java)
        if (activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            activity.startLockTask()
        }
    }

    // LOCK_TASK_FEATURE_NONE only disables *interacting* with the status/nav
    // bars (pull-down, back gesture) -- it doesn't hide them. Without this,
    // the status bar and a gesture-nav hint stay visibly drawn the whole
    // time, which reads as "not actually locked down" even though it is.
    // Transient system UI (a permission dialog, a Toast) can pop the bars
    // back into view, so callers should re-invoke this on window focus
    // regained, not just once at startup.
    fun hideSystemBars(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // Counterpart to hideSystemBars(), used only for the temporary
    // maintenance-unlock window below.
    private fun showSystemBars(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    // Server-triggered only (see the "unlock_maintenance" remote command) --
    // there is deliberately no on-device way to reach this. Exits Lock Task
    // and restores normal system UI so Settings/Developer Options/USB
    // debugging authorization become reachable for real maintenance, without
    // ever needing a factory reset just to fix a bug. The caller
    // (MainActivity) is responsible for scheduling the automatic re-lock --
    // this never stays open indefinitely by itself.
    fun exitForMaintenance(activity: Activity) {
        activity.stopLockTask()
        showSystemBars(activity)
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

        // Keeps USB debugging reachable for support/maintenance without
        // anyone needing physical access to Settings -> Developer Options
        // (which lock task blocks from ever being reached anyway). Doesn't
        // weaken the lockdown: DISALLOW_INSTALL_UNKNOWN_SOURCES etc. above
        // are untouched, and adb alone can't get past Lock Task without this
        // same device-owner app cooperating.
        runCatching { dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1") }
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

package com.smile.kiosk

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

// Phase 4 self-check/self-heal loop. Runs on a fixed interval (default 15
// min, matching device_policies.compliance_check_interval_minutes' default)
// regardless of app foreground state -- this is the piece that survives the
// app being killed/the device rebooting, unlike the in-Activity media-sync
// loop. Only repairs the narrowest thing that's actually wrong; a fully
// compliant device just gets a heartbeat row, no other action.
class ComplianceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val credentials = DeviceCredentialsStore(applicationContext)
        val deviceId = credentials.deviceId
        val tenantId = credentials.tenantId
        val deviceToken = credentials.deviceToken
        if (deviceId == null || tenantId == null || deviceToken == null) {
            return Result.success() // Not provisioned yet -- nothing to check.
        }

        val status = KioskLockdown.checkStatus(applicationContext)
        var complianceState = if (status.driftDetails.isEmpty()) "compliant" else "drift_detected"

        if (status.driftDetails.isNotEmpty() && status.isDeviceOwner) {
            // Repair everything reachable without an Activity (restrictions,
            // lock task package/features, default launcher). Re-entering lock
            // task itself needs the Activity in the foreground -- MainActivity
            // does that itself on its next start.
            runCatching {
                val dpm = applicationContext.getSystemService(DevicePolicyManager::class.java)
                val admin = KioskLockdown.adminComponent(applicationContext)
                KioskLockdown.applyPolicies(applicationContext, dpm, admin)
            }
            complianceState = "repaired"
        }

        runCatching {
            SupabaseApi.submitHeartbeat(deviceToken, deviceId, tenantId, complianceState, status.driftDetails)
        }

        runCatching {
            SupabaseApi.getPendingCommands(deviceToken, deviceId).forEach { command ->
                handleCommand(command, deviceToken, deviceId)
            }
        }

        return Result.success()
    }

    private suspend fun handleCommand(command: RemoteCommand, deviceToken: String, deviceId: String) {
        try {
            when (command.commandType) {
                "refresh_policy" -> {
                    SupabaseApi.getMediaBatch(deviceToken)
                    SupabaseApi.updateCommandStatus(deviceToken, command.id, "completed")
                }
                "reset_to_policy" -> {
                    val dpm = applicationContext.getSystemService(DevicePolicyManager::class.java)
                    val admin = KioskLockdown.adminComponent(applicationContext)
                    KioskLockdown.applyPolicies(applicationContext, dpm, admin)
                    SupabaseApi.updateCommandStatus(deviceToken, command.id, "completed")
                }
                "reboot" -> {
                    val dpm = applicationContext.getSystemService(DevicePolicyManager::class.java)
                    val admin = KioskLockdown.adminComponent(applicationContext)
                    if (dpm.isDeviceOwnerApp(applicationContext.packageName)) {
                        // Mark completed first -- the device won't be able to
                        // report back after reboot() actually fires.
                        SupabaseApi.updateCommandStatus(deviceToken, command.id, "completed")
                        dpm.reboot(admin)
                    } else {
                        SupabaseApi.updateCommandStatus(deviceToken, command.id, "failed", "device_owner_required")
                    }
                }
                "clear_media_cache" -> {
                    MediaCacheStore(applicationContext).clearAll()
                    // Reset the "has this device actually got it on disk"
                    // signal too, so the next sync cycle re-downloads
                    // everything and delivered_at stays truthful.
                    SupabaseApi.clearDeliveredFlags(deviceToken, deviceId)
                    SupabaseApi.updateCommandStatus(deviceToken, command.id, "completed")
                }
                "unlock_maintenance" -> {
                    // stopLockTask() is an Activity-only API -- this
                    // background worker can't do it. Left pending
                    // (deliberately not marked failed) for MainActivity's
                    // own short-interval poll to pick up and complete.
                }
                else -> {
                    SupabaseApi.updateCommandStatus(deviceToken, command.id, "failed", "unknown_command_type")
                }
            }
        } catch (e: Exception) {
            runCatching { SupabaseApi.updateCommandStatus(deviceToken, command.id, "failed", e.message) }
        }
    }

    companion object {
        private const val WORK_NAME = "kiosk_compliance_check"

        fun schedule(context: Context, intervalMinutes: Long = 15) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<ComplianceWorker>(
                intervalMinutes.coerceAtLeast(15),
                TimeUnit.MINUTES,
            ).setConstraints(constraints).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

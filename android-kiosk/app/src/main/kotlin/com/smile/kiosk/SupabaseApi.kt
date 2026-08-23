package com.smile.kiosk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

data class DevicePolicy(
    val displayMode: String,
    val slideshowIntervalSeconds: Int,
    val complianceCheckIntervalMinutes: Int,
)

data class MediaItem(
    val mediaRecipientId: String,
    val mediaItemId: String,
    val mediaType: String,
    val displayUrl: String?,
)

data class MediaBatch(val policy: DevicePolicy, val items: List<MediaItem>)

data class RemoteCommand(val id: String, val commandType: String)

class SupabaseApiException(message: String) : IOException(message)

// Thin wrapper around the three Edge Functions the kiosk needs. Mirrors the
// same endpoints the sender app / our manual test calls already validated.
object SupabaseApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    suspend fun claimDeviceProvisioning(code: String): Triple<String, String, String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("code", code)
                put("device_name", "Kiosk Test-Tablet")
                put("android_id", android.os.Build.MODEL)
                put("app_version", "0.1.0-dev")
            }
            val json = postJson("claim-device-provisioning", body, authToken = null)
            val deviceId = json.getString("device_id")
            val tenantId = json.getString("tenant_id")
            val deviceToken = json.getString("device_token")
            Triple(deviceId, tenantId, deviceToken)
        }

    suspend fun getMediaBatch(deviceToken: String): MediaBatch = withContext(Dispatchers.IO) {
        val json = postJson("get-media-batch", JSONObject(), authToken = deviceToken)

        val policyJson = json.getJSONObject("policy")
        val policy = DevicePolicy(
            displayMode = policyJson.getString("display_mode"),
            slideshowIntervalSeconds = policyJson.getInt("slideshow_interval_seconds"),
            complianceCheckIntervalMinutes = policyJson.getInt("compliance_check_interval_minutes"),
        )

        val itemsJson = json.getJSONArray("items")
        val items = (0 until itemsJson.length()).map { i ->
            val item = itemsJson.getJSONObject(i)
            MediaItem(
                mediaRecipientId = item.getString("media_recipient_id"),
                mediaItemId = item.getString("media_item_id"),
                mediaType = item.getString("media_type"),
                displayUrl = if (item.isNull("display_url")) null else item.getString("display_url"),
            )
        }
        MediaBatch(policy, items)
    }

    // ---- Compliance / remote commands (direct PostgREST, no Edge Function
    // needed -- device_heartbeats/remote_commands RLS already allows a
    // device's own JWT to insert/select/update its own rows).

    suspend fun submitHeartbeat(
        deviceToken: String,
        deviceId: String,
        tenantId: String,
        complianceState: String,
        driftDetails: List<String>,
    ) = withContext(Dispatchers.IO) {
        val nowIso = Instant.now().toString()

        val heartbeatBody = JSONObject().apply {
            put("device_id", deviceId)
            put("tenant_id", tenantId)
            put("compliance_state", complianceState)
            put("drift_details", JSONObject().put("issues", JSONArray(driftDetails)))
            put("app_version", "0.1.0-dev")
        }
        postRest("device_heartbeats", heartbeatBody, deviceToken)

        val deviceUpdateBody = JSONObject().apply {
            put("last_compliance_state", complianceState)
            put("last_compliance_check_at", nowIso)
            put("last_seen_at", nowIso)
        }
        patchRest("devices?id=eq.$deviceId", deviceUpdateBody, deviceToken)
    }

    suspend fun getPendingCommands(deviceToken: String, deviceId: String): List<RemoteCommand> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${SupabaseConfig.URL}/rest/v1/remote_commands?device_id=eq.$deviceId&status=eq.pending&select=id,command_type")
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer $deviceToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: "[]"
                if (!response.isSuccessful) {
                    throw SupabaseApiException("get remote_commands failed (${response.code}): $responseBody")
                }
                val array = JSONArray(responseBody)
                (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    RemoteCommand(id = item.getString("id"), commandType = item.getString("command_type"))
                }
            }
        }

    suspend fun updateCommandStatus(
        deviceToken: String,
        commandId: String,
        status: String,
        resultDetail: String? = null,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("status", status)
            put("completed_at", Instant.now().toString())
            if (resultDetail != null) put("result_detail", JSONObject().put("message", resultDetail))
        }
        patchRest("remote_commands?id=eq.$commandId", body, deviceToken)
    }

    private fun postRest(table: String, body: JSONObject, authToken: String) {
        val request = Request.Builder()
            .url("${SupabaseConfig.URL}/rest/v1/$table")
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("Prefer", "return=minimal")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SupabaseApiException("POST $table failed (${response.code}): ${response.body?.string()}")
            }
        }
    }

    private fun patchRest(pathWithQuery: String, body: JSONObject, authToken: String) {
        val request = Request.Builder()
            .url("${SupabaseConfig.URL}/rest/v1/$pathWithQuery")
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("Prefer", "return=minimal")
            .patch(body.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SupabaseApiException("PATCH $pathWithQuery failed (${response.code}): ${response.body?.string()}")
            }
        }
    }

    private fun postJson(functionName: String, body: JSONObject, authToken: String?): JSONObject {
        val request = Request.Builder()
            .url("${SupabaseConfig.URL}/functions/v1/$functionName")
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${authToken ?: SupabaseConfig.ANON_KEY}")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw SupabaseApiException("$functionName failed (${response.code}): $responseBody")
            }
            return JSONObject(responseBody)
        }
    }
}

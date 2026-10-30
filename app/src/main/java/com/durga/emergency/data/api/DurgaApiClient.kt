package com.durga.emergency.data.api

import android.content.Context
import android.os.Build
import android.util.Log
import com.durga.emergency.data.PreferencesManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class AuthResponse(
    val token: String,
    val userId: String,
    val phone: String,
    val name: String,
    val role: String,
    val hasPin: Boolean
)

data class IncidentCreateResponse(
    val incidentId: String,
    val status: String,
    val nearestPoliceStation: String,
    val nearestDistanceKm: Double?,
    val recipientsWithin1Km: Int,
    val pushDispatchStatus: String
)

data class IncidentStatusResponse(
    val incidentId: String,
    val status: String,
    val message: String
)

data class TestNotificationResponse(
    val success: Boolean,
    val status: String,
    val messageId: String?,
    val recipientUserId: String?,
    val note: String?
)

/**
 * Production-minded REST API Client connecting native Android app
 * to the central DURGA emergency response backend.
 */
class DurgaApiClient(private val context: Context) {

    companion object {
        private const val TAG = "DURGA_API_CLIENT"
        private val DEFAULT_BASE_URL = PreferencesManager.DEFAULT_SERVER_URL
        private const val EMULATOR_BASE_URL = "http://10.0.2.2:3000/api/v1"

        @Volatile
        private var instance: DurgaApiClient? = null

        fun getInstance(context: Context): DurgaApiClient {
            return instance ?: synchronized(this) {
                instance ?: DurgaApiClient(context.applicationContext).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val prefs = PreferencesManager(context)

    var baseUrl: String
        get() = prefs.serverBaseUrl
        set(value) {
            prefs.serverBaseUrl = value
        }

    private var authToken: String?
        get() = prefs.authToken
        set(value) {
            prefs.authToken = value
        }

    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = executeGet("$baseUrl/ping", null)
            val json = gson.fromJson(response, JsonObject::class.java)
            val success = json.get("success")?.asBoolean ?: false
            Result.success(success)
        } catch (e: Exception) {
            Log.e(TAG, "testConnection error", e)
            Result.failure(e)
        }
    }

    suspend fun getNearbyIncidents(
        latitude: Double?,
        longitude: Double?,
        radiusKm: Double = 1.0,
        excludeUserId: String? = null,
        excludeDeviceId: String? = null
    ): Result<List<com.durga.emergency.data.model.EmergencyEvent>> = withContext(Dispatchers.IO) {
        try {
            val queryParams = mutableListOf<String>()
            if (latitude != null) queryParams.add("latitude=$latitude")
            if (longitude != null) queryParams.add("longitude=$longitude")
            queryParams.add("radius=$radiusKm")
            if (!excludeUserId.isNullOrEmpty()) queryParams.add("excludeUserId=$excludeUserId")
            val effectiveDeviceId = excludeDeviceId ?: prefs.deviceId
            if (effectiveDeviceId.isNotEmpty()) queryParams.add("excludeDeviceId=$effectiveDeviceId")

            val queryString = queryParams.joinToString("&")
            val url = "$baseUrl/incidents/proximity?$queryString"
            val response = executeGet(url, authToken)

            val listType = object : com.google.gson.reflect.TypeToken<List<JsonObject>>() {}.type
            val jsonList: List<JsonObject> = gson.fromJson(response, listType) ?: emptyList()

            val events = jsonList.map { item ->
                com.durga.emergency.data.model.EmergencyEvent(
                    eventId = item.get("id")?.asString ?: "",
                    userId = item.get("user_id")?.asString ?: "",
                    userName = item.get("user_name")?.asString ?: "Citizen",
                    timestamp = item.get("created_at")?.asLong ?: System.currentTimeMillis(),
                    latitude = if (item.has("latitude") && !item.get("latitude").isJsonNull) item.get("latitude").asDouble else null,
                    longitude = if (item.has("longitude") && !item.get("longitude").isJsonNull) item.get("longitude").asDouble else null,
                    locationAccuracy = if (item.has("location_accuracy") && !item.get("location_accuracy").isJsonNull) item.get("location_accuracy").asFloat else 10f,
                    distressScore = if (item.has("distress_score") && !item.get("distress_score").isJsonNull) item.get("distress_score").asFloat else 0f,
                    distressClassification = item.get("distress_classification")?.asString ?: "Pending",
                    emergencyLevel = "HIGH",
                    audioUrl = item.get("audio_file_path")?.asString ?: "",
                    emergencyContactPhone = item.get("user_phone")?.asString ?: "",
                    nearestPoliceStationName = item.get("nearest_police_station")?.asString ?: "Central Station",
                    nearestPoliceStationDistanceKm = if (item.has("distanceKm") && !item.get("distanceKm").isJsonNull) item.get("distanceKm").asDouble else null,
                    status = item.get("status")?.asString ?: "ACTIVE"
                )
            }
            Result.success(events)
        } catch (e: Exception) {
            Log.e(TAG, "getNearbyIncidents error", e)
            Result.failure(e)
        }
    }

    suspend fun requestOtp(phone: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply { addProperty("phone", phone) }
            val response = executePost("$baseUrl/auth/request-otp", payload.toString(), null)
            val json = gson.fromJson(response, JsonObject::class.java)
            val testOtp = json.get("testOtp")?.asString ?: ""
            Result.success(testOtp)
        } catch (e: Exception) {
            Log.e(TAG, "requestOtp error", e)
            Result.failure(e)
        }
    }

    suspend fun verifyOtp(phone: String, otp: String, name: String, role: String): Result<AuthResponse> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                addProperty("phone", phone)
                addProperty("otp", otp)
                addProperty("name", name)
                addProperty("role", role)
            }
            val response = executePost("$baseUrl/auth/verify-otp", payload.toString(), null)
            val json = gson.fromJson(response, JsonObject::class.java)
            val token = json.get("token").asString
            val userObj = json.getAsJsonObject("user")

            authToken = token

            val auth = AuthResponse(
                token = token,
                userId = userObj.get("id").asString,
                phone = userObj.get("phone").asString,
                name = userObj.get("name").asString,
                role = userObj.get("role").asString,
                hasPin = userObj.get("hasPin")?.asBoolean ?: false
            )
            Result.success(auth)
        } catch (e: Exception) {
            Log.e(TAG, "verifyOtp error", e)
            Result.failure(e)
        }
    }

    suspend fun setUserPin(pin: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply { addProperty("pin", pin) }
            executePost("$baseUrl/auth/set-pin", payload.toString(), authToken)
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "setUserPin error", e)
            Result.failure(e)
        }
    }

    suspend fun registerDevice(
        fcmToken: String?,
        deviceId: String = prefs.deviceId,
        deviceModel: String = Build.MODEL,
        latitude: Double?,
        longitude: Double?,
        accuracy: Float?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                addProperty("deviceId", deviceId)
                addProperty("fcmToken", fcmToken)
                addProperty("deviceModel", deviceModel)
                if (latitude != null) addProperty("latitude", latitude)
                if (longitude != null) addProperty("longitude", longitude)
                if (accuracy != null) addProperty("accuracy", accuracy)
            }
            executePost("$baseUrl/devices/register", payload.toString(), authToken)
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "registerDevice error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun revokeDeviceToken(
        deviceId: String? = null,
        fcmToken: String? = null,
        authTokenOverride: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                if (!deviceId.isNullOrEmpty()) addProperty("deviceId", deviceId)
                if (!fcmToken.isNullOrEmpty()) addProperty("fcmToken", fcmToken)
            }
            val tokenToUse = authTokenOverride ?: authToken
            executePost("$baseUrl/devices/revoke-token", payload.toString(), tokenToUse)
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "revokeDeviceToken error", e)
            Result.failure(e)
        }
    }

    suspend fun updateProfile(
        name: String? = null,
        emergencyContactName: String? = null,
        emergencyContactPhone: String? = null,
        bloodGroup: String? = null,
        homeAddress: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                if (!name.isNullOrEmpty()) addProperty("name", name)
                if (!emergencyContactName.isNullOrEmpty()) addProperty("emergencyContactName", emergencyContactName)
                if (!emergencyContactPhone.isNullOrEmpty()) addProperty("emergencyContactPhone", emergencyContactPhone)
                if (!bloodGroup.isNullOrEmpty()) addProperty("bloodGroup", bloodGroup)
                if (!homeAddress.isNullOrEmpty()) addProperty("homeAddress", homeAddress)
            }
            executePost("$baseUrl/users/profile", payload.toString(), authToken)
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "updateProfile error", e)
            Result.failure(e)
        }
    }

    suspend fun sendTestNotification(
        targetUserId: String? = null,
        targetPhone: String? = null
    ): Result<TestNotificationResponse> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                if (!targetUserId.isNullOrEmpty()) addProperty("targetUserId", targetUserId)
                if (!targetPhone.isNullOrEmpty()) addProperty("targetPhone", targetPhone)
            }
            val response = executePost("$baseUrl/notifications/test", payload.toString(), authToken)
            val json = gson.fromJson(response, JsonObject::class.java)
            val res = TestNotificationResponse(
                success = json.get("success")?.asBoolean ?: false,
                status = json.get("status")?.asString ?: "UNKNOWN",
                messageId = if (json.has("messageId") && !json.get("messageId").isJsonNull) json.get("messageId").asString else null,
                recipientUserId = if (json.has("recipientUserId") && !json.get("recipientUserId").isJsonNull) json.get("recipientUserId").asString else null,
                note = if (json.has("note") && !json.get("note").isJsonNull) json.get("note").asString else null
            )
            Result.success(res)
        } catch (e: Exception) {
            Log.e(TAG, "sendTestNotification error", e)
            Result.failure(e)
        }
    }

    suspend fun createIncident(
        latitude: Double?,
        longitude: Double?,
        accuracy: Float?,
        triggerSource: String,
        distressScore: Float,
        distressClass: String
    ): Result<IncidentCreateResponse> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                if (latitude != null) addProperty("latitude", latitude)
                if (longitude != null) addProperty("longitude", longitude)
                if (accuracy != null) addProperty("locationAccuracy", accuracy)
                addProperty("triggerSource", triggerSource)
                addProperty("distressScore", distressScore)
                addProperty("distressClassification", distressClass)
                addProperty("deviceId", prefs.deviceId)
            }
            val response = executePost("$baseUrl/incidents", payload.toString(), authToken)
            val json = gson.fromJson(response, JsonObject::class.java)

            val createResp = IncidentCreateResponse(
                incidentId = json.get("incidentId").asString,
                status = json.get("status").asString,
                nearestPoliceStation = json.get("nearestPoliceStation")?.asString ?: "Central Station",
                nearestDistanceKm = if (json.has("nearestDistanceKm") && !json.get("nearestDistanceKm").isJsonNull) json.get("nearestDistanceKm").asDouble else null,
                recipientsWithin1Km = json.get("recipientsWithin1Km")?.asInt ?: 0,
                pushDispatchStatus = json.get("pushDispatchStatus")?.asString ?: "UNKNOWN"
            )
            Result.success(createResp)
        } catch (e: Exception) {
            Log.e(TAG, "createIncident error", e)
            Result.failure(e)
        }
    }

    suspend fun repeatSos(incidentId: String): Result<IncidentStatusResponse> = withContext(Dispatchers.IO) {
        try {
            val response = executePost("$baseUrl/incidents/$incidentId/repeat-sos", "{}", authToken)
            val json = gson.fromJson(response, JsonObject::class.java)
            val resp = IncidentStatusResponse(
                incidentId = json.get("incidentId").asString,
                status = json.get("status").asString,
                message = json.get("message")?.asString ?: ""
            )
            Result.success(resp)
        } catch (e: Exception) {
            Log.e(TAG, "repeatSos error", e)
            Result.failure(e)
        }
    }

    suspend fun cancelIncident(incidentId: String, pin: String): Result<IncidentStatusResponse> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply { addProperty("pin", pin) }
            val response = executePost("$baseUrl/incidents/$incidentId/cancel", payload.toString(), authToken)
            val json = gson.fromJson(response, JsonObject::class.java)
            val resp = IncidentStatusResponse(
                incidentId = json.get("incidentId").asString,
                status = json.get("status").asString,
                message = json.get("message")?.asString ?: ""
            )
            Result.success(resp)
        } catch (e: Exception) {
            Log.e(TAG, "cancelIncident error", e)
            Result.failure(e)
        }
    }

    suspend fun updateLocation(incidentId: String, latitude: Double, longitude: Double, accuracy: Float): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                addProperty("latitude", latitude)
                addProperty("longitude", longitude)
                addProperty("accuracy", accuracy)
            }
            executePatch("$baseUrl/incidents/$incidentId/location", payload.toString(), authToken)
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "updateLocation error", e)
            Result.failure(e)
        }
    }

    suspend fun uploadAudio(incidentId: String, audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val boundary = "==DURGA_AUDIO_BOUNDARY=="
            val url = URL("$baseUrl/incidents/$incidentId/audio")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                setRequestProperty("Connection", "Keep-Alive")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                if (authToken != null) {
                    setRequestProperty("Authorization", "Bearer $authToken")
                }
            }

            DataOutputStream(conn.outputStream).use { output ->
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"${audioFile.name}\"\r\n")
                output.writeBytes("Content-Type: audio/wav\r\n\r\n")

                FileInputStream(audioFile).use { input ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
                output.writeBytes("\r\n--$boundary--\r\n")
                output.flush()
            }

            val statusCode = conn.responseCode
            if (statusCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()
                val json = gson.fromJson(response, JsonObject::class.java)
                Result.success(json.get("audioUrl")?.asString ?: "")
            } else {
                val errReader = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream))
                val errText = errReader.readText()
                errReader.close()
                Result.failure(Exception("Upload failed HTTP $statusCode: $errText"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadAudio error", e)
            Result.failure(e)
        }
    }

    private fun executePost(urlString: String, jsonBody: String, token: String?): String {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DURGA-Emergency-Android/1.0")
            setRequestProperty("Bypass-Tunnel-Reminder", "true")
            setRequestProperty("X-Device-Id", prefs.deviceId)
            connectTimeout = 12000
            readTimeout = 12000
            if (token != null) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            doOutput = true
        }

        conn.outputStream.use { os ->
            val input = jsonBody.toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
        val response = stream.bufferedReader().use { it.readText() }

        if (code !in 200..299) {
            throw Exception("HTTP $code: $response")
        }
        return response
    }

    private fun executePatch(urlString: String, jsonBody: String, token: String?): String {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            try {
                requestMethod = "PATCH"
            } catch (e: Exception) {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DURGA-Emergency-Android/1.0")
            setRequestProperty("Bypass-Tunnel-Reminder", "true")
            setRequestProperty("X-Device-Id", prefs.deviceId)
            connectTimeout = 12000
            readTimeout = 12000
            if (token != null) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            doOutput = true
        }

        conn.outputStream.use { os ->
            val input = jsonBody.toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
        val response = stream.bufferedReader().use { it.readText() }

        if (code !in 200..299) {
            throw Exception("HTTP $code: $response")
        }
        return response
    }

    private fun executeGet(urlString: String, token: String?): String {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DURGA-Emergency-Android/1.0")
            setRequestProperty("Bypass-Tunnel-Reminder", "true")
            setRequestProperty("X-Device-Id", prefs.deviceId)
            connectTimeout = 12000
            readTimeout = 12000
            if (token != null) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
        val response = stream.bufferedReader().use { it.readText() }

        if (code !in 200..299) {
            throw Exception("HTTP $code: $response")
        }
        return response
    }

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86"))
    }
}

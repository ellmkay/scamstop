package com.scamkill.app.network

import com.scamkill.app.data.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ScamKillApi(private val prefs: Preferences) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl(): String = prefs.backendUrl.trimEnd('/')

    suspend fun checkSms(
        from: String,
        body: String,
        media: List<Map<String, String>> = emptyList(),
    ): SmsCheckResponse = withContext(Dispatchers.IO) {
        val mediaItems = media.map { MediaItem(data = it["data"] ?: "", contentType = it["contentType"] ?: "image/jpeg") }
        val payload = json.encodeToString(SmsCheckRequest.serializer(), SmsCheckRequest(from, body, mediaItems))
        val request = Request.Builder()
            .url("${baseUrl()}/api/sms-check")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: "{}"

        if (!response.isSuccessful) {
            return@withContext SmsCheckResponse(
                score = 0,
                verdict = "ERROR",
                reason = "Server returned ${response.code}",
            )
        }

        json.decodeFromString(SmsCheckResponse.serializer(), responseBody)
    }

    suspend fun healthCheck(): HealthResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}/health")
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            json.decodeFromString(HealthResponse.serializer(), body)
        } catch (e: Exception) {
            HealthResponse(ok = false)
        }
    }
}

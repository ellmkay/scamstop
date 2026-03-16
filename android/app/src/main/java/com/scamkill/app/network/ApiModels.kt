package com.scamkill.app.network

import kotlinx.serialization.Serializable

@Serializable
data class MediaItem(
    val data: String,
    val contentType: String,
)

@Serializable
data class SmsCheckRequest(
    val from: String,
    val body: String,
    val media: List<MediaItem> = emptyList(),
)

@Serializable
data class SmsCheckResponse(
    val score: Int = 0,
    val verdict: String = "UNKNOWN",
    val reason: String = "",
    val keywords: List<String> = emptyList(),
)

@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val activeCalls: Int = 0,
    val dashboardClients: Int = 0,
)

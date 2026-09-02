package com.example.webhoster.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class RelayResponse(
    val deviceId: String,
    val secret: String,
    val recoveryCode: String? = null,
    val url: String,
    val websocketUrl: String,
    val hostname: String? = null
)

@Serializable
data class TunnelFrame(
    val type: String,               // "request", "chunk", "end", "window", "cancel", "head", "error"
    val requestId: String,
    val method: String? = null,
    val path: String? = null,
    val headers: Map<String, String>? = null,
    val statusCode: Int? = null,
    val data: String? = null,       // Base64 chunk (if not binary)
    val body: String? = null,       // Inline request body
    val isBase64Encoded: Boolean? = null,
    val hasBody: Boolean? = null,
    val window: Int? = null,        // Initial grant
    val credit: Int? = null,        // Window top-up
    val message: String? = null     // For error frames
)

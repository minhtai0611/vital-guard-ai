package com.vitalguard.ai

import kotlinx.serialization.Serializable

@Serializable
data class TriggerFeatures(
    val perclos: Float,
    val eyeOpenProbability: Float,
    val headEulerAngleX: Float
)

@Serializable
data class TriggerPayload(
    val timestampMs: Long,
    val source: String,
    val score: Float,
    val confidence: Float,
    val state: String,
    val features: TriggerFeatures,
    val reason: String,
    val correlationId: String
) {
    companion object {
        const val STATE_NORMAL = "NORMAL"
        const val STATE_WARNING = "WARNING"
        const val STATE_CRITICAL = "CRITICAL"
        const val STATE_UNKNOWN = "UNKNOWN"
    }
}

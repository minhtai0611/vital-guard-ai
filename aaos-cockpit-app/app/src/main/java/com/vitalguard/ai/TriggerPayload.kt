package com.vitalguard.ai

import kotlinx.serialization.Serializable

@Serializable
data class TriggerFeatures(
    val perclos: Float,
    val eyeOpenProbability: Float,
    val headEulerAngleX: Float
)

@Serializable
data class DistractionInfo(
    val score: Float,
    val state: String,
    val escalationLevel: Int,
    val yawDeg: Float,
    val pitchDeg: Float,
    val handsVisibility: String,
    val handsOnWheel: Boolean,
    val reason: String
) {
    companion object {
        const val VISIBILITY_FULL = "FULL"
        const val VISIBILITY_PARTIAL = "PARTIAL"
        const val VISIBILITY_UNKNOWN = "UNKNOWN"
    }
}

@Serializable
data class TriggerPayload(
    val timestampMs: Long,
    val source: String,
    val score: Float,
    val confidence: Float,
    val state: String,
    val escalationLevel: Int,
    val features: TriggerFeatures,
    val reason: String,
    val correlationId: String,
    val distraction: DistractionInfo
) {
    companion object {
        const val STATE_NORMAL = "NORMAL"
        const val STATE_WARNING = "WARNING"
        const val STATE_CRITICAL = "CRITICAL"
        const val STATE_UNKNOWN = "UNKNOWN"
    }
}

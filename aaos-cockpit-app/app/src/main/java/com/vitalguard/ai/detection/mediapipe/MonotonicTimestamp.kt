package com.vitalguard.ai.detection.mediapipe

/**
 * Guards MediaPipe's LIVE_STREAM mode, which requires strictly increasing
 * timestamps -- direct port of dms-ai-engine/services/face_landmarker_client.py's
 * MonotonicTimestamp, including its non-finite and non-increasing fallbacks.
 */
class MonotonicTimestamp {
    private var last: Long? = null

    fun next(rawMs: Double): Long {
        val candidate: Long = if (!rawMs.isFinite()) {
            last?.plus(1) ?: 0L
        } else {
            val raw = rawMs.toLong()
            if (last != null && raw <= last!!) last!! + 1 else raw
        }
        last = candidate
        return candidate
    }
}

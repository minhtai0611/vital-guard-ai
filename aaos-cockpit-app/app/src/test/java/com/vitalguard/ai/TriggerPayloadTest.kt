package com.vitalguard.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerPayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses a well-formed CRITICAL payload`() {
        val raw = """
            {
              "timestampMs": 1700000000000,
              "source": "container-python",
              "score": 0.91,
              "confidence": 1.0,
              "state": "CRITICAL",
              "escalationLevel": 1,
              "features": {"perclos": 0.8, "eyeOpenProbability": 0.1, "headEulerAngleX": 28.0},
              "reason": "sustained_high_score",
              "correlationId": "vg-critical-0001",
              "distraction": {
                "score": 0.0, "state": "NORMAL", "escalationLevel": 1, "yawDeg": 0.0, "pitchDeg": 0.0,
                "handsVisibility": "FULL", "handsOnWheel": true, "reason": "none"
              }
            }
        """.trimIndent()

        val payload = json.decodeFromString<TriggerPayload>(raw)

        assertEquals(TriggerPayload.STATE_CRITICAL, payload.state)
        assertEquals("vg-critical-0001", payload.correlationId)
        assertEquals(0.8f, payload.features.perclos)
    }

    @Test
    fun `deserializes the distraction object correctly`() {
        val raw = """
            {
              "timestampMs": 0, "source": "container-python", "score": 0.1, "confidence": 1.0,
              "state": "NORMAL", "escalationLevel": 1,
              "features": {"perclos": 0.0, "eyeOpenProbability": 1.0, "headEulerAngleX": 0.0},
              "reason": "test", "correlationId": "vg-test-0001",
              "distraction": {
                "score": 0.9, "state": "CRITICAL", "escalationLevel": 2, "yawDeg": 45.0, "pitchDeg": 5.0,
                "handsVisibility": "FULL", "handsOnWheel": true, "reason": "gaze_off_road"
              }
            }
        """.trimIndent()

        val payload = json.decodeFromString<TriggerPayload>(raw)

        assertEquals(TriggerPayload.STATE_CRITICAL, payload.distraction.state)
        assertEquals(45.0f, payload.distraction.yawDeg)
        assertEquals("FULL", payload.distraction.handsVisibility)
        assertTrue(payload.distraction.handsOnWheel)
        assertEquals(2, payload.distraction.escalationLevel)
    }
}

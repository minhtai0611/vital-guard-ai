package com.vitalguard.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
              "features": {"perclos": 0.8, "eyeOpenProbability": 0.1, "headEulerAngleX": 28.0},
              "reason": "sustained_high_score",
              "correlationId": "vg-critical-0001"
            }
        """.trimIndent()

        val payload = json.decodeFromString<TriggerPayload>(raw)

        assertEquals(TriggerPayload.STATE_CRITICAL, payload.state)
        assertEquals("vg-critical-0001", payload.correlationId)
        assertEquals(0.8f, payload.features.perclos)
    }
}

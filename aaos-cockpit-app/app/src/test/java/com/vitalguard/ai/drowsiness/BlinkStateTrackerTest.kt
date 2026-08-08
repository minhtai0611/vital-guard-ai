package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkStateTrackerTest {

    @Test
    fun `blinkScore averages both eyes`() {
        val blendshapes = mapOf("eyeBlinkLeft" to 0.8f, "eyeBlinkRight" to 0.6f, "jawOpen" to 0.1f)
        assertEquals(0.7f, blinkScore(blendshapes), 0.0001f)
    }

    @Test
    fun `blinkScore missing category defaults to zero`() {
        // If Face Landmarker doesn't report a category for a frame (e.g. face
        // partially out of view), treat it as eyes-open rather than crashing.
        assertEquals(0.0f, blinkScore(mapOf("jawOpen" to 0.1f)), 0.0001f)
    }

    @Test
    fun `stays open below close threshold`() {
        val tracker = BlinkStateTracker()
        assertFalse(tracker.update(BLINK_CLOSE_THRESHOLD - 0.05f, now = 0.0))
    }

    @Test
    fun `closes above close threshold`() {
        val tracker = BlinkStateTracker()
        assertTrue(tracker.update(BLINK_CLOSE_THRESHOLD + 0.05f, now = 0.0))
    }

    @Test
    fun `ignores a single dip between the two thresholds`() {
        // Exact failure mode from the real drowsy-video finding: one noisy
        // frame dipping between the close and reopen thresholds must NOT
        // flip the state back to open -- only dropping below the LOWER
        // reopen threshold should.
        val tracker = BlinkStateTracker()
        assertTrue(tracker.update(BLINK_CLOSE_THRESHOLD + 0.10f, now = 0.0))
        val midpoint = (BLINK_CLOSE_THRESHOLD + BLINK_REOPEN_THRESHOLD) / 2f
        assertTrue("a dip that doesn't cross the reopen threshold must stay closed",
            tracker.update(midpoint, now = 0.03))
        assertTrue(tracker.update(BLINK_CLOSE_THRESHOLD + 0.10f, now = 0.07))
    }

    @Test
    fun `reopens only below reopen threshold`() {
        val tracker = BlinkStateTracker()
        tracker.update(BLINK_CLOSE_THRESHOLD + 0.10f, now = 0.0)
        assertFalse(tracker.update(BLINK_REOPEN_THRESHOLD - 0.05f, now = 0.03))
    }
}

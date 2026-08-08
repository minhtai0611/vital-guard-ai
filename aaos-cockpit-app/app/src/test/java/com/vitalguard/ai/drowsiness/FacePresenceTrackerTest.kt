/**
 * Tests for FacePresenceTracker, ported from dms-ai-engine/services/trigger_emitter.py.
 */
package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FacePresenceTrackerTest {

    @Test
    fun `no signal while face is visible`() {
        val tracker = FacePresenceTracker(sustainSeconds = 2.0)
        assertNull(tracker.update(hasFace = true, now = 0.0))
        assertNull(tracker.update(hasFace = true, now = 1.0))
    }

    @Test
    fun `unknown fires once after sustained loss`() {
        val tracker = FacePresenceTracker(sustainSeconds = 2.0)
        assertNull(tracker.update(hasFace = false, now = 0.0))
        assertNull(tracker.update(hasFace = false, now = 1.0))
        assertEquals(FacePresenceSignal.Unknown, tracker.update(hasFace = false, now = 2.1))
        assertNull("must not repeat UNKNOWN every call", tracker.update(hasFace = false, now = 3.0))
    }

    @Test
    fun `present fires once on face returning`() {
        val tracker = FacePresenceTracker(sustainSeconds = 2.0)
        tracker.update(hasFace = false, now = 0.0)
        assertEquals(FacePresenceSignal.Unknown, tracker.update(hasFace = false, now = 2.1))
        assertEquals(FacePresenceSignal.Present, tracker.update(hasFace = true, now = 3.0))
        assertNull("must not repeat PRESENT every call", tracker.update(hasFace = true, now = 3.1))
    }

    @Test
    fun `brief loss under sustain window emits nothing`() {
        val tracker = FacePresenceTracker(sustainSeconds = 2.0)
        tracker.update(hasFace = false, now = 0.0)
        assertNull("face came back before sustain elapsed", tracker.update(hasFace = true, now = 1.0))
    }
}

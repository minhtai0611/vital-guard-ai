package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * New integration tests (no Python equivalent exists). Drive
 * FacePresenceTracker + EscalationTracker together the same way the
 * orchestrator (MediaPipeReplayDetectionSource, Task 9) does, without
 * needing a real FaceLandmarker/video. See
 * docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md,
 * decisions D4 and D5.
 */
class DrowsinessEscalationIntegrationTest {

    @Test
    fun `escalation at level 2 resets to level 1 after a sustained face loss, then rebuilds from scratch`() {
        val faceTracker = FacePresenceTracker(sustainSeconds = 2.0)
        val escalation = EscalationTracker(levelUpSeconds = listOf(8.0, 16.0), repeatIntervalSeconds = listOf(10.0, 5.0, 4.0))

        // Drive escalation to level 2 while critical is active.
        escalation.update(criticalActive = true, now = 0.0)
        val (levelBeforeLoss, _, _) = escalation.update(criticalActive = true, now = 8.0)
        assertEquals(2, levelBeforeLoss)

        // Face is lost for 3 continuous seconds (>= the 2.0s sustain window).
        var unknownFired = false
        var t = 8.5
        while (t <= 11.5) {
            val signal = faceTracker.update(hasFace = false, now = t)
            if (signal == FacePresenceSignal.Unknown) {
                unknownFired = true
                escalation.reset() // exactly what the orchestrator does on this edge (decision D4)
            }
            t += 0.1
        }
        assertTrue("a 3s continuous face loss must cross the 2.0s sustain window", unknownFired)

        // Face returns; critical is still active (driver still drowsy) --
        // escalation must rebuild from level 1, NOT resume at the pre-loss level 2.
        faceTracker.update(hasFace = true, now = 11.6)
        val (levelAfterReturn, _, _) = escalation.update(criticalActive = true, now = 11.7)
        assertEquals("must restart from level 1 after reset(), not resume at the pre-loss level",
            1, levelAfterReturn)

        // And it can climb again given enough continued critical time.
        val (levelRebuilt, _, levelChangedRebuilt) = escalation.update(criticalActive = true, now = 19.7) // 11.7+8.0
        assertEquals(2, levelRebuilt)
        assertTrue(levelChangedRebuilt)
    }

    @Test
    fun `a face flicker of 1 sample amid a longer absence delays but does not permanently suppress UNKNOWN`() {
        // Documents the inherited, tested Python trade-off (decision D5): a
        // single good frame immediately cancels the absence timer
        // (FacePresenceTracker has no debounce on the "recover" side), so a
        // flicker delays -- but does not permanently prevent -- UNKNOWN from
        // eventually firing on renewed absence.
        val faceTracker = FacePresenceTracker(sustainSeconds = 2.0)
        assertNull(faceTracker.update(hasFace = false, now = 0.0))
        assertNull(faceTracker.update(hasFace = false, now = 1.0))
        // Single good frame at t=1.5 resets the absence timer entirely.
        assertNull(faceTracker.update(hasFace = true, now = 1.5))
        // Absence resumes -- must count a fresh 2.0s from here, not resume the old timer.
        assertNull(faceTracker.update(hasFace = false, now = 1.6))
        assertNull("only 1.9s of fresh continuous absence elapsed -- must not have fired yet",
            faceTracker.update(hasFace = false, now = 3.5))
        assertEquals(FacePresenceSignal.Unknown, faceTracker.update(hasFace = false, now = 3.7))
    }
}

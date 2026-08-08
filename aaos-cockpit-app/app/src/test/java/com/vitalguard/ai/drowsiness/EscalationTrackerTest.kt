/**
 * Ported from dms-ai-engine/services/escalation_tracker.py
 */
package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalationTrackerTest {

    @Test(expected = IllegalArgumentException::class)
    fun `constructor validates length mismatch`() {
        EscalationTracker(levelUpSeconds = listOf(8.0, 16.0), repeatIntervalSeconds = listOf(10.0, 5.0))
    }

    @Test
    fun `not critical returns level 1 and no repeat`() {
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        val (level, repeatDue, levelChanged) = tracker.update(criticalActive = false, now = 0.0)
        assertEquals(1, level)
        assertFalse(repeatDue)
        assertFalse(levelChanged)
    }

    @Test
    fun `onset tick is level 1 and repeat due fires immediately`() {
        // First tick of a new CRITICAL episode -- repeatDue=true is expected
        // (it anchors lastRepeatTime; it does NOT cause an extra publish,
        // since the emitter's own CRITICAL edge already publishes this same tick).
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        val (level, repeatDue, levelChanged) = tracker.update(criticalActive = true, now = 0.0)
        assertEquals(1, level)
        assertTrue(repeatDue)
        assertFalse(levelChanged)
    }

    @Test
    fun `level up boundary is inclusive at exact elapsed`() {
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        tracker.update(criticalActive = true, now = 0.0)
        val (level, _, levelChanged) = tracker.update(criticalActive = true, now = 8.0)
        assertEquals("elapsed==8.0 must already count as level 2 (>=, not >)", 2, level)
        assertTrue(levelChanged)
    }

    @Test
    fun `single tick jump from level 1 straight to level 3`() {
        // Simulates a large frame gap (e.g. a face-loss stretch) where
        // elapsed jumps past both level-up boundaries in one update() call.
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        tracker.update(criticalActive = true, now = 0.0)
        val (level, repeatDue, levelChanged) = tracker.update(criticalActive = true, now = 20.0)
        assertEquals(3, level)
        assertTrue(levelChanged)
        assertTrue(repeatDue)
    }

    @Test
    fun `level changed updates last repeat time to avoid double fire`() {
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        tracker.update(criticalActive = true, now = 0.0)           // level 1, lastRepeatTime=0
        tracker.update(criticalActive = true, now = 8.0)            // levelChanged -> 2, lastRepeatTime=8
        val r1 = tracker.update(criticalActive = true, now = 13.0)  // 13-8=5>=interval[1]=5.0
        assertEquals(2, r1.first); assertTrue(r1.second); assertFalse(r1.third)

        // level changes to 3 here; lastRepeatTime must become 16, NOT stay at 13
        val r2 = tracker.update(criticalActive = true, now = 16.0)
        assertEquals(3, r2.first); assertTrue(r2.third); assertTrue(r2.second)

        // without the fix, interval[2]=4.0 measured from the OLD anchor (13)
        // would make this next check fire at 17.0 (13+4); with the fix it
        // must NOT fire until 20.0 (16+4).
        val r3 = tracker.update(criticalActive = true, now = 17.0)
        assertFalse("must not double-fire 1s after the level-change announcement", r3.second)
        val r4 = tracker.update(criticalActive = true, now = 20.0)
        assertTrue(r4.second)
    }

    @Test
    fun `continues through a warning dip without resetting`() {
        // criticalActive is assumed to already be hysteresis-protected by the
        // emitter (it only goes false at score<=exitThreshold, not on a
        // WARNING dip) -- this locks in that EscalationTracker trusts that
        // input as-is and does not add its own reset logic on top.
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        tracker.update(criticalActive = true, now = 0.0)
        tracker.update(criticalActive = true, now = 16.0) // level 3
        val (level, _, _) = tracker.update(criticalActive = true, now = 22.0)
        assertEquals("a caller passing criticalActive=true continuously must never see the level drop", 3, level)
    }

    @Test
    fun `recovered resets to level 1 and level changed fires exactly once`() {
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        tracker.update(criticalActive = true, now = 0.0)
        tracker.update(criticalActive = true, now = 16.0) // level 3
        val (level, repeatDue, levelChanged) = tracker.update(criticalActive = false, now = 20.0)
        assertEquals(1, level); assertFalse(repeatDue); assertTrue(levelChanged)
        val (_, _, levelChanged2) = tracker.update(criticalActive = false, now = 21.0)
        assertFalse("must not repeat levelChanged on every subsequent non-critical tick", levelChanged2)
    }

    @Test
    fun `reset forces level 1 regardless of critical active`() {
        // reset() is for the face-loss path: criticalActive on the underlying
        // emitter is frozen (not updated) while hasFace=false, so the tracker
        // cannot detect a reset via criticalActive alone -- the orchestrator
        // calls reset() explicitly instead.
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        tracker.update(criticalActive = true, now = 0.0)
        tracker.update(criticalActive = true, now = 16.0) // level 3
        tracker.reset()
        val (level, _, _) = tracker.update(criticalActive = true, now = 16.1)
        assertEquals("reset() must force the next update() to start counting from level 1 again", 1, level)
    }
}

/**
 * Tests for TriggerEmitter, ported from dms-ai-engine/services/trigger_emitter.py.
 */
package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerEmitterTest {

    @Test
    fun `no trigger before sustain window elapses`() {
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        assertNull(emitter.update(0.9, now = 0.0))
        assertNull(emitter.update(0.9, now = 1.0))
    }

    @Test
    fun `trigger fires once after sustain`() {
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        emitter.update(0.9, now = 0.0)
        emitter.update(0.9, now = 1.0)
        assertEquals(TriggerSignal.Critical, emitter.update(0.9, now = 2.1))
    }

    @Test
    fun `no duplicate trigger while still above threshold`() {
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        emitter.update(0.9, now = 0.0)
        val firedFirst = emitter.update(0.9, now = 2.1)
        val firedAgain = emitter.update(0.9, now = 3.0)
        assertEquals(TriggerSignal.Critical, firedFirst)
        assertNull("must not trigger again while still in the old episode", firedAgain)
    }

    @Test
    fun `trigger rearms after dropping below exit threshold`() {
        val emitter = TriggerEmitter(enterThreshold = 0.85, exitThreshold = 0.50, sustainSeconds = 2.0, cooldownSeconds = 3.0)
        emitter.update(0.9, now = 0.0)
        emitter.update(0.9, now = 2.1) // fires 1st time
        emitter.update(0.3, now = 5.0) // drops below exit -> re-arms
        assertNull("not sustained again yet", emitter.update(0.9, now = 5.1))
        assertEquals(TriggerSignal.Critical, emitter.update(0.9, now = 7.2))
    }

    @Test
    fun `hysteresis prevents flicker around 0_85`() {
        val emitter = TriggerEmitter(enterThreshold = 0.85, exitThreshold = 0.50, sustainSeconds = 2.0)
        var t = 0.0
        var firedAny = false
        for (i in 0 until 10) {
            val score = if (i % 2 == 0) 0.86 else 0.84 // never drops to exitThreshold=0.50
            if (emitter.update(score, now = t) != null) firedAny = true
            t += 0.3
        }
        assertFalse(firedAny)
    }

    @Test
    fun `criticalActive property reflects internal state`() {
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        assertFalse(emitter.criticalActive)
        emitter.update(0.9, now = 0.0)
        emitter.update(0.9, now = 2.1)
        assertTrue(emitter.criticalActive)
        emitter.update(0.3, now = 5.0)
        assertFalse(emitter.criticalActive)
    }

    @Test
    fun `criticalActive does not flap on warning zone oscillation`() {
        val emitter = TriggerEmitter(enterThreshold = 0.85, exitThreshold = 0.50, sustainSeconds = 2.0, cooldownSeconds = 10.0)
        emitter.update(0.9, now = 0.0)
        emitter.update(0.9, now = 2.1)
        assertTrue(emitter.criticalActive)
        var t = 2.1
        for (i in 0 until 10) {
            val score = if (i % 2 == 0) 0.80 else 0.60 // oscillates in WARNING zone, never <=0.50
            emitter.update(score, now = t)
            assertTrue("must stay true at t=$t, score=$score", emitter.criticalActive)
            t += 0.3
        }
    }

    @Test
    fun `short dip below enter but above exit resets sustain timer`() {
        val emitter = TriggerEmitter(enterThreshold = 0.85, exitThreshold = 0.50, sustainSeconds = 2.0)
        emitter.update(0.9, now = 0.0)
        emitter.update(0.9, now = 1.5)
        emitter.update(0.7, now = 1.6) // dips but stays above exit -> aboveSince resets
        assertNull("must restart sustain timer", emitter.update(0.9, now = 1.7))
        assertEquals(TriggerSignal.Critical, emitter.update(0.9, now = 3.8))
    }

    @Test
    fun `update returns critical on fire`() {
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        emitter.update(0.9, now = 0.0)
        assertEquals(TriggerSignal.Critical, emitter.update(0.9, now = 2.1))
    }

    @Test
    fun `update returns null when not firing`() {
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        assertNull(emitter.update(0.9, now = 0.0))
    }

    @Test
    fun `recovered fires once on down edge after critical`() {
        val emitter = TriggerEmitter(enterThreshold = 0.85, exitThreshold = 0.50, sustainSeconds = 2.0, cooldownSeconds = 10.0)
        emitter.update(0.9, now = 0.0)
        assertEquals(TriggerSignal.Critical, emitter.update(0.9, now = 2.1))
        assertEquals(TriggerSignal.Recovered, emitter.update(0.3, now = 5.0))
        assertNull("must not repeat RECOVERED every call", emitter.update(0.3, now = 5.1))
    }

    @Test
    fun `recovered does not fire without a prior critical`() {
        val emitter = TriggerEmitter(enterThreshold = 0.85, exitThreshold = 0.50, sustainSeconds = 2.0, cooldownSeconds = 10.0)
        assertNull(emitter.update(0.3, now = 0.0))
        assertNull(emitter.update(0.2, now = 1.0))
    }
}

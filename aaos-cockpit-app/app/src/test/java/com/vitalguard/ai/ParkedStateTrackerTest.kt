package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParkedStateTrackerTest {
    private fun tracker() = ParkedStateTracker(
        enterThresholdKmh = 10f, enterSustainMs = 30_000L,
        exitThresholdKmh = 15f, exitSustainMs = 2_000L,
    )

    @Test
    fun `below threshold but not yet sustained does not enter parked`() {
        val t = tracker()
        assertNull(t.update(5f, nowMs = 0L))
        assertNull(t.update(5f, nowMs = 29_999L)) // 1ms short of the 30s sustain
    }

    @Test
    fun `brief dip above threshold mid sustain resets belowSince`() {
        val t = tracker()
        assertNull(t.update(5f, nowMs = 0L))
        assertNull(t.update(20f, nowMs = 10_000L)) // red light -- briefly above threshold
        assertNull(t.update(5f, nowMs = 15_000L))  // back below, but the clock restarted
        // total elapsed since the dip is only 30_000 - 15_000 = 15_000ms -- not enough
        assertNull(t.update(5f, nowMs = 30_000L))
    }

    @Test
    fun `sustained below threshold enters parked exactly once`() {
        val t = tracker()
        t.update(5f, nowMs = 0L)
        assertEquals(true, t.update(5f, nowMs = 30_000L))
        assertNull(t.update(5f, nowMs = 31_000L)) // already parked -- no repeat transition
    }

    @Test
    fun `speed in dead zone between exit and enter threshold while parked does not exit`() {
        val t = tracker()
        t.update(5f, nowMs = 0L)
        assertEquals(true, t.update(5f, nowMs = 30_000L))
        // 12 km/h is above the enter threshold (10) but below the exit threshold (15)
        assertNull(t.update(12f, nowMs = 32_000L))
        assertNull(t.update(12f, nowMs = 40_000L))
    }

    @Test
    fun `null speed while not parked does not enter parked`() {
        val t = tracker()
        t.update(5f, nowMs = 0L)
        assertNull(t.update(null, nowMs = 10_000L))
        assertNull(t.update(5f, nowMs = 30_000L)) // belowSince was reset by the null reading
    }

    @Test
    fun `null speed while parked resumes immediately`() {
        val t = tracker()
        t.update(5f, nowMs = 0L)
        assertEquals(true, t.update(5f, nowMs = 30_000L))
        assertEquals(false, t.update(null, nowMs = 30_100L))
    }
}

package com.vitalguard.ai.detection.mediapipe

import org.junit.Assert.assertEquals
import org.junit.Test

class MonotonicTimestampTest {

    @Test
    fun `first call passes a finite value through unchanged`() {
        val t = MonotonicTimestamp()
        assertEquals(100L, t.next(100.0))
    }

    @Test
    fun `strictly increasing values pass through unchanged`() {
        val t = MonotonicTimestamp()
        assertEquals(100L, t.next(100.0))
        assertEquals(200L, t.next(200.0))
        assertEquals(300L, t.next(300.0))
    }

    @Test
    fun `equal to previous is bumped by one`() {
        val t = MonotonicTimestamp()
        assertEquals(100L, t.next(100.0))
        assertEquals(101L, t.next(100.0))
    }

    @Test
    fun `less than previous is bumped by one, not restored to the raw value`() {
        val t = MonotonicTimestamp()
        assertEquals(100L, t.next(100.0))
        assertEquals(101L, t.next(50.0))
        assertEquals(102L, t.next(60.0)) // still below 101 -- bumped again, not reset to 60
    }

    @Test
    fun `non-finite value on first call falls back to zero`() {
        val t = MonotonicTimestamp()
        assertEquals(0L, t.next(Double.NaN))
    }

    @Test
    fun `non-finite value after a prior timestamp bumps the last value by one`() {
        val t = MonotonicTimestamp()
        t.next(500.0)
        assertEquals(501L, t.next(Double.POSITIVE_INFINITY))
        assertEquals(502L, t.next(Double.NaN))
    }

    @Test
    fun `fractional milliseconds are truncated, not rounded`() {
        val t = MonotonicTimestamp()
        assertEquals(100L, t.next(100.9))
    }
}

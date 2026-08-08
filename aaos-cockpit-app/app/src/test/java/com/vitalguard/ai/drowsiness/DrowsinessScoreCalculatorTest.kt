package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrowsinessScoreCalculatorTest {

    @Test
    fun `all eyes open no droop gives zero score`() {
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
        for (i in 0 until 20) {
            calc.addFrame(FrameFeatures(timestamp = i * 0.1, eyeClosed = false, headPitchDeg = 0.0))
        }
        assertEquals(0.0, calc.computeScore(), 0.0001)
    }

    @Test
    fun `sustained closed eyes and droop gives high score`() {
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
        for (i in 0 until 20) {
            calc.addFrame(FrameFeatures(timestamp = i * 0.1, eyeClosed = true, headPitchDeg = 30.0))
        }
        assertTrue(calc.computeScore() > 0.85)
    }

    @Test
    fun `single normal blink does not spike perclos`() {
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
        for (i in 0 until 19) {
            calc.addFrame(FrameFeatures(timestamp = i * 0.1, eyeClosed = false, headPitchDeg = 0.0))
        }
        val score = calc.addFrame(FrameFeatures(timestamp = 1.9, eyeClosed = true, headPitchDeg = 0.0))
        assertTrue("one normal blink must not cause a high score", score < 0.85)
    }

    @Test
    fun `baseline calibration removes seat tilt offset`() {
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
        calc.calibrateBaseline(pitchDeg = 10.0) // seat already tilted 10deg when sitting upright
        for (i in 0 until 20) {
            calc.addFrame(FrameFeatures(timestamp = i * 0.1, eyeClosed = false, headPitchDeg = 10.0))
        }
        // head is still at "upright" after subtracting baseline -> droop must be 0
        assertEquals(0.0, calc.computeScore(), 0.0001)
    }

    @Test
    fun `known limitation - non-neutral pose during the calibration window caps the score`() {
        // If the first BASELINE_CALIBRATION_SECONDS aren't neutral (e.g. driver
        // still adjusting the mirror), the wrong baseline is locked in for the
        // rest of the run. Documented known limitation (design doc decision
        // D7) -- Python has no test for this scenario either; ported as-is,
        // not fixed here.
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
        calc.calibrateBaseline(pitchDeg = 20.0) // wrongly calibrated against a droopy first second
        for (i in 0 until 20) {
            // Driver is actually fully drowsy: eyes closed, head at the SAME
            // droop as the bad calibration window -- droop reads as ~0, not 1.
            calc.addFrame(FrameFeatures(timestamp = i * 0.1, eyeClosed = true, headPitchDeg = 20.0))
        }
        val score = calc.computeScore()
        // 0.55*perclos(1.0) + 0.25*eyeClosedNow(1.0) + 0.20*droop(0.0) = 0.80
        assertEquals(0.80, score, 0.001)
        assertTrue("known limitation: capped below the 0.85 CRITICAL threshold by a bad calibration window",
            score < 0.85)
    }
}

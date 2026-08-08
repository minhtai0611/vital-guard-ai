package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden-file regression test. Feeds the exact same synthetic scenario as
 * dms-ai-engine/main.py::run_mock_stream() (30 frames eyes-open, 40 frames
 * eyes-closed+droop(28deg), 30 frames eyes-open, 0.1s steps, t accumulated
 * via repeated += 0.1 -- replicated exactly here, not as i*0.1, to reproduce
 * the same floating-point step boundaries as the recorded reference) through
 * the ported DrowsinessScoreCalculator -> TriggerEmitter chain, and asserts
 * against checkpoints read directly from the repo's own root evidence_run.csv
 * (the recorded output of that exact Python run -- its trigger_fired column
 * shows a real 1 at t=6.5, confirming this scenario, unlike drowsy.mp4,
 * actually reaches a debounced CRITICAL fire).
 */
class DrowsinessPipelineGoldenTest {

    private data class Row(val t: Double, val eyeClosed: Boolean, val pitch: Double)

    private fun scenario(): List<Row> {
        val rows = mutableListOf<Row>()
        var t = 0.0
        repeat(30) { rows.add(Row(t, eyeClosed = false, pitch = 0.0)); t += 0.1 }
        repeat(40) { rows.add(Row(t, eyeClosed = true, pitch = 28.0)); t += 0.1 }
        repeat(30) { rows.add(Row(t, eyeClosed = false, pitch = 0.0)); t += 0.1 }
        return rows
    }

    @Test
    fun `matches evidence_run csv checkpoints`() {
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        var triggerFiredAt: Double? = null

        for (row in scenario()) {
            val score = calc.addFrame(FrameFeatures(row.t, row.eyeClosed, row.pitch))
            val signal = emitter.update(score, row.t)
            if (signal == TriggerSignal.Critical && triggerFiredAt == null) triggerFiredAt = row.t

            // Checkpoints taken verbatim from the repo's root evidence_run.csv:
            when {
                closeTo(row.t, 2.9) -> assertEquals("t=2.9 (still eyes-open segment)", 0.000, score, 0.001)
                closeTo(row.t, 4.4) -> assertEquals("t=4.4 (mid ramp-up)", 0.863, score, 0.001)
                closeTo(row.t, 4.9) -> assertEquals("t=4.9 (saturated)", 1.000, score, 0.001)
                closeTo(row.t, 8.9) -> assertEquals("t=8.9 (recovered)", 0.000, score, 0.001)
            }
        }

        assertEquals("evidence_run.csv records trigger_fired=1 at exactly t=6.5",
            6.5, triggerFiredAt ?: -1.0, 0.001)
    }

    private fun closeTo(a: Double, b: Double) = kotlin.math.abs(a - b) < 0.001
}

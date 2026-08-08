package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Test

class DrowsinessPipelineConfigTest {
    @Test
    fun `values match dms-ai-engine main py's run_real_video construction`() {
        assertEquals(2.0, DrowsinessPipelineConfig.WINDOW_SECONDS, 0.0)
        assertEquals(10.0, DrowsinessPipelineConfig.SAMPLE_HZ, 0.0)
        assertEquals(25.0, DrowsinessPipelineConfig.MAX_DROOP_DEG, 0.0)
        assertEquals(0.85, DrowsinessPipelineConfig.ENTER_THRESHOLD, 0.0)
        assertEquals(0.50, DrowsinessPipelineConfig.EXIT_THRESHOLD, 0.0)
        assertEquals(2.0, DrowsinessPipelineConfig.SUSTAIN_SECONDS, 0.0)
        assertEquals(10.0, DrowsinessPipelineConfig.COOLDOWN_SECONDS, 0.0)
        assertEquals(2.0, DrowsinessPipelineConfig.FACE_ABSENCE_SUSTAIN_SECONDS, 0.0)
        assertEquals(listOf(8.0, 16.0), DrowsinessPipelineConfig.LEVEL_UP_SECONDS)
        assertEquals(listOf(10.0, 5.0, 4.0), DrowsinessPipelineConfig.REPEAT_INTERVAL_SECONDS)
        assertEquals(1.0, DrowsinessPipelineConfig.BASELINE_CALIBRATION_SECONDS, 0.0)
        assertEquals(30.0, DrowsinessPipelineConfig.FALLBACK_FPS, 0.0)
    }
}

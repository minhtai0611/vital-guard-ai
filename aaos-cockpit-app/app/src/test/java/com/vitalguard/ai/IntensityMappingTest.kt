package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IntensityMappingTest {
    @Test
    fun `high maps to fan8 temp20`() {
        assertEquals(8, IntensityMapping.fanSpeedFor(IntensityLevel.HIGH))
        assertEquals(20f, IntensityMapping.temperatureCFor(IntensityLevel.HIGH), 0.001f)
    }

    @Test
    fun `medium maps to fan5 temp22`() {
        assertEquals(5, IntensityMapping.fanSpeedFor(IntensityLevel.MEDIUM))
        assertEquals(22f, IntensityMapping.temperatureCFor(IntensityLevel.MEDIUM), 0.001f)
    }

    @Test
    fun `low maps to fan3 temp23`() {
        assertEquals(3, IntensityMapping.fanSpeedFor(IntensityLevel.LOW))
        assertEquals(23f, IntensityMapping.temperatureCFor(IntensityLevel.LOW), 0.001f)
    }

    @Test
    fun `no intensity level maps to zero fan or temperature`() {
        for (level in IntensityLevel.values()) {
            assertNotEquals(0, IntensityMapping.fanSpeedFor(level))
            assertNotEquals(0f, IntensityMapping.temperatureCFor(level), 0.001f)
        }
    }
}

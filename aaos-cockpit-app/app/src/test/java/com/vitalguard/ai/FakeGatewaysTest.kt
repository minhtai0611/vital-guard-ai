package com.vitalguard.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeGatewaysTest {
    @Test
    fun `fake climate gateway tracks apply and revert calls`() {
        val gateway = FakeClimateActuatorGateway()
        assertFalse(gateway.overrideApplied)

        gateway.applyDrowsinessOverride()
        assertTrue(gateway.overrideApplied)

        gateway.revertToBaseline()
        assertTrue(gateway.revertCalled)
    }

    @Test(expected = IllegalStateException::class)
    fun `fake climate gateway throws on apply when configured to`() {
        val gateway = FakeClimateActuatorGateway()
        gateway.throwOnApply = true
        gateway.applyDrowsinessOverride()
    }

    @Test
    fun `fake voice gateway tracks trigger and stop calls`() {
        val gateway = FakeVoiceAlertGateway()
        gateway.triggerAlert()
        gateway.stopAlert()
        assertTrue(gateway.alertTriggered)
        assertTrue(gateway.stopCalled)
    }
}

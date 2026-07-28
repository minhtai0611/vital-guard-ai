package com.vitalguard.ai

interface ClimateActuatorGateway {
    fun applyDrowsinessOverride()
    fun revertToBaseline()
}

class FakeClimateActuatorGateway : ClimateActuatorGateway {
    var overrideApplied: Boolean = false
    var revertCalled: Boolean = false
    var throwOnApply: Boolean = false
    var throwOnRevert: Boolean = false

    override fun applyDrowsinessOverride() {
        if (throwOnApply) throw IllegalStateException("simulated climate gateway failure")
        overrideApplied = true
    }

    override fun revertToBaseline() {
        if (throwOnRevert) throw IllegalStateException("simulated climate gateway revert failure")
        revertCalled = true
    }
}

package com.vitalguard.ai

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/**
 * Verifies BridgeClimateActuatorGateway sends the two broadcast actions with the right
 * action strings.
 *
 * Uses Mockito's `mockConstruction` (backed by Mockito 5's default inline mock maker — no
 * extra mockito-inline artifact needed) to capture the `Intent(action)` constructor argument
 * directly, instead of reading it back via `intent.action`. This module's unit tests run with
 * `testOptions.unitTests.returnDefaultValues = true` (see app/build.gradle, added so
 * DrowsinessController's android.util.Log calls don't throw "not mocked"); that same stubbing
 * makes every android.content.Intent method — including the real field storage the
 * constructor would normally do — a no-op that returns a default value, so
 * `Intent("action").action` always comes back null in this test environment regardless of
 * what was actually constructed. Capturing the constructor argument sidesteps that.
 */
class BridgeClimateActuatorGatewayTest {
    @Test
    fun `applyDrowsinessOverride sends the APPLY_HVAC_OVERRIDE broadcast`() {
        val context = mock(Context::class.java)
        val capturedActions = mutableListOf<String?>()

        mockConstruction(Intent::class.java) { _, constructionContext ->
            capturedActions.add(constructionContext.arguments().firstOrNull() as? String)
        }.use { mockedIntent ->
            BridgeClimateActuatorGateway(context).applyDrowsinessOverride()

            assertEquals(listOf("com.vitalguard.ai.bridge.APPLY_HVAC_OVERRIDE"), capturedActions)
            verify(context, times(1)).sendBroadcast(mockedIntent.constructed()[0])
        }
    }

    @Test
    fun `revertToBaseline sends the REVERT_HVAC_BASELINE broadcast`() {
        val context = mock(Context::class.java)
        val capturedActions = mutableListOf<String?>()

        mockConstruction(Intent::class.java) { _, constructionContext ->
            capturedActions.add(constructionContext.arguments().firstOrNull() as? String)
        }.use { mockedIntent ->
            BridgeClimateActuatorGateway(context).revertToBaseline()

            assertEquals(listOf("com.vitalguard.ai.bridge.REVERT_HVAC_BASELINE"), capturedActions)
            verify(context, times(1)).sendBroadcast(mockedIntent.constructed()[0])
        }
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vitalguard.ai

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleContextPollClientTest {
    @Test
    fun `a failing tick does not stop subsequent polling`() = runTest {
        val gateway = FakeVehicleContextGateway()
        gateway.throwOnGet = true
        val tracker = ParkedStateTracker(
            enterThresholdKmh = 10f, enterSustainMs = 20L,
            exitThresholdKmh = 15f, exitSustainMs = 20L,
        )
        val transitions = mutableListOf<Boolean>()
        var fakeNow = 0L

        val client = VehicleContextPollClient(
            gateway = gateway, tracker = tracker, scope = this,
            onParkedStateChanged = { transitions.add(it) },
            pollIntervalMs = 10L,
            nowMsProvider = { fakeNow },
        )
        client.start()

        advanceTimeBy(10); fakeNow += 10   // tick 1: gateway throws -- loop must survive
        gateway.throwOnGet = false
        gateway.speedKmh = 5f
        advanceTimeBy(10); fakeNow += 10   // tick 2: belowSince = 10
        advanceTimeBy(10); fakeNow += 10   // tick 3: 30 - 10 = 20 >= enterSustainMs(20) -> parked

        client.stop()
        assertEquals(listOf(true), transitions)
    }
}

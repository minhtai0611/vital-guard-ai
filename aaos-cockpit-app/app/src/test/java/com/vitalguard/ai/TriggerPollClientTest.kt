@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vitalguard.ai

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeTriggerFetcher(private val script: MutableList<FetchResult>) : TriggerFetcher {
    var callCount = 0
    override suspend fun fetchLatest(): FetchResult {
        callCount++
        return if (script.isNotEmpty()) script.removeAt(0) else FetchResult.NoNewTrigger
    }
}

private fun samplePayload(id: String) = TriggerPayload(
    timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f,
    state = TriggerPayload.STATE_CRITICAL,
    features = TriggerFeatures(0.8f, 0.1f, 28.0f), reason = "test", correlationId = id,
)

class TriggerPollClientTest {
    @Test
    fun `delivers a successful payload to onPayload`() = runTest {
        val fetcher = FakeTriggerFetcher(mutableListOf(FetchResult.Success(samplePayload("vg-0001"))))
        var received: TriggerPayload? = null
        val client = TriggerPollClient(
            fetcher = fetcher, scope = this,
            onPayload = { received = it }, onConnectionLost = {},
            pollIntervalMs = 10L,
        )
        client.start()
        advanceTimeBy(15)
        client.stop()

        assertEquals("vg-0001", received?.correlationId)
    }

    @Test
    fun `three consecutive failures trigger onConnectionLost exactly once`() = runTest {
        val fetcher = FakeTriggerFetcher(mutableListOf(FetchResult.Failure, FetchResult.Failure, FetchResult.Failure))
        var lostCount = 0
        val client = TriggerPollClient(
            fetcher = fetcher, scope = this,
            onPayload = {}, onConnectionLost = { lostCount++ },
            pollIntervalMs = 10L, failureThreshold = 3,
        )
        client.start()
        advanceTimeBy(35)
        client.stop()

        assertEquals(1, lostCount)
    }

    @Test
    fun `a successful poll resets the consecutive-failure count`() = runTest {
        val fetcher = FakeTriggerFetcher(mutableListOf(
            FetchResult.Failure, FetchResult.Failure,
            FetchResult.Success(samplePayload("vg-0002")),
            FetchResult.Failure, FetchResult.Failure,
        ))
        var lostCount = 0
        val client = TriggerPollClient(
            fetcher = fetcher, scope = this,
            onPayload = {}, onConnectionLost = { lostCount++ },
            pollIntervalMs = 10L, failureThreshold = 3,
        )
        client.start()
        advanceTimeBy(55)
        client.stop()

        assertEquals(0, lostCount) // never reached 3 CONSECUTIVE failures
    }

    @Test
    fun `NoNewTrigger is not treated as a failure`() = runTest {
        val fetcher = FakeTriggerFetcher(mutableListOf(FetchResult.NoNewTrigger, FetchResult.NoNewTrigger))
        var lostCount = 0
        var received: TriggerPayload? = null
        val client = TriggerPollClient(
            fetcher = fetcher, scope = this,
            onPayload = { received = it }, onConnectionLost = { lostCount++ },
            pollIntervalMs = 10L,
        )
        client.start()
        advanceTimeBy(25)
        client.stop()

        assertEquals(0, lostCount)
        assertNull(received)
    }
}

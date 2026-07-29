package com.vitalguard.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

sealed class FetchResult {
    data class Success(val payload: TriggerPayload) : FetchResult()
    object NoNewTrigger : FetchResult()
    object Failure : FetchResult()
}

interface TriggerFetcher {
    suspend fun fetchLatest(): FetchResult
}

/** Real fetcher: GET {baseUrl}/latest-trigger over the room-internal network-pin
 * only — see design doc Decision 3. 2s connect+read timeout. */
class HttpTriggerFetcher(private val baseUrl: String) : TriggerFetcher {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchLatest(): FetchResult {
        return try {
            val connection = URL("$baseUrl/latest-trigger").openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.requestMethod = "GET"
            when (connection.responseCode) {
                200 -> {
                    val body = BufferedReader(InputStreamReader(connection.inputStream)).readText()
                    FetchResult.Success(json.decodeFromString<TriggerPayload>(body))
                }
                204 -> FetchResult.NoNewTrigger
                else -> FetchResult.Failure
            }
        } catch (_: Exception) {
            FetchResult.Failure
        }
    }
}

class TriggerPollClient(
    private val fetcher: TriggerFetcher,
    private val scope: CoroutineScope,
    private val onPayload: (TriggerPayload) -> Unit,
    private val onConnectionLost: () -> Unit,
    private val pollIntervalMs: Long = 500L,
    private val failureThreshold: Int = 3
) {
    private var job: Job? = null
    private var consecutiveFailures = 0

    fun start() {
        job = scope.launch {
            while (true) {
                when (val result = fetcher.fetchLatest()) {
                    is FetchResult.Success -> {
                        consecutiveFailures = 0
                        onPayload(result.payload)
                    }
                    FetchResult.NoNewTrigger -> {
                        consecutiveFailures = 0
                    }
                    FetchResult.Failure -> {
                        consecutiveFailures++
                        if (consecutiveFailures == failureThreshold) {
                            onConnectionLost()
                        }
                    }
                }
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

package com.vitalguard.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VehicleContextPollClient(
    private val gateway: VehicleContextGateway,
    private val tracker: ParkedStateTracker,
    private val scope: CoroutineScope,
    private val onParkedStateChanged: (Boolean) -> Unit,
    private val pollIntervalMs: Long = 1000L,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            while (true) {
                try {
                    tracker.update(gateway.getCurrentSpeedKmh(), nowMsProvider())
                        ?.let { onParkedStateChanged(it) }
                } catch (t: Throwable) {
                    Log.e("VitalGuardVehicleContext", "Poll tick failed: ${t.message}")
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

package com.vitalguard.ai

import android.util.Log

enum class AlertSource { DROWSINESS, DISTRACTION }

/**
 * Single point of contact with [VoiceAlertGateway] for both
 * [DrowsinessController] and [DistractionController] -- prevents two
 * independently-triggered TTS messages from being spoken over each other.
 * Drowsiness always wins (fixed 2-source precedence, not a generic
 * priority scheme -- there are exactly two sources and no third is
 * planned). Tracks which source actually owns the currently-sounding
 * alert so a suppressed source's stopAlert() call can never cut off
 * whichever source IS legitimately active.
 */
class AlertArbiter(private val voiceAlertGateway: VoiceAlertGateway) {
    private val TAG = "VitalGuardAlertArbiter"
    private var drowsinessCriticalActive = false
    private var activeSpeaker: AlertSource? = null

    fun setDrowsinessCriticalActive(active: Boolean) {
        drowsinessCriticalActive = active
    }

    fun requestVoiceAlert(source: AlertSource) {
        if (source == AlertSource.DISTRACTION && drowsinessCriticalActive) {
            Log.i(TAG, "Suppressed distraction alert -- drowsiness CRITICAL has priority")
            return
        }
        val outgoingOwner = activeSpeaker
        if (outgoingOwner != null && outgoingOwner != source) {
            Log.i(TAG, "Handing off active speaker from $outgoingOwner to $source -- stopping outgoing owner first")
            voiceAlertGateway.stopAlert()
        }
        activeSpeaker = source
        when (source) {
            AlertSource.DROWSINESS -> voiceAlertGateway.triggerAlert()
            AlertSource.DISTRACTION -> voiceAlertGateway.triggerDistractionReminder()
        }
    }

    fun stopAlert(source: AlertSource) {
        if (activeSpeaker != source) {
            Log.i(TAG, "Ignored stopAlert from $source -- does not own the active alert (owner: $activeSpeaker)")
            return
        }
        activeSpeaker = null
        voiceAlertGateway.stopAlert()
    }
}

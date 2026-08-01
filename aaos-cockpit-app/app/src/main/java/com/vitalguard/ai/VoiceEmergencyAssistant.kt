package com.vitalguard.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class VoiceEmergencyAssistant(private val context: Context) : TextToSpeech.OnInitListener {
    private val TAG = "VitalGuardVoice"
    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US // Sử dụng tiếng Anh chuyên nghiệp theo proposal
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (interrupted) {
                        // Pure observability -- does not change timing/control flow. Lets
                        // rehearsal on the real demo device (adb logcat) catch a
                        // repeat_interval_seconds/utterance-length mismatch before the
                        // live show, per design doc Section 3 "Ràng buộc thời lượng TTS".
                        Log.w(TAG, "Utterance cut off before completion: $utteranceId")
                    }
                }
            })
        }
    }

    fun executeVoiceIntervention(level: Int, volume: Float) {
        // 1. Cấu hình Audio Attributes với độ ưu tiên cao nhất (ASSISTANT / EMERGENCY)
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // 2. Thiết lập yêu cầu cướp tiêu điểm âm thanh độc quyền (Mute tất cả nhạc của xe)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Log.d(TAG, "Audio focus state changed to: $focusChange")
            }
            .build()

        // 3. Gửi yêu cầu lên hệ thống âm thanh buồng lái
        val result = audioManager.requestAudioFocus(focusRequest!!)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "🔇 Audio Focus Obtained! Vehicle Media Muted.")
            speakAlert(level, volume)
        } else {
            Log.e(TAG, "❌ Audio Focus Request Denied.")
        }
    }

    // Level->copy mapping is Kotlin-owned -- Python only sends an escalationLevel
    // int (see design doc docs/superpowers/specs/2026-07-31-alert-escalation-design.md
    // Section 3). repeat_interval_seconds on the Python side was tuned to exceed
    // each of these utterances' estimated spoken duration with margin -- if this
    // copy changes, re-measure with TextToSpeech and re-tune the Python-side
    // interval constants, do not assume the margin still holds.
    private fun drowsinessAlertTextFor(level: Int): String = when (level) {
        1 -> "Warning! Drowsiness detected! Climate safety mode engaged. Please stay awake. Shall I guide you to the nearest rest stop?"
        2 -> "You're still drowsy. Please pull over now."
        else -> "Pull over immediately. Not safe to continue."
    }

    private fun speakAlert(level: Int, volume: Float) {
        val alertText = drowsinessAlertTextFor(level)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        tts?.speak(alertText, TextToSpeech.QUEUE_FLUSH, params, "EMERGENCY_ALERT")
        Log.i(TAG, "🗣️ Speaking Alert (level $level, volume=$volume): '$alertText'")
    }

    private fun distractionReminderTextFor(level: Int): String = when (level) {
        1 -> "Please keep your eyes on the road and both hands on the wheel."
        2 -> "Eyes on the road, please. This is important."
        else -> "Eyes on the road now!"
    }

    fun executeDistractionReminder(level: Int, volume: Float) {
        // Lighter than executeVoiceIntervention()'s _EXCLUSIVE request -- a brief
        // distraction reminder shouldn't seize/mute all cabin audio the way a
        // sustained drowsiness alert does. Placeholder wording/focus behavior,
        // pending Tài's sign-off (design doc Decision 5) -- functional default,
        // not a final UX decision.
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val reminderFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Log.d(TAG, "Distraction reminder audio focus state changed to: $focusChange")
            }
            .build()

        val result = audioManager.requestAudioFocus(reminderFocusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = reminderFocusRequest
            val reminderText = distractionReminderTextFor(level)
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
            tts?.speak(reminderText, TextToSpeech.QUEUE_FLUSH, params, "DISTRACTION_REMINDER")
            Log.i(TAG, "🗣️ Speaking distraction reminder (level $level, volume=$volume): '$reminderText'")
        } else {
            Log.e(TAG, "❌ Distraction reminder audio focus request denied.")
        }
    }

    fun releaseFocus() {
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            Log.d(TAG, "🔊 Audio Focus Released. Normal media restored.")
        }
        tts?.shutdown()
    }
}

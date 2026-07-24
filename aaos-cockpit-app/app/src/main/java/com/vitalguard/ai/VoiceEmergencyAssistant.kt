package com.vitalguard.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
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
        }
    }

    fun executeVoiceIntervention() {
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
            speakAlert()
        } else {
            Log.e(TAG, "❌ Audio Focus Request Denied.")
        }
    }

    private fun speakAlert() {
        val alertText = "Warning! Drowsiness detected! Climate safety mode engaged. Please stay awake. Shall I guide you to the nearest rest stop?"
        tts?.speak(alertText, TextToSpeech.QUEUE_FLUSH, null, "EMERGENCY_ALERT")
        Log.i(TAG, "🗣️ Speaking Alert: '$alertText'")
    }

    fun releaseFocus() {
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            Log.d(TAG, "🔊 Audio Focus Released. Normal media restored.")
        }
        tts?.shutdown()
    }
}

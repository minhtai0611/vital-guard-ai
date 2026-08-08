package com.vitalguard.ai.drowsiness

/**
 * Single source of truth for every tunable constant in the on-device
 * drowsiness pipeline. Values are copied verbatim from
 * dms-ai-engine/main.py's construction of DrowsinessScoreCalculator/
 * TriggerEmitter/FacePresenceTracker/EscalationTracker inside
 * run_real_video() -- the exact values already validated in
 * dms-ai-engine/CV_REMEDIATION_RESULTS.md's acceptance gates.
 *
 * Any future LiveDetectionSource MUST reuse this object rather than
 * hard-coding its own constants -- see
 * docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md,
 * decision D1. No live source exists yet; this guards against future drift.
 */
object DrowsinessPipelineConfig {
    // DrowsinessScoreCalculator window
    const val WINDOW_SECONDS = 2.0
    const val SAMPLE_HZ = 10.0
    const val MAX_DROOP_DEG = 25.0

    // TriggerEmitter
    const val ENTER_THRESHOLD = 0.85
    const val EXIT_THRESHOLD = 0.50
    const val SUSTAIN_SECONDS = 2.0
    const val COOLDOWN_SECONDS = 10.0

    // FacePresenceTracker
    const val FACE_ABSENCE_SUSTAIN_SECONDS = 2.0

    // EscalationTracker (drowsiness)
    val LEVEL_UP_SECONDS = listOf(8.0, 16.0)
    val REPEAT_INTERVAL_SECONDS = listOf(10.0, 5.0, 4.0)

    // Baseline pitch calibration
    const val BASELINE_CALIBRATION_SECONDS = 1.0

    // Replay-file sampling fallback when the video's own frame rate can't be
    // read (METADATA_KEY_CAPTURE_FRAMERATE is officially "if available" --
    // usually absent on normally-recorded, non slow-motion clips). Matches
    // dms-ai-engine/main.py's own `fps = 30.0` fallback exactly.
    const val FALLBACK_FPS = 30.0
}

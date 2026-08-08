package com.vitalguard.ai.drowsiness

/**
 * Ported from dms-ai-engine/services/score_calculator.py. Composite
 * Drowsiness Score in [0,1] from a sliding window of frame features. Not
 * scientifically validated as the specific "correct" weights -- see root
 * CLAUDE.md's disclosed-limitations section; only the general PERCLOS-based
 * fusion concept and the 0.85 threshold have real grounding.
 *
 * score = 0.55 * perclosWindow + 0.25 * eyeClosedNow + 0.20 * headDroopNorm
 */
data class FrameFeatures(
    val timestamp: Double,
    val eyeClosed: Boolean,
    val headPitchDeg: Double,
)

class DrowsinessScoreCalculator(
    windowSeconds: Double = DrowsinessPipelineConfig.WINDOW_SECONDS,
    sampleHz: Double = DrowsinessPipelineConfig.SAMPLE_HZ,
    private val maxDroopDeg: Double = DrowsinessPipelineConfig.MAX_DROOP_DEG,
) {
    private val maxSamples: Int = maxOf(1, (windowSeconds * sampleHz).toInt())
    private val window = ArrayDeque<FrameFeatures>()

    var baselinePitchDeg: Double = 0.0
        private set

    /** Call during the first few seconds while the driver sits upright, to
     * subtract seat/camera mount tilt from head-droop measurements. */
    fun calibrateBaseline(pitchDeg: Double) {
        baselinePitchDeg = pitchDeg
    }

    fun addFrame(frame: FrameFeatures): Double {
        window.addLast(frame)
        while (window.size > maxSamples) window.removeFirst()
        return computeScore()
    }

    fun computeScore(): Double {
        if (window.isEmpty()) return 0.0
        val perclos = window.count { it.eyeClosed }.toDouble() / window.size
        val eyeClosedNow = if (window.last().eyeClosed) 1.0 else 0.0
        val headDroopNorm = normalizedHeadDroop()
        val score = 0.55 * perclos + 0.25 * eyeClosedNow + 0.20 * headDroopNorm
        return score.coerceIn(0.0, 1.0)
    }

    private fun normalizedHeadDroop(): Double {
        val latestPitch = window.last().headPitchDeg - baselinePitchDeg
        return (latestPitch / maxDroopDeg).coerceIn(0.0, 1.0)
    }
}

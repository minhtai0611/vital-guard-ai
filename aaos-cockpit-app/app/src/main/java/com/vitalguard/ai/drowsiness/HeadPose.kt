package com.vitalguard.ai.drowsiness

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Ported from dms-ai-engine/services/head_pose.py. Head pitch/yaw estimation
 * from MediaPipe Face Landmarker's facial_transformation_matrixes -- a full
 * learned 3D face model fit, not a from-scratch solvePnP solve, which is why
 * this doesn't suffer the PnP flip-ambiguity that affected the project's old
 * head-pose extraction.
 *
 * `matrix` is a flat 16-element array, interpreted here as **row-major**
 * (matrix[row*4+col]) -- matching numpy's natural 2D indexing that Python's
 * head_pose.py assumes. This module's own unit tests validate the pure math
 * for that convention only; whether the real Android
 * FaceLandmarkerResult.facialTransformationMatrixes() output needs
 * transposing before being passed in is a separate, empirical, device-level
 * question answered in Task 9's on-device verification step -- see
 * docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md.
 */
object HeadPose {
    private fun at(matrix: FloatArray, row: Int, col: Int): Double = matrix[row * 4 + col].toDouble()

    /** Returns (x, y, z) Euler angles in degrees. */
    fun rotationMatrixToEulerDeg(matrix: FloatArray): Triple<Double, Double, Double> {
        val r00 = at(matrix, 0, 0)
        val r10 = at(matrix, 1, 0)
        val r20 = at(matrix, 2, 0)
        val r21 = at(matrix, 2, 1)
        val r22 = at(matrix, 2, 2)

        val x = Math.toDegrees(atan2(r21, r22))
        val y = Math.toDegrees(atan2(-r20, sqrt(r21 * r21 + r22 * r22)))
        val z = Math.toDegrees(atan2(r10, r00))
        return Triple(x, y, z)
    }

    /** Empirically determined to be pitch -- see module kdoc. */
    fun extractPitchDeg(matrix: FloatArray): Double = rotationMatrixToEulerDeg(matrix).first

    /** Empirically confirmed to be yaw -- see module kdoc. */
    fun extractYawDeg(matrix: FloatArray): Double = rotationMatrixToEulerDeg(matrix).second
}

package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class HeadPoseTest {

    private fun identity4x4(): FloatArray {
        val m = FloatArray(16)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return m
    }

    /** Pure rotation about the X axis, as a flat 16-element row-major 4x4 matrix. */
    private fun rotationMatrixX(angleDeg: Double): FloatArray {
        val a = Math.toRadians(angleDeg)
        val m = identity4x4()
        m[1 * 4 + 1] = cos(a).toFloat()
        m[1 * 4 + 2] = (-sin(a)).toFloat()
        m[2 * 4 + 1] = sin(a).toFloat()
        m[2 * 4 + 2] = cos(a).toFloat()
        return m
    }

    private fun rotationMatrixY(angleDeg: Double): FloatArray {
        val a = Math.toRadians(angleDeg)
        val m = identity4x4()
        m[0 * 4 + 0] = cos(a).toFloat()
        m[0 * 4 + 2] = sin(a).toFloat()
        m[2 * 4 + 0] = (-sin(a)).toFloat()
        m[2 * 4 + 2] = cos(a).toFloat()
        return m
    }

    private fun multiply4x4(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[row * 4 + k] * b[k * 4 + col]
                result[row * 4 + col] = sum
            }
        }
        return result
    }

    @Test
    fun `pure X rotation recovers the known angle`() {
        for (angle in listOf(-30.0, -10.0, 10.0, 30.0)) {
            val (x, y, z) = HeadPose.rotationMatrixToEulerDeg(rotationMatrixX(angle))
            assertTrue("expected x~$angle, got $x", abs(x - angle) < 0.01)
            assertTrue("pure X rotation leaked into y/z: y=$y z=$z", abs(y) < 0.01 && abs(z) < 0.01)
        }
    }

    @Test
    fun `pure Y rotation recovers the known angle`() {
        for (angle in listOf(-30.0, -10.0, 10.0, 30.0)) {
            val (x, y, z) = HeadPose.rotationMatrixToEulerDeg(rotationMatrixY(angle))
            assertTrue("expected y~$angle, got $y", abs(y - angle) < 0.01)
            assertTrue("pure Y rotation leaked into x/z: x=$x z=$z", abs(x) < 0.01 && abs(z) < 0.01)
        }
    }

    @Test
    fun `extractPitchDeg increases monotonically with the chosen axis rotation`() {
        val angles = listOf(-20.0, -10.0, 0.0, 10.0, 20.0, 30.0)
        val pitches = angles.map { HeadPose.extractPitchDeg(rotationMatrixX(it)) }
        for (i in 0 until pitches.size - 1) {
            assertTrue("pitch must be strictly increasing, got $pitches", pitches[i + 1] > pitches[i])
        }
    }

    @Test
    fun `extractPitchDeg is insensitive to the other axes`() {
        for (angle in listOf(-30.0, -15.0, 15.0, 30.0)) {
            val pitch = HeadPose.extractPitchDeg(rotationMatrixY(angle))
            assertTrue("non-pitch axis rotation of $angle deg leaked into pitch: got $pitch", abs(pitch) < 2.5)
        }
    }

    @Test
    fun `combined rotation does not corrupt pitch extraction`() {
        val pitchAngle = 20.0
        val otherAngle = 15.0
        // Ry left-multiplies Rx -- same composition order as head_pose.py's own test.
        val combined = multiply4x4(rotationMatrixY(otherAngle), rotationMatrixX(pitchAngle))
        val (x, y, z) = HeadPose.rotationMatrixToEulerDeg(combined)
        assertTrue("expected pitch axis ~$pitchAngle, got $x", abs(x - pitchAngle) < 0.01)
        assertTrue("expected other axis ~$otherAngle, got $y", abs(y - otherAngle) < 0.01)
        assertTrue("expected no leakage into the third axis, got $z", abs(z) < 0.01)
    }

    @Test
    fun `extractYawDeg increases monotonically with the chosen axis rotation`() {
        val angles = listOf(-20.0, -10.0, 0.0, 10.0, 20.0, 30.0)
        val yaws = angles.map { HeadPose.extractYawDeg(rotationMatrixY(it)) }
        for (i in 0 until yaws.size - 1) {
            assertTrue("yaw must be strictly increasing, got $yaws", yaws[i + 1] > yaws[i])
        }
    }

    @Test
    fun `extractYawDeg is insensitive to pitch rotation`() {
        for (angle in listOf(-30.0, -15.0, 15.0, 30.0)) {
            val yaw = HeadPose.extractYawDeg(rotationMatrixX(angle))
            assertTrue("non-yaw axis rotation of $angle deg leaked into yaw: got $yaw", abs(yaw) < 2.5)
        }
    }
}

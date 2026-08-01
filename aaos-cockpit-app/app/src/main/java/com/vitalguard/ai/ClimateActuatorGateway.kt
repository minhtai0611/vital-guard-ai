package com.vitalguard.ai

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.Intent
import android.util.Log

interface ClimateActuatorGateway {
    fun applyDrowsinessOverride(level: Int)
    fun revertToBaseline()
}

class FakeClimateActuatorGateway : ClimateActuatorGateway {
    var overrideApplied: Boolean = false
    var revertCalled: Boolean = false
    var throwOnApply: Boolean = false
    var throwOnRevert: Boolean = false
    var lastAppliedLevel: Int? = null

    override fun applyDrowsinessOverride(level: Int) {
        if (throwOnApply) throw IllegalStateException("simulated climate gateway failure")
        overrideApplied = true
        lastAppliedLevel = level
    }

    override fun revertToBaseline() {
        if (throwOnRevert) throw IllegalStateException("simulated climate gateway revert failure")
        revertCalled = true
    }
}

/** Real VHAL implementation — logic relocated verbatim from ClimateOverrideReceiver
 * (Task 13), which now only constructs this class for its dormant manual-fallback role. */
class RealClimateActuatorGateway(private val context: Context) : ClimateActuatorGateway {
    private val TAG = "VitalGuardClimate"

    companion object {
        private const val FALLBACK_AREA_ID = 1
        private const val FALLBACK_FAN_SPEED = 7
        private const val BASELINE_FAN_SPEED = 2
        private const val BASELINE_TEMPERATURE_C = 25.0f

        // Level->temperature mapping is Kotlin-owned -- Python only ever sends
        // an escalationLevel int, never a real actuation value (see design doc
        // docs/superpowers/specs/2026-07-31-alert-escalation-design.md Section 3).
        // Level 1 == the pre-escalation baseline behavior (was COLD_TEMPERATURE_C).
        private val TEMPERATURE_C_BY_LEVEL = mapOf(1 to 20.0f, 2 to 17.0f, 3 to 16.0f)
        private const val FALLBACK_TEMPERATURE_C = 20.0f // used if level is somehow outside 1-3

        private fun temperatureCFor(level: Int): Float = TEMPERATURE_C_BY_LEVEL[level] ?: FALLBACK_TEMPERATURE_C
    }

    override fun applyDrowsinessOverride(level: Int) {
        try {
            val car = Car.createCar(context)
            val carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val targetTemperatureC = temperatureCFor(level)

            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_AC_ON) { area ->
                carPropertyManager.setBooleanProperty(VehiclePropertyIds.HVAC_AC_ON, area, true)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_FAN_SPEED) { area ->
                val config = carPropertyManager.getCarPropertyConfig(VehiclePropertyIds.HVAC_FAN_SPEED)
                @Suppress("DEPRECATION")
                val maxFanSpeed = (config?.getMaxValue(area) as? Int) ?: FALLBACK_FAN_SPEED
                carPropertyManager.setIntProperty(VehiclePropertyIds.HVAC_FAN_SPEED, area, maxFanSpeed)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_TEMPERATURE_SET) { area ->
                val config = carPropertyManager.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
                @Suppress("DEPRECATION")
                val minTemperatureC = (config?.getMinValue(area) as? Float)
                val clampedTemperatureC = if (minTemperatureC != null && targetTemperatureC < minTemperatureC) {
                    Log.w(TAG, "Level $level target ${targetTemperatureC}C below config min ${minTemperatureC}C for area $area -- clamping")
                    minTemperatureC
                } else {
                    targetTemperatureC
                }
                carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, clampedTemperatureC)
            }
            Log.d(TAG, "Climate override applied at level $level: AC=ON, Fan=max, Temp=${targetTemperatureC}C")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to apply VHAL climate override at level $level: ${t.message}")
            throw t
        }
    }

    override fun revertToBaseline() {
        try {
            val car = Car.createCar(context)
            val carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_AC_ON) { area ->
                carPropertyManager.setBooleanProperty(VehiclePropertyIds.HVAC_AC_ON, area, false)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_FAN_SPEED) { area ->
                carPropertyManager.setIntProperty(VehiclePropertyIds.HVAC_FAN_SPEED, area, BASELINE_FAN_SPEED)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_TEMPERATURE_SET) { area ->
                carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, BASELINE_TEMPERATURE_C)
            }
            Log.d(TAG, "Climate reverted to baseline: AC=OFF, Fan=$BASELINE_FAN_SPEED, Temp=${BASELINE_TEMPERATURE_C}C")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to revert VHAL climate to baseline: ${t.message}")
            throw t
        }
    }

    private fun forEachSupportedArea(
        carPropertyManager: CarPropertyManager,
        propertyId: Int,
        action: (Int) -> Unit
    ) {
        val areaIds = carPropertyManager.getCarPropertyConfig(propertyId)?.areaIds
            ?: intArrayOf(FALLBACK_AREA_ID)
        for (area in areaIds) {
            try {
                action(area)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to set property 0x${propertyId.toString(16)} for area 0x${area.toString(16)}: ${t.message}")
            }
        }
    }
}

/** Sends an internal Broadcast Intent to the bridge-service priv-app instead of
 * calling CarPropertyManager directly — CLAUDE.md's Option C safety net. If the
 * bridge apk is missing/crashed, this call still returns normally (fire-and-forget
 * broadcast); it does not throw, so it cannot itself trip the controller's
 * OVERRIDE_FAILED path — bridge health must be verified separately via logcat. */
class BridgeClimateActuatorGateway(private val context: Context) : ClimateActuatorGateway {
    override fun applyDrowsinessOverride(level: Int) {
        context.sendBroadcast(Intent("com.vitalguard.ai.bridge.APPLY_HVAC_OVERRIDE"))
    }

    override fun revertToBaseline() {
        context.sendBroadcast(Intent("com.vitalguard.ai.bridge.REVERT_HVAC_BASELINE"))
    }
}

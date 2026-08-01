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
class RealClimateActuatorGateway(
    private val context: Context,
    private val alertPreferencesStore: AlertPreferencesStore,
) : ClimateActuatorGateway {
    private val TAG = "VitalGuardClimate"

    companion object {
        private const val FALLBACK_AREA_ID = 1
        private const val BASELINE_FAN_SPEED = 2
        private const val BASELINE_TEMPERATURE_C = 25.0f
    }

    // `level` (from the alert-escalation feature) is intentionally NOT used to
    // pick fan/temp values here -- per product decision, the driver's
    // climateIntensity preference alone determines response strength, never
    // silently overridden by how long CRITICAL has persisted. `level` still
    // drives WHEN this is called (DrowsinessController only re-invokes this
    // when the level changes) and is kept in the log line for diagnostics
    // only. Voice still escalates its urgency/copy independent of this
    // decision -- see
    // docs/superpowers/specs/2026-07-31-alert-preferences-parked-suppression-design.md.
    override fun applyDrowsinessOverride(level: Int) {
        try {
            val car = Car.createCar(context)
            val carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val intensity = alertPreferencesStore.get().climateIntensity

            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_AC_ON) { area ->
                carPropertyManager.setBooleanProperty(VehiclePropertyIds.HVAC_AC_ON, area, true)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_FAN_SPEED) { area ->
                val targetFan = IntensityMapping.fanSpeedFor(intensity)
                val config = carPropertyManager.getCarPropertyConfig(VehiclePropertyIds.HVAC_FAN_SPEED)
                val clamped = clampFanSpeed(targetFan, config, area)
                carPropertyManager.setIntProperty(VehiclePropertyIds.HVAC_FAN_SPEED, area, clamped)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_TEMPERATURE_SET) { area ->
                val targetTemp = IntensityMapping.temperatureCFor(intensity)
                val config = carPropertyManager.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
                val clamped = clampTemperature(targetTemp, config, area)
                carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, clamped)
            }
            Log.d(TAG, "Climate override applied at level $level, intensity=$intensity")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to apply VHAL climate override at level $level: ${t.message}")
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun clampFanSpeed(target: Int, config: android.car.hardware.CarPropertyConfig<*>?, area: Int): Int {
        if (config == null) {
            Log.w(TAG, "HVAC_FAN_SPEED clamp skipped — config unavailable, using raw intensity value $target")
            return target
        }
        val min = config.getMinValue(area) as? Int ?: return target
        val max = config.getMaxValue(area) as? Int ?: return target
        val clamped = target.coerceIn(min, max)
        if (clamped != target) Log.w(TAG, "HVAC_FAN_SPEED clamped $target -> $clamped (range [$min,$max])")
        return clamped
    }

    @Suppress("DEPRECATION")
    private fun clampTemperature(target: Float, config: android.car.hardware.CarPropertyConfig<*>?, area: Int): Float {
        if (config == null) {
            Log.w(TAG, "HVAC_TEMPERATURE_SET clamp skipped — config unavailable, using raw intensity value $target")
            return target
        }
        val min = config.getMinValue(area) as? Float ?: return target
        val max = config.getMaxValue(area) as? Float ?: return target
        val clamped = target.coerceIn(min, max)
        if (clamped != target) Log.w(TAG, "HVAC_TEMPERATURE_SET clamped $target -> $clamped (range [$min,$max])")
        return clamped
    }

    override fun revertToBaseline() {
        // unchanged from before -- baseline is fixed, never depends on climateIntensity or level
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

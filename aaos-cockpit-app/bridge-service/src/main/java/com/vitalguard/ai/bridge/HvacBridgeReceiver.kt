package com.vitalguard.ai.bridge

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** The only place in the bridge-service module that touches CarPropertyManager. Logic is
 * kept in sync with RealClimateActuatorGateway (aaos-cockpit-app/app/.../ClimateActuatorGateway.kt)
 * — see root CLAUDE.md's "HVAC Permission Risk" section, Option C: this priv-app isolates the
 * HVAC-write path so a broken bridge only needs this one small component fixed. */
class HvacBridgeReceiver : BroadcastReceiver() {
    private val TAG = "VitalGuardHvacBridge"

    companion object {
        const val ACTION_APPLY_OVERRIDE = "com.vitalguard.ai.bridge.APPLY_HVAC_OVERRIDE"
        const val ACTION_REVERT_BASELINE = "com.vitalguard.ai.bridge.REVERT_HVAC_BASELINE"
        private const val FALLBACK_AREA_ID = 1
        private const val FALLBACK_FAN_SPEED = 7
        private const val COLD_TEMPERATURE_C = 20.0f
        private const val BASELINE_FAN_SPEED = 2
        private const val BASELINE_TEMPERATURE_C = 25.0f
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_APPLY_OVERRIDE -> applyOverride(context)
            ACTION_REVERT_BASELINE -> revertBaseline(context)
        }
    }

    private fun applyOverride(context: Context) {
        try {
            val carPropertyManager = carPropertyManager(context)
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
                carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, COLD_TEMPERATURE_C)
            }
            Log.d(TAG, "Bridge applied climate override: AC=ON, Fan=max, Temp=${COLD_TEMPERATURE_C}C")
        } catch (t: Throwable) {
            Log.e(TAG, "Bridge failed to apply VHAL climate override: ${t.message}")
        }
    }

    private fun revertBaseline(context: Context) {
        try {
            val carPropertyManager = carPropertyManager(context)
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_AC_ON) { area ->
                carPropertyManager.setBooleanProperty(VehiclePropertyIds.HVAC_AC_ON, area, false)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_FAN_SPEED) { area ->
                carPropertyManager.setIntProperty(VehiclePropertyIds.HVAC_FAN_SPEED, area, BASELINE_FAN_SPEED)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_TEMPERATURE_SET) { area ->
                carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, BASELINE_TEMPERATURE_C)
            }
            Log.d(TAG, "Bridge reverted climate to baseline")
        } catch (t: Throwable) {
            Log.e(TAG, "Bridge failed to revert VHAL climate baseline: ${t.message}")
        }
    }

    private fun carPropertyManager(context: Context): CarPropertyManager {
        val car = Car.createCar(context)
        return car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
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
                Log.w(TAG, "Bridge failed to set property 0x${propertyId.toString(16)} for area 0x${area.toString(16)}: ${t.message}")
            }
        }
    }
}

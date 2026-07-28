package com.vitalguard.ai

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

interface ClimateActuatorGateway {
    fun applyDrowsinessOverride()
    fun revertToBaseline()
}

class FakeClimateActuatorGateway : ClimateActuatorGateway {
    var overrideApplied: Boolean = false
    var revertCalled: Boolean = false
    var throwOnApply: Boolean = false
    var throwOnRevert: Boolean = false

    override fun applyDrowsinessOverride() {
        if (throwOnApply) throw IllegalStateException("simulated climate gateway failure")
        overrideApplied = true
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
        private const val COLD_TEMPERATURE_C = 20.0f
        private const val BASELINE_FAN_SPEED = 2
        private const val BASELINE_TEMPERATURE_C = 25.0f
    }

    override fun applyDrowsinessOverride() {
        try {
            val car = Car.createCar(context)
            val carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

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
            Log.d(TAG, "Climate override applied: AC=ON, Fan=max, Temp=${COLD_TEMPERATURE_C}C")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to apply VHAL climate override: ${t.message}")
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

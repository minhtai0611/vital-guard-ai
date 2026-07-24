package com.vitalguard.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.car.Car
import android.car.hardware.property.CarPropertyManager
import android.car.VehiclePropertyIds
import android.util.Log

class ClimateOverrideReceiver(
    private val voiceAssistant: VoiceEmergencyAssistant? = null
) : BroadcastReceiver() {
    private val TAG = "VitalGuardClimate"

    companion object {
        const val ACTION_TRIGGER_ALERT = "com.vitalguard.ai.TRIGGER_ALERT"
        private const val FALLBACK_AREA_ID = 1
        private const val FALLBACK_FAN_SPEED = 7
        private const val COLD_TEMPERATURE_C = 20.0f
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TRIGGER_ALERT) {
            val score = intent.getFloatExtra("drowsiness_score", 0f)
            Log.w(TAG, "🚨 DMS Trigger Received! Drowsiness Score: $score")

            // Kích hoạt ngay lập tức cơ chế can thiệp đa giác quan
            overrideVehicleClimate(context)
            voiceAssistant?.executeVoiceIntervention()
        }
    }

    private fun overrideVehicleClimate(context: Context) {
        try {
            // Khởi tạo Car API kết nối xuống VHAL
            val car = Car.createCar(context)
            val carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

            Log.i(TAG, "⚡ Overriding VHAL Climate Control properties...")

            // Area layout (which zone IDs each property accepts) is vehicle-specific — querying
            // it beats hardcoding one, since a fixed areaId can be entirely invalid on a given
            // vehicle config (confirmed on-device: area 1 is rejected for these exact
            // properties on the AOSP reference car; the real areas are 0x75/0x31/0x44).
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_AC_ON) { area ->
                carPropertyManager.setBooleanProperty(VehiclePropertyIds.HVAC_AC_ON, area, true)
            }

            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_FAN_SPEED) { area ->
                val config = carPropertyManager.getCarPropertyConfig(VehiclePropertyIds.HVAC_FAN_SPEED)
                // getAreaIdConfig() would be the non-deprecated replacement, but it's missing from
                // this exact device's on-device android.car.jar despite compiling fine against the
                // API 34 SDK stub (confirmed on-device: NoSuchMethodError, crashed the service).
                // getMaxValue(Int) is deprecated but is what's actually present at runtime here.
                @Suppress("DEPRECATION")
                val maxFanSpeed = (config?.getMaxValue(area) as? Int) ?: FALLBACK_FAN_SPEED
                carPropertyManager.setIntProperty(VehiclePropertyIds.HVAC_FAN_SPEED, area, maxFanSpeed)
            }

            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_TEMPERATURE_SET) { area ->
                carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, COLD_TEMPERATURE_C)
            }

            Log.d(TAG, "❄️ VHAL Climate Override Executed: AC=ON, Fan=max, Temp=${COLD_TEMPERATURE_C}°C")

        } catch (t: Throwable) {
            // Car/VHAL API surface varies by vehicle and by car-lib version even within the same
            // platform API level (confirmed on-device: a missing method surfaced as
            // NoSuchMethodError, which is an Error, not an Exception — catching only Exception
            // let it crash the foreground service instead of degrading gracefully).
            Log.e(TAG, "❌ Failed to set VHAL properties: ${t.message}")
        }
    }

    /** Applies [action] to every area this vehicle actually configured for [propertyId], so a
     * wrong/missing zone assumption can't silently no-op the whole property. */
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

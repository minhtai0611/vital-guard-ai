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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.vitalguard.ai.TRIGGER_ALERT") {
            val score = intent.getDoubleExtra("drowsiness_score", 0.0)
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

            // Lấy ID vùng lái xe của Driver (thường mặc định là 1 hoặc qua getAreaIds)
            val driverAreaId = 1

            Log.i(TAG, "⚡ Overriding VHAL Climate Control properties...")

            // 1. Bật máy lạnh AC
            carPropertyManager.setBooleanProperty(
                VehiclePropertyIds.HVAC_AC_ON,
                driverAreaId,
                true
            )

            // 2. Tăng quạt gió lên mức tối đa (Mức 8)
            carPropertyManager.setIntProperty(
                VehiclePropertyIds.HVAC_FAN_SPEED,
                driverAreaId,
                8
            )

            // 3. Hạ nhiệt độ xuống 20.0°C để kích thích thần kinh da đầu lập tức
            carPropertyManager.setFloatProperty(
                VehiclePropertyIds.HVAC_TEMPERATURE_SET,
                driverAreaId,
                20.0f
            )

            Log.d(TAG, "❄️ VHAL Climate Override Executed: AC=ON, Fan=8, Temp=20°C")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to set VHAL properties: ${e.message}")
        }
    }
}

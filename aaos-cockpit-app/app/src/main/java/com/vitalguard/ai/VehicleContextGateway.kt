package com.vitalguard.ai

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

interface VehicleContextGateway {
    fun getCurrentSpeedKmh(): Float?   // null if the property is unreadable -- never fabricate
}

class FakeVehicleContextGateway : VehicleContextGateway {
    var speedKmh: Float? = 0f
    var throwOnGet: Boolean = false

    override fun getCurrentSpeedKmh(): Float? {
        if (throwOnGet) throw IllegalStateException("simulated vehicle context gateway failure")
        return speedKmh
    }
}

/** Real VHAL implementation. Connects once and keeps the Car reference --
 * polling this every second for the life of the app would otherwise leak a
 * binder connection to Car Service on every tick (see design doc). Call
 * disconnect() from the owning Service's onDestroy(). */
class RealVehicleContextGateway(context: Context) : VehicleContextGateway {
    private val TAG = "VitalGuardVehicleContext"
    private val car: Car = Car.createCar(context)
    private val carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

    override fun getCurrentSpeedKmh(): Float? = try {
        carPropertyManager
            .getProperty(Float::class.java, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
            .value * 3.6f // VHAL reports m/s
    } catch (t: Throwable) {
        Log.w(TAG, "PERF_VEHICLE_SPEED unreadable: ${t.message}")
        null
    }

    fun disconnect() = car.disconnect()
}

package com.meta.wearable.dat.externalsampleapps.cameraaccess.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class HeadingReading(
    val headingDegrees: Float,
    val accuracy: Int,
    val timestampMs: Long = SystemClock.elapsedRealtime(),
)

class MagneticHeadingService(
    private val sensorManager: SensorManager,
) : AutoCloseable {
    companion object {
        private const val SMOOTHING_ALPHA = 0.2f
    }

    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _heading = MutableStateFlow<HeadingReading?>(null)
    val heading: StateFlow<HeadingReading?> = _heading.asStateFlow()

    private var isStarted = false
    private var hasAccel = false
    private var hasMag = false
    private var filteredHeading: Float? = null
    private var currentAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val listener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        copyValues(event.values, gravity)
                        hasAccel = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        copyValues(event.values, geomagnetic)
                        hasMag = true
                    }
                    else -> return
                }
                updateHeading()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                currentAccuracy = accuracy
            }
        }

    fun start() {
        if (isStarted) return
        val accel = accelerometer ?: return
        val mag = magnetometer ?: return
        hasAccel = false
        hasMag = false
        filteredHeading = null
        sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(listener, mag, SensorManager.SENSOR_DELAY_GAME)
        isStarted = true
    }

    fun stop() {
        if (!isStarted) return
        sensorManager.unregisterListener(listener)
        isStarted = false
    }

    override fun close() {
        stop()
    }

    private fun updateHeading() {
        if (!hasAccel || !hasMag) return
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)
        if (!success) return
        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuthDeg = normalize360(Math.toDegrees(orientation[0].toDouble()).toFloat())
        val smoothed = smoothHeading(azimuthDeg)
        _heading.value = HeadingReading(headingDegrees = smoothed, accuracy = currentAccuracy)
    }

    private fun smoothHeading(rawHeading: Float): Float {
        val previous = filteredHeading
        if (previous == null) {
            filteredHeading = rawHeading
            return rawHeading
        }
        val delta = shortestSignedDeltaDegrees(previous, rawHeading)
        val next = normalize360(previous + (delta * SMOOTHING_ALPHA))
        filteredHeading = next
        return next
    }

    private fun shortestSignedDeltaDegrees(from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        if (abs(delta) < 0.001f) return 0f
        return delta
    }

    private fun normalize360(value: Float): Float {
        var out = value % 360f
        if (out < 0f) out += 360f
        return out
    }

    private fun copyValues(src: FloatArray, dest: FloatArray) {
        val count = minOf(src.size, dest.size)
        for (i in 0 until count) {
            dest[i] = src[i]
        }
    }
}

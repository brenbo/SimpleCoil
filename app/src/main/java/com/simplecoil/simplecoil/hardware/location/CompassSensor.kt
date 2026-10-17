/*
 * Copyright (C) 2026 Ethan Yonker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.simplecoil.simplecoil.hardware.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.view.Surface
import android.view.WindowManager

/**
 * Which way the phone's top points, in degrees from true north, for the heading-up map. Uses
 * the rotation vector sensor (or, on phones without a gyroscope, the magnetometer-only one), and
 * corrects magnetic north to true north once it knows where we are.
 */
class CompassSensor(context: Context) : SensorEventListener {

    fun interface Listener {
        fun onAzimuth(degreesFromTrueNorth: Double)
    }

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var listener: Listener? = null

    /** Degrees to add to magnetic north; 0 until [setLocation]. */
    @Volatile
    private var declination = 0f

    /** False on phones with no usable compass: the map then stays north-up. */
    val isAvailable: Boolean get() = sensor != null

    fun start(listener: Listener) {
        val s = sensor ?: return
        this.listener = listener
        // About 50 readings a second: the map turns as smoothly as the phone does
        sensors?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensors?.unregisterListener(this)
        listener = null
    }

    /** Where we are, for the difference between magnetic and true north. */
    fun setLocation(location: Location) {
        declination = GeomagneticField(location.latitude.toFloat(), location.longitude.toFloat(),
            location.altitude.toFloat(), System.currentTimeMillis()).declination
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // The screen's own "up", whichever way the display is turned
        @Suppress("DEPRECATION") // WindowManager.defaultDisplay: Context.display needs API 30
        val (axisX, axisY) = when (windowManager?.defaultDisplay?.rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val magnetic = Math.toDegrees(orientation[0].toDouble())
        listener?.onAzimuth(magnetic + declination)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

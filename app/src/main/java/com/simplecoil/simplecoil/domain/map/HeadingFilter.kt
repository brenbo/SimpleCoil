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

package com.simplecoil.simplecoil.domain.map

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns jumpy compass readings into a steady heading for a heading-up map (map design: Compass
 * rotation). Readings are smoothed on the circle, so 359° and 1° average to 0°, not 180°.
 *
 * The smoothing adapts: a reading a degree or two off is mostly jitter and moves the heading a
 * little, while one far off means the player is turning, and the heading follows almost at once.
 * So the map holds still when the phone does, and keeps up when it turns.
 *
 * While the player moves faster than walking pace, the GPS direction of travel wins over the
 * compass, which only says where the phone points. Not thread-safe; feed it from one thread.
 */
class HeadingFilter(
    /** How much of a reading to take when it barely differs, 0..1; lower is steadier. */
    private val smoothing: Double = DEFAULT_SMOOTHING,
    /** Above this speed (m/s) the GPS course is used instead of the compass. */
    private val courseSpeedMps: Float = COURSE_SPEED_MPS,
    /** How long a GPS course stays in charge after the last fast fix. */
    private val courseHoldMillis: Long = COURSE_HOLD_MILLIS,
) {
    private var x = 0.0
    private var y = 0.0
    private var hasValue = false
    private var courseUntil = Long.MIN_VALUE

    /** The smoothed heading, degrees clockwise from true north in 0..360; null before any reading. */
    val heading: Double?
        get() = if (hasValue) normalize(Math.toDegrees(atan2(y, x))) else null

    /** A compass reading, degrees from true north. Ignored while a GPS course is in charge. */
    fun onCompass(azimuthDegrees: Double, nowMillis: Long): Double? {
        if (nowMillis < courseUntil) return heading
        val current = heading
        val weight = if (current == null) 1.0 else {
            (smoothing + difference(current, azimuthDegrees) / FOLLOW_DEGREES).coerceIn(smoothing, MAX_WEIGHT)
        }
        blend(azimuthDegrees, weight)
        return heading
    }

    /** A GPS fix's direction of travel; only used when [speedMps] is above walking pace. */
    fun onCourse(bearingDegrees: Double, speedMps: Float, nowMillis: Long): Double? {
        if (speedMps < courseSpeedMps) return heading
        courseUntil = nowMillis + courseHoldMillis
        // GPS fixes come about once a second, so take more of each than of a compass reading
        blend(bearingDegrees, COURSE_SMOOTHING)
        return heading
    }

    fun reset() {
        hasValue = false
        courseUntil = Long.MIN_VALUE
    }

    private fun blend(degrees: Double, weight: Double) {
        val r = Math.toRadians(degrees)
        if (!hasValue) {
            x = cos(r)
            y = sin(r)
            hasValue = true
            return
        }
        x += (cos(r) - x) * weight
        y += (sin(r) - y) * weight
    }

    companion object {
        const val DEFAULT_SMOOTHING = 0.12

        /** A reading this many degrees off is followed (nearly) all the way at once. */
        const val FOLLOW_DEGREES = 25.0
        const val MAX_WEIGHT = 0.9
        const val COURSE_SMOOTHING = 0.5

        /** A brisk walk; anything faster is running. */
        const val COURSE_SPEED_MPS = 2.5f
        const val COURSE_HOLD_MILLIS = 3_000L

        /** [degrees] in 0..360. */
        fun normalize(degrees: Double): Double {
            val d = degrees % 360.0
            return if (d < 0) d + 360.0 else d
        }

        /** The smaller angle between two headings, 0..180. */
        fun difference(a: Double, b: Double): Double {
            val d = abs(normalize(a) - normalize(b))
            return if (d > 180) 360 - d else d
        }
    }
}

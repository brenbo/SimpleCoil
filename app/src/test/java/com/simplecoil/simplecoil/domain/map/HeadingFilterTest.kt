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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingFilterTest {

    @Test
    fun noHeadingBeforeAnyReading() {
        assertNull(HeadingFilter().heading)
    }

    @Test
    fun theFirstReadingIsTakenAsIs() {
        assertEquals(42.0, HeadingFilter().onCompass(42.0, 0)!!, 1e-9)
    }

    @Test
    fun negativeAzimuthsBecomeCompassDegrees() {
        assertEquals(270.0, HeadingFilter().onCompass(-90.0, 0)!!, 1e-9)
    }

    @Test
    fun smoothingCrossesNorthTheShortWay() {
        val filter = HeadingFilter()
        filter.onCompass(350.0, 0)
        repeat(50) { filter.onCompass(10.0, 0) }
        // Settles near 10°, never swinging round through 180°
        assertEquals(0.0, HeadingFilter.difference(filter.heading!!, 10.0), 0.5)
        val mid = HeadingFilter().apply { onCompass(350.0, 0) }.onCompass(10.0, 0)!!
        assertTrue("one step from 350 toward 10 stays near north, got $mid", HeadingFilter.difference(mid, 0.0) < 10.0)
    }

    @Test
    fun smallJitterIsSteadied() {
        val filter = HeadingFilter()
        filter.onCompass(90.0, 0)
        val after = filter.onCompass(92.0, 0)!!
        // A reading 2° off moves it only a fraction of that
        assertTrue("got $after", after > 90.0 && after < 90.5)
    }

    @Test
    fun aRealTurnIsFollowedAtOnce() {
        val filter = HeadingFilter()
        filter.onCompass(90.0, 0)
        val after = filter.onCompass(130.0, 0)!!
        assertTrue("got $after", after > 125.0)
    }

    @Test
    fun keepsUpWithANormalTurn() {
        // Turning a quarter turn a second, with the compass at 50 readings a second
        val filter = HeadingFilter()
        var lag = 0.0
        for (i in 0..100) {
            val actual = i * 90.0 / 50
            val shown = filter.onCompass(actual, i * 20L)!!
            if (i > 10) lag = maxOf(lag, HeadingFilter.difference(actual, shown))
        }
        assertTrue("lagged $lag°", lag < 10.0)
    }

    @Test
    fun aStillPhoneHoldsStill() {
        // ±1.5° of sensor noise around 45°
        val filter = HeadingFilter()
        var lowest = 360.0
        var highest = 0.0
        for (i in 0..200) {
            val shown = filter.onCompass(45.0 + if (i % 2 == 0) 1.5 else -1.5, i * 20L)!!
            if (i > 20) {
                lowest = minOf(lowest, shown)
                highest = maxOf(highest, shown)
            }
        }
        assertTrue("wobbled ${highest - lowest}°", highest - lowest < 1.0)
    }

    @Test
    fun runningUsesTheGpsCourseOverTheCompass() {
        val filter = HeadingFilter()
        filter.onCompass(0.0, 0)
        repeat(10) { filter.onCourse(180.0, 4f, 1_000L) }
        assertEquals(180.0, filter.heading!!, 1.0)
        // The phone pointing elsewhere doesn't matter while running
        filter.onCompass(90.0, 2_000L)
        assertEquals(180.0, filter.heading!!, 1.0)
    }

    @Test
    fun walkingIgnoresTheGpsCourse() {
        val filter = HeadingFilter()
        filter.onCompass(10.0, 0)
        filter.onCourse(200.0, 1f, 0)
        assertEquals(10.0, filter.heading!!, 1e-9)
    }

    @Test
    fun theCompassTakesOverAgainAfterStopping() {
        val filter = HeadingFilter()
        filter.onCourse(180.0, 4f, 0)
        filter.onCompass(90.0, HeadingFilter.COURSE_HOLD_MILLIS + 1)
        assertTrue(filter.heading!! < 180.0 && filter.heading!! > 90.0)
    }

    @Test
    fun resetForgets() {
        val filter = HeadingFilter()
        filter.onCompass(45.0, 0)
        filter.reset()
        assertNull(filter.heading)
    }

    @Test
    fun differenceIsTheSmallerAngle() {
        assertEquals(20.0, HeadingFilter.difference(350.0, 10.0), 1e-9)
        assertEquals(180.0, HeadingFilter.difference(0.0, 180.0), 1e-9)
        assertEquals(5.0, HeadingFilter.difference(-5.0, 0.0), 1e-9)
        assertEquals(355.0, HeadingFilter.normalize(-5.0), 1e-9)
        assertEquals(10.0, HeadingFilter.normalize(730.0), 1e-9)
    }
}

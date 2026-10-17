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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BorderTest {

    private val center = LatLon(39.0, -77.0)
    private fun at(northMeters: Double, eastMeters: Double) = GeoMath.offset(center, northMeters, eastMeters)

    /** A 200 m square with a 100 m bite out of its top-right corner: an L shape. */
    private val lShape = Border.Polygon(listOf(at(-100.0, -100.0), at(-100.0, 100.0), at(0.0, 100.0), at(0.0, 0.0), at(100.0, 0.0), at(100.0, -100.0)))

    @Test
    fun circleContainsAndMeasures() {
        val circle = Border.Circle(center, 100.0)
        assertTrue(circle.contains(at(60.0, 60.0)))
        assertFalse(circle.contains(at(80.0, 80.0)))
        assertEquals(30.0, circle.distanceToEdgeMeters(at(70.0, 0.0)), 0.1)
        assertEquals(20.0, circle.distanceToEdgeMeters(at(0.0, -120.0)), 0.1)
    }

    @Test
    fun circleRingGoesAllTheWayRound() {
        val ring = Border.Circle(center, 100.0).ring()
        assertEquals(Border.CIRCLE_SEGMENTS + 1, ring.size)
        assertEquals(ring.first().latitude, ring.last().latitude, 1e-9)
        for (p in ring) assertEquals(100.0, GeoMath.distanceMeters(center, p), 0.2)
    }

    @Test
    fun concavePolygonContains() {
        assertTrue(lShape.contains(at(-50.0, 50.0)))
        assertTrue(lShape.contains(at(50.0, -50.0)))
        // The bite is outside
        assertFalse(lShape.contains(at(50.0, 50.0)))
        assertFalse(lShape.contains(at(150.0, 0.0)))
    }

    @Test
    fun polygonDistanceIsToTheNearestEdge() {
        assertEquals(10.0, lShape.distanceToEdgeMeters(at(50.0, 10.0)), 0.1) // inside the bite, 10 m from the inner edge
        assertEquals(25.0, lShape.distanceToEdgeMeters(at(-125.0, 0.0)), 0.1) // below the bottom edge
        assertEquals(Math.hypot(20.0, 20.0), lShape.distanceToEdgeMeters(at(-120.0, 120.0)), 0.1) // past a corner
    }

    @Test
    fun polygonRingCloses() {
        val ring = lShape.ring()
        assertEquals(7, ring.size)
        assertEquals(ring.first(), ring.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPolygonNeedsThreeCorners() {
        Border.Polygon(listOf(center, at(10.0, 0.0)))
    }

    @Test
    fun aWalkSimplifiesToItsCorners() {
        // A 100 m square walked a metre at a time, with a little sideways wobble
        val walk = ArrayList<LatLon>()
        for (i in 0 until 100) walk += at(0.0 + (i % 3) * 0.3, i.toDouble())
        for (i in 0 until 100) walk += at(i.toDouble(), 100.0 + (i % 3) * 0.3)
        for (i in 0 until 100) walk += at(100.0 + (i % 3) * 0.3, 100.0 - i)
        for (i in 0..100) walk += at(100.0 - i, (i % 3) * 0.3)
        val corners = PathSimplifier.simplify(walk, 3.0)
        assertTrue("got ${corners.size} corners", corners.size in 5..6)
        val square = Border.Polygon(corners.dropLast(1))
        assertTrue(square.contains(at(50.0, 50.0)))
        assertFalse(square.contains(at(110.0, 50.0)))
    }

    @Test
    fun configRoundTrips() {
        val rules = BorderRules(countdownSeconds = 15, penalty = OutOfBoundsPenalty.LOSE_HEALTH, healthPerTick = 2, tickSeconds = 3,
            showOnlyWhenNear = true, nearDistanceMeters = 35.0, graceSeconds = 5, leaveMarginMeters = 4.0, accuracyGateMeters = 20.0, edgeBufferMeters = 12.0,
            units = DistanceUnits.METERS)
        for (border in listOf(Border.Circle(center, 120.0), lShape, null)) {
            val config = FieldConfig("Johnson Park", border, rules)
            val back = FieldConfig.fromBytes(config.toBytes())
            assertEquals(config, back)
            assertEquals(config.id, back.id)
        }
    }

    @Test
    fun theIdFollowsTheContent() {
        val a = FieldConfig("Park", Border.Circle(center, 120.0))
        assertEquals(a.id, FieldConfig("Park", Border.Circle(center, 120.0)).id)
        assertNotEquals(a.id, a.copy(border = Border.Circle(center, 121.0)).id)
        assertNotEquals(a.id, a.copy(rules = a.rules.copy(countdownSeconds = 11)).id)
    }

    @Test
    fun aNewerBorderTypeIsIgnored() {
        val json = JSONObject().put("name", "x").put("border", JSONObject().put("type", "hexagon"))
        assertNull(FieldConfig.fromJson(json).border)
    }

    @Test
    fun rulesAreClampedToWhatTheServerOffers() {
        val wild = BorderRules(countdownSeconds = 500, healthPerTick = 0, tickSeconds = 99, graceSeconds = -3,
            leaveMarginMeters = 50.0, accuracyGateMeters = 2.0, edgeBufferMeters = 100.0).clamped()
        assertEquals(60, wild.countdownSeconds)
        assertEquals(1, wild.healthPerTick)
        assertEquals(10, wild.tickSeconds)
        assertEquals(0, wild.graceSeconds)
        assertEquals(10.0, wild.leaveMarginMeters, 1e-9)
        assertEquals(5.0, wild.accuracyGateMeters, 1e-9)
        assertEquals(30.0, wild.edgeBufferMeters, 1e-9)
        assertEquals(0.0, BorderRules(accuracyGateMeters = 0.0).clamped().accuracyGateMeters, 1e-9)
    }

    @Test
    fun missingRulesGetTheDefaults() {
        val rules = BorderRules.fromJson(JSONObject().put("countdown", 20))
        assertEquals(20, rules.countdownSeconds)
        assertEquals(OutOfBoundsPenalty.DISABLE_BLASTER, rules.penalty)
        assertEquals(3, rules.graceSeconds)
    }

    @Test
    fun units() {
        assertEquals("33 ft", DistanceUnits.FEET.format(10.0))
        assertEquals("10 m", DistanceUnits.METERS.format(10.0))
        assertEquals(3.048, DistanceUnits.FEET.toMeters(10.0), 0.001)
        assertEquals(10.0, DistanceUnits.METERS.toMeters(10.0), 1e-9)
        assertEquals(DistanceUnits.FEET, DistanceUnits.defaultFor(Locale.US))
        assertEquals(DistanceUnits.METERS, DistanceUnits.defaultFor(Locale.UK))
    }
}

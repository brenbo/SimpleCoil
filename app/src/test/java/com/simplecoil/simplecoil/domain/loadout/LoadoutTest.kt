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

package com.simplecoil.simplecoil.domain.loadout

import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.GeoMath
import com.simplecoil.simplecoil.domain.map.LatLon
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LoadoutTest {

    private val center = LatLon(39.0, -77.0)
    private fun at(northMeters: Double, eastMeters: Double) = GeoMath.offset(center, northMeters, eastMeters)

    @Test
    fun rulesRoundTrip() {
        val rules = LoadoutRules(
            ammo = AmmoMode.SCAVENGER, startingReserve = 7, refillAtSpawn = false, scavengeSeconds = 45, scavengeMagazines = 3,
            dropsEnabled = true, dropKinds = setOf(DropKind.UAV, DropKind.BURST), dropIntervalMinutes = 4, dropWarningSeconds = 10,
            claimSeconds = 3, dropLastsMinutes = 2, claimRadiusMeters = 12.0, dropsNearestTeamOnly = true, overshieldHits = 5,
            uavSeconds = 20, killstreaks = true, uavKills = 2, fullAutoKills = 4, trackShots = false, maxShotMeters = 100.0,
            longestShotAward = false,
        )
        assertEquals(rules, LoadoutRules.fromJson(JSONObject(rules.toJson().toString())))
    }

    @Test
    fun missingOrUnknownValuesFallBack() {
        assertEquals(LoadoutRules(), LoadoutRules.fromJson(null))
        assertEquals(LoadoutRules(), LoadoutRules.fromJson(JSONObject()))
        val odd = LoadoutRules.fromJson(JSONObject().put("ammo", "infinite").put("dropKinds", JSONArray().put("nuke").put("medkit")))
        assertEquals(AmmoMode.UNLIMITED, odd.ammo)
        assertEquals(setOf(DropKind.MEDKIT), odd.dropKinds)
    }

    @Test
    fun valuesAreKeptInRange() {
        val rules = LoadoutRules(startingReserve = 0, claimRadiusMeters = 100.0, maxShotMeters = 1.0, uavKills = -3).clamped()
        assertEquals(1, rules.startingReserve)
        assertEquals(20.0, rules.claimRadiusMeters, 0.0)
        assertEquals(15.0, rules.maxShotMeters, 0.0)
        assertEquals(0, rules.uavKills)
    }

    @Test
    fun dropsRoundTripAndSkipUnknownKinds() {
        val drops = listOf(Drop("d1", DropKind.SUPPLY, center, 1_000, 2_000), Drop("d2", DropKind.SCAVENGE, at(10.0, 0.0), 5, 6))
        val json = Drop.listToJson(drops)
        assertEquals(drops, Drop.listFromJson(JSONArray(json.toString())))
        json.put(JSONArray().put("d3").put("nuke").put(1.0).put(2.0).put(3).put(4))
        assertEquals(drops, Drop.listFromJson(json))
        assertEquals(emptyList<Drop>(), Drop.listFromJson(null))
    }

    @Test
    fun theFieldCarriesItsLoadoutAndDrops() {
        val config = FieldConfig(loadout = LoadoutRules(ammo = AmmoMode.LIMITED), drops = listOf(Drop("d1", DropKind.UAV, center, 1, 2)))
        val back = FieldConfig.fromJson(config.toJson())
        assertEquals(config.loadout, back.loadout)
        assertEquals(config.drops, back.drops)
    }

    // The live field

    private val rules = LoadoutRules(dropsEnabled = true, dropWarningSeconds = 30, dropLastsMinutes = 2, claimRadiusMeters = 8.0, scavengeSeconds = 60)
    private val live = LiveField().apply { start(FieldConfig(border = Border.Circle(center, 100.0), loadout = rules)) }

    @Test
    fun aDropLandsAfterTheWarningAndThenExpires() {
        val drop = live.drop(DropKind.AMMO, center, 0)!!
        assertEquals(30_000L, drop.landsAt)
        assertEquals(30_000L + 120_000L, drop.expiresAt)
        assertEquals(listOf(drop), live.config()!!.drops)

        // Not before it lands
        assertNull(live.claim(drop.id, 1, emptyMap(), 10_000))
        assertFalse(live.prune(100_000))
        assertTrue(live.prune(150_000))
        assertTrue(live.config()!!.drops.isEmpty())
    }

    @Test
    fun onlyOnePlayerGetsADrop() {
        val drop = live.drop(DropKind.SUPPLY, center, 0)!!
        assertEquals(DropKind.SUPPLY, live.claim(drop.id, 1, emptyMap(), 40_000))
        assertNull(live.claim(drop.id, 2, emptyMap(), 40_001))
    }

    @Test
    fun anEnemyOnTheDropContestsIt() {
        val drop = live.drop(DropKind.MEDKIT, center, 0)!!
        val enemyOnIt = mapOf(1 to listOf(at(1.0, 0.0)), 2 to listOf(at(3.0, 0.0)))
        assertNull(live.claim(drop.id, 1, enemyOnIt, 40_000))
        // A teammate nearby doesn't, nor does an enemy further off
        val clear = mapOf(1 to listOf(at(1.0, 0.0)), 2 to listOf(at(30.0, 0.0)))
        assertEquals(DropKind.MEDKIT, live.claim(drop.id, 1, clear, 40_000))
    }

    @Test
    fun aScavengePickupIsThereAtOnce() {
        val drop = live.scavenge(center, 1_000)!!
        assertEquals(DropKind.SCAVENGE, drop.kind)
        assertEquals(1_000L, drop.landsAt)
        assertEquals(61_000L, drop.expiresAt)
        assertEquals(DropKind.SCAVENGE, live.claim(drop.id, 1, emptyMap(), 1_000))
    }

    @Test
    fun movedSpawnsShowWithANewGeneration() {
        val before = live.config()!!.spawnGeneration
        live.moveSpawns(emptyList())
        assertEquals(before + 1, live.config()!!.spawnGeneration)
        live.stop()
        assertNull(live.config())
        assertNull(live.drop(DropKind.AMMO, center, 0))
    }

    @Test
    fun dropSpotsAreInsideAndClearOfTheEdge() {
        val border = Border.Circle(center, 100.0)
        val random = Random(3)
        repeat(50) {
            val spot = LiveField.dropSpot(border, 15.0, random = random)!!
            assertTrue(border.contains(spot))
            assertTrue(border.distanceToEdgeMeters(spot) >= 15.0)
        }
        assertNull(LiveField.dropSpot(Border.Circle(center, 10.0), 50.0, random = random))
    }

    @Test
    fun dropsLandClearOfSpawnsAndEachOther() {
        val border = Border.Circle(center, 100.0)
        val random = Random(4)
        val spawn = at(0.0, 50.0) to 35.0
        val placed = mutableListOf<LatLon>()
        repeat(5) {
            val keepClear = listOf(spawn) + placed.map { it to LiveField.DROP_SPACING_METERS }
            val spot = LiveField.dropSpot(border, 10.0, keepClear, random)!!
            assertTrue(GeoMath.distanceMeters(spot, spawn.first) >= 35.0)
            placed.forEach { assertTrue(GeoMath.distanceMeters(spot, it) >= LiveField.DROP_SPACING_METERS) }
            placed += spot
        }
    }

    @Test
    fun aCrowdedFieldStillGetsTheClearestSpot() {
        // Too small to stay 100 m from the spawn: the drop goes as far from it as it can
        val border = Border.Circle(center, 40.0)
        val spot = LiveField.dropSpot(border, 5.0, listOf(center to 100.0), Random(5))!!
        assertTrue(border.contains(spot))
        assertTrue(GeoMath.distanceMeters(spot, center) > 25.0)
    }

    // Shot distances

    @Test
    fun aShotIsMeasuredFromBothPlayers() {
        val stats = ShotStats()
        val shot = stats.record(1, 2, Fix(center, 0), Fix(at(60.0, 0.0), 0), 5_000, 152.0)
        assertNotNull(shot)
        assertEquals(60.0, shot!!.meters, 0.5)
        stats.record(3, 2, Fix(center, 0), Fix(at(90.0, 0.0), 0), 5_000, 152.0)
        assertEquals(3, stats.longest()!!.shooterId)
        assertEquals(2, stats.all().size)
    }

    @Test
    fun eachPlayersLongestShotIsRanked() {
        val stats = ShotStats()
        stats.record(1, 2, Fix(center, 0), Fix(at(40.0, 0.0), 0), 0, 152.0)
        stats.record(1, 3, Fix(center, 0), Fix(at(70.0, 0.0), 0), 0, 152.0)
        stats.record(2, 1, Fix(center, 0), Fix(at(90.0, 0.0), 0), 0, 152.0)
        val best = stats.bestByShooter()
        assertEquals(listOf(2, 1), best.map { it.shooterId })
        assertEquals(3, best[1].victimId)

        val json = JSONObject().put("players", JSONArray()
            .put(JSONArray().put(1).put("Ann").put(70.0).put("Cat"))
            .put(JSONArray().put(2).put("Bob").put(90.0).put("Ann")))
        assertEquals(listOf("Bob", "Ann"), ShotRank.listFromJson(json.toString()).map { it.name })
        assertEquals(emptyList<ShotRank>(), ShotRank.listFromJson("{}"))
    }

    @Test
    fun badShotsAreLeftOut() {
        val stats = ShotStats()
        // Too far for a blaster: bad GPS
        assertNull(stats.record(1, 2, Fix(center, 0), Fix(at(300.0, 0.0), 0), 0, 152.0))
        // A stale position
        assertNull(stats.record(1, 2, Fix(center, 0), Fix(at(30.0, 0.0), 0), 20_000, 152.0))
        // No position, or yourself
        assertNull(stats.record(1, 2, null, Fix(center, 0), 0, 152.0))
        assertNull(stats.record(1, 1, Fix(center, 0), Fix(center, 0), 0, 152.0))
        assertNull(stats.longest())
    }

    @Test
    fun theLongestShotReadsBack() {
        val json = JSONObject().put("longest", JSONObject()
            .put("shooterName", "Ann").put("victimName", "Bob").put("meters", 88.5)
            .put("from", JSONArray().put(39.0).put(-77.0)).put("to", JSONArray().put(39.001).put(-77.0)))
        val shot = LongestShot.fromJson(json.toString())!!
        assertEquals("Ann", shot.shooterName)
        assertEquals(88.5, shot.meters, 0.0)
        assertEquals(LatLon(39.001, -77.0), shot.to)
        assertNull(LongestShot.fromJson("{}"))
    }
}

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

import com.simplecoil.simplecoil.domain.map.DistanceUnits
import com.simplecoil.simplecoil.domain.map.LatLon
import org.json.JSONArray
import org.json.JSONObject

/** How ammo works (map design: Limited ammo). */
enum class AmmoMode(val wireValue: String) {
    /** Reload as often as you like: the game as it always was. */
    UNLIMITED("unlimited"),

    /** A reserve of magazines; refilled at your spawn or by an ammo drop. */
    LIMITED("limited"),

    /** As [LIMITED], and an eliminated player drops an ammo pickup where they fell. */
    SCAVENGER("scavenger");

    companion object {
        fun fromWire(value: String?): AmmoMode = entries.firstOrNull { it.wireValue == value } ?: UNLIMITED
    }
}

/** What a drop gives (map design: Drops). [lostOnDeath] ones last until the player is eliminated. */
enum class DropKind(val wireValue: String, val lostOnDeath: Boolean) {
    /** Full auto. */
    SUPPLY("supply", true),

    /** Burst of three. */
    BURST("burst", true),

    /** A full reserve of magazines. */
    AMMO("ammo", false),

    /** Full health. */
    MEDKIT("medkit", false),

    /** The next elimination respawns in place, with no countdown and no walk back. */
    EXTRA_LIFE("life", false),

    /** The next few hits do nothing. */
    OVERSHIELD("shield", true),

    /** Every enemy shows on your map for a few seconds. */
    UAV("uav", false),

    /** Where a player fell with scavenger ammo on: a few magazines. */
    SCAVENGE("scavenge", false);

    companion object {
        fun fromWire(value: String?): DropKind? = entries.firstOrNull { it.wireValue == value }

        /** The kinds the server can drop on a timer (scavenge pickups only come from eliminations). */
        val TIMED = listOf(SUPPLY, BURST, AMMO, MEDKIT, EXTRA_LIFE, OVERSHIELD, UAV)
    }
}

/**
 * A drop on the field: announced, then landing at [landsAt] and gone at [expiresAt] (server
 * wall-clock milliseconds; phones correct for their own clock).
 */
data class Drop(val id: String, val kind: DropKind, val point: LatLon, val landsAt: Long, val expiresAt: Long) {
    fun isLanded(serverNow: Long) = serverNow >= landsAt
    fun isExpired(serverNow: Long) = serverNow >= expiresAt

    companion object {
        fun listToJson(drops: List<Drop>): JSONArray = JSONArray().apply {
            drops.forEach {
                put(JSONArray().put(it.id).put(it.kind.wireValue).put(it.point.latitude).put(it.point.longitude).put(it.landsAt).put(it.expiresAt))
            }
        }

        fun listFromJson(json: JSONArray?): List<Drop> {
            json ?: return emptyList()
            return (0 until json.length()).mapNotNull {
                val d = json.getJSONArray(it)
                // A kind from a newer server: skip it
                val kind = DropKind.fromWire(d.getString(1)) ?: return@mapNotNull null
                Drop(d.getString(0), kind, LatLon(d.getDouble(2), d.getDouble(3)), d.getLong(4), d.getLong(5))
            }
        }
    }
}

/** The loadout settings (map design: Ammo, drops and power-ups; Longest shot). Every value is a server setting. */
data class LoadoutRules(
    val ammo: AmmoMode = AmmoMode.UNLIMITED,
    /** Magazines a player starts each life with, with limited ammo. */
    val startingReserve: Int = 5,
    /** Standing in your own spawn refills the reserve. */
    val refillAtSpawn: Boolean = true,
    /** How long a scavenger pickup lies where a player fell. */
    val scavengeSeconds: Int = 60,
    /** Magazines in a scavenger pickup. */
    val scavengeMagazines: Int = 2,

    val dropsEnabled: Boolean = false,
    val dropKinds: Set<DropKind> = setOf(DropKind.SUPPLY, DropKind.AMMO, DropKind.MEDKIT),
    val dropIntervalMinutes: Int = 3,
    val dropWarningSeconds: Int = 30,
    val claimSeconds: Int = 5,
    val dropLastsMinutes: Int = 3,
    /** How close a player has to stand to claim a drop. */
    val claimRadiusMeters: Double = 8.0,
    /** Only the team whose spawn is nearest a drop sees it. */
    val dropsNearestTeamOnly: Boolean = false,
    /** Overshield: how many hits it stops. */
    val overshieldHits: Int = 3,
    /** UAV: how long enemies show. */
    val uavSeconds: Int = 15,

    val killstreaks: Boolean = false,
    /** Eliminations in a row (without dying) for a UAV; 0 = never. */
    val uavKills: Int = 3,
    /** Eliminations in a row for full auto; 0 = never. */
    val fullAutoKills: Int = 5,

    /** Work out how far each elimination was, from both players' GPS. */
    val trackShots: Boolean = true,
    /**
     * Longer than this can't be a real shot (bad GPS): left out. At night, with no sunlight to
     * drown the infrared, a blaster has hit at 1,000 ft and more; by day it reaches less.
     */
    val maxShotMeters: Double = DEFAULT_MAX_SHOT_METERS,
    val longestShotAward: Boolean = true,
    /** What the post-game map shows: nothing, the kills, or the kills, a heatmap and a replay. */
    val postGameMap: PostGameMap = PostGameMap.FULL,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ammo", ammo.wireValue)
        .put("reserve", startingReserve)
        .put("refillAtSpawn", refillAtSpawn)
        .put("scavengeSeconds", scavengeSeconds)
        .put("scavengeMagazines", scavengeMagazines)
        .put("drops", dropsEnabled)
        .put("dropKinds", JSONArray().apply { dropKinds.sortedBy { it.ordinal }.forEach { put(it.wireValue) } })
        .put("dropInterval", dropIntervalMinutes)
        .put("dropWarning", dropWarningSeconds)
        .put("claimSeconds", claimSeconds)
        .put("dropLasts", dropLastsMinutes)
        .put("claimRadius", claimRadiusMeters)
        .put("nearestTeamOnly", dropsNearestTeamOnly)
        .put("overshieldHits", overshieldHits)
        .put("uavSeconds", uavSeconds)
        .put("killstreaks", killstreaks)
        .put("uavKills", uavKills)
        .put("fullAutoKills", fullAutoKills)
        .put("trackShots", trackShots)
        .put("maxShot", maxShotMeters)
        .put("longestShot", longestShotAward)
        .put("postGameMap", postGameMap.wireValue)

    /** These rules with every value inside the range the server offers. */
    fun clamped(): LoadoutRules = copy(
        startingReserve = startingReserve.coerceIn(1, 20),
        scavengeSeconds = scavengeSeconds.coerceIn(15, 120),
        scavengeMagazines = scavengeMagazines.coerceIn(1, 10),
        dropIntervalMinutes = dropIntervalMinutes.coerceIn(1, 10),
        dropWarningSeconds = dropWarningSeconds.coerceIn(0, 60),
        claimSeconds = claimSeconds.coerceIn(1, 15),
        dropLastsMinutes = dropLastsMinutes.coerceIn(1, 10),
        claimRadiusMeters = claimRadiusMeters.coerceIn(5.0, 20.0),
        overshieldHits = overshieldHits.coerceIn(1, 10),
        uavSeconds = uavSeconds.coerceIn(5, 60),
        uavKills = uavKills.coerceIn(0, 20),
        fullAutoKills = fullAutoKills.coerceIn(0, 20),
        maxShotMeters = maxShotMeters.coerceIn(15.0, MAX_MAX_SHOT_METERS),
    )

    companion object {
        /** 2,000 ft: a long night shot still counts. */
        const val DEFAULT_MAX_SHOT_METERS = 2_000 / DistanceUnits.FEET_PER_METER

        /** The most the host can set: 5 km (about 16,400 ft), far past any blaster. */
        const val MAX_MAX_SHOT_METERS = 5_000.0

        private const val OLD_DEFAULT_MAX_SHOT_METERS = 152.0

        fun fromJson(json: JSONObject?): LoadoutRules {
            val d = LoadoutRules()
            if (json == null) return d
            val kinds = json.optJSONArray("dropKinds")?.let { a -> (0 until a.length()).mapNotNull { DropKind.fromWire(a.getString(it)) }.toSet() }
            return LoadoutRules(
                ammo = AmmoMode.fromWire(json.optString("ammo", d.ammo.wireValue)),
                startingReserve = json.optInt("reserve", d.startingReserve),
                refillAtSpawn = json.optBoolean("refillAtSpawn", d.refillAtSpawn),
                scavengeSeconds = json.optInt("scavengeSeconds", d.scavengeSeconds),
                scavengeMagazines = json.optInt("scavengeMagazines", d.scavengeMagazines),
                dropsEnabled = json.optBoolean("drops", d.dropsEnabled),
                dropKinds = kinds ?: d.dropKinds,
                dropIntervalMinutes = json.optInt("dropInterval", d.dropIntervalMinutes),
                dropWarningSeconds = json.optInt("dropWarning", d.dropWarningSeconds),
                claimSeconds = json.optInt("claimSeconds", d.claimSeconds),
                dropLastsMinutes = json.optInt("dropLasts", d.dropLastsMinutes),
                claimRadiusMeters = json.optDouble("claimRadius", d.claimRadiusMeters),
                dropsNearestTeamOnly = json.optBoolean("nearestTeamOnly", d.dropsNearestTeamOnly),
                overshieldHits = json.optInt("overshieldHits", d.overshieldHits),
                uavSeconds = json.optInt("uavSeconds", d.uavSeconds),
                killstreaks = json.optBoolean("killstreaks", d.killstreaks),
                uavKills = json.optInt("uavKills", d.uavKills),
                fullAutoKills = json.optInt("fullAutoKills", d.fullAutoKills),
                trackShots = json.optBoolean("trackShots", d.trackShots),
                // 152 m (500 ft) was the old default, from before night shots past 1,000 ft: a field
                // saved with it gets the new one
                maxShotMeters = json.optDouble("maxShot", d.maxShotMeters).let { if (it == OLD_DEFAULT_MAX_SHOT_METERS) d.maxShotMeters else it },
                longestShotAward = json.optBoolean("longestShot", d.longestShotAward),
                postGameMap = PostGameMap.fromWire(json.optString("postGameMap", d.postGameMap.wireValue)),
            ).clamped()
        }
    }
}

/** The post-game map (map design: Longest shot and post-game stats). */
enum class PostGameMap(val wireValue: String) {
    OFF("off"),
    KILLS("kills"),

    /** Kills, heatmap and replay: the server records everyone's path. */
    FULL("full");

    companion object {
        fun fromWire(value: String?): PostGameMap = entries.firstOrNull { it.wireValue == value } ?: FULL
    }
}

/** Another player as a UAV shows them: id, team and where they were. */
data class UavPlayer(val playerId: Int, val team: Int, val point: LatLon) {
    companion object {
        fun listFromJson(json: String): List<UavPlayer> {
            val players = JSONObject(json).getJSONArray("players")
            return (0 until players.length()).map {
                val p = players.getJSONArray(it)
                UavPlayer(p.getInt(0), p.getInt(1), LatLon(p.getDouble(2), p.getDouble(3)))
            }
        }
    }
}

/** A player's longest shot this game, for the scoreboard's ranking. */
data class ShotRank(val playerId: Int, val name: String, val meters: Double, val victimName: String) {
    companion object {
        /** The ranking in a stats reply, longest first; empty if it has none. */
        fun listFromJson(json: String): List<ShotRank> {
            val players = JSONObject(json).optJSONArray("players") ?: return emptyList()
            return (0 until players.length()).map {
                val p = players.getJSONArray(it)
                ShotRank(p.getInt(0), p.getString(1), p.getDouble(2), p.getString(3))
            }.sortedByDescending { it.meters }
        }
    }
}

/** The game's longest shot (map design: Longest shot): who, whom, how far, and from where to where. */
data class LongestShot(val shooterName: String, val victimName: String, val meters: Double, val from: LatLon, val to: LatLon) {
    companion object {
        fun fromJson(json: String): LongestShot? {
            val longest = JSONObject(json).optJSONObject("longest") ?: return null
            val from = longest.getJSONArray("from")
            val to = longest.getJSONArray("to")
            return LongestShot(longest.optString("shooterName"), longest.optString("victimName"), longest.getDouble("meters"),
                LatLon(from.getDouble(0), from.getDouble(1)), LatLon(to.getDouble(0), to.getDouble(1)))
        }
    }
}

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

import com.simplecoil.simplecoil.domain.loadout.Drop
import com.simplecoil.simplecoil.domain.loadout.LoadoutRules
import com.simplecoil.simplecoil.domain.mode.BattleRoyaleRules
import com.simplecoil.simplecoil.domain.mode.GameMode
import com.simplecoil.simplecoil.domain.mode.MarksmanRules
import com.simplecoil.simplecoil.domain.mode.FlagRules
import com.simplecoil.simplecoil.domain.mode.ExtractionRules
import com.simplecoil.simplecoil.domain.mode.Objective
import com.simplecoil.simplecoil.domain.mode.ObjectiveRules
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/** What happens when a player stays out of bounds past the countdown (map design: Border). */
enum class OutOfBoundsPenalty(val wireValue: String) {
    /** The blaster can't shoot until the player is back. */
    DISABLE_BLASTER("disable"),

    /** The player loses health every few seconds until back. */
    LOSE_HEALTH("health"),

    /** The player is eliminated. */
    ELIMINATED("eliminate"),

    /** Only the warning; nothing else happens. */
    WARNING_ONLY("warning");

    companion object {
        fun fromWire(value: String?): OutOfBoundsPenalty = entries.firstOrNull { it.wireValue == value } ?: DISABLE_BLASTER
    }
}

/** How distances are shown. */
enum class DistanceUnits(val wireValue: String) {
    FEET("ft"), METERS("m");

    /** [meters] as a whole number in these units, with its unit: "33 ft", "10 m". */
    fun format(meters: Double): String = when (this) {
        FEET -> "${(meters * FEET_PER_METER).roundToInt()} ft"
        METERS -> "${meters.roundToInt()} m"
    }

    /** A number typed in these units, as metres. */
    fun toMeters(value: Double): Double = if (this == FEET) value / FEET_PER_METER else value

    /** [meters] in these units. */
    fun fromMeters(meters: Double): Double = if (this == FEET) meters * FEET_PER_METER else meters

    companion object {
        const val FEET_PER_METER = 3.28084

        fun fromWire(value: String?): DistanceUnits = entries.firstOrNull { it.wireValue == value } ?: defaultFor(Locale.getDefault())

        /** Feet in the US, Liberia and Myanmar; metres everywhere else. */
        fun defaultFor(locale: Locale): DistanceUnits = if (locale.country in setOf("US", "LR", "MM")) FEET else METERS
    }
}

/**
 * The out-of-bounds rules and GPS tolerances (map design: GPS limits and Border). Every value is
 * a server setting; these defaults are the design's.
 */
data class BorderRules(
    /** Seconds outside before the penalty starts. */
    val countdownSeconds: Int = 10,
    val penalty: OutOfBoundsPenalty = OutOfBoundsPenalty.DISABLE_BLASTER,
    /** With [OutOfBoundsPenalty.LOSE_HEALTH]: this much health... */
    val healthPerTick: Int = 1,
    /** ...every this many seconds. */
    val tickSeconds: Int = 2,
    /** Show the border only when near it, rather than always. */
    val showOnlyWhenNear: Boolean = false,
    /** With [showOnlyWhenNear]: the border shows within this distance of it (and always outside). */
    val nearDistanceMeters: Double = 20.0,
    /** A player only counts as outside after this many seconds there, so GPS jitter is ignored. */
    val graceSeconds: Int = 3,
    /** Leaving takes this far past the line; coming back only needs the line. */
    val leaveMarginMeters: Double = 3.0,
    /** Fixes less accurate than this are ignored; 0 = use every fix. */
    val accuracyGateMeters: Double = 15.0,
    /** Nothing is auto-placed this close to the border (spawns, objectives, drops). */
    val edgeBufferMeters: Double = 10.0,
    val units: DistanceUnits = DistanceUnits.FEET,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("countdown", countdownSeconds)
        .put("penalty", penalty.wireValue)
        .put("healthPerTick", healthPerTick)
        .put("tickSeconds", tickSeconds)
        .put("onlyNear", showOnlyWhenNear)
        .put("nearDistance", nearDistanceMeters)
        .put("grace", graceSeconds)
        .put("leaveMargin", leaveMarginMeters)
        .put("accuracyGate", accuracyGateMeters)
        .put("edgeBuffer", edgeBufferMeters)
        .put("units", units.wireValue)

    /** These rules with every value inside the range the server offers. */
    fun clamped(): BorderRules = copy(
        countdownSeconds = countdownSeconds.coerceIn(0, 60),
        healthPerTick = healthPerTick.coerceIn(1, 10),
        tickSeconds = tickSeconds.coerceIn(1, 10),
        graceSeconds = graceSeconds.coerceIn(0, 10),
        leaveMarginMeters = leaveMarginMeters.coerceIn(0.0, 10.0),
        accuracyGateMeters = if (accuracyGateMeters <= 0) 0.0 else accuracyGateMeters.coerceIn(5.0, 50.0),
        edgeBufferMeters = edgeBufferMeters.coerceIn(0.0, 30.0),
        nearDistanceMeters = nearDistanceMeters.coerceIn(5.0, 100.0),
    )

    companion object {
        fun fromJson(json: JSONObject?): BorderRules {
            val d = BorderRules()
            if (json == null) return d
            return BorderRules(
                countdownSeconds = json.optInt("countdown", d.countdownSeconds),
                penalty = OutOfBoundsPenalty.fromWire(json.optString("penalty", d.penalty.wireValue)),
                healthPerTick = json.optInt("healthPerTick", d.healthPerTick),
                tickSeconds = json.optInt("tickSeconds", d.tickSeconds),
                showOnlyWhenNear = json.optBoolean("onlyNear", d.showOnlyWhenNear),
                nearDistanceMeters = json.optDouble("nearDistance", d.nearDistanceMeters),
                graceSeconds = json.optInt("grace", d.graceSeconds),
                leaveMarginMeters = json.optDouble("leaveMargin", d.leaveMarginMeters),
                accuracyGateMeters = json.optDouble("accuracyGate", d.accuracyGateMeters),
                edgeBufferMeters = json.optDouble("edgeBuffer", d.edgeBufferMeters),
                units = DistanceUnits.fromWire(json.optString("units", d.units.wireValue)),
            ).clamped()
        }
    }
}

/**
 * Everything about a field except its photo (map design: Map objects): the border, the
 * respawn points and their rules. The server sends it to every phone; it is small, so it
 * changes without resending the photo. Later phases add objectives and drops here.
 */
data class FieldConfig(
    val name: String = "",
    val border: Border? = null,
    val rules: BorderRules = BorderRules(),
    /** The respawn points (map design: Respawn points). */
    val spawns: List<Spawn> = emptyList(),
    val spawnRules: SpawnRules = SpawnRules(),
    /** Counts the times the server moved the spawns this game, so phones can fade old into new. */
    val spawnGeneration: Int = 0,
    /** Ammo, drops, killstreaks and shot distance (map design: Ammo, drops and power-ups). */
    val loadout: LoadoutRules = LoadoutRules(),
    /** The drops on the field right now, during a game. */
    val drops: List<Drop> = emptyList(),
    /** The game mode (map design: Game modes). */
    val mode: GameMode = GameMode.CLASSIC,
    /** The capture points; empty means placed for each game by field size. */
    val objectives: List<Objective> = emptyList(),
    val objectiveRules: ObjectiveRules = ObjectiveRules(),
    /** Each objective mode's settings as last saved, so switching modes and back keeps them. */
    val modeRules: Map<GameMode, ObjectiveRules> = emptyMap(),
    val battleRoyale: BattleRoyaleRules = BattleRoyaleRules(),
    val marksman: MarksmanRules = MarksmanRules(),
    val flag: FlagRules = FlagRules(),
    val extraction: ExtractionRules = ExtractionRules(),
) {
    /** Changes whenever anything in the config does; phones compare it to skip a download. */
    val id: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(toJson().toString().toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
    }

    fun toJson(): JSONObject {
        val json = JSONObject().put("version", VERSION).put("name", name).put("rules", rules.toJson())
            .put("spawns", Spawn.listToJson(spawns))
            .put("spawnRules", spawnRules.toJson())
            .put("spawnGeneration", spawnGeneration)
            .put("loadout", loadout.toJson())
            .put("drops", Drop.listToJson(drops))
            .put("mode", mode.wireValue)
            .put("objectives", Objective.listToJson(objectives))
            .put("objectiveRules", objectiveRules.toJson())
            .put("modeRules", JSONObject().apply { modeRules.forEach { (mode, r) -> put(mode.wireValue, r.toJson()) } })
            .put("battleRoyale", battleRoyale.toJson())
            .put("marksman", marksman.toJson())
            .put("flag", flag.toJson())
            .put("extraction", extraction.toJson())
        when (val b = border) {
            is Border.Circle -> json.put("border", JSONObject().put("type", "circle")
                .put("lat", b.center.latitude).put("lon", b.center.longitude).put("radius", b.radiusMeters))
            is Border.Polygon -> json.put("border", JSONObject().put("type", "polygon")
                .put("points", JSONArray().apply { b.corners.forEach { put(JSONArray().put(it.latitude).put(it.longitude)) } }))
            null -> {}
        }
        return json
    }

    companion object {
        const val VERSION = 1

        @Throws(IOException::class)
        fun fromJson(json: JSONObject): FieldConfig = try {
            val border = json.optJSONObject("border")?.let { b ->
                when (b.getString("type")) {
                    "circle" -> Border.Circle(LatLon(b.getDouble("lat"), b.getDouble("lon")), b.getDouble("radius"))
                    "polygon" -> {
                        val points = b.getJSONArray("points")
                        Border.Polygon((0 until points.length()).map {
                            val p = points.getJSONArray(it)
                            LatLon(p.getDouble(0), p.getDouble(1))
                        })
                    }
                    else -> null // a border type from a newer server: play without it
                }
            }
            FieldConfig(
                name = json.optString("name", ""),
                border = border,
                rules = BorderRules.fromJson(json.optJSONObject("rules")),
                spawns = Spawn.listFromJson(json.optJSONArray("spawns")),
                spawnRules = SpawnRules.fromJson(json.optJSONObject("spawnRules")),
                spawnGeneration = json.optInt("spawnGeneration", 0),
                loadout = LoadoutRules.fromJson(json.optJSONObject("loadout")),
                drops = Drop.listFromJson(json.optJSONArray("drops")),
                mode = GameMode.fromWire(json.optString("mode")),
                objectives = Objective.listFromJson(json.optJSONArray("objectives")),
                objectiveRules = ObjectiveRules.fromJson(json.optJSONObject("objectiveRules")),
                modeRules = json.optJSONObject("modeRules")?.let { m ->
                    GameMode.entries.filter { it.hasObjectives && m.has(it.wireValue) }.associateWith { ObjectiveRules.fromJson(m.optJSONObject(it.wireValue)) }
                } ?: emptyMap(),
                battleRoyale = BattleRoyaleRules.fromJson(json.optJSONObject("battleRoyale")),
                marksman = MarksmanRules.fromJson(json.optJSONObject("marksman")),
                flag = FlagRules.fromJson(json.optJSONObject("flag")),
                extraction = ExtractionRules.fromJson(json.optJSONObject("extraction")),
            )
        } catch (e: JSONException) {
            throw IOException("bad field config", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("bad field border", e)
        }

        @Throws(IOException::class)
        fun fromBytes(bytes: ByteArray): FieldConfig = try {
            fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (e: JSONException) {
            throw IOException("bad field config", e)
        }
    }

    fun toBytes(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)
}

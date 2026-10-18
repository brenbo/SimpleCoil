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

package com.simplecoil.simplecoil.hardware.audio

import com.simplecoil.simplecoil.domain.session.SessionSound

/**
 * How one game sound plays (map design: Audio).
 *
 * [file] is its own effect in res/raw ("sfx_drop_incoming"); with more takes beside it
 * ("_2", "_3"...) one is picked at random, never the same twice running. Until it is there,
 * [fallback] plays instead: one of the sounds the app ships, at [fallbackRate] and [repeats]
 * times, so every event still sounds different. [vary] nudges pitch and volume a little each
 * time, as a real shot never sounds quite the same twice.
 *
 * [voice] is its radio callout ("voice_zone_closing"), played once recorded, queued by
 * [priority] (higher first). [essential] callouts play even with "essential only".
 */
data class SoundCue(
    val file: String,
    val fallback: String,
    val fallbackRate: Float = 1f,
    val repeats: Int = 1,
    val vary: Boolean = true,
    val voice: String? = null,
    val priority: Int = 0,
    val essential: Boolean = false,
)

object SoundCues {

    /** How far a varied sound's pitch moves either way, and how quiet it may get. */
    const val PITCH_SPREAD = 0.06f
    const val VOLUME_SPREAD = 0.15f

    /** The cue for [sound]. */
    fun of(sound: SessionSound): SoundCue = when (sound) {
        // The blaster: never quite the same twice
        SessionSound.SHOT -> SoundCue("shootingshort", "shootingshort")
        SessionSound.SHOT_AUTO -> SoundCue("shootingauto", "shootingshort")
        SessionSound.EMPTY -> SoundCue("empty", "empty")
        SessionSound.RELOAD -> SoundCue("reload", "reload")
        SessionSound.HIT -> SoundCue("hit", "hit")
        SessionSound.ELIMINATED -> SoundCue("eliminated", "eliminated")
        SessionSound.SPAWN -> SoundCue("spawn", "spawn")
        SessionSound.CONNECTED -> SoundCue("sfx_connected", "spawn")
        SessionSound.BEEP -> SoundCue("beep", "beep", vary = false)
        SessionSound.SCORE -> SoundCue("score", "score")
        SessionSound.HIT_CONFIRM -> SoundCue("hit_confirm", "hit_confirm")
        SessionSound.HIT_ELIMINATED -> SoundCue("hit_eliminated", "hit_eliminated")
        SessionSound.HIT_SHIELDED -> SoundCue("hit_shielded", "hit_shielded")
        // The whistle at the end of a game stays exactly as it is
        SessionSound.GAME_END -> SoundCue("game_end", "game_end", vary = false)

        SessionSound.OUT_OF_BOUNDS -> SoundCue("sfx_out_of_bounds", "empty", 0.7f, 2, voice = "voice_leaving_area", priority = 9, essential = true)
        SessionSound.BACK_IN_BOUNDS -> SoundCue("sfx_back_in_bounds", "spawn", 1.3f, voice = "voice_back_in_area", priority = 3)
        SessionSound.DAMAGE_TICK -> SoundCue("sfx_damage_tick", "hit", 0.8f)
        SessionSound.ZONE_WARNING -> SoundCue("sfx_zone_warning", "beep", 0.7f, 2, voice = "voice_outside_zone", priority = 9, essential = true)
        SessionSound.ZONE_CLOSING -> SoundCue("sfx_zone_closing", "beep", 0.6f, 3, voice = "voice_zone_closing", priority = 8, essential = true)
        SessionSound.SPAWN_MOVING -> SoundCue("sfx_spawn_moving", "spawn", 0.8f, voice = "voice_spawn_moving", priority = 5)
        SessionSound.DROP_INCOMING -> SoundCue("sfx_drop_incoming", "beep", 1.5f, 2, voice = "voice_drop_inbound", priority = 6)
        SessionSound.POWER_UP -> SoundCue("sfx_power_up", "score", 1.25f, voice = "voice_power_up", priority = 4)
        SessionSound.KILLSTREAK -> SoundCue("sfx_killstreak", "hit_eliminated", 1.2f, 2, voice = "voice_killstreak", priority = 4)
        SessionSound.UAV -> SoundCue("sfx_uav", "beep", 1.8f, 3, voice = "voice_uav_online", priority = 5)
        SessionSound.LAST_MAG -> SoundCue("sfx_last_mag", "empty", 1.3f, voice = "voice_last_mag", priority = 3)
        SessionSound.CAPTURING -> SoundCue("sfx_capturing", "beep", 1.2f, voice = "voice_capturing", priority = 4)
        SessionSound.SECURED -> SoundCue("sfx_secured", "score", 1f, voice = "voice_secured", priority = 7, essential = true)
        SessionSound.LOST -> SoundCue("sfx_lost", "hit_shielded", 0.8f, voice = "voice_lost", priority = 7, essential = true)
        SessionSound.ENEMY_CAPTURING -> SoundCue("sfx_enemy_capturing", "beep", 0.8f, 2, voice = "voice_hostiles_capturing", priority = 8, essential = true)
        SessionSound.POINT_MOVED -> SoundCue("sfx_point_moved", "spawn", 0.9f, 2, voice = "voice_point_moved", priority = 6, essential = true)
        SessionSound.FLAG_TAKEN -> SoundCue("sfx_flag_taken", "hit_shielded", 1.1f, 2, voice = "voice_flag_taken", priority = 8, essential = true)
        SessionSound.FLAG_CAPTURED -> SoundCue("sfx_flag_captured", "score", 1.1f, 2, voice = "voice_flag_captured", priority = 7, essential = true)
        SessionSound.ROUND_START -> SoundCue("sfx_round_start", "spawn", 1f, voice = "voice_round_start", priority = 6, essential = true)
        SessionSound.BOMB_PLANTED -> SoundCue("sfx_bomb_planted", "beep", 0.6f, 3, voice = "voice_charge_set", priority = 9, essential = true)
        SessionSound.BOMB_DEFUSED -> SoundCue("sfx_bomb_defused", "score", 0.9f, voice = "voice_charge_defused", priority = 9, essential = true)
        SessionSound.BOMB_EXPLODED -> SoundCue("sfx_bomb_exploded", "eliminated", 0.6f, priority = 9)
        SessionSound.INFECTED -> SoundCue("sfx_infected", "eliminated", 0.7f, voice = "voice_infected", priority = 8, essential = true)
        SessionSound.LOOT -> SoundCue("sfx_loot", "score", 1.4f)
        SessionSound.LOOT_BANKED -> SoundCue("sfx_loot_banked", "score", 0.95f, 2, voice = "voice_loot_banked", priority = 5)
        SessionSound.VICTORY -> SoundCue("sfx_victory", "score", 0.8f, 2, vary = false, voice = "voice_victory", priority = 10, essential = true)
        SessionSound.DEFEAT -> SoundCue("sfx_defeat", "eliminated", 0.6f, vary = false, voice = "voice_defeat", priority = 10, essential = true)
        SessionSound.LONGEST_SHOT -> SoundCue("sfx_longest_shot", "hit_confirm", 0.9f, voice = "voice_longest_shot", priority = 2)
    }

    /** Every resource name a cue might use: files, fallbacks and voices. */
    fun names(): Set<String> = SessionSound.entries.map { of(it) }.flatMap { listOfNotNull(it.file, it.fallback, it.voice) }.toSet()
}

/**
 * Radio callouts in turn (map design: Audio, "No pile-ups"): one at a time, the most important
 * first; a line said lately isn't said again until its cooldown passes; while one plays, others
 * wait in line (only the most important waiting one is kept). Plain logic; the player asks it
 * what to say.
 */
class CalloutQueue(private val cooldownMillis: Long = COOLDOWN_MILLIS) {
    private var busyUntil = 0L
    private var waiting: Pair<String, Int>? = null
    private val lastSaid = HashMap<String, Long>()

    /** True while a callout is playing (effects are ducked under it). */
    fun isBusy(nowMillis: Long): Boolean = nowMillis < busyUntil

    /** [voice] wants saying at [priority]; returns it if it should play now, else keeps it in line (or drops it). */
    fun offer(voice: String, priority: Int, nowMillis: Long): String? {
        val said = lastSaid[voice]
        if (said != null && nowMillis - said < cooldownMillis) return null
        if (!isBusy(nowMillis)) return voice
        val queued = waiting
        if (queued == null || priority > queued.second) waiting = voice to priority
        return null
    }

    /** [voice] started playing for [durationMillis]. */
    fun playing(voice: String, durationMillis: Long, nowMillis: Long) {
        busyUntil = nowMillis + durationMillis + GAP_MILLIS
        lastSaid[voice] = nowMillis
        if (waiting?.first == voice) waiting = null
    }

    /** The line waiting, once the current one is done; null if none or still busy. */
    fun next(nowMillis: Long): String? {
        if (isBusy(nowMillis)) return null
        val line = waiting ?: return null
        waiting = null
        val said = lastSaid[line.first]
        return line.first.takeIf { said == null || nowMillis - said >= cooldownMillis }
    }

    companion object {
        /** The same callout no more often than this. */
        const val COOLDOWN_MILLIS = 8_000L

        /** A short breath between callouts. */
        const val GAP_MILLIS = 300L
    }
}

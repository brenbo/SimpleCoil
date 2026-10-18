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
 * When a sound plays (map design: Audio, "No pile-ups"). The blaster's own sounds play at once;
 * everything else takes its turn in an [AnnouncementQueue], so no two announcements overlap.
 */
enum class Lane {
    /** Shots, hits, reloads, beeps: at once, over anything. */
    INSTANT,

    /** Kills and deaths: in turn, but ahead of the rest, and only while still fresh. */
    URGENT,

    /** Map and game-mode events: in turn, in the order they happened. */
    NORMAL,

    /** The game ending: cuts off whatever plays and clears the line. */
    FINAL,
}

/**
 * How one game sound plays (map design: Audio).
 *
 * [file] is its own effect in res/raw ("sfx_drop_incoming"); with more takes beside it
 * ("_2", "_3"...) one is picked at random, never the same twice running. Until it is there,
 * [fallback] plays instead: one of the sounds the app ships, at [fallbackRate] and [repeats]
 * times, so every event still sounds different. [vary] nudges pitch and volume a little each
 * time, as a real shot never sounds quite the same twice.
 *
 * [voice] is its radio callout ("voice_zone_closing"), said after the effect once recorded.
 * [essential] callouts play even with "essential only". With [effectUnderVoice] false the effect
 * plays only when the callout doesn't.
 */
data class SoundCue(
    val file: String,
    val fallback: String,
    val fallbackRate: Float = 1f,
    val repeats: Int = 1,
    val vary: Boolean = true,
    val voice: String? = null,
    val essential: Boolean = false,
    val lane: Lane = Lane.NORMAL,
    val effectUnderVoice: Boolean = true,
)

object SoundCues {

    /** How far a varied sound's pitch moves either way, and how quiet it may get. */
    const val PITCH_SPREAD = 0.06f
    const val VOLUME_SPREAD = 0.15f

    /** The cue for [sound]. */
    fun of(sound: SessionSound): SoundCue = when (sound) {
        // The blaster: never quite the same twice, and never kept waiting
        SessionSound.SHOT -> SoundCue("shootingshort", "shootingshort", lane = Lane.INSTANT)
        SessionSound.SHOT_AUTO -> SoundCue("shootingauto", "shootingshort", lane = Lane.INSTANT)
        SessionSound.EMPTY -> SoundCue("empty", "empty", lane = Lane.INSTANT)
        SessionSound.RELOAD -> SoundCue("reload", "reload", lane = Lane.INSTANT)
        SessionSound.HIT -> SoundCue("hit", "hit", lane = Lane.INSTANT)
        SessionSound.SPAWN -> SoundCue("spawn", "spawn", lane = Lane.INSTANT)
        SessionSound.CONNECTED -> SoundCue("sfx_connected", "spawn", lane = Lane.INSTANT)
        SessionSound.BEEP -> SoundCue("beep", "beep", vary = false, lane = Lane.INSTANT)
        SessionSound.HIT_CONFIRM -> SoundCue("hit_confirm", "hit_confirm", lane = Lane.INSTANT)
        SessionSound.HIT_SHIELDED -> SoundCue("hit_shielded", "hit_shielded", lane = Lane.INSTANT)
        SessionSound.DAMAGE_TICK -> SoundCue("sfx_damage_tick", "hit", 0.8f, lane = Lane.INSTANT)
        // Kills and deaths
        SessionSound.ELIMINATED -> SoundCue("eliminated", "eliminated", lane = Lane.URGENT)
        SessionSound.HIT_ELIMINATED -> SoundCue("hit_eliminated", "hit_eliminated", lane = Lane.URGENT)
        SessionSound.SCORE -> SoundCue("score", "score", lane = Lane.URGENT)
        // The whistle at the end of a game stays exactly as it is
        SessionSound.GAME_END -> SoundCue("game_end", "game_end", vary = false, lane = Lane.FINAL)
        SessionSound.VICTORY -> SoundCue("sfx_victory", "score", 0.8f, 2, vary = false, voice = "voice_victory", essential = true, lane = Lane.FINAL)
        SessionSound.DEFEAT -> SoundCue("sfx_defeat", "eliminated", 0.6f, vary = false, voice = "voice_defeat", essential = true, lane = Lane.FINAL)

        SessionSound.OUT_OF_BOUNDS -> SoundCue("sfx_out_of_bounds", "empty", 0.7f, 2, voice = "voice_leaving_area", essential = true)
        SessionSound.BACK_IN_BOUNDS -> SoundCue("sfx_back_in_bounds", "spawn", 1.3f, voice = "voice_back_in_area")
        SessionSound.ZONE_WARNING -> SoundCue("sfx_zone_warning", "beep", 0.7f, 2, voice = "voice_outside_zone", essential = true)
        SessionSound.ZONE_CLOSING -> SoundCue("sfx_zone_closing", "beep", 0.6f, 3, voice = "voice_zone_closing", essential = true)
        SessionSound.SPAWN_MOVING -> SoundCue("sfx_spawn_moving", "spawn", 0.8f, voice = "voice_spawn_moving")
        // Just the callout when there is one: the effect under it made it hard to follow
        SessionSound.DROP_INCOMING -> SoundCue("sfx_drop_incoming", "beep", 1.5f, 2, voice = "voice_drop_inbound", effectUnderVoice = false)
        SessionSound.POWER_UP -> SoundCue("sfx_power_up", "score", 1.25f, voice = "voice_power_up")
        SessionSound.KILLSTREAK -> SoundCue("sfx_killstreak", "hit_eliminated", 1.2f, 2, voice = "voice_killstreak")
        SessionSound.UAV -> SoundCue("sfx_uav", "beep", 1.8f, 3, voice = "voice_uav_online")
        SessionSound.LAST_MAG -> SoundCue("sfx_last_mag", "empty", 1.3f, voice = "voice_last_mag")
        SessionSound.CAPTURING -> SoundCue("sfx_capturing", "beep", 1.2f, voice = "voice_capturing")
        SessionSound.SECURED -> SoundCue("sfx_secured", "score", 1f, voice = "voice_secured", essential = true)
        SessionSound.LOST -> SoundCue("sfx_lost", "hit_shielded", 0.8f, voice = "voice_lost", essential = true)
        SessionSound.ENEMY_CAPTURING -> SoundCue("sfx_enemy_capturing", "beep", 0.8f, 2, voice = "voice_hostiles_capturing", essential = true)
        SessionSound.POINT_MOVED -> SoundCue("sfx_point_moved", "spawn", 0.9f, 2, voice = "voice_point_moved", essential = true)
        SessionSound.FLAG_TAKEN -> SoundCue("sfx_flag_taken", "hit_shielded", 1.1f, 2, voice = "voice_flag_taken", essential = true)
        SessionSound.FLAG_CAPTURED -> SoundCue("sfx_flag_captured", "score", 1.1f, 2, voice = "voice_flag_captured", essential = true)
        SessionSound.ROUND_START -> SoundCue("sfx_round_start", "spawn", 1f, voice = "voice_round_start", essential = true)
        SessionSound.BOMB_PLANTED -> SoundCue("sfx_bomb_planted", "beep", 0.6f, 3, voice = "voice_charge_set", essential = true)
        SessionSound.BOMB_DEFUSED -> SoundCue("sfx_bomb_defused", "score", 0.9f, voice = "voice_charge_defused", essential = true)
        SessionSound.BOMB_EXPLODED -> SoundCue("sfx_bomb_exploded", "eliminated", 0.6f, essential = true)
        SessionSound.INFECTED -> SoundCue("sfx_infected", "eliminated", 0.7f, voice = "voice_infected", essential = true)
        SessionSound.LOOT -> SoundCue("sfx_loot", "score", 1.4f)
        SessionSound.LOOT_BANKED -> SoundCue("sfx_loot_banked", "score", 0.95f, 2, voice = "voice_loot_banked")
    }

    /** Every resource name a cue might use: files, fallbacks and voices. */
    fun names(): Set<String> = SessionSound.entries.map { of(it) }.flatMap { listOfNotNull(it.file, it.fallback, it.voice) }.toSet()
}

/**
 * Announcements in turn (map design: Audio, "No pile-ups"): one at a time, in the order they
 * happened, kills and deaths ahead of the rest, the end of the game ahead of everything (it
 * cuts off what plays and clears the line). The same thing isn't queued twice, and one that
 * waited too long is dropped rather than played late. Plain logic; the player tells it when
 * what plays has ended.
 */
class AnnouncementQueue<T>(
    private val urgentMaxWaitMillis: Long = URGENT_MAX_WAIT_MILLIS,
    private val maxWaitMillis: Long = MAX_WAIT_MILLIS,
    private val essentialMaxWaitMillis: Long = ESSENTIAL_MAX_WAIT_MILLIS,
) {
    private class Item<T>(val value: T, val lane: Lane, val essential: Boolean, val queuedAt: Long)

    private val waiting = ArrayList<Item<T>>()
    private var current: Item<T>? = null

    /** What [offer] decided: [play] now (if not null), after stopping what plays if [stopCurrent]. */
    data class Decision<T>(val play: T?, val stopCurrent: Boolean = false)

    /** True while an announcement plays. */
    val isBusy: Boolean get() = current != null

    fun offer(value: T, lane: Lane, essential: Boolean, nowMillis: Long): Decision<T> {
        require(lane != Lane.INSTANT) { "instant sounds don't queue" }
        val item = Item(value, lane, essential, nowMillis)
        if (lane == Lane.FINAL) {
            waiting.removeAll { it.lane != Lane.FINAL }
            val playing = current
            if (playing == null || playing.lane != Lane.FINAL) {
                current = item
                return Decision(value, stopCurrent = playing != null)
            }
            waiting += item
            return Decision(null)
        }
        // The game is over: nothing else now
        if (current?.lane == Lane.FINAL || waiting.any { it.lane == Lane.FINAL }) return Decision(null)
        if (current?.value == value || waiting.any { it.value == value }) return Decision(null)
        if (current == null) {
            current = item
            return Decision(value)
        }
        if (lane == Lane.URGENT) waiting.add(waiting.indexOfFirst { it.lane == Lane.NORMAL }.takeIf { it >= 0 } ?: waiting.size, item)
        else waiting += item
        return Decision(null)
    }

    /** What played has ended; returns what plays next, if anything is still worth playing. */
    fun finished(nowMillis: Long): T? {
        current = null
        while (waiting.isNotEmpty()) {
            val item = waiting.removeAt(0)
            val maxWait = when {
                item.lane == Lane.FINAL -> Long.MAX_VALUE
                item.lane == Lane.URGENT -> urgentMaxWaitMillis
                item.essential -> essentialMaxWaitMillis
                else -> maxWaitMillis
            }
            if (nowMillis - item.queuedAt <= maxWait) {
                current = item
                return item.value
            }
        }
        return null
    }

    companion object {
        /** A kill heard more than this late is confusing, not useful. */
        const val URGENT_MAX_WAIT_MILLIS = 2_500L
        const val MAX_WAIT_MILLIS = 6_000L
        const val ESSENTIAL_MAX_WAIT_MILLIS = 12_000L

        /** A short breath between announcements, and between an effect and its callout. */
        const val GAP_MILLIS = 250L

        /** The same callout no more often than this. */
        const val VOICE_COOLDOWN_MILLIS = 8_000L
    }
}

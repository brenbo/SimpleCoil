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
package com.simplecoil.simplecoil.domain.session

/** Spawn/elimination status shown over the play screen. */
sealed interface SpawnPhase {
    /** Nothing shown (alive, or no game). */
    data object None : SpawnPhase

    /** Eliminated (or starting): respawn countdown in whole seconds. */
    data class Spawning(val seconds: Long, val eliminatedBy: String, val startingGame: Boolean) : SpawnPhase

    /** Eliminated with manual respawn: waiting for the player to start the respawn countdown. */
    data class AwaitingRespawn(val eliminatedBy: String, val needsSpawn: Boolean = false) : SpawnPhase

    /**
     * The respawn countdown is done, but the player is off the field ([outOfBounds]) or not at a
     * spawn yet: they respawn once that is fixed (map design: Border, Respawn points).
     */
    data class AwaitingReturn(val eliminatedBy: String, val startingGame: Boolean, val outOfBounds: Boolean = true) : SpawnPhase

    /** Eliminated with no lives left; no respawn. */
    data class OutOfLives(val eliminatedBy: String) : SpawnPhase

    /** Joined too late to play now: watching until the next round ([untilNextRound]) or game. */
    data class SittingOut(val untilNextRound: Boolean) : SpawnPhase
}

/** The game clock. */
sealed interface GameClock {
    /** Not running; [countdownText] is what the countdown view shows ("MM:SS"), if set. */
    data class Stopped(val countdownText: String?) : GameClock

    /** Counting up from [base] (SystemClock.elapsedRealtime()). */
    data class Chronometer(val base: Long) : GameClock

    /** Counting down; [text] is "MM:SS". */
    data class Countdown(val text: String) : GameClock
}

/** Everything the play screen shows from the blaster session. */
data class SessionState(
    /** A game has started and not yet ended (the player may be eliminated or respawning). */
    val gameInProgress: Boolean = false,
    val health: Int,
    val maxHealth: Int,
    val shotsRemaining: Byte = 0,
    /** Reload bar instead of the shot count; null until the first reload. */
    val reloadBarShown: Boolean? = null,
    val hitsTaken: Int = 0,
    /** Eliminations, or lives left with a lives limit. */
    val eliminationDisplay: Int = 0,
    val shotMode: Int,
    val recoilEnabled: Boolean = true,
    /** The player may change the shot mode: more than one mode is allowed. */
    val shotModeChangeable: Boolean = true,
    /** The player may toggle recoil: the server doesn't force it. */
    val recoilChangeable: Boolean = true,
    val battery: BatteryLevelPolicy.Level? = null,
    /** GattProtocolHandler.BLASTER_TYPE_*; null until the blaster reports it. */
    val blasterType: Byte? = null,
    val grenadePaired: Boolean = false,
    val spawn: SpawnPhase = SpawnPhase.None,
    /** Magazines left with limited ammo; null for unlimited (map design: Limited ammo). */
    val reserve: Int? = null,
    /** Full auto or burst from a drop or killstreak, until eliminated; null if none. */
    val poweredShotMode: Int? = null,
    /** Hits the overshield still stops. */
    val overshieldHits: Int = 0,
    /** Eliminations that will respawn in place. */
    val extraLives: Int = 0,
    /** Name shown by the "you're hit" overlay while it plays; null when not playing. */
    val hitOverlay: String? = null,
    /** Respawn shield seconds left; null when not shielded. */
    val shieldSeconds: Long? = null,
    val clock: GameClock = GameClock.Stopped(null),
)

/** Game sounds. SCORE is played by the network side, the rest by the session. */
enum class SessionSound {
    BEEP, ELIMINATED, EMPTY, HIT, RELOAD, SCORE, SHOT, SPAWN,

    /** The blaster connected, outside a game. */
    CONNECTED,

    /** A shot in burst or full auto (SHOT is single fire). */
    SHOT_AUTO,

    /** Our shot hit another player (HIT, OUT or SHIELDHIT from the server). */
    HIT_CONFIRM, HIT_ELIMINATED, HIT_SHIELDED,

    /** A running game ended, offline or networked. */
    GAME_END,

    // Map events (map design: Audio): each has its own sound, and a radio callout once recorded

    /** Leaving the field; back on it. */
    OUT_OF_BOUNDS, BACK_IN_BOUNDS,

    /** Health lost outside the border or the Battle Royale zone. */
    DAMAGE_TICK,

    /** Battle Royale: outside the zone, and the zone about to close. */
    ZONE_WARNING, ZONE_CLOSING,

    /** Spawns moving to new spots. */
    SPAWN_MOVING,

    /** A drop announced; a drop or power-up claimed. */
    DROP_INCOMING, POWER_UP,

    /** A killstreak reward, and a UAV coming online. */
    KILLSTREAK, UAV,

    /** Limited ammo: the last magazine is in. */
    LAST_MAG,

    /** Capture points: your team taking one, taking it, losing it, and the enemy taking one of yours. */
    CAPTURING, SECURED, LOST, ENEMY_CAPTURING,

    /** Hardpoint: the point moved. */
    POINT_MOVED,

    /** Capture the Flag: your flag taken; a flag captured. */
    FLAG_TAKEN, FLAG_CAPTURED,

    /** Search & Destroy: a round starting, the bomb planted, defused, going off. */
    ROUND_START, BOMB_PLANTED, BOMB_DEFUSED, BOMB_EXPLODED,

    /** Infection: you were infected. */
    INFECTED,

    /** Extraction: loot picked up, and banked. */
    LOOT, LOOT_BANKED,

    /** Extraction: the extraction zones open. */
    EXTRACTION_OPEN,

    /** The game is won or lost (an objective game). */
    VICTORY, DEFEAT,
}

enum class SessionToast { GRENADE_PAIRED, OUT_OF_LIVES, RECOIL_FORCED_ENABLED, RECOIL_FORCED_DISABLED, GAME_TIME_EXPIRED, OUT_OF_AMMO, EXTRA_LIFE_USED }

/** One-shot outputs. */
sealed interface SessionEffect {
    data class Sound(val sound: SessionSound) : SessionEffect
    data object Vibrate : SessionEffect
    data class Toast(val toast: SessionToast) : SessionEffect

    /** First telemetry packet of a connection: show the play screen, start network discovery. */
    data object CommunicationStarted : SessionEffect

    /** The engine sent the player id to the blaster: persist it and redisplay the team. */
    data object TeamSent : SessionEffect

    /** The blaster reports a new player id (already stored): redisplay the team. */
    data object PlayerIdReported : SessionEffect

    /** The thumb (mode) button was held for a long press; it did not cycle the shot mode. */
    data object ThumbLongPressed : SessionEffect

    /** Push to talk: the thumb button held, and let go (voice chat on). */
    data object TalkStarted : SessionEffect
    data object TalkStopped : SessionEffect

    /** The game must end (time ran out). */
    data object EndGameRequested : SessionEffect
}

/** Why a respawn waits (map design: Border, Respawn points). */
enum class RespawnHold {
    /** The player is off the field. */
    OUT_OF_BOUNDS,

    /** The player hasn't walked back to a spawn yet. */
    AWAY_FROM_SPAWN,
}

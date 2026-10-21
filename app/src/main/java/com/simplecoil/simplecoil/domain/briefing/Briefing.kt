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

package com.simplecoil.simplecoil.domain.briefing

import com.simplecoil.simplecoil.domain.loadout.AmmoMode
import com.simplecoil.simplecoil.domain.loadout.DropKind
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.OutOfBoundsPenalty
import com.simplecoil.simplecoil.domain.mode.GameMode
import kotlin.math.roundToInt

/** What a slide's picture shows; the briefing screen draws it (and animates it). */
enum class SlideArt {
    TRIGGER, RELOAD, THUMB, POWER,
    DROP, AMMO, KILLSTREAK,
    CLASSIC, CAPTURE, HARDPOINT, ZONE, MARKSMAN, FLAG, ONE_FLAG, LOOT, BOMB, INFECTION,
    TEAMS, FREE_FOR_ALL, RESPAWN, NO_RESPAWN, BORDER, WIN,
    /** Four teams: four groups of players (TEAMS is two). */
    FOUR_TEAMS,
}

/** The three parts of a briefing: the blaster's buttons, the drops, and how this game plays. */
enum class Section { BUTTONS, DROPS, MODE }

/**
 * One slide. [key] names it the same on every phone, so the server can say which one is up
 * while each phone draws its own (a pistol or a rifle; the game's settings as it knows them).
 */
data class Slide(val key: String, val section: Section, val title: String, val body: String, val art: SlideArt, val drop: DropKind? = null)

/** What the briefing is about: the game as the server will run it. */
data class BriefingInputs(
    /** The field (rules, drops, mode); null when there is none, or a classic game with GPS off. */
    val field: FieldConfig?,
    val freeForAll: Boolean,
    val teamCount: Int,
    val respawnSeconds: Long,
    val manualRespawn: Boolean,
    /** Lives each player has; 0 for no limit. */
    val livesLimit: Int,
    /** Minutes the game lasts; 0 for no limit. */
    val timeLimitMinutes: Int,
    /** Score that ends the game; 0 for none. */
    val scoreLimit: Int,
    val voiceChat: Boolean,
)

/**
 * The briefing the host walks everyone through before a game (the "instructions" button): what
 * the blaster's buttons do, what the drops do, and how this game mode plays with these settings.
 * Pure: the server and every phone build the same list from the same settings.
 */
object Briefing {

    fun slides(input: BriefingInputs): List<Slide> = buttons(input) + drops(input) + mode(input)

    /** Where "skip to the game mode" goes. */
    fun firstModeSlide(slides: List<Slide>): Slide? = slides.firstOrNull { it.section == Section.MODE }

    private fun buttons(input: BriefingInputs): List<Slide> {
        val limited = input.field?.loadout?.ammo?.let { it != AmmoMode.UNLIMITED } == true
        return listOf(
            Slide("buttons.trigger", Section.BUTTONS, "Trigger",
                "Pull it to shoot. Aim for the other players' sensors: every hit takes some of their health.", SlideArt.TRIGGER),
            Slide("buttons.reload", Section.BUTTONS, "Reload button",
                "Press it to reload a full magazine." +
                    (if (limited) " Each reload uses one of your spare magazines, so make them count." else "") +
                    (if (input.manualRespawn) " After you're eliminated, hold it to respawn." else ""),
                SlideArt.RELOAD),
            Slide("buttons.thumb", Section.BUTTONS, "Weapon switch",
                "Tap it to change fire mode: single shot, burst or full auto, whichever the host allows. " +
                    if (input.voiceChat) "Hold it to talk to your team on voice chat; let go to stop."
                    else "Hold it for two seconds to see the scoreboard.",
                SlideArt.THUMB),
            Slide("buttons.power", Section.BUTTONS, "Power button",
                "Turns the blaster on. A short press turns recoil on or off, if the host lets players choose.", SlideArt.POWER),
        )
    }

    private fun drops(input: BriefingInputs): List<Slide> {
        val loadout = input.field?.loadout ?: return emptyList()
        val slides = mutableListOf<Slide>()
        if (loadout.dropsEnabled && loadout.dropKinds.isNotEmpty()) {
            slides += Slide("drops.intro", Section.DROPS, "Supply drops",
                "About every ${loadout.dropIntervalMinutes} min a crate falls on the field. You get ${loadout.dropWarningSeconds} s of " +
                    "warning and its spot on your map. Stand on it for ${loadout.claimSeconds} s to claim it before anyone else does." +
                    if (loadout.dropsNearestTeamOnly) " Each crate shows only to the team whose spawn is nearest." else "",
                SlideArt.DROP)
            for (kind in DropKind.TIMED.filter { it in loadout.dropKinds }) {
                slides += Slide("drops.${kind.wireValue}", Section.DROPS, dropName(kind), dropText(kind, input), SlideArt.DROP, kind)
            }
        }
        if (loadout.ammo != AmmoMode.UNLIMITED) {
            slides += Slide("drops.ammo", Section.DROPS, "Limited ammo",
                "You start each life with ${loadout.startingReserve} spare magazines." +
                    (if (loadout.refillAtSpawn) " Standing in your own spawn fills them up again." else "") +
                    (if (loadout.ammo == AmmoMode.SCAVENGER) " A player who's eliminated leaves ${loadout.scavengeMagazines} " +
                        "magazines where they fell, for ${loadout.scavengeSeconds} s: grab them." else ""),
                SlideArt.AMMO)
        }
        if (loadout.killstreaks && (loadout.uavKills > 0 || loadout.fullAutoKills > 0)) {
            val rewards = listOfNotNull(
                loadout.uavKills.takeIf { it > 0 }?.let { "$it in a row: a UAV shows every enemy on your map for ${loadout.uavSeconds} s" },
                loadout.fullAutoKills.takeIf { it > 0 }?.let { "$it in a row: full auto" },
            )
            slides += Slide("drops.killstreaks", Section.DROPS, "Killstreaks",
                "Eliminate players without being eliminated yourself. ${rewards.joinToString("; ")}. Dying starts the count over.",
                SlideArt.KILLSTREAK)
        }
        return slides
    }

    private fun dropName(kind: DropKind) = when (kind) {
        DropKind.SUPPLY -> "Full auto"
        DropKind.BURST -> "Burst"
        DropKind.AMMO -> "Ammo"
        DropKind.MEDKIT -> "Medkit"
        DropKind.EXTRA_LIFE -> "Extra life"
        DropKind.OVERSHIELD -> "Overshield"
        DropKind.UAV -> "UAV"
        DropKind.SCAVENGE -> "Scavenged ammo"
    }

    private fun dropText(kind: DropKind, input: BriefingInputs): String {
        val loadout = input.field?.loadout
        val untilDeath = if (kind.lostOnDeath) " You keep it until you're eliminated." else ""
        return when (kind) {
            DropKind.SUPPLY -> "Unlocks full auto: hold the trigger and keep firing. Switch to it with the weapon switch.$untilDeath"
            DropKind.BURST -> "Unlocks three-round burst on the weapon switch.$untilDeath"
            DropKind.AMMO -> "A full set of spare magazines."
            DropKind.MEDKIT -> "Back to full health at once."
            DropKind.EXTRA_LIFE -> "The next time you're eliminated you're straight back in, right where you are: no countdown, no walk back."
            DropKind.OVERSHIELD -> "The next ${loadout?.overshieldHits ?: 3} hits on you do nothing.$untilDeath"
            DropKind.UAV -> "Every enemy shows on your map for ${loadout?.uavSeconds ?: 15} s."
            DropKind.SCAVENGE -> "Spare magazines left where a player fell."
        }
    }

    private fun mode(input: BriefingInputs): List<Slide> {
        val field = input.field
        val mode = field?.mode ?: GameMode.CLASSIC
        val slides = mutableListOf(goal(mode, input))
        slides += teams(mode, input)
        slides += respawns(mode, input)
        field?.border?.let { slides += border(input) }
        slides += winning(mode, input)
        return slides
    }

    private fun goal(mode: GameMode, input: BriefingInputs): Slide {
        val field = input.field
        val obj = field?.objectiveRules
        val side = if (input.freeForAll) "you" else "your team"
        return when (mode) {
            GameMode.CLASSIC -> Slide("mode.goal", Section.MODE, "Classic",
                "Eliminate the other players. Every elimination scores a point for $side.", SlideArt.CLASSIC)
            GameMode.DOMINATION -> Slide("mode.goal", Section.MODE, "Domination",
                "Capture the points on your map (A, B, C…) by standing in one for ${obj?.captureSeconds ?: 15} s" +
                    (if (obj?.moreCaptureFaster != false) ", faster with more of $side there" else "") +
                    ". Every point $side holds scores ${obj?.pointsPerTick ?: 1} every ${obj?.tickSeconds ?: 5} s. " +
                    "An enemy standing in it stops the capture, so defend what you take.",
                SlideArt.CAPTURE)
            GameMode.HARDPOINT -> Slide("mode.goal", Section.MODE, "Hardpoint",
                "One point is live at a time and it moves every ${obj?.moveSeconds ?: 90} s; your map shows where it goes next. " +
                    "Stand in it to score ${obj?.pointsPerTick ?: 1} every ${obj?.tickSeconds ?: 5} s. With both sides in it, nobody scores.",
                SlideArt.HARDPOINT)
            GameMode.BATTLE_ROYALE -> {
                val br = field?.battleRoyale
                Slide("mode.goal", Section.MODE, "Battle Royale",
                    "The safe zone on your map shrinks in ${br?.phases ?: 4} steps. Outside it you lose " +
                        "${br?.damagePerTick ?: 1} health every ${br?.tickSeconds ?: 3} s" +
                        (if (br?.damageRises != false) ", more each step" else "") +
                        ". Stay inside, and be the last one standing.",
                    SlideArt.ZONE)
            }
            GameMode.MARKSMAN -> {
                val m = field?.marksman
                Slide("mode.goal", Section.MODE, "Marksman",
                    "Every elimination scores its distance, so long shots count most." +
                        (if ((m?.minimumMeters ?: 0.0) > 0) " Shots closer than ${m!!.minimumMeters.roundToInt()} m score nothing." else ""),
                    SlideArt.MARKSMAN)
            }
            GameMode.CAPTURE_THE_FLAG -> {
                val f = field?.flag
                if (f?.oneFlag == true) Slide("mode.goal", Section.MODE, "Capture the Flag: one flag",
                    "One flag sits in the middle of the field. Grab it and bring it back to your own base to score." +
                        " While both teams are on it, nobody can take it." +
                        " A carrier who's eliminated drops the flag; anyone can grab it, and it goes back to the middle after ${f.droppedReturnsSeconds} s." +
                        " The carrier shows on the other team's map every ${f.carrierShownEverySeconds} s.",
                    SlideArt.ONE_FLAG)
                else Slide("mode.goal", Section.MODE, "Capture the Flag",
                    "Run to the other team's base, take their flag, and bring it back to yours." +
                        (if (f?.ownFlagHomeToScore != false) " Your own flag has to be home to score." else "") +
                        " A carrier who's eliminated drops the flag; it goes home after ${f?.droppedReturnsSeconds ?: 30} s." +
                        " The carrier shows on everyone's map every ${f?.carrierShownEverySeconds ?: 10} s.",
                    SlideArt.FLAG)
            }
            GameMode.EXTRACTION -> {
                val e = field?.extraction
                Slide("mode.goal", Section.MODE, "Extraction",
                    "Loot lies around the field. Stand on a pile for ${e?.collectSeconds ?: 10} s to pick it up, then carry it to an " +
                        "extraction zone to bank it. The zones open after ${e?.opensAfterMinutes ?: 6} min: red until then, green once " +
                        "they're open. Get eliminated and you drop what you carry.",
                    SlideArt.LOOT)
            }
            GameMode.SEARCH_AND_DESTROY -> {
                val b = field?.bomb
                Slide("mode.goal", Section.MODE, "Search & Destroy",
                    "One side attacks, one defends. An attacker carries the bomb: stand at a site for ${b?.plantSeconds ?: 10} s to " +
                        "plant it, and it goes off ${b?.fuseSeconds ?: 45} s later. Defenders stop the plant, or stand at the bomb for " +
                        "${b?.defuseSeconds ?: 15} s to defuse it. Eliminating the whole other side also wins the round." +
                        (if (b?.swapSides != false) " Sides swap at half time." else ""),
                    SlideArt.BOMB)
            }
            GameMode.INFECTION -> {
                val inf = field?.infection
                Slide("mode.goal", Section.MODE, "Infection",
                    "${inf?.startingInfected ?: 1} player${if ((inf?.startingInfected ?: 1) == 1) "" else "s"} start infected. Anyone " +
                        "the infected eliminate joins them. Survivors win by lasting ${inf?.roundMinutes ?: 10} min; the infected win by " +
                        "catching everyone. Every ${inf?.revealEverySeconds ?: 60} s the survivors show on the infected's maps.",
                    SlideArt.INFECTION)
            }
        }
    }

    private fun teams(mode: GameMode, input: BriefingInputs): Slide = when {
        mode == GameMode.INFECTION -> Slide("mode.teams", Section.MODE, "Survivors and infected",
            "There are no teams to start with: it's survivors against the infected, and the infected keep growing.", SlideArt.FREE_FOR_ALL)
        input.freeForAll -> Slide("mode.teams", Section.MODE, "Everyone for themselves",
            "No teams: every other player is an enemy. Trust nobody.", SlideArt.FREE_FOR_ALL)
        else -> Slide("mode.teams", Section.MODE, "${input.teamCount} teams",
            "Your team's color is on your screen." +
                (if (input.voiceChat) " Hold the weapon switch to talk to them." else "") +
                " Friendly fire doesn't count, so shoot the other colors.",
            if (input.teamCount == 4) SlideArt.FOUR_TEAMS else SlideArt.TEAMS)
    }

    private fun respawns(mode: GameMode, input: BriefingInputs): Slide {
        val spawns = input.field?.spawnRules
        val walkBack = spawns?.walkBack == true && input.field.spawns.isNotEmpty()
        val howBack = buildString {
            append("After you're eliminated you wait ${input.respawnSeconds} s")
            if (walkBack) append(" and walk back to one of your team's spawns (your map shows the way)")
            if (input.manualRespawn) append(", then hold the reload button to come back in") else append(" before you're back in")
            append(".")
        }
        return when {
            mode == GameMode.SEARCH_AND_DESTROY -> Slide("mode.respawn", Section.MODE, "No respawns",
                "Eliminated means out for the rest of the round. Everyone comes back for the next round" +
                    (if (walkBack) ", once they've all walked back to their spawns." else "."),
                SlideArt.NO_RESPAWN)
            mode == GameMode.BATTLE_ROYALE -> {
                val until = input.field?.battleRoyale?.respawnUntilPhase ?: 3
                if (until <= 0) Slide("mode.respawn", Section.MODE, "No respawns",
                    "Eliminated means out for the rest of the game.", SlideArt.NO_RESPAWN)
                else Slide("mode.respawn", Section.MODE, "Respawns run out",
                    "$howBack That lasts until the zone's step $until; after that, eliminated means out for good.", SlideArt.RESPAWN)
            }
            mode == GameMode.INFECTION -> Slide("mode.respawn", Section.MODE, "Respawns",
                "$howBack If an infected player got you, you come back infected.", SlideArt.RESPAWN)
            input.livesLimit > 0 -> Slide("mode.respawn", Section.MODE, "${input.livesLimit} lives",
                "$howBack You have ${input.livesLimit} lives: when they run out, you're out of the game.", SlideArt.RESPAWN)
            else -> Slide("mode.respawn", Section.MODE, "Respawns", howBack, SlideArt.RESPAWN)
        }
    }

    private fun border(input: BriefingInputs): Slide {
        val rules = input.field?.rules ?: return Slide("mode.border", Section.MODE, "Stay on the field", "", SlideArt.BORDER)
        val penalty = when (rules.penalty) {
            OutOfBoundsPenalty.DISABLE_BLASTER -> "your blaster stops working until you're back"
            OutOfBoundsPenalty.LOSE_HEALTH -> "you lose ${rules.healthPerTick} health every ${rules.tickSeconds} s until you're back"
            OutOfBoundsPenalty.ELIMINATED -> "you're eliminated"
            OutOfBoundsPenalty.WARNING_ONLY -> "you'll get a warning"
        }
        return Slide("mode.border", Section.MODE, "Stay on the field",
            "The field's edge is the red line on your map. Step outside and you get ${rules.countdownSeconds} s to come back; " +
                "after that, $penalty.",
            SlideArt.BORDER)
    }

    private fun winning(mode: GameMode, input: BriefingInputs): Slide {
        val field = input.field
        val side = if (input.freeForAll) "player" else "team"
        val target = when (mode) {
            GameMode.CLASSIC -> if (input.scoreLimit > 0) "The first $side to ${input.scoreLimit} eliminations wins." else null
            GameMode.DOMINATION, GameMode.HARDPOINT -> "The first $side to ${field?.objectiveRules?.scoreToWin ?: 200} points wins."
            GameMode.BATTLE_ROYALE -> "The last $side standing wins."
            GameMode.MARKSMAN -> field?.marksman?.distanceToWinMeters?.takeIf { it > 0 }
                ?.let { "The first $side to ${it.roundToInt()} m in total wins." } ?: "The longest total distance wins."
            GameMode.CAPTURE_THE_FLAG -> "The first team to ${field?.flag?.capturesToWin ?: 3} captures wins."
            GameMode.EXTRACTION -> field?.extraction?.lootToWin?.takeIf { it > 0 }
                ?.let { "The first $side to bank $it loot wins." } ?: "The most loot banked wins."
            GameMode.SEARCH_AND_DESTROY -> "The first team to win ${field?.bomb?.roundsToWin ?: 4} rounds wins."
            GameMode.INFECTION -> "Survivors win by lasting the round; the infected win by catching everyone."
        }
        val clock = if (input.timeLimitMinutes > 0) "The game ends after ${input.timeLimitMinutes} min" +
            (if (mode == GameMode.CLASSIC || target == null) "." else " if nobody has won by then.") else null
        val text = listOfNotNull(target, clock).joinToString(" ").ifEmpty { "The game runs until the host ends it." }
        return Slide("mode.win", Section.MODE, "How to win", "$text Good luck!", SlideArt.WIN)
    }
}

/**
 * The briefing as it runs: [id] for this showing (a new one each time the host starts one) and
 * the [slide] up now, by key. Sent by the field service (`SCFIELD1 BRIEF -`): JSON, or NONE when
 * no briefing is running.
 *
 * It carries the host's whole slide, [shown], with where it is in the show ([index] of [total]),
 * so every phone says exactly what the host's screen says, whatever settings the phone has.
 */
data class BriefingNow(val id: Long, val slide: String, val shown: Slide? = null, val index: Int = -1, val total: Int = 0) {
    fun toBytes(): ByteArray {
        val json = org.json.JSONObject().put("id", id).put("slide", slide)
        shown?.let {
            json.put("section", it.section.name).put("title", it.title).put("body", it.body).put("art", it.art.name)
                .put("index", index).put("total", total)
            it.drop?.let { drop -> json.put("drop", drop.wireValue) }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromBytes(bytes: ByteArray): BriefingNow? = runCatching {
            val json = org.json.JSONObject(String(bytes, Charsets.UTF_8))
            val key = json.getString("slide")
            val shown = if (json.has("title")) runCatching {
                Slide(key, Section.valueOf(json.getString("section")), json.getString("title"), json.getString("body"),
                    SlideArt.valueOf(json.getString("art")), DropKind.fromWire(json.optString("drop").ifEmpty { null }))
            }.getOrNull() else null
            BriefingNow(json.getLong("id"), key, shown, json.optInt("index", -1), json.optInt("total", 0))
        }.getOrNull()
    }
}

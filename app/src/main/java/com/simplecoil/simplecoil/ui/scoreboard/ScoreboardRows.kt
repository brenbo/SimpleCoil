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

package com.simplecoil.simplecoil.ui.scoreboard

import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.domain.team.TeamColors
import com.simplecoil.simplecoil.domain.team.TeamLayout

/** One line of the scoreboard. Everything a row shows is in it, so DiffUtil sees every change. */
sealed interface ScoreboardRow {
    /** The column titles; the last column is lives remaining when a lives limit is on. */
    data class ColumnHeader(val livesColumn: Boolean) : ScoreboardRow

    /** A team's header ([team] 0: the free-for-all "players" header). Tapping it edits the team sizes when [editable]. */
    data class TeamHeader(
        val team: Int,
        val playerCount: Int,
        val capacity: Int,
        val score: Int,
        val editable: Boolean,
        /** Voice chat's mute-all for the team: null when not shown, else whether all of them are muted here. */
        val allMuted: Boolean? = null,
        /** The colour it's shown in; [TeamColors.NONE] for none. */
        val color: Int = TeamColors.colorOf(team),
        /** A squad's number (squad mode), else null. */
        val squad: Int? = null,
        /** This squad is the one being edited. */
        val editing: Boolean = false,
        /** Squad mode, ranked: a player alone, as a squad of one ([team] is their id). */
        val solo: Boolean = false,
        /**
         * A player dragged under this header joins this team: a team, a squad's team, or 0 to
         * play alone (squad mode); null when players can't be dragged.
         */
        val dropTeam: Int? = null,
    ) : ScoreboardRow

    data class Player(
        val playerId: Byte,
        val name: String?,
        val points: Int,
        /** Lives remaining when the player has a lives limit, eliminations otherwise. */
        val livesOrEliminations: Int,
        val connected: Boolean,
        /** This phone's own player. */
        val isSelf: Boolean,
        val kickable: Boolean,
        /** GattProtocolHandler.BLASTER_TYPE_*; 0 when unknown. */
        val blasterType: Byte = 0,
        val hasGrenade: Boolean = false,
        /** Voice chat's mute button: null when not shown (no voice chat, or this phone's player), else muted here. */
        val muted: Boolean? = null,
        /** Editing a squad: true to show its add button, false its remove button, null neither. */
        val squadAdd: Boolean? = null,
        /** Ranked by player: the colour of their team or squad beside their name ([TeamColors.NONE]: none). */
        val color: Int = TeamColors.NONE,
        /** Can be long-pressed and dragged onto another team or squad. */
        val draggable: Boolean = false,
    ) : ScoreboardRow

    /**
     * Squad mode on the server, in the lobby: Create squad, or with squad [editing] being edited,
     * how to and Done. [full]: all 64 squads are made.
     */
    data class SquadBar(val editing: Int?, val full: Boolean) : ScoreboardRow

    /** How the board is ranked (teams or squads only): by [rank], tapped to choose. [squads]: says squad, not team. */
    data class RankBar(val rank: ScoreboardRank, val squads: Boolean) : ScoreboardRow
}

/** How the scoreboard ranks: the winning team (or squad) first, or the best player first. */
enum class ScoreboardRank { TEAMS, PLAYERS }

/** What the scoreboard needs besides the players. */
data class ScoreboardContext(
    val layout: TeamLayout,
    val gameLimit: Int,
    val livesLimit: Int,
    /** The play screen's scoreboard (a client), as opposed to the dedicated server's. */
    val isClient: Boolean,
    /** This phone's player id; 0 on the server. */
    val selfId: Byte,
    /** This phone's player is a server admin. */
    val isAdmin: Boolean,
    /** Voice chat is on for this phone: rows get mute buttons. */
    val voiceOn: Boolean = false,
    /** The players muted on this phone. */
    val muted: Set<Int> = emptySet(),
    /** Squad mode on the server: the squads can be changed (in the lobby). */
    val squadsEditable: Boolean = false,
    /** The squad being edited (it may have nobody in it yet), or null. */
    val editingSquad: Int? = null,
    /** Teams (or squads) best first, or every player best first. */
    val rank: ScoreboardRank = ScoreboardRank.TEAMS,
    /** The rank-by dropdown is offered (the server: a wide screen, in a game); without it, teams. */
    val rankOffered: Boolean = true,
    /** The host or an admin between games: players drag onto other teams or squads (not in free for all). */
    val teamsDraggable: Boolean = false,
)

/**
 * Groups and sorts the scoreboard: teams by total score, then lives remaining (with a lives
 * limit) or fewest eliminations, then team number; players within a team by score, then lives
 * remaining or fewest eliminations, then name, then id. Pure; moved from
 * PlayerDisplayDataListAdapter.
 */
object ScoreboardRows {

    fun build(players: Array<PlayerDisplayData?>?, context: ScoreboardContext): List<ScoreboardRow> {
        val rows = mutableListOf<ScoreboardRow>()
        val livesFlag = context.gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES != 0
        rows += ScoreboardRow.ColumnHeader(livesFlag)
        val canEdit = !context.isClient || context.isAdmin
        val layout = context.layout
        fun at(id: Int): PlayerDisplayData? = if (players != null && id < players.size) players[id] else null

        // With teams or squads: the rank-by dropdown, above the column titles
        if (!layout.mode.isFreeForAll && context.rankOffered) rows.add(0, ScoreboardRow.RankBar(context.rank, layout.mode.isSquads))
        if (!layout.mode.isFreeForAll && context.rankOffered && context.rank == ScoreboardRank.PLAYERS) {
            // Every player best first: eliminations less deaths, then score, then as the teams sort them
            val all = (1..GameSettingsRepository.MAX_PLAYER_ID).mapNotNull { at(it) }
            val best = compareByDescending<PlayerDisplayData> { it.points - it.eliminated }.thenByDescending { it.points }
                .then(playerOrder(context))
            all.sortedWith(best).forEach { rows += playerRow(it, context, rank = true) }
            return rows
        }

        if (layout.mode.isSquads) {
            buildSquads(rows, (1..GameSettingsRepository.MAX_PLAYER_ID).mapNotNull { at(it) }, context, livesFlag)
            return rows
        }

        if (layout.mode.isFreeForAll) {
            val all = (1..GameSettingsRepository.MAX_PLAYER_ID).mapNotNull { at(it) }
            rows += ScoreboardRow.TeamHeader(0, 0, 0, 0, canEdit, allMuted(all, context))
            val list = (1..GameSettingsRepository.MAX_PLAYER_ID).mapNotNull { at(it) }.sortedWith(playerOrder(context))
            list.forEach { rows += playerRow(it, context) }
            return rows
        }

        class Team(val number: Int) {
            val members = mutableListOf<PlayerDisplayData>()
            var points = 0
            var livesRemaining = 0
            var eliminations = 0
            var livesLimitSet = false
        }
        val teams = (1..layout.mode.teamCount).map { t ->
            Team(t).apply {
                for (id in layout.teamStartId(t)..layout.teamEndId(t)) {
                    val p = at(id) ?: continue
                    members += p
                    points += p.points
                    eliminations += p.eliminated
                    if (isLivesLimitSet(p, context)) {
                        livesLimitSet = true
                        livesRemaining += livesRemaining(p, context)
                    }
                }
            }
        }
        val sizes = layout.teamSizes()
        val teamOrder = Comparator<Team> { t1, t2 ->
            if (t1.points != t2.points) return@Comparator t2.points.compareTo(t1.points)
            if (t1.livesLimitSet || t2.livesLimitSet || livesFlag) {
                if (t1.livesRemaining != t2.livesRemaining) return@Comparator t2.livesRemaining.compareTo(t1.livesRemaining)
            } else if (t1.eliminations != t2.eliminations) {
                return@Comparator t1.eliminations.compareTo(t2.eliminations)
            }
            t1.number.compareTo(t2.number)
        }
        for (team in teams.sortedWith(teamOrder)) {
            val capacity = sizes.getOrElse(team.number - 1) { 16 }
            rows += ScoreboardRow.TeamHeader(team.number, team.members.size, capacity, team.points, canEdit, allMuted(team.members, context),
                dropTeam = team.number.takeIf { context.teamsDraggable })
            for (p in team.members.sortedWith(playerOrder(context))) rows += playerRow(p, context)
        }
        return rows
    }

    /**
     * Squad mode: on the server the squad bar (Create squad, or Done), then each squad under its
     * coloured header (best first, as teams are; the one being edited even with nobody in it
     * yet), then everyone in no squad under the players header.
     */
    private fun buildSquads(rows: MutableList<ScoreboardRow>, all: List<PlayerDisplayData>, context: ScoreboardContext, livesFlag: Boolean) {
        val layout = context.layout
        val editing = context.editingSquad.takeIf { context.squadsEditable }
        // At the very top, above the column titles
        if (context.squadsEditable) rows.add(0, ScoreboardRow.SquadBar(editing, full = layout.squads.size >= TeamLayout.MAX_SQUADS && editing == null))
        val numbers = (layout.squads.keys + listOfNotNull(editing)).toSortedSet()
        val squads = numbers.map { n -> n to all.filter { layout.squadOf(it.playerID.toInt()) == n } }
        fun total(members: List<PlayerDisplayData>) = members.sumOf { it.points }
        fun lives(members: List<PlayerDisplayData>) = members.sumOf { livesRemaining(it, context) }
        val order = Comparator<Pair<Int, List<PlayerDisplayData>>> { (n1, m1), (n2, m2) ->
            if (total(m1) != total(m2)) return@Comparator total(m2).compareTo(total(m1))
            if (livesFlag && lives(m1) != lives(m2)) return@Comparator lives(m2).compareTo(lives(m1))
            // Ties: squads (positive numbers) before players alone (their negated ids)
            if ((n1 > 0) != (n2 > 0)) return@Comparator if (n1 > 0) -1 else 1
            kotlin.math.abs(n1).compareTo(kotlin.math.abs(n2))
        }
        fun addSquad(n: Int, members: List<PlayerDisplayData>) {
            val team = TeamLayout.SQUAD_TEAM_BASE + n
            rows += ScoreboardRow.TeamHeader(team, members.size, 0, total(members), context.squadsEditable,
                allMuted(members, context), color = layout.colorOf(team), squad = n, editing = n == editing,
                dropTeam = team.takeIf { context.teamsDraggable })
            for (p in members.sortedWith(playerOrder(context))) rows += playerRow(p, context)
        }
        val alone = all.filter { layout.squadOf(it.playerID.toInt()) == null }
        val aloneDrop = 0.takeIf { context.teamsDraggable }
        if (editing != null || numbers.isEmpty()) {
            // Making squads (or none yet): squads in number order, so the edited one's header
            // doesn't jump about, then everyone else together, where the + buttons are
            for ((n, members) in squads) addSquad(n, members)
            if (alone.isNotEmpty() || numbers.isEmpty()) {
                rows += ScoreboardRow.TeamHeader(0, 0, 0, 0, false, allMuted(alone, context), dropTeam = aloneDrop)
                for (p in alone.sortedWith(playerOrder(context))) rows += playerRow(p, context)
            }
            return
        }
        // Ranked: a player alone is a squad of one, in among the squads by score
        val entries = squads.map { it.first to it.second } + alone.map { -it.playerID.toInt() to listOf(it) }
        // Players alone next to each other share one bar, so they don't look like the squad above's
        val run = ArrayList<PlayerDisplayData>()
        fun flushAlone() {
            if (run.isEmpty()) return
            rows += ScoreboardRow.TeamHeader(run.first().playerID.toInt(), run.size, 0, run.sumOf { it.points }, false, null,
                color = TeamColors.NONE, solo = true, dropTeam = aloneDrop)
            run.forEach { rows += playerRow(it, context) }
            run.clear()
        }
        for ((n, members) in entries.sortedWith(order)) {
            if (n > 0) {
                flushAlone()
                addSquad(n, members)
            } else {
                run += members.single()
            }
        }
        flushAlone()
    }

    private fun playerRow(p: PlayerDisplayData, context: ScoreboardContext, rank: Boolean = false): ScoreboardRow.Player {
        val id = p.playerID
        val stat = when {
            p.overrideLives -> if (p.lives != 0) p.lives - p.eliminated else p.eliminated
            context.gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES != 0 -> context.livesLimit - p.eliminated
            else -> p.eliminated
        }
        val canKick = !context.isClient || context.isAdmin
        val idInt = id.toInt()
        return ScoreboardRow.Player(
            playerId = id,
            name = p.playerName,
            points = p.points,
            livesOrEliminations = stat,
            connected = p.isConnected,
            isSelf = id == context.selfId,
            kickable = canKick && idInt in 1..GameSettingsRepository.MAX_PLAYER_ID && id != context.selfId,
            blasterType = p.blasterType,
            hasGrenade = p.hasGrenade,
            muted = (idInt in context.muted).takeIf { context.voiceOn && id != context.selfId && teammate(idInt, context) },
            squadAdd = context.editingSquad?.takeIf { context.squadsEditable && context.rank == ScoreboardRank.TEAMS }?.let { context.layout.squadOf(idInt) != it },
            color = if (rank) context.layout.colorOf(context.layout.teamOf(idInt)) else TeamColors.NONE,
            draggable = context.teamsDraggable && !rank && !context.layout.mode.isFreeForAll,
        )
    }

    /**
     * [playerId] is on this phone's team: the only players heard (in free for all nobody is, and
     * an admin talking to everyone can't be muted), so the only ones with a mute button.
     */
    private fun teammate(playerId: Int, context: ScoreboardContext): Boolean =
        !context.layout.mode.isFreeForAll && context.layout.teamOf(playerId) == context.layout.teamOf(context.selfId)

    /** The team header's mute-all: this phone's team only, with someone else on it; all of them muted here? */
    private fun allMuted(members: List<PlayerDisplayData>, context: ScoreboardContext): Boolean? {
        if (!context.voiceOn) return null
        val others = members.map { it.playerID.toInt() }.filter { it != context.selfId.toInt() && teammate(it, context) }
        return if (others.isEmpty()) null else others.all { it in context.muted }
    }

    private fun isLivesLimitSet(p: PlayerDisplayData, context: ScoreboardContext): Boolean =
        if (p.overrideLives) p.lives != 0 else context.gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES != 0

    private fun livesRemaining(p: PlayerDisplayData, context: ScoreboardContext): Int = when {
        p.overrideLives -> if (p.lives != 0) p.lives - p.eliminated else 0
        context.gameLimit and GameSettingsRepository.GAME_LIMIT_LIVES != 0 -> context.livesLimit - p.eliminated
        else -> 0
    }

    private fun playerOrder(context: ScoreboardContext) = Comparator<PlayerDisplayData> { p1, p2 ->
        if (p1.points != p2.points) return@Comparator p2.points.compareTo(p1.points)
        if (isLivesLimitSet(p1, context) || isLivesLimitSet(p2, context)) {
            val l1 = livesRemaining(p1, context)
            val l2 = livesRemaining(p2, context)
            if (l1 != l2) return@Comparator l2.compareTo(l1)
        } else if (p1.eliminated != p2.eliminated) {
            return@Comparator p1.eliminated.compareTo(p2.eliminated)
        }
        val byName = (p1.playerName ?: "").compareTo(p2.playerName ?: "", ignoreCase = true)
        if (byName != 0) return@Comparator byName
        (p1.playerID.toInt() and 0xFF).compareTo(p2.playerID.toInt() and 0xFF)
    }
}

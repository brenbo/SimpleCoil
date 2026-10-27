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
import com.simplecoil.simplecoil.domain.team.TeamLayout
import com.simplecoil.simplecoil.domain.team.TeamColors
import com.simplecoil.simplecoil.domain.team.TeamMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scoreboard's grouping and sorting; ported from PlayerDisplayDataListAdapterTest with the same orderings. */
class ScoreboardRowsTest {

    private fun context(
        mode: TeamMode = TeamMode.FREE_FOR_ALL,
        gameLimit: Int = GameSettingsRepository.GAME_LIMIT_NONE,
        livesLimit: Int = 0,
        isClient: Boolean = false,
        selfId: Byte = 0,
        isAdmin: Boolean = false,
    ) = ScoreboardContext(TeamLayout.defaultFor(mode), gameLimit, livesLimit, isClient, selfId, isAdmin)

    private fun players(vararg list: PlayerDisplayData): Array<PlayerDisplayData?> {
        val data = arrayOfNulls<PlayerDisplayData>(GameSettingsRepository.MAX_PLAYER_ID + 2)
        list.forEach { data[it.playerID.toInt()] = it }
        return data
    }

    private fun player(id: Int, name: String, points: Int, eliminated: Int = 0) = PlayerDisplayData().apply {
        playerID = id.toByte()
        playerName = name
        this.points = points
        this.eliminated = eliminated
    }

    private fun List<ScoreboardRow>.playerIds() = filterIsInstance<ScoreboardRow.Player>().map { it.playerId.toInt() }

    @Test
    fun ffa_sortsByScore() {
        val rows = ScoreboardRows.build(players(player(1, "Player 1", 10), player(2, "Player 2", 25), player(3, "Player 3", 15)), context())
        // Column header, the PLAYERS header, then 2 (25), 3 (15), 1 (10)
        assertEquals(5, rows.size)
        assertTrue(rows[0] is ScoreboardRow.ColumnHeader)
        assertEquals(0, (rows[1] as ScoreboardRow.TeamHeader).team)
        assertEquals(listOf(2, 3, 1), rows.playerIds())
    }

    @Test
    fun ffa_sortsByFewestEliminations_withoutALivesLimit() {
        val rows = ScoreboardRows.build(players(player(1, "Player 1", 10, 3), player(2, "Player 2", 10, 0), player(3, "Player 3", 10, 1)), context())
        assertEquals(listOf(2, 3, 1), rows.playerIds())
    }

    @Test
    fun ffa_sortsByMostLivesRemaining_withALivesLimit() {
        val rows = ScoreboardRows.build(
            players(player(1, "Player 1", 10, 4), player(2, "Player 2", 10, 1), player(3, "Player 3", 10, 2)),
            context(gameLimit = GameSettingsRepository.GAME_LIMIT_LIVES, livesLimit = 10),
        )
        assertEquals(listOf(2, 3, 1), rows.playerIds())
        assertEquals(listOf(9, 8, 6), rows.filterIsInstance<ScoreboardRow.Player>().map { it.livesOrEliminations })
        assertTrue((rows[0] as ScoreboardRow.ColumnHeader).livesColumn)
    }

    @Test
    fun ffa_sortsByNameIgnoringCase() {
        val rows = ScoreboardRows.build(players(player(1, "Charlie", 10), player(2, "alice", 10), player(3, "Bob", 10)), context())
        assertEquals(listOf(2, 3, 1), rows.playerIds())
    }

    @Test
    fun teams_sortByTeamScore_withTheirPlayersUnderThem() {
        val rows = ScoreboardRows.build(
            players(player(1, "Team 1 Low", 5), player(2, "Team 1 High", 15), player(33, "Team 2 High", 50)),
            context(mode = TeamMode.TWO_TEAMS),
        )
        // Rank-by, column header, team 2 (50), 33, team 1 (20), 2 (15), 1 (5)
        assertEquals(7, rows.size)
        assertEquals(ScoreboardRow.RankBar(ScoreboardRank.TEAMS, squads = false), rows[0])
        assertEquals(ScoreboardRow.TeamHeader(2, 1, 32, 50, true), rows[2])
        assertEquals(33, (rows[3] as ScoreboardRow.Player).playerId.toInt())
        assertEquals(ScoreboardRow.TeamHeader(1, 2, 32, 20, true), rows[4])
        assertEquals(listOf(33, 2, 1), rows.playerIds())
    }

    @Test
    fun overriddenLives_showLivesRemaining() {
        val p = player(1, "A", 0, 2).apply {
            overrideLives = true
            lives = 5
        }
        val row = ScoreboardRows.build(players(p), context()).filterIsInstance<ScoreboardRow.Player>().single()
        assertEquals(3, row.livesOrEliminations)
    }

    @Test
    fun client_editsAndKicksOnlyAsAnAdmin_andNeverKicksItself() {
        val data = players(player(1, "Me", 0), player(17, "Them", 0))
        val plain = ScoreboardRows.build(data, context(mode = TeamMode.TWO_TEAMS, isClient = true, selfId = 1))
        assertFalse(plain.filterIsInstance<ScoreboardRow.TeamHeader>().any { it.editable })
        assertFalse(plain.filterIsInstance<ScoreboardRow.Player>().any { it.kickable })

        val admin = ScoreboardRows.build(data, context(mode = TeamMode.TWO_TEAMS, isClient = true, selfId = 1, isAdmin = true))
        assertTrue(admin.filterIsInstance<ScoreboardRow.TeamHeader>().all { it.editable })
        val byId = admin.filterIsInstance<ScoreboardRow.Player>().associateBy { it.playerId.toInt() }
        assertFalse(byId.getValue(1).kickable)
        assertTrue(byId.getValue(1).isSelf)
        assertTrue(byId.getValue(17).kickable)
    }

    @Test
    fun withVoiceChat_teammatesGetAMuteButton_andYourTeamAMuteAll_nobodyElse() {
        // Two teams: 1 (me), 2 and 3 on team 1, 33 and 34 on team 2
        val data = players(player(1, "Me", 0), player(2, "Mate", 0), player(3, "Pal", 0), player(33, "A", 0), player(34, "B", 0))
        val quiet = ScoreboardRows.build(data, context(mode = TeamMode.TWO_TEAMS, isClient = true, selfId = 1))
        assertTrue(quiet.filterIsInstance<ScoreboardRow.Player>().all { it.muted == null })

        val voice = ScoreboardRows.build(data, context(mode = TeamMode.TWO_TEAMS, isClient = true, selfId = 1)
            .copy(voiceOn = true, muted = setOf(2)))
        val byId = voice.filterIsInstance<ScoreboardRow.Player>().associateBy { it.playerId.toInt() }
        assertEquals(null, byId.getValue(1).muted)
        assertEquals(true, byId.getValue(2).muted)
        assertEquals(false, byId.getValue(3).muted)
        // The other team can't be heard anyway
        assertEquals(null, byId.getValue(33).muted)
        val headers = voice.filterIsInstance<ScoreboardRow.TeamHeader>().associateBy { it.team }
        assertEquals(false, headers.getValue(1).allMuted)
        assertEquals(null, headers.getValue(2).allMuted)

        // Free for all: nobody to mute
        val ffa = ScoreboardRows.build(data, context(mode = TeamMode.FREE_FOR_ALL, isClient = true, selfId = 1).copy(voiceOn = true))
        assertTrue(ffa.filterIsInstance<ScoreboardRow.Player>().all { it.muted == null })
        assertTrue(ffa.filterIsInstance<ScoreboardRow.TeamHeader>().all { it.allMuted == null })
    }

    @Test
    fun rankedByPlayer_bestFirst_eliminationsLessDeaths_withTheirTeamsColour() {
        val rows = ScoreboardRows.build(
            // 10 - 8 = 2, 6 - 0 = 6, 9 - 1 = 8: the best player is the one with most to show for their deaths
            players(player(1, "Sprayer", 10, 8), player(2, "Careful", 6, 0), player(33, "Ace", 9, 1)),
            context(mode = TeamMode.TWO_TEAMS).copy(rank = ScoreboardRank.PLAYERS),
        )
        assertEquals(ScoreboardRow.RankBar(ScoreboardRank.PLAYERS, squads = false), rows[0])
        assertTrue(rows.none { it is ScoreboardRow.TeamHeader })
        assertEquals(listOf(33, 2, 1), rows.playerIds())
        assertEquals(TeamColors.TEAM_2, rows.filterIsInstance<ScoreboardRow.Player>().first().color)
    }

    @Test
    fun squads_eachUnderItsHeader_thenEveryoneAlone_andFreeForAllHasNoRankBy() {
        val layout = com.simplecoil.simplecoil.domain.team.TeamLayout.defaultFor(TeamMode.SQUADS).withSquads(mapOf(2 to setOf(3, 4)))
        val rows = ScoreboardRows.build(players(player(3, "A", 4), player(4, "B", 1), player(9, "Solo", 2)),
            context(mode = TeamMode.SQUADS).copy(layout = layout))
        assertEquals(ScoreboardRow.RankBar(ScoreboardRank.TEAMS, squads = true), rows[0])
        val squad = rows.filterIsInstance<ScoreboardRow.TeamHeader>().first()
        assertEquals(2, squad.squad)
        assertEquals(TeamColors.TEAM_2, squad.color)
        assertEquals(listOf(3, 4, 9), rows.playerIds())
        assertTrue(ScoreboardRows.build(players(player(1, "A", 1)), context()).none { it is ScoreboardRow.RankBar })
    }

    @Test
    fun squads_aPlayerAlone_ranksAsASquadOfOne() {
        val layout = com.simplecoil.simplecoil.domain.team.TeamLayout.defaultFor(TeamMode.SQUADS)
            .withSquads(mapOf(1 to setOf(1, 2), 2 to setOf(4, 5)))
        val rows = ScoreboardRows.build(
            players(player(1, "A", 6), player(2, "B", 4), player(3, "CaptainAmerica", 7), player(4, "Wolverine", 2), player(5, "Deadpool", 1)),
            context(mode = TeamMode.SQUADS).copy(layout = layout))
        // Squad 1 (10), Captain America alone (7), squad 2 (3)
        val headers = rows.filterIsInstance<ScoreboardRow.TeamHeader>()
        assertEquals(listOf(1, null, 2), headers.map { it.squad })
        assertTrue(headers[1].solo)
        assertEquals(7, headers[1].score)
        assertEquals(listOf(1, 2, 3, 4, 5), rows.playerIds())
    }

    @Test
    fun squads_playersAloneNextToEachOther_shareOneBar() {
        val layout = com.simplecoil.simplecoil.domain.team.TeamLayout.defaultFor(TeamMode.SQUADS).withSquads(mapOf(1 to setOf(1, 2)))
        val rows = ScoreboardRows.build(
            players(player(1, "A", 9), player(2, "B", 9), player(3, "Solo1", 5), player(4, "Solo2", 4)),
            context(mode = TeamMode.SQUADS).copy(layout = layout))
        val headers = rows.filterIsInstance<ScoreboardRow.TeamHeader>()
        assertEquals(2, headers.size)
        assertTrue(headers[1].solo)
        assertEquals(2, headers[1].playerCount)
        assertEquals(listOf(1, 2, 3, 4), rows.playerIds())
    }
}

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

package com.simplecoil.simplecoil.domain.mode

/** How a game ended for this player: won, lost or a draw, with their side's score and the best other one. */
data class GameResult(val outcome: Outcome, val mine: Int, val theirs: Int) {

    enum class Outcome { VICTORY, DEFEAT, DRAW }

    companion object {
        /**
         * The result for player [myId] on [myTeam] (their own id in free for all). A game the server
         * ran ([state]) goes by its winner, or by the scores when it ended without one (time ran out).
         * A classic game goes by the teams' totals of their players' [points] (id to points),
         * [teamOf] each; classic free for all has none. Null when there is nothing to tell.
         */
        fun of(
            state: ModeState?,
            myId: Int,
            myTeam: Int,
            freeForAll: Boolean,
            points: Map<Int, Int>,
            teamOf: (Int) -> Int,
        ): GameResult? {
            if (state != null) {
                val side = state.sideOf(myId, myTeam)
                val mine = state.scores[side] ?: 0
                val theirs = state.scores.filterKeys { it != side }.values.maxOrNull() ?: 0
                val outcome = when {
                    state.winner != 0 -> if (state.winner == side) Outcome.VICTORY else Outcome.DEFEAT
                    state.scores.isEmpty() -> return null
                    else -> compare(mine, theirs)
                }
                return GameResult(outcome, mine, theirs)
            }
            if (freeForAll) return null
            val totals = points.entries.groupBy({ teamOf(it.key) }, { it.value }).mapValues { it.value.sum() }
            // A game with only one side playing has no one to beat
            if (totals.size < 2 || myTeam !in totals) return null
            val mine = totals.getValue(myTeam)
            val theirs = totals.filterKeys { it != myTeam }.values.max()
            return GameResult(compare(mine, theirs), mine, theirs)
        }

        private fun compare(mine: Int, theirs: Int) = when {
            mine > theirs -> Outcome.VICTORY
            mine < theirs -> Outcome.DEFEAT
            else -> Outcome.DRAW
        }
    }
}

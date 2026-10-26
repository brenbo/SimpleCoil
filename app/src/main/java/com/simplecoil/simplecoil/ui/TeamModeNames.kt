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

package com.simplecoil.simplecoil.ui

import androidx.annotation.StringRes
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.domain.team.TeamMode

/** A team mode's short name, as tiles and buttons show it ("FFA", as "Free for all" doesn't fit them). */
@get:StringRes
val TeamMode.shortName: Int
    get() = when (this) {
        TeamMode.TWO_TEAMS -> R.string.team_mode_2teams
        TeamMode.THREE_TEAMS -> R.string.team_mode_3teams
        TeamMode.FOUR_TEAMS -> R.string.team_mode_4teams
        TeamMode.FREE_FOR_ALL -> R.string.team_mode_ffa_short
        TeamMode.SQUADS -> R.string.team_mode_squads
    }

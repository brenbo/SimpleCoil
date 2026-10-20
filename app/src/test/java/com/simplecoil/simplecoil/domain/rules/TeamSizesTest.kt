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

package com.simplecoil.simplecoil.domain.rules

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TeamSizesTest {

    @Test
    fun settingATeam_theLastTeamGetsTheRest() {
        assertArrayEquals(intArrayOf(20, 43), TeamSizes.adjust(intArrayOf(32, 31), 0, 20))
        assertArrayEquals(intArrayOf(16, 30, 16, 1), TeamSizes.adjust(intArrayOf(16, 16, 16, 15), 1, 30))
    }

    @Test
    fun noTeamGoesBelowOne() {
        assertArrayEquals(intArrayOf(62, 1), TeamSizes.adjust(intArrayOf(32, 31), 0, 99))
        assertArrayEquals(intArrayOf(1, 62), TeamSizes.adjust(intArrayOf(32, 31), 0, 0))
        // Four teams: the last keeps one, whatever the others take
        assertArrayEquals(intArrayOf(16, 16, 30, 1), TeamSizes.adjust(intArrayOf(16, 16, 16, 15), 2, 40))
    }

    @Test
    fun theLastTeamIsntSetDirectly() {
        assertArrayEquals(intArrayOf(32, 31), TeamSizes.adjust(intArrayOf(32, 31), 1, 10))
    }

    @Test
    fun evenSplits_alwaysFillEverySlot() {
        assertArrayEquals(intArrayOf(32, 31), TeamSizes.even(2))
        assertArrayEquals(intArrayOf(16, 16, 16, 15), TeamSizes.even(4))
        assertEquals(63, TeamSizes.even(3).sum())
        assertEquals(62, TeamSizes.maxSize(2))
        assertEquals(60, TeamSizes.maxSize(4))
    }
}

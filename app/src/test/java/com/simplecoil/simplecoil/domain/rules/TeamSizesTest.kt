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
        assertArrayEquals(intArrayOf(20, 44), TeamSizes.adjust(intArrayOf(32, 32), 0, 20))
        assertArrayEquals(intArrayOf(16, 30, 16, 2), TeamSizes.adjust(intArrayOf(16, 16, 16, 16), 1, 30))
    }

    @Test
    fun noTeamGoesBelowOne() {
        assertArrayEquals(intArrayOf(63, 1), TeamSizes.adjust(intArrayOf(32, 32), 0, 99))
        assertArrayEquals(intArrayOf(1, 63), TeamSizes.adjust(intArrayOf(32, 32), 0, 0))
        // Four teams: the last keeps one, whatever the others take
        assertArrayEquals(intArrayOf(16, 16, 31, 1), TeamSizes.adjust(intArrayOf(16, 16, 16, 16), 2, 40))
    }

    @Test
    fun settingTheLastTeam_theTeamBeforeItGetsTheRest() {
        assertArrayEquals(intArrayOf(54, 10), TeamSizes.adjust(intArrayOf(32, 32), 1, 10))
        assertArrayEquals(intArrayOf(16, 16, 12, 20), TeamSizes.adjust(intArrayOf(16, 16, 16, 16), 3, 20))
        assertArrayEquals(intArrayOf(16, 16, 1, 31), TeamSizes.adjust(intArrayOf(16, 16, 16, 16), 3, 99))
    }

    @Test
    fun evenSplits_alwaysFillEverySlot() {
        assertArrayEquals(intArrayOf(32, 32), TeamSizes.even(2))
        assertArrayEquals(intArrayOf(16, 16, 16, 16), TeamSizes.even(4))
        assertEquals(64, TeamSizes.even(3).sum())
        assertEquals(63, TeamSizes.maxSize(2))
        assertEquals(61, TeamSizes.maxSize(4))
    }
}

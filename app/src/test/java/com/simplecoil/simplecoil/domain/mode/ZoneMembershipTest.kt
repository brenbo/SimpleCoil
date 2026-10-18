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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneMembershipTest {

    private val zone = ZoneMembership(graceMillis = 3_000, leaveMarginMeters = 3.0)

    @Test
    fun inOnlyAfterTheGraceTime() {
        assertFalse(zone.update(1, "A", 5.0, 10.0, 0))
        assertFalse(zone.update(1, "A", 5.0, 10.0, 2_000))
        assertTrue(zone.update(1, "A", 5.0, 10.0, 3_000))
    }

    @Test
    fun aStepOutDuringTheGraceStartsItOver() {
        zone.update(1, "A", 5.0, 10.0, 0)
        zone.update(1, "A", 12.0, 10.0, 2_000)
        assertFalse(zone.update(1, "A", 5.0, 10.0, 3_000))
        assertFalse(zone.update(1, "A", 5.0, 10.0, 5_000))
        assertTrue(zone.update(1, "A", 5.0, 10.0, 6_000))
    }

    @Test
    fun outOnlyPastTheMargin() {
        zone.update(1, "A", 5.0, 10.0, 0)
        zone.update(1, "A", 5.0, 10.0, 3_000)
        // Jitter just past the edge: still in
        assertTrue(zone.update(1, "A", 12.0, 10.0, 4_000))
        // Well past it: out
        assertFalse(zone.update(1, "A", 14.0, 10.0, 5_000))
        assertFalse(zone.update(1, "A", 9.0, 10.0, 6_000))
    }

    @Test
    fun noPositionIsOut() {
        zone.update(1, "A", 5.0, 10.0, 0)
        zone.update(1, "A", 5.0, 10.0, 3_000)
        assertFalse(zone.update(1, "A", null, 10.0, 4_000))
    }
}

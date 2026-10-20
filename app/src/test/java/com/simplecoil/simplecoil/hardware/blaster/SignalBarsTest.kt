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

package com.simplecoil.simplecoil.hardware.blaster

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalBarsTest {

    @Test
    fun closerIsMoreBars() {
        assertEquals(listOf(4, 4, 3, 2, 1), listOf(-40, -60, -65, -79, -95).map(SignalBars::of))
    }

    @Test
    fun inRange_isStrongestFirst_andDropsBlastersNotHeardLately() {
        val near = FoundBlaster("AA", "SRG1-A", -50, seenAt = 10_000)
        val far = FoundBlaster("BB", "SRG1-B", -85, seenAt = 10_000)
        val gone = FoundBlaster("CC", "SRG1-C", -40, seenAt = 1_000)
        assertEquals(listOf(near, far), SignalBars.inRange(listOf(far, gone, near), now = 12_000))
    }
}

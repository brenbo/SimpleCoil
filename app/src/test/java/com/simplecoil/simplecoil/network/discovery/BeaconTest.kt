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

package com.simplecoil.simplecoil.network.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The beacon line, as it comes over UDP or TCP (17513, for a Chromebook's server). */
class BeaconTest {

    @Test
    fun aV08Beacon_givesTheNameStateAndFingerprint() {
        val fp = "ab".repeat(32)
        val beacon = DiscoveryClient.parseBeacon("SimpleCoil:BEACON:Home|inGame=1|allowJoin=0|tls=$fp")!!
        assertEquals("Home", beacon.name)
        assertEquals(true, beacon.inGame)
        assertEquals(false, beacon.allowJoin)
        assertEquals(fp, beacon.fingerprint)
    }

    @Test
    fun anOlderServersBeacon_hasNoFingerprint_andJunkIsntOne() {
        assertNull(DiscoveryClient.parseBeacon("SimpleCoil:BEACON:Old|inGame=0|allowJoin=1")!!.fingerprint)
        assertNull(DiscoveryClient.parseBeacon("hello"))
        assertNull(DiscoveryClient.parseBeacon(""))
    }
}

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

package com.simplecoil.simplecoil.network.imagery

import com.simplecoil.simplecoil.network.imagery.FieldImageryProtocol.Kind
import com.simplecoil.simplecoil.network.imagery.FieldImageryProtocol.Reply
import com.simplecoil.simplecoil.network.imagery.FieldImageryProtocol.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The field service's drop, UAV and stats lines (NETWORK_PROTOCOL.md section 8). */
class FieldImageryProtocolTest {

    @Test
    fun aClaimNamesTheDropAndThePlayer() {
        val line = FieldImageryProtocol.claimLine("d12", 5)
        assertEquals("SCFIELD1 CLAIM d12,5\n", line)
        val request = FieldImageryProtocol.parseRequest(line)!!
        assertEquals(Request(Kind.CLAIM, "d12,5"), request)
        assertEquals("d12" to 5, FieldImageryProtocol.parseClaim(request.knownId))
        assertNull(FieldImageryProtocol.parseClaim("d12"))
        assertNull(FieldImageryProtocol.parseClaim(",5"))
        assertNull(FieldImageryProtocol.parseClaim("d12,x"))
    }

    @Test
    fun uavAndStatsRequests() {
        assertEquals(Request(Kind.UAV, "7"), FieldImageryProtocol.parseRequest(FieldImageryProtocol.requestLine("7", Kind.UAV)))
        assertEquals(Request(Kind.STATS, ""), FieldImageryProtocol.parseRequest(FieldImageryProtocol.requestLine(null, Kind.STATS)))
    }

    @Test
    fun grantedAndDenied() {
        assertEquals(Reply.Granted("supply"), FieldImageryProtocol.parseReply(FieldImageryProtocol.grantedLine("supply")))
        assertEquals(Reply.Denied, FieldImageryProtocol.parseReply(FieldImageryProtocol.DENIED_LINE))
    }

    @Test
    fun configRepliesCarryTheServerClock() {
        assertEquals(Reply.Pack(100), FieldImageryProtocol.parseReply(FieldImageryProtocol.packLine(100, 1234L)))
        assertEquals(1234L, FieldImageryProtocol.serverTime(FieldImageryProtocol.packLine(100, 1234L)))
        assertEquals(Reply.Same, FieldImageryProtocol.parseReply(FieldImageryProtocol.sameLine(99L)))
        assertEquals(99L, FieldImageryProtocol.serverTime(FieldImageryProtocol.sameLine(99L)))
        assertEquals(Reply.None, FieldImageryProtocol.parseReply(FieldImageryProtocol.noneLine(5L)))
        assertEquals(5L, FieldImageryProtocol.serverTime(FieldImageryProtocol.noneLine(5L)))
        // An older server sends none
        assertNull(FieldImageryProtocol.serverTime(FieldImageryProtocol.packLine(100)))
        assertNull(FieldImageryProtocol.serverTime(FieldImageryProtocol.SAME_LINE))
    }
}

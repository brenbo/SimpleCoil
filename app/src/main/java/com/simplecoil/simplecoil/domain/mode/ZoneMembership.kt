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

/**
 * Who counts as standing in a zone, steady against GPS jitter (map design: GPS limits): a player
 * counts as in only after being inside for [graceMillis], and as out only once they are more
 * than [leaveMarginMeters] past the edge, so someone on the line doesn't flicker in and out.
 */
class ZoneMembership(private val graceMillis: Long, private val leaveMarginMeters: Double) {

    private class Member(var inside: Boolean, var enteredAt: Long)

    private val members = HashMap<Pair<Int, String>, Member>()

    /** [player]'s [distanceMeters] from [zone]'s centre now; true if they count as in it. */
    fun update(player: Int, zone: String, distanceMeters: Double?, radiusMeters: Double, nowMillis: Long): Boolean {
        val key = player to zone
        val member = members[key]
        if (distanceMeters == null) {
            // No position: out
            members.remove(key)
            return false
        }
        if (member?.inside == true) {
            // Once in, only well past the edge is out
            if (distanceMeters > radiusMeters + leaveMarginMeters) members.remove(key) else return true
            return false
        }
        if (distanceMeters > radiusMeters) {
            members.remove(key)
            return false
        }
        val entered = member?.enteredAt ?: nowMillis.also { members[key] = Member(false, it) }
        if (nowMillis - entered >= graceMillis) {
            members[key] = Member(true, entered)
            return true
        }
        return false
    }

    fun clear() = members.clear()
}

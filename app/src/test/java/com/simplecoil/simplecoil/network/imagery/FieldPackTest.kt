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

import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.BorderRules
import com.simplecoil.simplecoil.domain.map.DistanceUnits
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.GeoBounds
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.map.OutOfBoundsPenalty
import com.simplecoil.simplecoil.domain.map.UsgsImagery
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Test helpers shared by the imagery tests. */
object FieldPacks {
    val BOUNDS = GeoBounds(north = 39.0025, south = 38.9975, east = -76.9968, west = -77.0032)

    /** A fake JPEG: the two start bytes, then [size] - 2 bytes of [fill]. */
    fun jpeg(size: Int = 5_000, fill: Byte = 7): ByteArray = ByteArray(size) { fill }.also {
        it[0] = 0xFF.toByte()
        it[1] = 0xD8.toByte()
    }

    fun pack(image: ByteArray = jpeg(), bounds: GeoBounds = BOUNDS, createdAt: Long = 1_760_000_000_000L): FieldPack =
        FieldPack.create(bounds, 1000, 1000, image, UsgsImagery.CREDIT, createdAt)
}

class FieldPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun encodesAndDecodes() {
        val pack = FieldPacks.pack()
        val back = FieldPackCodec.decode(FieldPackCodec.encode(pack))
        assertEquals(pack.info, back.info)
        assertArrayEquals(pack.image, back.image)
        assertEquals(UsgsImagery.CREDIT, back.info.credit)
    }

    @Test
    fun theIdFollowsThePhotoAndItsPlace() {
        val a = FieldPacks.pack()
        assertEquals(a.info.id, FieldPacks.pack(createdAt = 5).info.id)
        assertNotEquals(a.info.id, FieldPacks.pack(image = FieldPacks.jpeg(fill = 9)).info.id)
        val moved = GeoBounds(39.0035, 38.9985, -76.9968, -77.0032)
        assertNotEquals(a.info.id, FieldPacks.pack(bounds = moved).info.id)
        assertEquals(32, a.info.id.length)
    }

    @Test(expected = IOException::class)
    fun refusesSomethingElse() {
        FieldPackCodec.decode("not a pack at all, just text".toByteArray())
    }

    @Test(expected = IOException::class)
    fun refusesACutShortPack() {
        val bytes = FieldPackCodec.encode(FieldPacks.pack())
        FieldPackCodec.decode(bytes.copyOf(bytes.size - 10))
    }

    @Test(expected = IOException::class)
    fun refusesAHugeLength() {
        val bytes = FieldPackCodec.encode(FieldPacks.pack())
        // The info length field, right after the magic, claims 2 GB
        bytes[8] = 0x7F
        FieldPackCodec.decode(bytes)
    }

    @Test
    fun theStoreKeepsOnePack() {
        val store = FieldPackStore(File(tmp.root, "field"))
        assertNull(store.refresh())
        assertNull(store.load())
        assertNull(store.bytes())

        val first = FieldPacks.pack()
        store.save(first)
        assertEquals(first.info, store.info.value)

        val second = FieldPacks.pack(image = FieldPacks.jpeg(fill = 3))
        store.save(second)
        assertEquals(second.info, store.info.value)
        // A new store on the same folder finds it, as after a restart
        val reopened = FieldPackStore(File(tmp.root, "field"))
        assertEquals(second.info, reopened.refresh())
        assertArrayEquals(second.image, reopened.load()!!.image)
        assertArrayEquals(FieldPackCodec.encode(second), reopened.bytes())

        reopened.clear()
        assertNull(reopened.info.value)
        assertNull(FieldPackStore(File(tmp.root, "field")).refresh())
    }

    @Test
    fun theBorderAndRulesSurviveARestart() {
        val config = FieldConfig("Johnson Park", Border.Circle(LatLon(39.0, -77.0), 120.0),
            BorderRules(penalty = OutOfBoundsPenalty.ELIMINATED, units = DistanceUnits.METERS))
        FieldConfigStore(File(tmp.root, "server")).save(config)
        // A new store on the same folder, as after the app restarts
        assertEquals(config, FieldConfigStore(File(tmp.root, "server")).refresh())
    }

    @Test
    fun aDamagedPackIsDropped() {
        val dir = File(tmp.root, "field")
        FieldPackStore(dir).save(FieldPacks.pack())
        val file = dir.listFiles()!!.single()
        file.writeBytes(file.readBytes().copyOf(100))
        val store = FieldPackStore(dir)
        assertNull(store.refresh())
        assertTrue(!file.exists())
    }
}

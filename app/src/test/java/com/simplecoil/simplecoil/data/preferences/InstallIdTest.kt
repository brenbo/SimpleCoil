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

package com.simplecoil.simplecoil.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class InstallIdTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(): File = File(folder.root, "install_id")

    @Test
    fun firstRun_makesAUuid_andKeepsIt() {
        val id = InstallId.read(file())
        assertEquals(id, UUID.fromString(id).toString())
        assertEquals(id, file().readText())
    }

    @Test
    fun laterRuns_readTheSameOne() {
        val first = InstallId.read(file())
        assertEquals(first, InstallId.read(file()))
    }

    @Test
    fun aDamagedFile_isReplacedWithANewId() {
        file().writeText("not an id")
        val id = InstallId.read(file())
        assertNotEquals("not an id", id)
        assertEquals(id, UUID.fromString(id).toString())
        assertEquals(id, InstallId.read(file()))
    }
}

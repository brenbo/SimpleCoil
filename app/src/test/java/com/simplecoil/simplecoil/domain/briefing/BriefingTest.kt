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

package com.simplecoil.simplecoil.domain.briefing

import com.simplecoil.simplecoil.domain.loadout.AmmoMode
import com.simplecoil.simplecoil.domain.loadout.DropKind
import com.simplecoil.simplecoil.domain.loadout.LoadoutRules
import com.simplecoil.simplecoil.domain.map.Border
import com.simplecoil.simplecoil.domain.map.FieldConfig
import com.simplecoil.simplecoil.domain.map.LatLon
import com.simplecoil.simplecoil.domain.mode.GameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefingTest {

    private val field = FieldConfig(border = Border.Circle(LatLon(39.0, -77.0), 100.0))

    private fun inputs(
        field: FieldConfig? = this.field,
        freeForAll: Boolean = false,
        livesLimit: Int = 0,
        voice: Boolean = false,
        manualRespawn: Boolean = false,
        timeLimit: Int = 0,
    ) = BriefingInputs(field, freeForAll, if (freeForAll) 0 else 2, respawnSeconds = 10, manualRespawn = manualRespawn,
        livesLimit = livesLimit, timeLimitMinutes = timeLimit, scoreLimit = 0, voiceChat = voice)

    @Test
    fun buttonsThenDropsThenTheGame_eachKeyOnce() {
        val slides = Briefing.slides(inputs(field.copy(loadout = LoadoutRules(dropsEnabled = true, killstreaks = true))))
        val sections = slides.map { it.section }
        assertEquals(sections.sortedBy { it.ordinal }, sections)
        assertEquals(slides.size, slides.map { it.key }.toSet().size)
        assertEquals(listOf("buttons.trigger", "buttons.reload", "buttons.thumb", "buttons.power"),
            slides.filter { it.section == Section.BUTTONS }.map { it.key })
        assertEquals("mode.goal", Briefing.firstModeSlide(slides)!!.key)
    }

    @Test
    fun aDropSlideForEachDropTheFieldHas_noneWithDropsOff() {
        val on = Briefing.slides(inputs(field.copy(loadout = LoadoutRules(dropsEnabled = true, dropKinds = setOf(DropKind.MEDKIT, DropKind.UAV)))))
        assertEquals(listOf(null, DropKind.MEDKIT, DropKind.UAV), on.filter { it.section == Section.DROPS }.map { it.drop })
        val off = Briefing.slides(inputs(field.copy(loadout = LoadoutRules(dropsEnabled = false))))
        assertTrue(off.none { it.section == Section.DROPS })
        // Limited ammo gets its own slide, drops or not
        val ammo = Briefing.slides(inputs(field.copy(loadout = LoadoutRules(ammo = AmmoMode.SCAVENGER))))
        assertTrue(ammo.any { it.key == "drops.ammo" && "where they fell" in it.body })
    }

    @Test
    fun theGameSlidesFollowTheSettings() {
        val ffa = Briefing.slides(inputs(freeForAll = true)).single { it.key == "mode.teams" }
        assertEquals(SlideArt.FREE_FOR_ALL, ffa.art)
        val teams = Briefing.slides(inputs(voice = true)).single { it.key == "mode.teams" }
        assertTrue("talk" in teams.body)
        // Search & Destroy: no respawns, whatever the respawn time
        val snd = Briefing.slides(inputs(field.copy(mode = GameMode.SEARCH_AND_DESTROY)))
        assertEquals(SlideArt.NO_RESPAWN, snd.single { it.key == "mode.respawn" }.art)
        assertEquals("Search & Destroy", snd.single { it.key == "mode.goal" }.title)
        // A lives limit and manual respawn
        val lives = Briefing.slides(inputs(livesLimit = 3, manualRespawn = true)).single { it.key == "mode.respawn" }
        assertEquals("3 lives", lives.title)
        assertTrue("hold the reload button" in lives.body)
        // With no border there's no border slide
        assertNull(Briefing.slides(inputs(field = null)).firstOrNull { it.key == "mode.border" })
        assertTrue(Briefing.slides(inputs()).any { it.key == "mode.border" })
    }

    @Test
    fun theThumbButtonSaysWhatHoldingItDoes() {
        assertTrue("scoreboard" in Briefing.slides(inputs()).single { it.key == "buttons.thumb" }.body)
        assertTrue("talk" in Briefing.slides(inputs(voice = true)).single { it.key == "buttons.thumb" }.body)
    }

    @Test
    fun howToWin_andTheClock() {
        val classic = Briefing.slides(inputs(field = null, timeLimit = 15)).single { it.key == "mode.win" }
        assertTrue("15 min" in classic.body)
        val ctf = Briefing.slides(inputs(field.copy(mode = GameMode.CAPTURE_THE_FLAG))).single { it.key == "mode.win" }
        assertTrue("captures" in ctf.body)
        assertFalse("15 min" in ctf.body)
    }

    @Test
    fun theBriefingGoesOverTheWire() {
        val now = BriefingNow(42, "drops.uav")
        assertEquals(now, BriefingNow.fromBytes(now.toBytes()))
        assertNull(BriefingNow.fromBytes("nope".toByteArray()))
        // The host's whole slide goes with it, so every phone says what the host's screen says
        val slides = Briefing.slides(inputs(field.copy(loadout = LoadoutRules(dropsEnabled = true, dropKinds = setOf(DropKind.UAV)))))
        val uav = slides.indexOfFirst { it.key == "drops.uav" }
        val full = BriefingNow(42, "drops.uav", slides[uav], uav, slides.size)
        assertEquals(full, BriefingNow.fromBytes(full.toBytes()))
        val border = slides.indexOfFirst { it.key == "mode.border" }
        val withText = BriefingNow(7, "mode.border", slides[border], border, slides.size)
        assertEquals(slides[border].body, BriefingNow.fromBytes(withText.toBytes())!!.shown!!.body)
    }

    @Test
    fun everyModeHasAGoalSlide() {
        for (mode in GameMode.entries) {
            val slides = Briefing.slides(inputs(field.copy(mode = mode)))
            assertTrue(mode.name, slides.any { it.key == "mode.goal" && it.body.isNotBlank() })
        }
    }
}

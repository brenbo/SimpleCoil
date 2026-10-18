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

package com.simplecoil.simplecoil.hardware.audio

import com.simplecoil.simplecoil.domain.session.SessionEffect
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.domain.session.SessionToast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFeedbackTest {

    @Test
    fun soundsAndVibration_arePlayedInOrder_otherEffectsIgnored() {
        val played = mutableListOf<String>()
        val feedback = SessionFeedback(
            object : SoundEffectPlayer { override fun play(sound: SessionSound) { played += sound.name } },
            object : HapticsPlayer { override fun vibrate() { played += "vibrate" } },
        )
        listOf(SessionEffect.Vibrate, SessionEffect.Sound(SessionSound.HIT), SessionEffect.Toast(SessionToast.OUT_OF_LIVES),
            SessionEffect.CommunicationStarted, SessionEffect.Sound(SessionSound.BEEP)).forEach(feedback::onEffect)
        assertEquals(listOf("vibrate", "HIT", "BEEP"), played)
    }

    @Test
    fun theBlastersSoundsPlayTheSameFilesAsBefore_varied_butTheWhistleIsNot() {
        val expected = mapOf(
            SessionSound.BEEP to "beep", SessionSound.ELIMINATED to "eliminated", SessionSound.EMPTY to "empty",
            SessionSound.HIT to "hit", SessionSound.RELOAD to "reload", SessionSound.SCORE to "score",
            SessionSound.SHOT to "shootingshort", SessionSound.SPAWN to "spawn",
            SessionSound.HIT_CONFIRM to "hit_confirm", SessionSound.HIT_ELIMINATED to "hit_eliminated",
            SessionSound.HIT_SHIELDED to "hit_shielded", SessionSound.GAME_END to "game_end")
        expected.forEach { (sound, file) -> assertEquals(sound.name, file, SoundCues.of(sound).file) }
        assertTrue(SoundCues.of(SessionSound.SHOT).vary)
        assertFalse(SoundCues.of(SessionSound.GAME_END).vary)
        assertEquals(250L, AudioHapticsPlayer.HIT_VIBRATE_DURATION_MILLIS)
    }

    @Test
    fun everyNewSoundFallsBackToOneTheAppShips() {
        val shipped = setOf("beep", "eliminated", "empty", "hit", "reload", "score", "shootingshort", "spawn", "hit_confirm",
            "hit_eliminated", "hit_shielded", "game_end")
        for (sound in SessionSound.entries) assertTrue(sound.name, SoundCues.of(sound).fallback in shipped)
    }

    private val queue = AnnouncementQueue<String>(urgentMaxWaitMillis = 2_000, maxWaitMillis = 5_000, essentialMaxWaitMillis = 10_000)

    @Test
    fun announcementsTakeTurns_inTheOrderTheyHappened() {
        // A drop and the zone closing at once: one, then the other, never both
        assertEquals("drop", queue.offer("drop", Lane.NORMAL, false, 0).play)
        assertNull(queue.offer("zone", Lane.NORMAL, true, 100).play)
        assertNull(queue.offer("uav", Lane.NORMAL, false, 200).play)
        assertTrue(queue.isBusy)
        assertEquals("zone", queue.finished(1_500))
        assertEquals("uav", queue.finished(3_000))
        assertNull(queue.finished(4_000))
        assertFalse(queue.isBusy)
    }

    @Test
    fun killsGoAheadOfTheRest_butOnlyWhileFresh() {
        queue.offer("zone", Lane.NORMAL, false, 0)
        queue.offer("drop", Lane.NORMAL, false, 0)
        queue.offer("kill", Lane.URGENT, false, 100)
        queue.offer("score", Lane.URGENT, false, 100)
        assertEquals("kill", queue.finished(1_000))
        // The score waited too long: dropped rather than played late
        assertEquals("drop", queue.finished(2_500))
    }

    @Test
    fun theSameThingIsntQueuedTwice_andStaleOnesAreDropped() {
        queue.offer("zone", Lane.NORMAL, false, 0)
        queue.offer("warning", Lane.NORMAL, false, 100)
        assertNull(queue.offer("warning", Lane.NORMAL, false, 200).play)
        queue.offer("flag", Lane.NORMAL, true, 300)
        // 6 s later: the warning is stale, the essential flag callout still counts
        assertEquals("flag", queue.finished(6_000))
        assertNull(queue.finished(7_000))
    }

    @Test
    fun theEndOfTheGameCutsOffWhatPlays_andNothingElseFollows() {
        queue.offer("killstreak", Lane.NORMAL, false, 0)
        queue.offer("uav", Lane.NORMAL, false, 0)
        val end = queue.offer("whistle", Lane.FINAL, false, 300)
        assertEquals("whistle", end.play)
        assertTrue(end.stopCurrent)
        assertNull(queue.offer("victory", Lane.FINAL, true, 300).play)
        assertNull(queue.offer("secured", Lane.NORMAL, true, 400).play)
        assertEquals("victory", queue.finished(2_000))
        assertNull(queue.finished(4_000))
    }
}

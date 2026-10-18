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

    @Test
    fun calloutsTakeTurnsMostImportantFirst() {
        val queue = CalloutQueue(cooldownMillis = 8_000)
        assertEquals("a", queue.offer("a", 1, 0))
        queue.playing("a", 2_000, 0)
        // While it plays, the more important waiting line is kept
        assertNull(queue.offer("b", 1, 500))
        assertNull(queue.offer("c", 5, 600))
        assertTrue(queue.isBusy(1_000))
        assertNull(queue.next(1_000))
        assertEquals("c", queue.next(2_400))
        queue.playing("c", 1_000, 2_400)
        // The same line again too soon: not said
        assertNull(queue.offer("a", 9, 5_000))
        assertEquals("a", queue.offer("a", 9, 9_000))
    }
}

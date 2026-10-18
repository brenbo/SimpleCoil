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

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.simplecoil.simplecoil.domain.session.SessionSound
import kotlin.random.Random

/** The player's sound choices (map design: Audio settings). */
data class AudioSettings(
    /** 0 to 1. */
    val effectsVolume: Float = 1f,
    val voiceVolume: Float = 1f,
    val voiceOn: Boolean = true,
    /** Only the callouts that matter most. */
    val essentialOnly: Boolean = false,
    val vibration: Boolean = true,
)

/**
 * The game's sounds (map design: Audio): every [SessionSound] through its [SoundCue], from a
 * SoundPool loaded once. Effects vary a little each time and rotate through their takes;
 * callouts go through a [CalloutQueue], and effects duck under them.
 */
class GameSounds(
    context: Context,
    private val settings: () -> AudioSettings,
    private val random: Random = Random.Default,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : SoundEffectPlayer {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        .build()

    /** Each name's takes, as loaded sample ids. */
    private val takes = HashMap<String, List<Int>>()

    /** How long each voice line plays. */
    private val durations = HashMap<String, Long>()
    private val lastTake = HashMap<String, Int>()
    private val queue = CalloutQueue()
    private val handler = Handler(Looper.getMainLooper())

    init {
        val app = context.applicationContext
        // By name, from the resource table (kept whole by res/raw/keep.xml), so a sound added to
        // res/raw is found without code: R.raw's own fields don't survive a release build's shrinking
        @Suppress("DiscouragedApi")
        fun raw(name: String): Int? = app.resources.getIdentifier(name, "raw", app.packageName).takeIf { it != 0 }
        for (name in SoundCues.names()) {
            // The file, then any more takes of it: name_2, name_3...
            val ids = (listOf(name) + (2..MAX_TAKES).map { "${name}_$it" }).mapNotNull { raw(it) }
            if (ids.isEmpty()) continue
            takes[name] = ids.map { pool.load(app, it, 1) }
            if (name.startsWith(VOICE_PREFIX)) durations[name] = duration(app, ids.first())
        }
    }

    private fun duration(context: Context, resId: Int): Long = try {
        context.resources.openRawResourceFd(resId).use { fd ->
            MediaMetadataRetriever().run {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                val millis = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                release()
                millis ?: DEFAULT_VOICE_MILLIS
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "No duration for a voice line: " + e.message)
        DEFAULT_VOICE_MILLIS
    }

    override fun play(sound: SessionSound) {
        val cue = SoundCues.of(sound)
        val s = settings()
        val now = clock()
        // Under a callout, effects step back
        val volume = s.effectsVolume * if (queue.isBusy(now)) DUCKED else 1f
        if (takes.containsKey(cue.file)) {
            playTake(cue.file, 1f, volume, cue.vary)
        } else {
            repeat(cue.repeats) { i ->
                if (i == 0) playTake(cue.fallback, cue.fallbackRate, volume, cue.vary)
                else handler.postDelayed({ playTake(cue.fallback, cue.fallbackRate, volume, cue.vary) }, i * REPEAT_GAP_MILLIS)
            }
        }
        val voice = cue.voice ?: return
        if (!s.voiceOn || (s.essentialOnly && !cue.essential) || !takes.containsKey(voice)) return
        queue.offer(voice, cue.priority, now)?.let { say(it) }
    }

    private fun say(voice: String) {
        val now = clock()
        val millis = durations[voice] ?: DEFAULT_VOICE_MILLIS
        queue.playing(voice, millis, now)
        playTake(voice, 1f, settings().voiceVolume, vary = false)
        // Then whatever waited
        handler.postDelayed({ queue.next(clock())?.let { say(it) } }, millis + CalloutQueue.GAP_MILLIS + 20)
    }

    /** One take of [name]: a different one from last time when there are several, a little varied if [vary]. */
    private fun playTake(name: String, rate: Float, volume: Float, vary: Boolean) {
        val ids = takes[name] ?: return
        var index = if (ids.size == 1) 0 else random.nextInt(ids.size)
        if (ids.size > 1 && index == lastTake[name]) index = (index + 1) % ids.size
        lastTake[name] = index
        val pitch = if (vary) rate * (1f + (random.nextFloat() * 2 - 1) * SoundCues.PITCH_SPREAD) else rate
        val loud = (if (vary) volume * (1f - random.nextFloat() * SoundCues.VOLUME_SPREAD) else volume).coerceIn(0f, 1f)
        if (loud <= 0f) return
        // SoundPool plays from half to twice the speed
        pool.play(ids[index], loud, loud, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    fun release() = pool.release()

    private companion object {
        const val TAG = "GameSounds"
        const val MAX_STREAMS = 12
        const val MAX_TAKES = 6
        const val VOICE_PREFIX = "voice_"
        const val DEFAULT_VOICE_MILLIS = 1_800L
        const val DUCKED = 0.45f
        const val REPEAT_GAP_MILLIS = 150L
    }
}

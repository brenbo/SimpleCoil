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
import java.util.concurrent.ConcurrentHashMap
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
 * SoundPool loaded once. Effects vary a little each time and rotate through their takes. The
 * blaster's sounds play at once; every other sound takes its turn in an [AnnouncementQueue],
 * effect first and then its callout, so nothing talks over anything else. The blaster's sounds
 * duck under a callout.
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

    /** How long each sample plays, by sample id; measured off the main thread as the app starts. */
    private val durations = ConcurrentHashMap<Int, Long>()
    private val lastTake = HashMap<String, Int>()
    private val queue = AnnouncementQueue<SessionSound>()
    private val handler = Handler(Looper.getMainLooper())

    /** When each callout was last said. */
    private val lastSaid = HashMap<String, Long>()

    /** Counts announcements: a timer from one that was cut off finds it changed and does nothing. */
    private var announcement = 0

    /** The streams of the announcement playing, to cut it off when the game ends. */
    private val announcing = ArrayList<Int>()

    /** Until when a callout is talking. */
    private var talkingUntil = 0L

    init {
        val app = context.applicationContext
        // By name, from the resource table (kept whole by res/raw/keep.xml), so a sound added to
        // res/raw is found without code: R.raw's own fields don't survive a release build's shrinking
        @Suppress("DiscouragedApi")
        fun raw(name: String): Int? = app.resources.getIdentifier(name, "raw", app.packageName).takeIf { it != 0 }
        val resources = ArrayList<Pair<Int, Int>>()
        for (name in SoundCues.names()) {
            // The file, then any more takes of it: name_2, name_3...
            val ids = (listOf(name) + (2..MAX_TAKES).map { "${name}_$it" }).mapNotNull { raw(it) }
            if (ids.isEmpty()) continue
            takes[name] = ids.map { res -> pool.load(app, res, 1).also { resources += it to res } }
        }
        Thread({ for ((sample, res) in resources) durations[sample] = duration(app, res) }, "SoundLengths").start()
    }

    private fun duration(context: Context, resId: Int): Long = try {
        context.resources.openRawResourceFd(resId).use { fd ->
            MediaMetadataRetriever().run {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                val millis = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                release()
                millis ?: DEFAULT_MILLIS
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "No length for a sound: " + e.message)
        DEFAULT_MILLIS
    }

    override fun play(sound: SessionSound) {
        val cue = SoundCues.of(sound)
        if (cue.lane == Lane.INSTANT) {
            // Under a callout, the blaster steps back
            val ducked = if (clock() < talkingUntil) DUCKED else 1f
            playEffect(cue, settings().effectsVolume * ducked)
            return
        }
        val decision = queue.offer(sound, cue.lane, cue.essential, clock())
        if (decision.stopCurrent) cutOff()
        decision.play?.let { announce(it) }
    }

    /** Plays [sound]'s effect, then its callout; then whatever waited. */
    private fun announce(sound: SessionSound) {
        val cue = SoundCues.of(sound)
        val s = settings()
        val now = clock()
        val voice = cue.voice?.takeIf { voice ->
            s.voiceOn && (cue.essential || !s.essentialOnly) && takes.containsKey(voice) &&
                lastSaid[voice].let { it == null || now - it >= AnnouncementQueue.VOICE_COOLDOWN_MILLIS }
        }
        val id = ++announcement
        announcing.clear()
        var length = 0L
        if (voice == null || cue.effectUnderVoice) length = playEffect(cue, s.effectsVolume, announcing)
        if (voice != null) {
            lastSaid[voice] = now
            val startIn = if (length > 0) length + AnnouncementQueue.GAP_MILLIS else 0L
            val voiceLength = takes[voice]?.firstOrNull()?.let { durations[it] } ?: DEFAULT_MILLIS
            handler.postDelayed({
                if (id != announcement) return@postDelayed
                talkingUntil = clock() + voiceLength
                playTake(voice, 1f, settings().voiceVolume, vary = false)?.let { announcing += it.first }
            }, startIn)
            length = startIn + voiceLength
        }
        handler.postDelayed({
            if (id == announcement) queue.finished(clock())?.let { announce(it) }
        }, length + AnnouncementQueue.GAP_MILLIS)
    }

    /** The game ended: what was being announced stops. */
    private fun cutOff() {
        announcement++
        announcing.forEach(pool::stop)
        announcing.clear()
        talkingUntil = 0
    }

    /** [cue]'s effect (or its stand-in) at [volume]; returns how long it plays. */
    private fun playEffect(cue: SoundCue, volume: Float, streams: MutableList<Int>? = null): Long {
        if (takes.containsKey(cue.file)) {
            val (stream, length) = playTake(cue.file, 1f, volume, cue.vary) ?: return 0
            streams?.add(stream)
            return length
        }
        var length = 0L
        repeat(cue.repeats) { i ->
            if (i == 0) {
                playTake(cue.fallback, cue.fallbackRate, volume, cue.vary)?.let { (stream, l) -> streams?.add(stream); length = l }
            } else {
                handler.postDelayed({ playTake(cue.fallback, cue.fallbackRate, volume, cue.vary)?.let { streams?.add(it.first) } }, i * REPEAT_GAP_MILLIS)
            }
        }
        return length + (cue.repeats - 1) * REPEAT_GAP_MILLIS
    }

    /**
     * One take of [name]: a different one from last time when there are several, a little varied
     * if [vary]. Returns its stream and how long it plays, or null if it didn't.
     */
    private fun playTake(name: String, rate: Float, volume: Float, vary: Boolean): Pair<Int, Long>? {
        val ids = takes[name] ?: return null
        var index = if (ids.size == 1) 0 else random.nextInt(ids.size)
        if (ids.size > 1 && index == lastTake[name]) index = (index + 1) % ids.size
        lastTake[name] = index
        // SoundPool plays from half to twice the speed
        val pitch = (if (vary) rate * (1f + (random.nextFloat() * 2 - 1) * SoundCues.PITCH_SPREAD) else rate).coerceIn(0.5f, 2f)
        val loud = (if (vary) volume * (1f - random.nextFloat() * SoundCues.VOLUME_SPREAD) else volume).coerceIn(0f, 1f)
        val length = ((durations[ids[index]] ?: DEFAULT_MILLIS) / pitch).toLong()
        if (loud <= 0f) return 0 to length
        return pool.play(ids[index], loud, loud, 1, 0, pitch) to length
    }

    fun release() = pool.release()

    private companion object {
        const val TAG = "GameSounds"
        const val MAX_STREAMS = 12
        const val MAX_TAKES = 6

        /** A sound whose length isn't known yet. */
        const val DEFAULT_MILLIS = 1_200L
        const val DUCKED = 0.45f
        const val REPEAT_GAP_MILLIS = 150L
    }
}

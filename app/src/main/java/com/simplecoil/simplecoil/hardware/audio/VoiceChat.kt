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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.simplecoil.simplecoil.network.voice.VoiceAccess
import com.simplecoil.simplecoil.network.voice.VoiceProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Push-to-talk on a player's phone: while [talk] is on, the microphone goes to the server (which
 * passes it to teammates, or to everyone for an admin who asks); whatever the server passes on
 * plays, one stream per speaker (unless muted here). Only while the server has voice chat on
 * for this player ([setAccess]): to listen, and to talk if it lets them. Plain threads: audio
 * can't wait on the main thread.
 */
class VoiceChat(
    context: Context,
    private val playerId: () -> Int,
    private val server: () -> InetAddress?,
    /** An admin talking to all players rather than their team. */
    private val everyone: () -> Boolean,
    private val volume: () -> Float,
) {
    private val app = context.applicationContext

    private val _enabled = MutableStateFlow(false)

    /** The server has voice chat on for this player. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _talking = MutableStateFlow(false)

    /** This player is talking now. */
    val talking: StateFlow<Boolean> = _talking.asStateFlow()

    private val _speakers = MutableStateFlow<Set<Int>>(emptySet())

    /** Who is heard right now, by player id. */
    val speakers: StateFlow<Set<Int>> = _speakers.asStateFlow()

    private val _needsMic = MutableStateFlow(false)

    /** Talking needs the microphone permission, which this phone hasn't given yet. */
    val needsMic: StateFlow<Boolean> = _needsMic.asStateFlow()

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var recording = false

    private val _canTalk = MutableStateFlow(false)

    /** This player may talk too, not just listen. */
    val canTalk: StateFlow<Boolean> = _canTalk.asStateFlow()

    private val _muted = MutableStateFlow<Set<Int>>(emptySet())

    /** Players this phone has muted (only here). An admin talking to everyone is heard anyway. */
    val muted: StateFlow<Set<Int>> = _muted.asStateFlow()

    fun setMuted(playerIds: Collection<Int>, mute: Boolean) {
        _muted.value = if (mute) _muted.value + playerIds else _muted.value - playerIds.toSet()
    }

    /** With voice chat off, an admin talks only to everyone (no team chat). */
    @Volatile
    private var broadcastOnly = false

    /**
     * What the server lets this player do: hear, talk too, or nothing. With voice chat off a
     * phone still listens for an admin talking to everyone, and an admin may do that, but voice
     * chat doesn't count as on ([enabled]: no mute buttons, no team chat).
     */
    fun setAccess(access: VoiceAccess) {
        val listen = access != VoiceAccess.OFF
        val talk = access == VoiceAccess.TALK || access == VoiceAccess.BROADCAST
        broadcastOnly = access == VoiceAccess.BROADCAST
        _canTalk.value = talk
        if (!talk) talk(false)
        _needsMic.value = talk && !hasMic()
        _enabled.value = access == VoiceAccess.TALK || access == VoiceAccess.LISTEN
        if (listen) open() else close()
    }

    /** The microphone permission was just given (or refused). */
    fun onMicPermission() {
        _needsMic.value = _canTalk.value && !hasMic()
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Push to talk: [on] while the button is held. */
    fun talk(on: Boolean) {
        if (on && (!_canTalk.value || socket == null || (broadcastOnly && !everyone()))) return
        if (on && !hasMic()) {
            _needsMic.value = true
            return
        }
        if (_talking.value == on) return
        _talking.value = on
        if (on) {
            recording = true
            Thread({ record() }, "VoiceMic").start()
        } else {
            recording = false
        }
    }

    private fun open() {
        if (socket != null) return
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = RECEIVE_TIMEOUT_MILLIS
                bind(InetSocketAddress(VoiceProtocol.PORT))
            }
        } catch (e: SocketException) {
            Log.w(TAG, "No voice socket: " + e.message)
            return
        }
        socket = s
        Thread({ listen(s) }, "VoiceIn").start()
    }

    private fun close() {
        socket?.close()
        socket = null
        _speakers.value = emptySet()
    }

    /** The microphone, 20 ms at a time, to the server, until [talk] goes off. */
    @SuppressLint("MissingPermission") // checked before the thread starts
    private fun record() {
        val minBuffer = AudioRecord.getMinBufferSize(VoiceProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            // Voice communication: the phone's own echo cancelling and noise suppression
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, VoiceProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, VoiceProtocol.FRAME_BYTES * 4))
        } catch (e: Exception) {
            Log.w(TAG, "No microphone: " + e.message)
            _talking.value = false
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            _talking.value = false
            return
        }
        val frame = ByteArray(VoiceProtocol.FRAME_BYTES)
        var seq = 0
        recorder.startRecording()
        try {
            while (recording) {
                var read = 0
                while (read < frame.size && recording) {
                    val n = recorder.read(frame, read, frame.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < frame.size) continue
                val to = server() ?: continue
                val s = socket ?: break
                val bytes = VoiceProtocol.encode(VoiceProtocol.Packet(playerId(), everyone(), seq, frame))
                seq = (seq + 1) and 0xFF
                try {
                    s.send(DatagramPacket(bytes, bytes.size, to, VoiceProtocol.PORT))
                } catch (e: Exception) {
                    Log.d(TAG, "Voice not sent: " + e.message)
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }

    private class Speaker(val track: AudioTrack, var lastHeard: Long)

    /** What the server passes on, each speaker on their own stream; quiet ones are let go. */
    private fun listen(s: DatagramSocket) {
        val speakers = HashMap<Int, Speaker>()
        val buffer = ByteArray(VoiceProtocol.MAX_PACKET)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (!s.isClosed) {
                val now = SystemClock.elapsedRealtime()
                try {
                    packet.setLength(buffer.size)
                    s.receive(packet)
                    val voice = VoiceProtocol.decode(buffer, packet.length)
                    // Muted here, unless an admin is talking to everyone (the server marks only those)
                    if (voice != null && voice.playerId != playerId() && (voice.everyone || voice.playerId !in _muted.value)) {
                        val speaker = speakers.getOrPut(voice.playerId) { Speaker(newTrack(), now) }
                        // Talking again after a pause: the same cushion as a new speaker's
                        if (now - speaker.lastHeard > SPEAKING_MILLIS) cushion(speaker.track)
                        speaker.lastHeard = now
                        speaker.track.setVolume(volume().coerceIn(0f, 1f))
                        speaker.track.write(voice.audio, 0, voice.audio.size, AudioTrack.WRITE_NON_BLOCKING)
                    }
                } catch (_: SocketTimeoutException) {
                }
                // Who is talking now, and streams quiet long enough to let go
                speakers.entries.removeAll { (_, sp) ->
                    (now - sp.lastHeard > RELEASE_MILLIS).also { if (it) sp.track.release() }
                }
                val heard = speakers.filterValues { now - it.lastHeard < SPEAKING_MILLIS }.keys
                if (heard != _speakers.value) _speakers.value = heard
            }
        } catch (e: Exception) {
            if (!s.isClosed) Log.w(TAG, "Voice stopped: " + e.message)
        } finally {
            speakers.values.forEach { it.track.release() }
            _speakers.value = emptySet()
        }
    }

    private fun newTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(VoiceProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(VoiceProtocol.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(maxOf(minBuffer, VoiceProtocol.FRAME_BYTES * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track ->
                cushion(track)
                track.play()
            }
    }

    /** A little silence first, so a packet arriving late doesn't break up the start. */
    private fun cushion(track: AudioTrack) {
        track.write(ByteArray(VoiceProtocol.FRAME_BYTES * JITTER_FRAMES), 0, VoiceProtocol.FRAME_BYTES * JITTER_FRAMES, AudioTrack.WRITE_NON_BLOCKING)
    }

    private companion object {
        const val TAG = "VoiceChat"
        const val RECEIVE_TIMEOUT_MILLIS = 200

        /** Heard within this long: shown as talking. */
        const val SPEAKING_MILLIS = 400L

        /** Quiet this long: their stream is let go (a new one starts when they talk again). */
        const val RELEASE_MILLIS = 3_000L

        /** Frames of silence ahead of a new speaker, to ride out late packets. */
        const val JITTER_FRAMES = 3
    }
}

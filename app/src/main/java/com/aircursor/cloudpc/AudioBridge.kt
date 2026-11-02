package com.aircursor.cloudpc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.DataInputStream
import java.io.File

/**
 * Sound from AirCursor PC: PulseAudio inside the PC plays into a virtual "Phone speaker" and
 * streams it (16-bit stereo, 44.1 kHz) through a private socket in the PC's /tmp. This plays it
 * with the phone's media volume. Silence pauses the player, a backlog is dropped (low delay).
 */
object AudioBridge {
    private const val TAG = "AudioBridge"
    private const val RATE = 44100
    private const val FRAME = 4                       // 2 channels × 16 bit
    private const val CHUNK = RATE / 50 * FRAME       // 20 ms
    @Volatile private var running = false
    @Volatile private var socket: LocalSocket? = null

    fun start(c: Context) {
        if (running) return
        running = true
        val path = File(CloudPcEngine.rootfs(c), "tmp/cloudpc-audio.sock").absolutePath
        Thread({ loop(path) }, "cloudpc-audio").start()
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun loop(path: String) {
        while (running) {
            var track: AudioTrack? = null
            try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                socket = s
                val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(min * 2, CHUNK * 8))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                val input = DataInputStream(s.inputStream)
                val buf = ByteArray(CHUNK)
                var silent = 0
                var playing = false
                while (running) {
                    input.readFully(buf)
                    // Keep the delay small: drop anything older than ~120 ms.
                    val backlog = input.available()
                    if (backlog > RATE * FRAME / 8) {
                        val drop = (backlog - RATE * FRAME / 20) / FRAME * FRAME
                        if (drop > 0) input.skipBytes(drop)
                    }
                    if (buf.all { it == 0.toByte() }) {
                        if (++silent > 100 && playing) { // 2 s of silence: pause (no battery use)
                            track.pause()
                            track.flush()
                            playing = false
                        }
                        if (!playing) continue
                    } else {
                        silent = 0
                        if (!playing) {
                            track.play()
                            playing = true
                        }
                    }
                    track.write(buf, 0, buf.size)
                }
            } catch (e: Exception) {
                if (running) Log.d(TAG, "audio: ${e.message}")
            } finally {
                try {
                    track?.release()
                } catch (_: Exception) {
                }
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null
            }
            if (running) Thread.sleep(1500) // the PC is starting, or PulseAudio restarted
        }
    }
}

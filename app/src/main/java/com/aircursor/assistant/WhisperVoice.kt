package com.aircursor.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** whisper.cpp in the app (libaircursor_whisper.so, 64-bit phones). */
object WhisperNative {
    /** The native library loaded (false on 32-bit phones: they keep the phone's own speech service). */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("aircursor_whisper")
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic external fun nativeInit(libDir: String, modelPath: String): Long

    @JvmStatic external fun nativeTranscribe(
        handle: Long, audio: FloatArray, language: String, prompt: String?, threads: Int, audioCtx: Int,
    ): String

    @JvmStatic external fun nativeFree(handle: Long)
}

/**
 * Offline voice for the assistant: listens on the microphone until the command is over, then
 * Whisper turns it into text on the phone (no internet, the audio never leaves the phone).
 */
class WhisperVoice(context: Context) {
    interface Listener {
        fun level(level: Float)
        /** The words are over; now writing them down. */
        fun thinking()
        /** What was said (cleaned), or "" when nothing was heard. */
        fun heard(text: String)
        fun failed(message: String)
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private var handle = 0L
    private var loadedModel: WhisperModel? = null
    @Volatile private var cancelled = false
    @Volatile var busy = false
        private set

    var model: WhisperModel
        get() = WhisperModel.entries.firstOrNull { it.name == prefs.getString("whisper_model", null) } ?: WhisperModel.BASE
        set(m) = prefs.edit().putString("whisper_model", m.name).apply()

    fun modelFile(m: WhisperModel) = File(app.filesDir, "whisper/${m.file}")

    fun isDownloaded(m: WhisperModel) = modelFile(m).length() == m.bytes

    /** Offline voice can be used now (64-bit phone and the chosen model on the phone). */
    val ready get() = WhisperNative.available && isDownloaded(model)

    /** Downloads [m] once (checked byte for byte); [progress] gets the percent. */
    fun download(m: WhisperModel, progress: (Int) -> Unit, done: (String?) -> Unit) {
        Thread({
            val err = try {
                fetch(m) { pct -> main.post { progress(pct) } }
                null
            } catch (e: Exception) {
                e.message ?: "download failed"
            }
            main.post { done(err) }
        }, "whisper-download").start()
    }

    private fun fetch(m: WhisperModel, progress: (Int) -> Unit) {
        val out = modelFile(m)
        if (out.length() == m.bytes) return
        out.parentFile?.mkdirs()
        val part = File(out.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        var url = URL(m.url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {   // the model host answers with a redirect to its file server
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            if (code != 200) throw IllegalStateException("server said $code")
            break
        }
        var got = 0L
        var lastPct = -1
        conn.inputStream.use { input ->
            part.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    got += n
                    val pct = (got * 100 / m.bytes).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        progress(pct)
                    }
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != m.bytes || hex != m.sha256) {
            part.delete()
            throw IllegalStateException("the file did not arrive complete – try again")
        }
        if (!part.renameTo(out)) throw IllegalStateException("could not save the model")
    }

    /** Listens for one command; the listener is called on the main thread. */
    fun listen(listener: Listener) {
        if (busy) return
        busy = true
        cancelled = false
        Thread({
            try {
                val audio = record(listener)
                if (audio == null || cancelled) {
                    if (!cancelled) main.post { listener.heard("") }
                    return@Thread
                }
                main.post { listener.thinking() }
                val text = transcribe(audio)
                if (!cancelled) main.post { listener.heard(text) }
            } catch (e: Exception) {
                if (!cancelled) main.post { listener.failed(e.message ?: "voice failed") }
            } finally {
                busy = false
            }
        }, "whisper-listen").start()
    }

    fun cancel() {
        cancelled = true
    }

    /** Microphone audio from just before the first word to the end of the command; null when nobody spoke. */
    @SuppressLint("MissingPermission")   // asked before the assistant listens
    private fun record(listener: Listener): FloatArray? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 8),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("The microphone is busy – try again.")
        }
        val endpoint = SpeechEndpoint(RATE)
        val pcm = ShortArray(RATE * 12)
        var used = 0
        var speechAt = -1
        val frame = ShortArray(FRAME)
        try {
            rec.startRecording()
            while (!cancelled) {
                val n = rec.read(frame, 0, FRAME)
                if (n <= 0) continue
                val room = minOf(n, pcm.size - used)
                System.arraycopy(frame, 0, pcm, used, room)
                used += room
                val before = endpoint.state
                val state = endpoint.feed(frame, n)
                val lvl = endpoint.level
                main.post { listener.level(lvl) }
                if (before == SpeechEndpoint.State.WAITING && state == SpeechEndpoint.State.SPEAKING) speechAt = used - n
                if (state == SpeechEndpoint.State.NO_SPEECH) return null
                if (state == SpeechEndpoint.State.DONE || used >= pcm.size) break
            }
        } finally {
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
        }
        if (cancelled || speechAt < 0) return null
        // Keep a little before the first word (its start is quiet) and pad the end with silence.
        val from = (speechAt - RATE * PRE_ROLL_MS / 1000).coerceAtLeast(0)
        val out = FloatArray(used - from + RATE / 4)
        for (i in from until used) out[i - from] = pcm[i] / 32768f
        return out
    }

    private fun transcribe(audio: FloatArray): String = synchronized(lock) {
        val m = model
        if (handle == 0L || loadedModel != m) {
            if (handle != 0L) WhisperNative.nativeFree(handle)
            handle = WhisperNative.nativeInit(app.applicationInfo.nativeLibraryDir, modelFile(m).path)
            loadedModel = m
            if (handle == 0L) throw IllegalStateException("The voice model could not be opened – download it again.")
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        // The shorter audio window: about as fast as the tiny model and more accurate on base; the
        // tiny model wrote nonsense with it in tests, so it keeps the full window.
        val ctx = if (m == WhisperModel.BASE) AUDIO_CTX else 0
        val raw = WhisperNative.nativeTranscribe(handle, audio, WhisperText.LANGUAGE, WhisperText.HINT, threads, ctx)
        WhisperText.clean(raw)
    }

    /** Frees the model (about its file size of memory). */
    fun release() {
        cancelled = true
        synchronized(lock) {
            if (handle != 0L) WhisperNative.nativeFree(handle)
            handle = 0L
            loadedModel = null
        }
    }

    companion object {
        private const val RATE = 16_000
        private const val FRAME = 320           // 20 ms
        private const val PRE_ROLL_MS = 300

        /** Look at about 15 s of audio instead of Whisper's full 30 s: a command is short (faster). */
        private const val AUDIO_CTX = 768
    }
}

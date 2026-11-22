package com.oyuntr.dubbing

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Plays generated WAV/MP3 clips and drops late responses after a newer subtitle arrives. */
class AiSpeechAudioPlayer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    fun play(audio: SpeechAudio) {
        val ticket = generation.incrementAndGet()
        Thread {
            val extension = if (audio.mimeType.contains("wav", ignoreCase = true)) ".wav" else ".mp3"
            val file = File.createTempFile("oyuntr-speech-", extension, appContext.cacheDir)
            try {
                file.writeBytes(audio.data)
                main.post {
                    if (generation.get() != ticket) {
                        file.delete()
                        return@post
                    }
                    releaseCurrent()
                    currentFile = file
                    player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        setDataSource(file.absolutePath)
                        setOnPreparedListener { if (generation.get() == ticket) it.start() }
                        setOnCompletionListener { if (generation.get() == ticket) releaseCurrent() }
                        setOnErrorListener { _, _, _ ->
                            if (generation.get() == ticket) releaseCurrent()
                            true
                        }
                        prepareAsync()
                    }
                }
            } catch (_: Exception) {
                file.delete()
            }
        }.start()
    }

    fun stop() {
        generation.incrementAndGet()
        main.post { releaseCurrent() }
    }

    override fun close() = stop()

    private fun releaseCurrent() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        currentFile?.delete()
        currentFile = null
    }
}

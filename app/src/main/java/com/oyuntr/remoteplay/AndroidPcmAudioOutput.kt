package com.oyuntr.remoteplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Plays decoded signed 16-bit PCM supplied by the session's Opus decoder. */
class AndroidPcmAudioOutput : AutoCloseable {
    private val lock = Any()
    private val pcmQueue = ArrayBlockingQueue<ByteArray>(12)
    @Volatile private var closed = false
    @Volatile private var workerTrack: AudioTrack? = null
    private var track: AudioTrack? = null

    private val writer = Thread({
        while (!closed) {
            try {
                val pcm = pcmQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (pcm.isEmpty()) continue
                val active = workerTrack ?: continue
                var offset = 0
                while (offset < pcm.size && !closed) {
                    val written = active.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
            } catch (_: InterruptedException) {
                if (closed) break
            } catch (_: IllegalStateException) {
                // Audio was stopped/reconfigured while a queued frame was being written.
            }
        }
    }, "oyuntr-game-audio").apply { isDaemon = true; start() }

    fun configure(channels: Int, sampleRateHz: Int) {
        synchronized(lock) {
            require(channels == 1 || channels == 2) { "Unsupported PS4 audio channel count: $channels" }
            require(sampleRateHz in 8_000..192_000) { "Unsupported PS4 audio sample rate: $sampleRateHz" }
            track?.runCatching { pause(); flush(); stop() }
            track?.release()
            track = null
            workerTrack = null
            pcmQueue.clear()
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuffer = AudioTrack.getMinBufferSize(sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            require(minBuffer > 0) { "Android AudioTrack rejected the PS4 audio format" }
            val bufferSize = maxOf(minBuffer * 4, sampleRateHz * channels * 2 / 5)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track?.play()
            workerTrack = track
        }
    }

    fun writePcm(pcm: ByteArray) {
        if (closed || pcm.isEmpty() || workerTrack == null) return
        val copy = pcm.copyOf()
        if (!pcmQueue.offer(copy)) {
            pcmQueue.poll()
            pcmQueue.offer(copy)
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        pcmQueue.clear()
        pcmQueue.offer(WAKE_WRITER)
        track?.runCatching { pause(); flush(); stop() }
        track?.release()
        track = null
        workerTrack = null
        writer.interrupt()
    }

    private companion object { val WAKE_WRITER = ByteArray(0) }
}

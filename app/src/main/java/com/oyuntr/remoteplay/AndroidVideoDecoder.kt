package com.oyuntr.remoteplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Android-owned H.264 access-unit decoder. chiaki-ng samples are queued without modifying them. */
class AndroidVideoDecoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val frameRate: Int = 30,
) : AutoCloseable {
    private val lock = Any()
    private val pts = AtomicLong(0)
    private var codec: MediaCodec? = null

    fun start(surface: Surface) = synchronized(lock) {
        check(codec == null) { "Video decoder is already running" }
        require(surface.isValid) { "Video output surface is invalid" }
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
        } catch (error: Exception) {
            decoder.release()
            throw error
        }
    }

    /** Returns false when the codec is not ready; the caller should request/rely on a new keyframe. */
    fun queueSample(sample: ByteArray): Boolean = synchronized(lock) {
        val active = codec ?: return false
        if (sample.isEmpty()) return false
        val index = active.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) return false
        val input = active.getInputBuffer(index) ?: run {
            active.queueInputBuffer(index, 0, 0, pts.getAndAdd(PRESENTATION_STEP_US), 0)
            return false
        }
        if (sample.size > input.capacity()) {
            active.queueInputBuffer(index, 0, 0, pts.getAndAdd(PRESENTATION_STEP_US), 0)
            return false
        }
        input.clear()
        input.put(sample)
        active.queueInputBuffer(index, 0, sample.size, pts.getAndAdd(PRESENTATION_STEP_US), 0)
        drainOutput(active)
        true
    }

    private fun drainOutput(active: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = active.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (index >= 0) active.releaseOutputBuffer(index, info.size > 0) else return
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            codec?.let { active ->
                try {
                    active.stop()
                } finally {
                    active.release()
                    codec = null
                }
            }
        }
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 0L
        const val PRESENTATION_STEP_US = 33_333L
    }
}

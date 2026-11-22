package com.oyuntr.ocr

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import java.util.concurrent.atomic.AtomicBoolean

/** Samples the game Surface without capturing the separate subtitle overlay drawn above it. */
class SurfaceSubtitleFrameSource(
    private val surfaceView: SurfaceView,
    private val controller: SubtitleOverlayController,
    private val intervalMillis: Long = 500,
) : AutoCloseable {
    init {
        require(intervalMillis > 0) { "Subtitle capture interval must be positive" }
    }
    private val thread = HandlerThread("oyuntr-subtitle-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val captureInFlight = AtomicBoolean(false)
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        handler.post(captureLoop)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(captureLoop)
        controller.clear()
    }

    override fun close() {
        stop()
        thread.quitSafely()
    }

    private val captureLoop = object : Runnable {
        override fun run() {
            if (!running) return
            val width = surfaceView.width
            val height = surfaceView.height
            if (width > 0 && height > 0 && surfaceView.holder.surface.isValid && captureInFlight.compareAndSet(false, true)) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(surfaceView, bitmap, { result ->
                        captureInFlight.set(false)
                        if (result == PixelCopy.SUCCESS && running) {
                            controller.submit(
                                BitmapSubtitleFrame(
                                    bitmap = bitmap,
                                    presentationTimeMicros = SystemClock.elapsedRealtimeNanos() / 1_000,
                                    recycleOnRelease = true,
                                ),
                            ).whenComplete { _, _ -> Unit }
                        } else {
                            bitmap.recycle()
                        }
                    }, handler)
                } catch (_: RuntimeException) {
                    captureInFlight.set(false)
                    bitmap.recycle()
                }
            }
            if (running) handler.postDelayed(this, intervalMillis)
        }
    }
}

package com.oyuntr.ocr

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/** Applies only the newest frame result so a slow translation cannot replace a newer subtitle. */
class SubtitleOverlayController(
    private val pipeline: SubtitleTranslationPipeline,
    private val overlay: SubtitleOverlayView,
    private val onTranslatedSubtitle: (TranslatedSubtitle?) -> Unit = {},
) {
    private val newestFrame = AtomicLong(0)
    private val generation = AtomicLong(0)

    fun submit(frame: SubtitleImageFrame): CompletableFuture<Unit> {
        val ticket = generation.get()
        return pipeline.process(frame).thenApply { subtitle ->
            if (generation.get() == ticket && subtitle is SubtitleFrameResult.Ready) {
                val ready = subtitle.subtitle
                if (ready == null) {
                    newestFrame.set(Long.MIN_VALUE)
                    overlay.clear()
                    onTranslatedSubtitle(null)
                } else if (ready.presentationTimeMicros >= newestFrame.get()) {
                    newestFrame.set(ready.presentationTimeMicros)
                    overlay.show(ready)
                    onTranslatedSubtitle(ready)
                }
            }
            Unit
        }
    }

    fun clear() {
        generation.incrementAndGet()
        newestFrame.set(Long.MIN_VALUE)
        overlay.clear()
    }
}

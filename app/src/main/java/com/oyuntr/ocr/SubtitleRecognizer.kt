package com.oyuntr.ocr

import android.graphics.Bitmap
import java.util.concurrent.CompletableFuture

/** A decoded image supplied by a video decoder or a test/image-import source. */
interface SubtitleImageFrame {
    val bitmap: Bitmap
    val presentationTimeMicros: Long
    fun release() {}
}

data class BitmapSubtitleFrame(
    override val bitmap: Bitmap,
    override val presentationTimeMicros: Long,
    private val recycleOnRelease: Boolean = false,
) : SubtitleImageFrame {
    override fun release() {
        if (recycleOnRelease && !bitmap.isRecycled) bitmap.recycle()
    }
}

data class RecognizedSubtitleLine(
    val text: String,
    val bounds: NormalizedBounds,
    val confidence: Float?,
)

/** Relative coordinates allow the overlay to scale with the TV video surface. */
data class NormalizedBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class RecognitionResult(
    val lines: List<RecognizedSubtitleLine>,
    val detectedLanguageTag: String?,
)

/** OCR is replaceable and independent from the Remote Play provider. */
interface SubtitleRecognizer {
    fun recognize(frame: SubtitleImageFrame): CompletableFuture<RecognitionResult>
}

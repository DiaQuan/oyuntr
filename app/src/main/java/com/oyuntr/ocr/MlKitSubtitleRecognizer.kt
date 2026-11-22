package com.oyuntr.ocr

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CompletableFuture

/** On-device Latin-script OCR for subtitle frames. The bundled model works offline. */
class MlKitSubtitleRecognizer : SubtitleRecognizer, AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun recognize(frame: SubtitleImageFrame): CompletableFuture<RecognitionResult> {
        val result = CompletableFuture<RecognitionResult>()
        val bitmap = frame.bitmap
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            result.completeExceptionally(IllegalArgumentException("Subtitle frame bitmap is invalid"))
            return result
        }

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                    val rect = line.boundingBox ?: return@mapNotNull null
                    RecognizedSubtitleLine(
                        text = line.text.trim(),
                        bounds = NormalizedBounds(
                            left = (rect.left.toFloat() / bitmap.width).coerceIn(0f, 1f),
                            top = (rect.top.toFloat() / bitmap.height).coerceIn(0f, 1f),
                            right = (rect.right.toFloat() / bitmap.width).coerceIn(0f, 1f),
                            bottom = (rect.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f),
                        ),
                        confidence = line.confidence.takeIf { it >= 0f },
                    ).takeIf { it.text.isNotBlank() }
                }
                val detectedLanguage = text.textBlocks.asSequence()
                    .flatMap { it.lines.asSequence() }
                    .map { it.recognizedLanguage }
                    .filter { it.isNotBlank() && it != "und" }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                result.complete(RecognitionResult(lines, detectedLanguageTag = detectedLanguage))
            }
            .addOnFailureListener { error -> result.completeExceptionally(error) }
        return result
    }

    override fun close() = recognizer.close()
}

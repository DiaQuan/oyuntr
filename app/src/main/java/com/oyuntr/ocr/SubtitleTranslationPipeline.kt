package com.oyuntr.ocr

import com.oyuntr.translation.TranslationRequest
import com.oyuntr.translation.TranslationService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

data class TranslatedSubtitle(
    val originalText: String,
    val translatedText: String,
    val bounds: NormalizedBounds,
    val presentationTimeMicros: Long,
    val gameId: String = "unknown",
)

sealed interface SubtitleFrameResult {
    data class Ready(val subtitle: TranslatedSubtitle?) : SubtitleFrameResult
    data object SkippedWhileBusy : SubtitleFrameResult
}

/** Selects lower-screen subtitle lines, joins them, translates and preserves their screen bounds. */
class SubtitleTranslationPipeline(
    private val recognizer: SubtitleRecognizer,
    private val translationService: TranslationService,
    private val gameId: String,
    private val sourceLanguageTag: String,
    private val targetLanguageTag: String = "tr-TR",
) {
    private val recognitionInFlight = AtomicBoolean(false)

    /** Drops overlapping frames instead of building an OCR backlog while the game is moving. */
    fun process(frame: SubtitleImageFrame): CompletableFuture<SubtitleFrameResult> {
        if (!recognitionInFlight.compareAndSet(false, true)) {
            frame.release()
            return CompletableFuture.completedFuture(SubtitleFrameResult.SkippedWhileBusy)
        }
        val recognitionFuture = try {
            recognizer.recognize(frame)
        } catch (error: RuntimeException) {
            frame.release()
            recognitionInFlight.set(false)
            return CompletableFuture<SubtitleFrameResult>().also { it.completeExceptionally(error) }
        }
        return recognitionFuture.whenComplete { _, _ -> frame.release() }.thenCompose { recognition ->
            val candidates = recognition.lines
                .filter { line -> line.bounds.bottom >= MIN_SUBTITLE_BOTTOM }
                .sortedWith(compareBy<RecognizedSubtitleLine> { it.bounds.top }.thenBy { it.bounds.left })
            if (candidates.isEmpty()) {
                return@thenCompose CompletableFuture.completedFuture<SubtitleFrameResult>(SubtitleFrameResult.Ready(null))
            }

            val original = candidates.joinToString(" ") { it.text.trim() }
                .replace(WHITESPACE, " ")
                .trim()
            if (original.isBlank()) {
                return@thenCompose CompletableFuture.completedFuture<SubtitleFrameResult>(SubtitleFrameResult.Ready(null))
            }

            val bounds = NormalizedBounds(
                left = candidates.minOf { it.bounds.left },
                top = candidates.minOf { it.bounds.top },
                right = candidates.maxOf { it.bounds.right },
                bottom = candidates.maxOf { it.bounds.bottom },
            )
            val request = TranslationRequest(
                gameId = gameId,
                sourceText = original,
                sourceLanguageTag = recognition.detectedLanguageTag ?: sourceLanguageTag,
                targetLanguageTag = targetLanguageTag,
            )
            translationService.translate(request).thenApply<SubtitleFrameResult> { translated ->
                SubtitleFrameResult.Ready(
                    TranslatedSubtitle(original, translated.text, bounds, frame.presentationTimeMicros, gameId),
                )
            }
        }.whenComplete { _, _ -> recognitionInFlight.set(false) }
    }

    private companion object {
        const val MIN_SUBTITLE_BOTTOM = 0.58f
        val WHITESPACE = Regex("\\s+")
    }
}

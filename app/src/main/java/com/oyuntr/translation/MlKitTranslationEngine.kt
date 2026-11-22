package com.oyuntr.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Offline translation after the requested language model has been downloaded. */
class MlKitTranslationEngine : TranslationEngine, AutoCloseable {
    override val id: String = "mlkit-on-device"
    private val translators = ConcurrentHashMap<String, Translator>()

    override fun translate(request: TranslationRequest): CompletableFuture<TranslationResult> {
        val source = TranslateLanguage.fromLanguageTag(request.sourceLanguageTag)
        val target = TranslateLanguage.fromLanguageTag(request.targetLanguageTag)
        if (source == null || target == null) {
            return failed(IllegalArgumentException("Unsupported translation language tag"))
        }
        if (request.sourceText.isBlank()) {
            return failed(IllegalArgumentException("Translation text cannot be blank"))
        }

        val key = "$source->$target"
        val translator = translators.computeIfAbsent(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build(),
            )
        }
        val future = CompletableFuture<TranslationResult>()
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(request.sourceText)
                    .addOnSuccessListener { translated ->
                        future.complete(TranslationResult(translated, id))
                    }
                    .addOnFailureListener { error -> future.completeExceptionally(error) }
            }
            .addOnFailureListener { error -> future.completeExceptionally(error) }
        return future
    }

    override fun close() {
        translators.values.forEach(Translator::close)
        translators.clear()
    }

    private fun <T> failed(error: Throwable) = CompletableFuture<T>().also {
        it.completeExceptionally(error)
    }
}

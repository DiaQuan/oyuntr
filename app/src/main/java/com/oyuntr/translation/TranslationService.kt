package com.oyuntr.translation

/** Applies OyunTR's game-aware cache before calling a configured translation provider. */
class TranslationService(
    private val engine: TranslationEngine,
    private val cache: TranslationCache,
) {
    fun translate(request: TranslationRequest) = cache.get(request)?.let {
        java.util.concurrent.CompletableFuture.completedFuture(it)
    } ?: engine.translate(request).thenApply { result ->
        cache.put(request, result)
        result
    }
}

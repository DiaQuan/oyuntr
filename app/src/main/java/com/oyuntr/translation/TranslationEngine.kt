package com.oyuntr.translation

import java.util.concurrent.CompletableFuture

data class TranslationRequest(
    val gameId: String,
    val sourceText: String,
    val sourceLanguageTag: String,
    val targetLanguageTag: String = "tr-TR",
)

data class TranslationResult(
    val text: String,
    val providerId: String,
    val wasCached: Boolean = false,
)

/** Provider boundary for OyunTR's Turkish game-text translation. */
interface TranslationEngine {
    val id: String

    fun translate(request: TranslationRequest): CompletableFuture<TranslationResult>
}

interface TranslationCache {
    fun get(request: TranslationRequest): TranslationResult?
    fun put(request: TranslationRequest, result: TranslationResult)
    fun clearGame(gameId: String)
}

/** Small process-local LRU cache. Persistent storage can replace this behind the same interface. */
class InMemoryTranslationCache(private val capacity: Int = 512) : TranslationCache {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private data class Key(
        val gameId: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val normalizedText: String,
    )

    private val entries = object : LinkedHashMap<Key, TranslationResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TranslationResult>?): Boolean =
            size > capacity
    }

    @Synchronized
    override fun get(request: TranslationRequest): TranslationResult? =
        entries[keyFor(request)]?.copy(wasCached = true)

    @Synchronized
    override fun put(request: TranslationRequest, result: TranslationResult) {
        entries[keyFor(request)] = result.copy(wasCached = false)
    }

    @Synchronized
    override fun clearGame(gameId: String) {
        val iterator = entries.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().gameId == gameId) iterator.remove()
        }
    }

    private fun keyFor(request: TranslationRequest) = Key(
        gameId = request.gameId.trim().lowercase(),
        sourceLanguage = request.sourceLanguageTag.trim().lowercase(),
        targetLanguage = request.targetLanguageTag.trim().lowercase(),
        normalizedText = request.sourceText.trim().replace(Regex("\\s+"), " "),
    )
}

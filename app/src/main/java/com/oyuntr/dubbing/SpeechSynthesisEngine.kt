package com.oyuntr.dubbing

import java.util.concurrent.CompletableFuture

data class SpeechRequest(
    val text: String,
    val languageTag: String = "tr-TR",
    val voiceId: String? = null,
    val gameId: String? = null,
    val speakerId: String? = null,
    val expressiveness: Float = 0.5f,
)

data class SpeechAudio(
    val data: ByteArray,
    val mimeType: String,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
)

/** Replaceable boundary for OyunTR's self-hosted speech synthesis integration. */
interface SpeechSynthesisEngine {
    val id: String

    fun synthesize(request: SpeechRequest): CompletableFuture<SpeechAudio>
}

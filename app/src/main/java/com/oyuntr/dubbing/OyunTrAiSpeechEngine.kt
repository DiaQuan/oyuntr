package com.oyuntr.dubbing

import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture

/** Calls the self-hosted OyunTR Chatterbox relay. */
class OyunTrAiSpeechEngine(
    private val endpoint: String,
    private val appToken: String,
) : SpeechSynthesisEngine {
    override val id: String = "chatterbox-multilingual-v3-relay"

    override fun synthesize(request: SpeechRequest): CompletableFuture<SpeechAudio> = CompletableFuture.supplyAsync {
        require(endpoint.startsWith("https://", ignoreCase = true)) {
            "AI dubbing server must use HTTPS"
        }
        require(request.text.isNotBlank() && request.text.length <= 4096) { "Subtitle text length is invalid" }
        require(!request.voiceId.isNullOrBlank()) { "Choose an AI voice for this character first" }
        val base = endpoint.trimEnd('/')
        val connection = (URL("$base/v1/speech").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $appToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "audio/wav")
        }
        try {
            val body = JSONObject()
                .put("text", request.text)
                .put("voice_id", request.voiceId)
                .put("language_tag", request.languageTag)
                .put("speaker_id", request.speakerId)
                .put("game_id", request.gameId)
                .put("exaggeration", request.expressiveness)
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(300) }.orEmpty()
                throw IllegalStateException("AI speech relay failed ($status): $detail")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_AUDIO_BYTES) throw IllegalStateException("AI audio is too large")
                    output.write(buffer, 0, count)
                }
            }
            SpeechAudio(output.toByteArray(), connection.contentType ?: "audio/wav")
        } finally {
            connection.disconnect()
        }
    }

    fun uploadVoiceReference(voiceId: String, displayName: String, wavData: ByteArray): CompletableFuture<AiVoiceOption> =
        CompletableFuture.supplyAsync {
            require(endpoint.startsWith("https://", ignoreCase = true)) { "Voice sample upload requires HTTPS" }
            require(wavData.size in 44..MAX_AUDIO_BYTES) { "WAV sample size is invalid" }
            val name = URLEncoder.encode(displayName, Charsets.UTF_8.name())
            val encodedId = URLEncoder.encode(voiceId, Charsets.UTF_8.name())
            val connection = (URL("${endpoint.trimEnd('/')}/v1/voices/$encodedId/reference?name=$name")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 30_000
                doOutput = true
                setFixedLengthStreamingMode(wavData.size)
                setRequestProperty("Authorization", "Bearer $appToken")
                setRequestProperty("Content-Type", "audio/wav")
            }
            try {
                connection.outputStream.use { it.write(wavData) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(300) }.orEmpty()
                    throw IllegalStateException("Voice sample upload failed ($status): $detail")
                }
                val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                AiVoiceOption(response.getString("id"), response.getString("name"), response.optString("category"))
            } finally {
                connection.disconnect()
            }
        }

    fun deleteVoiceReference(voiceId: String): CompletableFuture<Unit> = CompletableFuture.supplyAsync {
        require(endpoint.startsWith("https://", ignoreCase = true)) { "Voice sample deletion requires HTTPS" }
        val encodedId = URLEncoder.encode(voiceId, Charsets.UTF_8.name())
        val connection = (URL("${endpoint.trimEnd('/')}/v1/voices/$encodedId/reference")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 5_000
            readTimeout = 12_000
            setRequestProperty("Authorization", "Bearer $appToken")
        }
        try {
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(300) }.orEmpty()
                throw IllegalStateException("Voice sample deletion failed (${connection.responseCode}): $detail")
            }
            Unit
        } finally {
            connection.disconnect()
        }
    }

    fun listVoices(): CompletableFuture<List<AiVoiceOption>> = CompletableFuture.supplyAsync {
        val connection = (URL("${endpoint.trimEnd('/')}/v1/voices").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 12_000
            setRequestProperty("Authorization", "Bearer $appToken")
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("AI voice list failed (${connection.responseCode})")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val voices = JSONObject(body).optJSONArray("voices") ?: JSONArray()
            (0 until voices.length()).map { index ->
                val voice = voices.getJSONObject(index)
                AiVoiceOption(voice.getString("id"), voice.getString("name"), voice.optString("category"))
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object { private const val MAX_AUDIO_BYTES = 16 * 1024 * 1024 }
}

data class AiVoiceOption(val id: String, val name: String, val category: String)

package com.oyuntr.dubbing

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.oyuntr.ocr.TranslatedSubtitle
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Local device-voice fallback. This is Android TTS, not an AI voice provider. */
class AndroidTurkishDubbing(
    context: Context,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val locale = Locale.forLanguageTag("tr-TR")
    private val preferences = context.applicationContext.getSharedPreferences("dubbing_preferences", Context.MODE_PRIVATE)
    @Volatile private var enabled = false
    @Volatile private var ready = false
    @Volatile private var closed = false
    private var lastSpokenKey = ""
    private var pendingText = ""
    private var pendingSpeakerId = MAIN_CHARACTER_ID
    private var pendingId = 0L
    private var activeSpeakerId = preferences.getString(KEY_ACTIVE_SPEAKER, MAIN_CHARACTER_ID) ?: MAIN_CHARACTER_ID
    private var profiles = loadProfiles()
    private var turkishVoices: List<Voice> = emptyList()
    private var aiEngine: OyunTrAiSpeechEngine? = null
    private var aiEnabled = preferences.getBoolean(KEY_AI_ENABLED, false)
    private var aiPlayer = AiSpeechAudioPlayer(context)

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { result ->
            main.post {
                if (closed) return@post
                if (result != TextToSpeech.SUCCESS) {
                    onStatus("Cihazın konuşma motoru başlatılamadı")
                    return@post
                }
                val languageResult = tts?.setLanguage(locale) ?: TextToSpeech.ERROR
                ready = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED && languageResult >= TextToSpeech.LANG_AVAILABLE
                turkishVoices = tts?.voices.orEmpty().filter { it.locale.language.equals("tr", ignoreCase = true) }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
                assignInitialVoices()
                tts?.setOnUtteranceProgressListener(progressListener)
                onStatus(
                    when {
                        !ready -> "Cihazda Türkçe konuşma sesi yok; TTS verisini yükleyin"
                        turkishVoices.size < profiles.size -> "Türkçe ses hazır; bazı karakterler aynı sesi paylaşacak"
                        else -> "Türkçe ses hazır; ${turkishVoices.size} ayrı karakter sesi bulundu"
                    },
                )
            }
        }
    }

    fun setEnabled(value: Boolean) {
        main.post {
            enabled = value
            pendingId++
            pendingText = ""
            lastSpokenKey = ""
            if (!value) {
                tts?.stop()
                onStatus("Türkçe seslendirme kapalı")
            } else if (ready) onStatus("Türkçe cihaz sesi açık")
            else onStatus("Türkçe ses motoru hazırlanıyor…")
        }
    }

    fun characterProfiles(): List<CharacterVoiceProfile> = synchronized(this) { profiles.toList() }

    fun activeCharacter(): CharacterVoiceProfile = synchronized(this) {
        profiles.firstOrNull { it.id == activeSpeakerId } ?: profiles.first()
    }

    fun addCharacter(displayName: String): CharacterVoiceProfile? {
        val name = displayName.trim().take(40)
        if (name.isBlank()) return null
        synchronized(this) {
            if (profiles.any { it.displayName.equals(name, ignoreCase = true) }) return profiles.first { it.displayName.equals(name, ignoreCase = true) }
            val id = "character-${UUID.randomUUID()}"
            val voice = turkishVoices.getOrNull(profiles.size % turkishVoices.size.coerceAtLeast(1))?.name
            val profile = CharacterVoiceProfile(id, name, voice)
            profiles = profiles + profile
            saveProfiles()
            activeSpeakerId = id
            preferences.edit().putString(KEY_ACTIVE_SPEAKER, id).apply()
            return profile
        }
    }

    fun selectCharacter(id: String) {
        if (profiles.none { it.id == id }) return
        activeSpeakerId = id
        preferences.edit().putString(KEY_ACTIVE_SPEAKER, id).apply()
    }

    fun availableCharacterVoices(): List<Pair<String, String>> = turkishVoices.map { it.name to it.name }

    fun assignVoice(characterId: String, voiceName: String) {
        if (turkishVoices.none { it.name == voiceName }) return
        synchronized(this) {
            profiles = profiles.map { if (it.id == characterId) it.copy(voiceName = voiceName) else it }
            saveProfiles()
        }
    }

    fun configureAiRelay(endpoint: String, appToken: String): Boolean {
        val url = endpoint.trim().trimEnd('/')
        val token = appToken.trim()
        if (!url.startsWith("https://", ignoreCase = true) || token.isBlank()) return false
        preferences.edit().putString(KEY_AI_ENDPOINT, url).apply()
        aiEngine = OyunTrAiSpeechEngine(url, token)
        onStatus("Chatterbox relay sunucusu ayarlandı")
        return true
    }

    fun savedAiEndpoint(): String = preferences.getString(KEY_AI_ENDPOINT, "").orEmpty()

    fun loadAiVoices(onResult: (List<AiVoiceOption>?, String?) -> Unit) {
        val engine = aiEngine
        if (engine == null) {
            onResult(null, "Önce AI sunucu adresini ve erişim tokenini kaydedin")
            return
        }
        engine.listVoices().whenComplete { voices, error ->
            main.post {
                if (error != null) onResult(null, error.cause?.message ?: error.message ?: "Ses listesi alınamadı")
                else onResult(voices, null)
            }
        }
    }

    fun setAiEnabled(value: Boolean) {
        aiEnabled = value
        preferences.edit().putBoolean(KEY_AI_ENABLED, value).apply()
        if (!value) aiPlayer.stop()
        onStatus(if (value) "AI karakter seslendirmesi açık" else "AI ses kapalı; cihaz sesi seçiliyse yerel TTS kullanılır")
    }

    fun assignAiVoice(characterId: String, voiceId: String) {
        val value = voiceId.trim().take(128)
        if (value.isBlank()) return
        synchronized(this) {
            profiles = profiles.map { if (it.id == characterId) it.copy(aiVoiceId = value) else it }
            saveProfiles()
        }
    }

    fun uploadCharacterVoiceReference(
        characterId: String,
        wavData: ByteArray,
        onResult: (AiVoiceOption?, String?) -> Unit,
    ) {
        val profile = characterProfiles().firstOrNull { it.id == characterId }
        val engine = aiEngine
        if (profile == null || engine == null) {
            onResult(null, "Önce Chatterbox relay adresi ve erişim tokeni gir")
            return
        }
        engine.uploadVoiceReference(profile.id, profile.displayName, wavData).whenComplete { voice, error ->
            main.post {
                if (error != null) {
                    onResult(null, error.cause?.message ?: error.message ?: "Ses örneği yüklenemedi")
                } else {
                    assignAiVoice(profile.id, voice.id)
                    onResult(voice, null)
                }
            }
        }
    }

    fun deleteCharacterVoiceReference(characterId: String, onResult: (String?) -> Unit) {
        val profile = characterProfiles().firstOrNull { it.id == characterId }
        val engine = aiEngine
        if (profile == null || engine == null) {
            onResult("Önce Chatterbox relay adresi ve erişim tokeni gir")
            return
        }
        if (profile.aiVoiceId != profile.id) {
            onResult("Bu profilde kendi yüklediğin örnek bağlı değil; başka karakterin örneğini silmedim")
            return
        }
        engine.deleteVoiceReference(profile.id).whenComplete { _, error ->
            main.post {
                if (error != null) {
                    onResult(error.cause?.message ?: error.message ?: "Ses örneği silinemedi")
                } else {
                    synchronized(this) {
                        profiles = profiles.map { if (it.id == profile.id) it.copy(aiVoiceId = null) else it }
                        saveProfiles()
                    }
                    onResult(null)
                }
            }
        }
    }

    fun assignExpressiveness(characterId: String, value: Float) {
        val level = value.coerceIn(0.25f, 1.0f)
        synchronized(this) {
            profiles = profiles.map { if (it.id == characterId) it.copy(expressiveness = level) else it }
            saveProfiles()
        }
    }

    /** Stops current/pending speech while keeping the user's toggle setting. */
    fun interrupt() {
        main.post {
            pendingId++
            pendingText = ""
            tts?.stop()
            aiPlayer.stop()
        }
    }

    /** Lets the user check the installed Turkish voice without a console connection. */
    fun preview() {
        main.post {
            val profile = activeCharacter()
            if (aiEnabled) {
                val engine = aiEngine
                if (engine == null || profile.aiVoiceId.isNullOrBlank()) {
                    onStatus("AI için HTTPS sunucu adresi ve ${profile.displayName} ses kimliği gerekli")
                    return@post
                }
                engine.synthesize(
                    SpeechRequest(
                        text = "${profile.displayName} için Türkçe ses denemesi.",
                        voiceId = profile.aiVoiceId,
                        gameId = "ps4-local",
                        speakerId = profile.id,
                        expressiveness = profile.expressiveness,
                    ),
                ).whenComplete { audio, error ->
                    main.post {
                        if (error != null) onStatus("AI ses denemesi başarısız: ${error.cause?.message ?: error.message}")
                        else {
                            aiPlayer.play(audio)
                            onStatus("${profile.displayName} Chatterbox Multilingual V3 sesi deneniyor")
                        }
                    }
                }
                return@post
            }
            if (!ready) {
                onStatus("Önce cihazın Türkçe konuşma sesini hazırlayın")
                return@post
            }
            val voiceName = profile.voiceName
            turkishVoices.firstOrNull { it.name == voiceName }?.let { tts?.setVoice(it) }
            val result = tts?.speak("${profile.displayName} için Türkçe ses denemesi.", TextToSpeech.QUEUE_FLUSH, null, "oyuntr-preview")
                ?: TextToSpeech.ERROR
            onStatus(if (result == TextToSpeech.ERROR) "Ses denemesi başlatılamadı" else "${profile.displayName} karakterinin sesi deneniyor")
        }
    }

    /** Debounces OCR changes, then speaks each stable translated line once. */
    fun accept(subtitle: TranslatedSubtitle?) {
        main.post {
            if (closed || (!enabled && !aiEnabled)) return@post
            val text = subtitle?.translatedText?.trim().orEmpty()
            if (text.isBlank()) {
                if (pendingText.isNotBlank() || lastSpokenKey.isNotBlank()) {
                    val ticket = ++pendingId
                    pendingText = ""
                    main.postDelayed({ if (ticket == pendingId && pendingText.isBlank()) lastSpokenKey = "" }, BLANK_RESET_DELAY_MS)
                }
                return@post
            }
            val profile = speakerFor(subtitle)
            val speechKey = "${profile.id}:${normalize(text)}"
            if (speechKey == lastSpokenKey || (profile.id == pendingSpeakerId && normalize(text) == normalize(pendingText))) return@post
            pendingText = text
            pendingSpeakerId = profile.id
            val ticket = ++pendingId
            main.postDelayed({
                if (closed || ticket != pendingId || pendingText.isBlank() || (!aiEnabled && (!enabled || !ready))) return@postDelayed
                lastSpokenKey = "${pendingSpeakerId}:${normalize(pendingText)}"
                val id = "oyuntr-${UUID.randomUUID()}"
                val selectedVoice = synchronized(this) { profiles.firstOrNull { it.id == pendingSpeakerId }?.voiceName }
                val speaker = synchronized(this) { profiles.firstOrNull { it.id == pendingSpeakerId } }
                if (aiEnabled) {
                    val engine = aiEngine
                    val remoteVoiceId = speaker?.aiVoiceId
                    if (engine == null || remoteVoiceId.isNullOrBlank()) {
                        onStatus("AI için sunucu ve ${speaker?.displayName ?: "karakter"} ses kimliği gerekli")
                        lastSpokenKey = ""
                        return@postDelayed
                    }
                    engine.synthesize(
                        SpeechRequest(
                            text = pendingText,
                            voiceId = remoteVoiceId,
                            gameId = subtitle?.gameId ?: "unknown",
                            speakerId = speaker.id,
                            expressiveness = speaker.expressiveness,
                        ),
                    ).whenComplete { audio, error ->
                        main.post {
                            if (ticket != pendingId || !aiEnabled || closed) return@post
                            if (error != null) {
                                lastSpokenKey = ""
                                onStatus("AI TTS isteği başarısız: ${error.cause?.message ?: error.message}")
                            } else {
                                aiPlayer.play(audio)
                                onStatus("${speaker.displayName} Chatterbox ile konuşuyor")
                            }
                        }
                    }
                } else {
                    turkishVoices.firstOrNull { it.name == selectedVoice }?.let { tts?.setVoice(it) }
                    val result = tts?.speak(pendingText, TextToSpeech.QUEUE_FLUSH, null, id) ?: TextToSpeech.ERROR
                    if (result == TextToSpeech.ERROR) onStatus("Türkçe seslendirme başlatılamadı")
                }
            }, STABILITY_DELAY_MS)
        }
    }

    override fun close() {
        main.post {
            closed = true
            pendingId++
            tts?.stop()
            tts?.shutdown()
            aiPlayer.close()
            tts = null
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = Unit
        @Deprecated("Deprecated by Android")
        override fun onError(utteranceId: String?) {
            main.post { if (!closed) onStatus("Cihaz Türkçe metni seslendiremedi") }
        }
        override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
    }

    private fun normalize(value: String) = value.lowercase(locale).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Uses an exact `Name: line` subtitle prefix when it matches a saved profile; otherwise the selected speaker is used. */
    private fun speakerFor(subtitle: TranslatedSubtitle?): CharacterVoiceProfile {
        val original = subtitle?.originalText.orEmpty()
        val colon = original.indexOfAny(charArrayOf(':', '：'))
        if (colon in 1..40) {
            val label = original.substring(0, colon).trim()
            synchronized(this) {
                profiles.firstOrNull { it.displayName.equals(label, ignoreCase = true) }?.let { profile ->
                    if (activeSpeakerId != profile.id) {
                        activeSpeakerId = profile.id
                        preferences.edit().putString(KEY_ACTIVE_SPEAKER, profile.id).apply()
                        onStatus("Altyazı etiketinden konuşan bulundu: ${profile.displayName}")
                    }
                    return profile
                }
            }
        }
        return activeCharacter()
    }

    private fun assignInitialVoices() = synchronized(this) {
        if (turkishVoices.isEmpty()) return@synchronized
        var nextIndex = 0
        profiles = profiles.map { profile ->
            if (profile.voiceName != null && turkishVoices.any { it.name == profile.voiceName }) profile
            else profile.copy(voiceName = turkishVoices[nextIndex++ % turkishVoices.size].name)
        }
        saveProfiles()
    }

    private fun loadProfiles(): List<CharacterVoiceProfile> {
        val saved = preferences.getString(KEY_PROFILES, null)
        if (saved != null) try {
            val json = JSONArray(saved)
            return (0 until json.length()).map { index ->
                val item = json.getJSONObject(index)
                CharacterVoiceProfile(
                    item.getString("id"), item.getString("name"),
                    item.optString("voice").takeIf(String::isNotBlank),
                    item.optString("aiVoiceId").takeIf(String::isNotBlank),
                    item.optDouble("expressiveness", 0.5).toFloat().coerceIn(0.25f, 1.0f),
                )
            }.ifEmpty { defaultProfiles() }
        } catch (_: Exception) { /* Recover with defaults if saved data is malformed. */ }
        return defaultProfiles()
    }

    private fun saveProfiles() {
        val json = JSONArray()
        profiles.forEach { profile ->
            json.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.displayName)
                    .put("voice", profile.voiceName ?: "")
                    .put("aiVoiceId", profile.aiVoiceId ?: "")
                    .put("expressiveness", profile.expressiveness)
            )
        }
        preferences.edit().putString(KEY_PROFILES, json.toString()).apply()
    }

    private fun defaultProfiles() = listOf(
        CharacterVoiceProfile(MAIN_CHARACTER_ID, "Ana Karakter"),
        CharacterVoiceProfile("npc-1", "NPC 1"),
        CharacterVoiceProfile("npc-2", "NPC 2"),
    )

    companion object {
        private const val KEY_PROFILES = "character_voice_profiles"
        private const val KEY_ACTIVE_SPEAKER = "active_character_id"
        private const val KEY_AI_ENDPOINT = "ai_speech_endpoint"
        private const val KEY_AI_ENABLED = "ai_speech_enabled"
        const val MAIN_CHARACTER_ID = "main-character"
        private const val STABILITY_DELAY_MS = 450L
        private const val BLANK_RESET_DELAY_MS = 1_500L
    }
}

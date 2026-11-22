package com.oyuntr

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.speech.tts.TextToSpeech
import java.io.ByteArrayOutputStream
import com.oyuntr.ocr.MlKitSubtitleRecognizer
import com.oyuntr.ocr.SubtitleOverlayController
import com.oyuntr.ocr.SubtitleOverlayView
import com.oyuntr.ocr.SubtitleTranslationPipeline
import com.oyuntr.ocr.SurfaceSubtitleFrameSource
import com.oyuntr.remoteplay.AndroidConsoleCredentialVault
import com.oyuntr.remoteplay.AndroidVideoDecoder
import com.oyuntr.remoteplay.AndroidPcmAudioOutput
import com.oyuntr.remoteplay.ChiakiNativeGateway
import com.oyuntr.remoteplay.ConsoleTarget
import com.oyuntr.remoteplay.ConnectionState
import com.oyuntr.remoteplay.ControllerState
import com.oyuntr.remoteplay.RemotePlayCoordinator
import com.oyuntr.remoteplay.RemotePlayFailure
import com.oyuntr.translation.InMemoryTranslationCache
import com.oyuntr.translation.MlKitTranslationEngine
import com.oyuntr.translation.TranslationService
import com.oyuntr.dubbing.AndroidTurkishDubbing

/** Local PS4 pairing and bitmap subtitle demonstration screen. */
class RemotePlayActivity : Activity(), SurfaceHolder.Callback {
    private val gateway = ChiakiNativeGateway()
    private val decoder = AndroidVideoDecoder()
    private val gameAudioOutput = AndroidPcmAudioOutput()
    private lateinit var vault: AndroidConsoleCredentialVault
    private lateinit var surfaceView: SurfaceView
    private lateinit var subtitleView: SubtitleOverlayView
    private lateinit var subtitleController: SubtitleOverlayController
    private lateinit var subtitleRecognizer: MlKitSubtitleRecognizer
    private lateinit var translationEngine: MlKitTranslationEngine
    private lateinit var dubbing: AndroidTurkishDubbing
    private lateinit var hostInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var accountIdInput: EditText
    private lateinit var consolePinInput: EditText
    private lateinit var status: TextView
    private lateinit var dubbingStatus: TextView
    private lateinit var activeCharacterLabel: TextView
    private lateinit var aiEndpointInput: EditText
    private lateinit var aiRelayTokenInput: EditText
    private var sessionCoordinator: RemotePlayCoordinator? = null
    private var frameSource: SurfaceSubtitleFrameSource? = null
    private var outputSurface: Surface? = null
    private var decoderStarted = false
    private val pressedControllerButtons = mutableSetOf<Long>()
    private var leftStickX = 0
    private var leftStickY = 0
    private var rightStickX = 0
    private var rightStickY = 0
    private var leftTrigger = 0
    private var rightTrigger = 0
    private var pendingVoiceUploadCharacterId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = AndroidConsoleCredentialVault(this)
        subtitleRecognizer = MlKitSubtitleRecognizer()
        translationEngine = MlKitTranslationEngine()
        dubbing = AndroidTurkishDubbing(this) { message ->
            runOnUiThread {
                if (::dubbingStatus.isInitialized) dubbingStatus.text = message
                refreshActiveCharacterLabel()
            }
        }
        val service = TranslationService(translationEngine, InMemoryTranslationCache())
        subtitleController = SubtitleOverlayController(
            SubtitleTranslationPipeline(subtitleRecognizer, service, gameId = "ps4-local", sourceLanguageTag = "en"),
            SubtitleOverlayView(this).also { subtitleView = it },
            dubbing::accept,
        )
        gateway.setVideoSampleSink { sample, _, _ -> decoder.queueSample(sample) }
        gateway.setAudioFormatSink(gameAudioOutput::configure)
        gateway.setAudioPcmSink(gameAudioOutput::writePcm)
        sessionCoordinator = RemotePlayCoordinator(
            gateway,
            object : RemotePlayCoordinator.Observer {
                override fun onStateChanged(state: ConnectionState) = runOnUiThread {
                    status.text = "PS4: ${state.name}"
                    if (state == ConnectionState.SESSION_CONNECTED) startSubtitleCapture()
                    if (state == ConnectionState.WAITING_FOR_PIN) requestSessionPin()
                    if (state == ConnectionState.DISCONNECTED || state == ConnectionState.FAILED) {
                        stopSubtitleCapture()
                        resetControllerState()
                    }
                }

                override fun onDecodedVideoFrame(frame: com.oyuntr.remoteplay.DecodedVideoFrame) = Unit

                override fun onFailure(error: RemotePlayFailure) = runOnUiThread {
                    status.text = error.userMessage
                    Toast.makeText(this@RemotePlayActivity, error.userMessage, Toast.LENGTH_LONG).show()
                }
            },
        )
        setContentView(buildScreen())
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        outputSurface = holder.surface
        startDecoderIfReady()
        if (sessionCoordinator?.currentState() == ConnectionState.SESSION_CONNECTED) startSubtitleCapture()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isRemoteSessionActive()) {
            val button = keyToControllerButton(event.keyCode)
            if (button != 0L) {
                if (event.action == KeyEvent.ACTION_DOWN) pressedControllerButtons.add(button)
                else if (event.action == KeyEvent.ACTION_UP) pressedControllerButtons.remove(button)
                sendControllerSnapshot()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isRemoteSessionActive() && event.action == MotionEvent.ACTION_MOVE &&
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            leftStickX = axisToShort(event.getAxisValue(MotionEvent.AXIS_X))
            leftStickY = axisToShort(event.getAxisValue(MotionEvent.AXIS_Y))
            val rightX = event.getAxisValue(MotionEvent.AXIS_Z)
                .takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_RX)
            val rightY = event.getAxisValue(MotionEvent.AXIS_RZ)
                .takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_RY)
            rightStickX = axisToShort(rightX)
            rightStickY = axisToShort(rightY)
            leftTrigger = axisToTrigger(event.getAxisValue(MotionEvent.AXIS_LTRIGGER).takeIf { it > 0f }
                ?: event.getAxisValue(MotionEvent.AXIS_BRAKE))
            rightTrigger = axisToTrigger(event.getAxisValue(MotionEvent.AXIS_RTRIGGER).takeIf { it > 0f }
                ?: event.getAxisValue(MotionEvent.AXIS_GAS))
            setDigitalButton(ControllerState.DPAD_LEFT, event.getAxisValue(MotionEvent.AXIS_HAT_X) < -0.5f)
            setDigitalButton(ControllerState.DPAD_RIGHT, event.getAxisValue(MotionEvent.AXIS_HAT_X) > 0.5f)
            setDigitalButton(ControllerState.DPAD_UP, event.getAxisValue(MotionEvent.AXIS_HAT_Y) < -0.5f)
            setDigitalButton(ControllerState.DPAD_DOWN, event.getAxisValue(MotionEvent.AXIS_HAT_Y) > 0.5f)
            sendControllerSnapshot()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopSubtitleCapture()
        outputSurface = null
        decoderStarted = false
        decoder.close()
    }

    override fun onDestroy() {
        stopSubtitleCapture()
        if (sessionCoordinator?.currentState() != ConnectionState.DISCONNECTED) sessionCoordinator?.disconnect()
        gateway.setVideoSampleSink(null)
        gateway.setAudioFormatSink(null)
        gateway.setAudioPcmSink(null)
        gameAudioOutput.close()
        gateway.close()
        decoder.close()
        subtitleRecognizer.close()
        translationEngine.close()
        subtitleController.clear()
        dubbing.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated by Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VOICE_REFERENCE) return
        if (resultCode != RESULT_OK) {
            pendingVoiceUploadCharacterId = null
            return
        }
        val uri = data?.data ?: run {
            dubbingStatus.text = "Ses örneği seçilmedi"
            return
        }
        val characterId = pendingVoiceUploadCharacterId ?: return
        pendingVoiceUploadCharacterId = null
        dubbingStatus.text = "WAV referans dosyası okunuyor…"
        Thread {
            try {
                val wav = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_VOICE_REFERENCE_BYTES) {
                            throw IllegalArgumentException("WAV dosyası en fazla 16 MB olabilir")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw IllegalArgumentException("Ses dosyası okunamadı")
                dubbingStatus.post { dubbingStatus.text = "Ses örneği Chatterbox sunucusuna yükleniyor…" }
                dubbing.uploadCharacterVoiceReference(characterId, wav) { voice, error ->
                    dubbingStatus.text = error ?: "${voice?.name} referans sesi karaktere atandı"
                }
            } catch (error: Exception) {
                dubbingStatus.post { dubbingStatus.text = error.message ?: "Ses örneği yüklenemedi" }
            }
        }.start()
    }

    private fun startDecoderIfReady() {
        val surface = outputSurface ?: return
        if (decoderStarted || !surface.isValid) return
        try {
            decoder.start(surface)
            decoderStarted = true
        } catch (error: Exception) {
            status.text = "Video decoder açılamadı: ${error.message ?: "bilinmeyen hata"}"
        }
    }

    private fun registerConsole() {
        val host = hostInput.text.toString().trim()
        val pin = pinInput.text.toString().trim()
        val accountId = parseAccountId(accountIdInput.text.toString())
        if (accountId == null) {
            status.text = "PSN Account ID’yi 8 bayt Base64 veya 16 haneli hex olarak gir"
            return
        }
        status.text = "PS4 kayıt isteği gönderiliyor… PS4 üzerindeki kayıt PIN’ini gir."
        try {
            gateway.registerLocalConsole(host, pin, consolePinInput.text.toString().trim(), accountId) { registration ->
                runOnUiThread {
                    if (registration == null) {
                        status.text = "PS4 kaydı başarısız veya iptal edildi"
                        return@runOnUiThread
                    }
                    try {
                        vault.save(host, registration)
                        status.text = "PS4 kaydı tamamlandı ve cihazda şifreli saklandı"
                    } catch (error: Exception) {
                        status.text = "Kayıt bilgisi güvenli saklanamadı: ${error.message}"
                    }
                }
            }
        } catch (error: RuntimeException) {
            status.text = error.message ?: "PS4 kaydı başlatılamadı"
        }
    }

    private fun connectConsole() {
        startDecoderIfReady()
        val host = hostInput.text.toString().trim()
        val registration = try {
            vault.load(host)
        } catch (error: Exception) {
            status.text = error.message ?: "PS4 kaydı okunamadı"
            return
        }
        if (registration == null) {
            status.text = "Önce PS4’ü kaydet"
            return
        }
        if (!decoderStarted) {
            status.text = "Video Surface hazır değil"
            return
        }
        try {
            sessionCoordinator?.connect(
                ConsoleTarget(
                    id = host,
                    displayName = "Yerel PS4",
                    host = host,
                    registration = registration,
                ),
            )
        } catch (error: RuntimeException) {
            status.text = error.message ?: "PS4 bağlantısı başlatılamadı"
        }
    }

    private fun startSubtitleCapture() {
        if (frameSource != null) return
        frameSource = SurfaceSubtitleFrameSource(surfaceView, subtitleController).also { it.start() }
    }

    private fun stopSubtitleCapture() {
        frameSource?.close()
        frameSource = null
        dubbing.interrupt()
    }

    private fun isRemoteSessionActive() = sessionCoordinator?.currentState() == ConnectionState.SESSION_CONNECTED ||
        sessionCoordinator?.currentState() == ConnectionState.STREAMING

    private fun keyToControllerButton(keyCode: Int): Long = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> ControllerState.CROSS
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> ControllerState.CIRCLE
        KeyEvent.KEYCODE_BUTTON_X -> ControllerState.SQUARE
        KeyEvent.KEYCODE_BUTTON_Y -> ControllerState.TRIANGLE
        KeyEvent.KEYCODE_DPAD_LEFT -> ControllerState.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerState.DPAD_RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> ControllerState.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> ControllerState.DPAD_DOWN
        KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> ControllerState.R1
        KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerState.R3
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> ControllerState.OPTIONS
        KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerState.SHARE
        KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.PS
        else -> 0L
    }

    private fun setDigitalButton(button: Long, down: Boolean) {
        if (down) pressedControllerButtons.add(button) else pressedControllerButtons.remove(button)
    }

    private fun sendControllerSnapshot() {
        val state = ControllerState(
            buttons = pressedControllerButtons.fold(0L) { mask, button -> mask or button },
            leftStickX = leftStickX,
            leftStickY = leftStickY,
            rightStickX = rightStickX,
            rightStickY = rightStickY,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
        )
        try {
            sessionCoordinator?.sendControllerState(state)
        } catch (error: IllegalStateException) {
            status.text = error.message ?: "Kumanda girdisi gönderilemedi"
        }
    }

    private fun resetControllerState() {
        pressedControllerButtons.clear()
        leftStickX = 0
        leftStickY = 0
        rightStickX = 0
        rightStickY = 0
        leftTrigger = 0
        rightTrigger = 0
    }

    private fun axisToShort(value: Float) = (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
    private fun axisToTrigger(value: Float) = (value.coerceIn(0f, 1f) * 255f).toInt()

    private fun requestSessionPin() {
        val pin = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this)
            .setTitle("PS4 bağlantı PIN’i")
            .setMessage("PS4 ekranında gösterilen PIN’i gir.")
            .setView(pin)
            .setNegativeButton("İptal", null)
            .setPositiveButton("Gönder") { _, _ ->
                try {
                    sessionCoordinator?.submitConsolePin(pin.text.toString())
                } catch (error: RuntimeException) {
                    status.text = error.message ?: "PIN gönderilemedi"
                }
            }
            .show()
    }

    private fun parseAccountId(input: String): ByteArray? {
        val value = input.trim()
        if (value.matches(Regex("(?i)^(0x)?[0-9a-f]{16}$"))) {
            val hex = value.removePrefix("0x").removePrefix("0X")
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        return try {
            Base64.decode(value, Base64.DEFAULT).takeIf { it.size == 8 }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(20), dp(32), dp(28))
            setBackgroundColor(Color.rgb(8, 16, 24))
        }
        root.addView(text("PS4 Yerel Bağlantı ve Türkçe Altyazı", 26, Color.WHITE))
        status = text("PS4 adresini gir, konsolu kaydet ve bağlan.", 16, Color.LTGRAY)
        root.addView(status)
        dubbingStatus = text("Türkçe cihaz sesi hazırlanıyor…", 14, Color.LTGRAY)
        root.addView(dubbingStatus)
        val dubbingPrefs = getSharedPreferences("dubbing_preferences", MODE_PRIVATE)
        aiEndpointInput = input("Chatterbox sunucusu HTTPS adresi", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
            setText(dubbing.savedAiEndpoint())
        }
        aiRelayTokenInput = input("OyunTR sunucu erişim tokeni", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        root.addView(aiEndpointInput)
        root.addView(aiRelayTokenInput)
        root.addView(button("AI sunucu ayarını kaydet", ::saveAiRelaySettings))
        val dubbingToggle = Switch(this).apply {
            text = "Cihazdaki yerel TTS’yi kullan"
            setTextColor(Color.WHITE)
            isFocusable = true
            isChecked = dubbingPrefs.getBoolean("enabled", false)
            setOnCheckedChangeListener { _, checked ->
                dubbingPrefs.edit().putBoolean("enabled", checked).apply()
                dubbing.setEnabled(checked)
            }
        }
        dubbing.setEnabled(dubbingToggle.isChecked)
        root.addView(dubbingToggle)
        root.addView(Switch(this).apply {
            text = "Chatterbox ile karakter dublajını kullan"
            setTextColor(Color.WHITE)
            isFocusable = true
            isChecked = dubbingPrefs.getBoolean("ai_speech_enabled", false)
            setOnCheckedChangeListener { _, checked -> dubbing.setAiEnabled(checked) }
        })
        activeCharacterLabel = text("Konuşan: ${dubbing.activeCharacter().displayName}", 16, Color.WHITE)
        root.addView(activeCharacterLabel)
        root.addView(button("Konuşan karakteri seç", ::chooseCharacter))
        root.addView(button("Karakter / NPC ekle", ::addCharacter))
        root.addView(button("Seçili karaktere ayrı Türkçe sesi ata", ::chooseCharacterVoice))
        root.addView(text("Chatterbox Multilingual V3: karakter başına izinli Türkçe WAV ses örneği yükle. İfade seviyesi karaktere özeldir.", 14, Color.LTGRAY))
        root.addView(button("Seçili karakterin WAV ses örneğini yükle", ::chooseCharacterVoiceSample))
        root.addView(button("Seçili karakterin yüklediğim ses örneğini sil", ::deleteCharacterVoiceSample))
        root.addView(button("Sunucudaki klon seslerden karakter sesi seç", ::chooseAiCharacterVoice))
        root.addView(button("Seçili karakterin ifade seviyesini ayarla", ::chooseCharacterExpressiveness))
        root.addView(button("Türkçe sesi dene", dubbing::preview))
        root.addView(button("Cihazın ses paketlerini aç") {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (_: Exception) {
                dubbingStatus.text = "Ses paketi ekranı bu cihazda açılamadı; Android Sesli Okuma ayarlarını kontrol edin."
            }
        })

        val video = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            surfaceView = SurfaceView(this@RemotePlayActivity)
            addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(subtitleView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val videoHeight = ((resources.displayMetrics.widthPixels - dp(64)) * 9f / 16f).toInt()
        root.addView(video, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, videoHeight).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        })

        hostInput = input("PS4 yerel IP adresi", InputType.TYPE_CLASS_TEXT)
        pinInput = input("PS4 kayıt ekranındaki PIN", InputType.TYPE_CLASS_NUMBER)
        accountIdInput = input("PSN Account ID (8 bayt Base64 veya 16 haneli hex)", InputType.TYPE_CLASS_TEXT)
        consolePinInput = input("PS4 console PIN (boş bırakılabilir)", InputType.TYPE_CLASS_NUMBER)
        listOf(hostInput, pinInput, accountIdInput, consolePinInput).forEach { root.addView(it) }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        actions.addView(button("PS4’ü kaydet", ::registerConsole), buttonParams())
        actions.addView(button("Kaydı iptal et", gateway::cancelRegistration), buttonParams())
        actions.addView(button("Bağlan", ::connectConsole), buttonParams())
        actions.addView(button("Bağlantıyı kes") { sessionCoordinator?.disconnect() })
        root.addView(actions)
        return ScrollView(this).apply { addView(root) }
    }

    private fun chooseCharacter() {
        val profiles = dubbing.characterProfiles()
        AlertDialog.Builder(this)
            .setTitle("Konuşan karakter")
            .setItems(profiles.map { it.displayName }.toTypedArray()) { _, index ->
                dubbing.selectCharacter(profiles[index].id)
                refreshActiveCharacterLabel()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun addCharacter() {
        val name = input("Karakter adı (ör. Lara, NPC: Demirci)", InputType.TYPE_CLASS_TEXT)
        AlertDialog.Builder(this)
            .setTitle("Ana karakter veya NPC ekle")
            .setMessage("Karakter profili oluştur. Sonra bu profile ayrı Chatterbox ses örneği veya cihaz sesi seçebilirsin.")
            .setView(name)
            .setNegativeButton("İptal", null)
            .setPositiveButton("Ekle") { _, _ ->
                val profile = dubbing.addCharacter(name.text.toString())
                if (profile == null) dubbingStatus.text = "Karakter adı boş olamaz"
                else {
                    refreshActiveCharacterLabel()
                    dubbingStatus.text = "${profile.displayName} profili seçildi"
                }
            }
            .show()
    }

    private fun chooseCharacterVoice() {
        val character = dubbing.activeCharacter()
        val voices = dubbing.availableCharacterVoices()
        if (voices.isEmpty()) {
            dubbingStatus.text = "Kurulu TTS motoru ayrı Türkçe ses sunmuyor; ses verisini yükleyin"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("${character.displayName} için Türkçe ses")
            .setItems(voices.map { it.second }.toTypedArray()) { _, index ->
                dubbing.assignVoice(character.id, voices[index].first)
                dubbingStatus.text = "${character.displayName} için ${voices[index].second} seçildi"
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun saveAiRelaySettings() {
        val configured = dubbing.configureAiRelay(aiEndpointInput.text.toString(), aiRelayTokenInput.text.toString())
        dubbingStatus.text = if (configured) {
            aiRelayTokenInput.text.clear()
            "Chatterbox relay ayarlandı. Model kendi sunucunda çalışır; ücretli TTS API anahtarı gerekmez."
        } else "HTTPS sunucu adresi ve OyunTR erişim tokeni gerekli"
    }

    private fun chooseAiCharacterVoice() {
        val character = dubbing.activeCharacter()
        dubbingStatus.text = "Klonlanmış ses kataloğu alınıyor…"
        dubbing.loadAiVoices { voices, error ->
            if (error != null || voices.isNullOrEmpty()) {
                dubbingStatus.text = error ?: "Sunucuda kayıtlı ses örneği yok; bu karaktere WAV yükle"
                return@loadAiVoices
            }
            AlertDialog.Builder(this)
                .setTitle("${character.displayName} Chatterbox klon sesi")
                .setItems(voices.map { "${it.name} (${it.category.ifBlank { "Klonlanmış ses" }})" }.toTypedArray()) { _, index ->
                    dubbing.assignAiVoice(character.id, voices[index].id)
                    dubbingStatus.text = "${character.displayName} için ${voices[index].name} seçildi"
                }
                .setNegativeButton("Kapat", null)
                .show()
        }
    }

    private fun chooseCharacterVoiceSample() {
        val character = dubbing.activeCharacter()
        pendingVoiceUploadCharacterId = character.id
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        try {
            startActivityForResult(picker, REQUEST_VOICE_REFERENCE)
            dubbingStatus.text = "${character.displayName} için tek konuşmacılı, izinli Türkçe WAV seç (0,5–30 sn; en fazla 16 MB)."
        } catch (_: Exception) {
            pendingVoiceUploadCharacterId = null
            dubbingStatus.text = "Dosya seçici açılamadı"
        }
    }

    private fun deleteCharacterVoiceSample() {
        val character = dubbing.activeCharacter()
        AlertDialog.Builder(this)
            .setTitle("${character.displayName} ses örneği silinsin mi?")
            .setMessage("Bu karakter için kendi yüklediğin referans dosyası relay sunucusundan silinecek.")
            .setNegativeButton("İptal", null)
            .setPositiveButton("Sil") { _, _ ->
                dubbingStatus.text = "Ses örneği sunucudan siliniyor…"
                dubbing.deleteCharacterVoiceReference(character.id) { error ->
                    dubbingStatus.text = error ?: "${character.displayName} ses örneği sunucudan silindi"
                }
            }
            .show()
    }

    private fun chooseCharacterExpressiveness() {
        val character = dubbing.activeCharacter()
        val levels = listOf(0.3f, 0.5f, 0.7f, 0.9f)
        val labels = listOf("Az vurgulu", "Doğal", "Belirgin ifade", "Güçlü ifade")
        val selected = levels.indices.minByOrNull { kotlin.math.abs(levels[it] - character.expressiveness) } ?: 1
        AlertDialog.Builder(this)
            .setTitle("${character.displayName} konuşma ifadesi")
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, index ->
                dubbing.assignExpressiveness(character.id, levels[index])
                dubbingStatus.text = "${character.displayName}: ${labels[index]}"
                dialog.dismiss()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun refreshActiveCharacterLabel() {
        if (::activeCharacterLabel.isInitialized) activeCharacterLabel.text = "Konuşan: ${dubbing.activeCharacter().displayName}"
    }

    private fun input(hintText: String, inputTypeValue: Int) = EditText(this).apply {
        hint = hintText
        inputType = inputTypeValue
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
        minHeight = dp(48)
    }

    private fun text(value: String, size: Int, color: Int) = android.widget.TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun buttonParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginEnd = dp(10)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_VOICE_REFERENCE = 4207
        const val MAX_VOICE_REFERENCE_BYTES = 16 * 1024 * 1024
    }
}

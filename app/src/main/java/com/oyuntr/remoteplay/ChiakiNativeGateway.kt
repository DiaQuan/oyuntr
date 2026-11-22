package com.oyuntr.remoteplay

import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

/** OyunTR-owned JNI adapter for chiaki-ng's PS4 local session API. */
class ChiakiNativeGateway : RemotePlayGateway, AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "oyuntr-remote-play").apply { isDaemon = true }
    }
    private val lock = Any()
    private var nativeHandle = 0L
    private var listener: RemotePlayGateway.Listener? = null
    private var attempt = 0L
    private var registrationHandle = 0L
    private var registrationCompletion: ((ConsoleRegistration?) -> Unit)? = null
    @Volatile private var videoSampleSink: ((ByteArray, Int, Boolean) -> Boolean)? = null
    @Volatile private var audioFormatSink: ((Int, Int) -> Unit)? = null
    @Volatile private var audioPcmSink: ((ByteArray) -> Unit)? = null

    /** Attach an OyunTR decoder before connecting so native samples have a consumer. */
    fun setVideoSampleSink(sink: ((ByteArray, Int, Boolean) -> Boolean)?) {
        videoSampleSink = sink
    }

    fun setAudioFormatSink(sink: ((Int, Int) -> Unit)?) { audioFormatSink = sink }
    fun setAudioPcmSink(sink: ((ByteArray) -> Unit)?) { audioPcmSink = sink }

    fun onNativeAudioFormat(channels: Int, sampleRateHz: Int) {
        try { audioFormatSink?.invoke(channels, sampleRateHz) } catch (_: RuntimeException) { /* Drop invalid formats. */ }
    }

    fun onNativeAudioPcm(pcm: ByteArray) {
        try { audioPcmSink?.invoke(pcm) } catch (_: RuntimeException) { /* Drop output when the device sink closes. */ }
    }

    /** Stable JNI callback; false asks the protocol library to treat the sample as dropped. */
    fun onNativeVideoSample(sample: ByteArray, framesLost: Int, frameRecovered: Boolean): Boolean =
        try {
            videoSampleSink?.invoke(sample, framesLost, frameRecovered) == true
        } catch (_: RuntimeException) {
            false
        }

    /** Starts a local PS4 10.x pairing attempt using the PIN displayed by the console. */
    fun registerLocalConsole(
        host: String,
        pin: String,
        consolePin: String,
        psnAccountId: ByteArray,
        onComplete: (ConsoleRegistration?) -> Unit,
    ) {
        require(host.isNotBlank()) { "PS4 host address is required" }
        require(pin.isNotBlank() && pin.all(Char::isDigit)) { "PS4 registration PIN must contain digits" }
        require(consolePin.isBlank() || consolePin.all(Char::isDigit)) { "Console PIN must contain digits" }
        require(psnAccountId.size == 8) { "PSN account ID must be 8 bytes" }
        ChiakiNativeBridge.ensureLoaded()
        worker.execute {
            try {
                synchronized(lock) {
                    check(registrationHandle == 0L) { "A PS4 registration attempt is already active" }
                    registrationCompletion = onComplete
                }
                val handle = ChiakiNativeBridge.nativeStartRegistration(
                    host,
                    pin.toInt(),
                    consolePin.ifBlank { "0" }.toInt(),
                    psnAccountId,
                    this,
                )
                check(handle != 0L) { "PS4 registration could not start" }
                synchronized(lock) { registrationHandle = handle }
            } catch (_: RuntimeException) {
                val completion = synchronized(lock) { registrationCompletion.also { registrationCompletion = null } }
                completion?.invoke(null)
            }
        }
    }

    fun cancelRegistration() {
        worker.execute {
            val handle = synchronized(lock) { registrationHandle }
            if (handle != 0L) {
                ChiakiNativeBridge.nativeStopRegistration(handle)
                ChiakiNativeBridge.nativeDestroyRegistration(handle)
            }
            val completion = synchronized(lock) {
                if (registrationHandle == handle) registrationHandle = 0L
                registrationCompletion.also { registrationCompletion = null }
            }
            completion?.invoke(null)
        }
    }

    /** Copies registration bytes before chiaki-ng releases its callback structure. */
    fun onNativeRegistrationResult(success: Boolean, key: ByteArray?, morning: ByteArray?) {
        try {
            worker.execute {
                val handle = synchronized(lock) { registrationHandle }
                if (handle != 0L) ChiakiNativeBridge.nativeDestroyRegistration(handle)
                val completion = synchronized(lock) {
                    if (registrationHandle == handle) registrationHandle = 0L
                    registrationCompletion.also { registrationCompletion = null }
                }
                val credentials = if (success && key?.size == 16 && morning?.size == 16) {
                    ConsoleRegistration(key.copyOf(), morning.copyOf())
                } else null
                completion?.invoke(credentials)
            }
        } catch (_: RejectedExecutionException) {
            // close() has already queued cancellation and native teardown.
        }
    }

    override fun connect(target: ConsoleTarget, listener: RemotePlayGateway.Listener) {
        ChiakiNativeBridge.ensureLoaded()
        val thisAttempt = synchronized(lock) {
            check(nativeHandle == 0L && this.listener == null) { "A Remote Play session is already active" }
            this.listener = listener
            ++attempt
        }
        listener.onStateChanged(ConnectionState.CONNECTING)
        worker.execute {
            var createdHandle = 0L
            try {
                val host = target.host?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("PS4 host is required for a local connection")
                val registration = target.registration
                    ?: throw IllegalArgumentException("Register this PS4 before connecting")
                require(registration.registrationKey.size == 16) { "PS4 registration key must be 16 bytes" }
                require(registration.morning.size == 16) { "PS4 morning value must be 16 bytes" }

                createdHandle = ChiakiNativeBridge.nativeCreateSession(host, registration.registrationKey, registration.morning, this)
                check(createdHandle != 0L) { "Native PS4 session could not be created" }
                synchronized(lock) {
                    if (attempt != thisAttempt) {
                        ChiakiNativeBridge.nativeDestroySession(createdHandle)
                        return@execute
                    }
                    nativeHandle = createdHandle
                }
                val result = ChiakiNativeBridge.nativeStartSession(createdHandle)
                check(result == 0) { "chiaki-ng could not start the PS4 session (code $result)" }
            } catch (error: RuntimeException) {
                if (createdHandle != 0L) ChiakiNativeBridge.nativeDestroySession(createdHandle)
                synchronized(lock) {
                    if (attempt == thisAttempt && nativeHandle == createdHandle) nativeHandle = 0L
                    if (attempt == thisAttempt) this.listener = null
                }
                if (synchronized(lock) { attempt == thisAttempt }) {
                    listener.onFailure(
                        RemotePlayFailure(
                            classifyFailure(error),
                            error.message ?: "PS4 connection could not start",
                        ),
                    )
                }
            }
        }
    }

    override fun disconnect(): CompletableFuture<Unit> {
        val completion = CompletableFuture<Unit>()
        // Invalidate an in-flight connect before it can publish a newly allocated handle.
        synchronized(lock) { ++attempt }
        worker.execute {
            try {
                val handle = synchronized(lock) { nativeHandle }
                if (handle != 0L) {
                    ChiakiNativeBridge.nativeStopSession(handle)
                    ChiakiNativeBridge.nativeDestroySession(handle)
                }
                val activeListener = synchronized(lock) {
                    if (nativeHandle == handle) nativeHandle = 0L
                    listener.also { listener = null }
                }
                activeListener?.onStateChanged(ConnectionState.DISCONNECTED)
                completion.complete(Unit)
            } catch (error: RuntimeException) {
                completion.completeExceptionally(error)
            }
        }
        return completion
    }

    override fun sendControllerState(state: ControllerState) {
        require((state.buttons and 0xFFFF.inv().toLong()) == 0L) {
            "Controller button mask uses unsupported bits"
        }
        require(
            state.leftStickX in Short.MIN_VALUE..Short.MAX_VALUE &&
                state.leftStickY in Short.MIN_VALUE..Short.MAX_VALUE &&
                state.rightStickX in Short.MIN_VALUE..Short.MAX_VALUE &&
                state.rightStickY in Short.MIN_VALUE..Short.MAX_VALUE,
        ) { "Stick axes must fit signed 16-bit controller values" }
        require(state.leftTrigger in 0..255 && state.rightTrigger in 0..255) {
            "Analog trigger values must be between 0 and 255"
        }
        val handle = synchronized(lock) { nativeHandle }
        check(handle != 0L) { "No active PS4 session" }
        worker.execute {
            ChiakiNativeBridge.nativeSetControllerState(
                handle,
                state.buttons.toInt(),
                state.leftStickX,
                state.leftStickY,
                state.rightStickX,
                state.rightStickY,
                state.leftTrigger,
                state.rightTrigger,
            )
        }
    }

    override fun submitConsolePin(pin: String) {
        require(pin.length in 4..8 && pin.all(Char::isDigit)) { "Console PIN must contain 4–8 digits" }
        val handle = synchronized(lock) { nativeHandle }
        check(handle != 0L) { "No active PS4 session" }
        worker.execute { ChiakiNativeBridge.nativeSubmitConsolePin(handle, pin) }
    }

    /** Called by the native event callback. Keep it public for stable JNI name/signature. */
    fun onNativeEvent(event: Int, message: String?) {
        val activeListener = synchronized(lock) { listener } ?: return
        when (event) {
            EVENT_CONNECTED -> activeListener.onStateChanged(ConnectionState.SESSION_CONNECTED)
            EVENT_PIN_REQUESTED -> activeListener.onStateChanged(ConnectionState.WAITING_FOR_PIN)
            EVENT_SESSION_ENDED, EVENT_SESSION_FAILED -> worker.execute {
                val handle = synchronized(lock) { nativeHandle }
                if (handle != 0L) ChiakiNativeBridge.nativeDestroySession(handle)
                val completedListener = synchronized(lock) {
                    if (nativeHandle == handle) nativeHandle = 0L
                    listener.also { listener = null }
                }
                if (event == EVENT_SESSION_FAILED) {
                    completedListener?.onFailure(
                        RemotePlayFailure(
                            RemotePlayFailure.Category.NETWORK,
                            message ?: "PS4 session ended unexpectedly",
                        ),
                    )
                } else {
                    completedListener?.onStateChanged(ConnectionState.DISCONNECTED)
                }
            }
        }
    }

    override fun close() {
        cancelRegistration()
        disconnect()
        worker.shutdown()
    }

    private companion object {
        const val EVENT_CONNECTED = 1
        const val EVENT_PIN_REQUESTED = 2
        const val EVENT_SESSION_ENDED = 3
        const val EVENT_SESSION_FAILED = 4

        fun classifyFailure(error: RuntimeException): RemotePlayFailure.Category {
            val message = error.message.orEmpty().lowercase()
            return when {
                error is IllegalArgumentException && message.contains("registration") ->
                    RemotePlayFailure.Category.REGISTRATION
                message.contains("native provider") || message.contains("native ps4 session") ->
                    RemotePlayFailure.Category.PROVIDER_UNAVAILABLE
                else -> RemotePlayFailure.Category.NETWORK
            }
        }
    }
}

/** Private ABI surface, separate from the OyunTR domain APIs above. */
private object ChiakiNativeBridge {
    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                System.loadLibrary("oyuntr_remoteplay")
                loaded = true
            } catch (error: UnsatisfiedLinkError) {
                throw IllegalStateException("PS4 native provider is not available in this build", error)
            }
        }
    }

    external fun nativeCreateSession(host: String, registrationKey: ByteArray, morning: ByteArray, callback: ChiakiNativeGateway): Long
    external fun nativeStartSession(handle: Long): Int
    external fun nativeStopSession(handle: Long)
    external fun nativeDestroySession(handle: Long)
    external fun nativeSetControllerState(handle: Long, buttons: Int, leftX: Int, leftY: Int, rightX: Int, rightY: Int, leftTrigger: Int, rightTrigger: Int)
    external fun nativeSubmitConsolePin(handle: Long, pin: String)
    external fun nativeStartRegistration(host: String, pin: Int, consolePin: Int, psnAccountId: ByteArray, callback: ChiakiNativeGateway): Long
    external fun nativeStopRegistration(handle: Long)
    external fun nativeDestroyRegistration(handle: Long)
}

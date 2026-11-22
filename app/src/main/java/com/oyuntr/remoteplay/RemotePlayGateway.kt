package com.oyuntr.remoteplay

import java.util.concurrent.CompletableFuture

/** OyunTR-owned boundary for a future PlayStation Remote Play provider. */
interface RemotePlayGateway {
    /** Starts a connection attempt. Completion and media arrive through [listener]. */
    fun connect(target: ConsoleTarget, listener: Listener)

    /** Requests a clean stop and releases provider-owned session resources. */
    fun disconnect(): CompletableFuture<Unit>

    /** Sends a normalized OyunTR controller state to the active provider. */
    fun sendControllerState(state: ControllerState)

    /** Supplies the PS4 console PIN after the console requests one. */
    fun submitConsolePin(pin: String)

    interface Listener {
        fun onStateChanged(state: ConnectionState)
        /** Called only after the provider has decoded a sample into OyunTR's chosen frame format. */
        fun onDecodedVideoFrame(frame: DecodedVideoFrame)
        fun onFailure(error: RemotePlayFailure)
    }
}

/** Deliberately contains only OyunTR domain data, never upstream C/JNI types. */
data class ConsoleTarget(
    val id: String,
    val displayName: String,
    val host: String? = null,
    val registration: ConsoleRegistration? = null,
)

/** Sensitive values from a completed local console registration. Keep them out of logs. */
data class ConsoleRegistration(
    val registrationKey: ByteArray,
    val morning: ByteArray,
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    WAITING_FOR_REGISTRATION,
    WAITING_FOR_PIN,
    SESSION_CONNECTED,
    STREAMING,
    STOPPING,
    FAILED,
}

/** OyunTR media output. chiaki-ng's video-sample callback is compressed input, not this type. */
interface DecodedVideoFrame {
    val width: Int
    val height: Int
    val presentationTimeMicros: Long
}

data class ControllerState(
    val buttons: Long = 0,
    val leftStickX: Int = 0,
    val leftStickY: Int = 0,
    val rightStickX: Int = 0,
    val rightStickY: Int = 0,
    /** Analog trigger values in the DualShock range 0..255. */
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
) {
    companion object Buttons {
        const val CROSS = 1L
        const val CIRCLE = 2L
        const val SQUARE = 4L
        const val TRIANGLE = 8L
        const val DPAD_LEFT = 16L
        const val DPAD_RIGHT = 32L
        const val DPAD_UP = 64L
        const val DPAD_DOWN = 128L
        const val L1 = 256L
        const val R1 = 512L
        const val L3 = 1024L
        const val R3 = 2048L
        const val OPTIONS = 4096L
        const val SHARE = 8192L
        const val TOUCHPAD = 16384L
        const val PS = 32768L
    }
}

data class RemotePlayFailure(
    val category: Category,
    val userMessage: String,
) {
    enum class Category {
        NETWORK,
        REGISTRATION,
        UNSUPPORTED_DEVICE,
        PROVIDER_UNAVAILABLE,
        UNKNOWN,
    }
}

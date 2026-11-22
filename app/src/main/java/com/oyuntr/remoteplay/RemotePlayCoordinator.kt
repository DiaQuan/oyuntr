package com.oyuntr.remoteplay

/** Coordinates UI requests with a provider while rejecting stale callbacks from old sessions. */
class RemotePlayCoordinator(
    private val gateway: RemotePlayGateway,
    private val observer: Observer,
) {
    interface Observer {
        fun onStateChanged(state: ConnectionState)
        fun onDecodedVideoFrame(frame: DecodedVideoFrame)
        fun onFailure(error: RemotePlayFailure)
    }

    private val lock = Any()
    private var generation = 0L
    private var state = ConnectionState.DISCONNECTED

    fun currentState(): ConnectionState = synchronized(lock) { state }

    fun connect(target: ConsoleTarget) {
        val token = synchronized(lock) {
            check(state == ConnectionState.DISCONNECTED || state == ConnectionState.FAILED) {
                "A Remote Play session is already active"
            }
            generation += 1
            state = ConnectionState.CONNECTING
            generation
        }
        observer.onStateChanged(ConnectionState.CONNECTING)

        try {
            gateway.connect(target, object : RemotePlayGateway.Listener {
                override fun onStateChanged(newState: ConnectionState) {
                    publishState(token, newState)
                }

                override fun onDecodedVideoFrame(frame: DecodedVideoFrame) {
                    if (isCurrent(token) && currentState() == ConnectionState.STREAMING) {
                        observer.onDecodedVideoFrame(frame)
                    }
                }

                override fun onFailure(error: RemotePlayFailure) {
                    if (publishState(token, ConnectionState.FAILED)) observer.onFailure(error)
                }
            })
        } catch (error: RuntimeException) {
            if (publishState(token, ConnectionState.FAILED)) {
                observer.onFailure(
                    RemotePlayFailure(
                        RemotePlayFailure.Category.PROVIDER_UNAVAILABLE,
                        error.message ?: "Remote Play provider could not start",
                    ),
                )
            }
        }
    }

    fun disconnect() {
        val token = synchronized(lock) {
            if (state == ConnectionState.DISCONNECTED) return
            generation += 1
            state = ConnectionState.STOPPING
            generation
        }
        observer.onStateChanged(ConnectionState.STOPPING)
        try {
            gateway.disconnect().whenComplete { _, error ->
                if (error != null) {
                    if (publishState(token, ConnectionState.FAILED)) {
                        observer.onFailure(
                            RemotePlayFailure(
                                RemotePlayFailure.Category.PROVIDER_UNAVAILABLE,
                                error.cause?.message ?: error.message ?: "Remote Play provider could not stop cleanly",
                            ),
                        )
                    }
                } else {
                    publishState(token, ConnectionState.DISCONNECTED)
                }
            }
        } catch (error: RuntimeException) {
            if (publishState(token, ConnectionState.FAILED)) {
                observer.onFailure(
                    RemotePlayFailure(
                        RemotePlayFailure.Category.PROVIDER_UNAVAILABLE,
                        error.message ?: "Remote Play provider could not stop cleanly",
                    ),
                )
            }
        }
    }

    fun sendControllerState(input: ControllerState) {
        check(currentState() == ConnectionState.STREAMING || currentState() == ConnectionState.SESSION_CONNECTED) {
            "Controller input requires a connected PS4 session"
        }
        gateway.sendControllerState(input)
    }

    fun submitConsolePin(pin: String) {
        require(pin.length in 4..8 && pin.all(Char::isDigit)) { "Console PIN must contain 4–8 digits" }
        check(currentState() == ConnectionState.WAITING_FOR_PIN) { "The console has not requested a PIN" }
        gateway.submitConsolePin(pin)
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lock) { generation == token }

    private fun publishState(token: Long, newState: ConnectionState): Boolean {
        val changed = synchronized(lock) {
            if (generation != token) return false
            if (!isAllowedTransition(state, newState)) return false
            if (state == newState) return false
            state = newState
            true
        }
        if (changed) observer.onStateChanged(newState)
        return changed
    }

    private fun isAllowedTransition(from: ConnectionState, to: ConnectionState): Boolean = when (from) {
        ConnectionState.DISCONNECTED -> to == ConnectionState.CONNECTING
        ConnectionState.CONNECTING -> to in setOf(
            ConnectionState.WAITING_FOR_REGISTRATION,
            ConnectionState.WAITING_FOR_PIN,
            ConnectionState.SESSION_CONNECTED,
            ConnectionState.STREAMING,
            ConnectionState.STOPPING,
            ConnectionState.FAILED,
            ConnectionState.DISCONNECTED,
        )
        ConnectionState.WAITING_FOR_REGISTRATION -> to in setOf(
            ConnectionState.CONNECTING,
            ConnectionState.WAITING_FOR_PIN,
            ConnectionState.SESSION_CONNECTED,
            ConnectionState.STREAMING,
            ConnectionState.STOPPING,
            ConnectionState.FAILED,
            ConnectionState.DISCONNECTED,
        )
        ConnectionState.STREAMING -> to in setOf(
            ConnectionState.STOPPING,
            ConnectionState.FAILED,
            ConnectionState.DISCONNECTED,
        )
        ConnectionState.SESSION_CONNECTED -> to in setOf(
            ConnectionState.WAITING_FOR_PIN,
            ConnectionState.STREAMING,
            ConnectionState.STOPPING,
            ConnectionState.FAILED,
            ConnectionState.DISCONNECTED,
        )
        ConnectionState.WAITING_FOR_PIN -> to in setOf(
            ConnectionState.CONNECTING,
            ConnectionState.SESSION_CONNECTED,
            ConnectionState.STREAMING,
            ConnectionState.STOPPING,
            ConnectionState.FAILED,
            ConnectionState.DISCONNECTED,
        )
        ConnectionState.STOPPING -> to == ConnectionState.DISCONNECTED || to == ConnectionState.FAILED
        ConnectionState.FAILED -> to == ConnectionState.CONNECTING || to == ConnectionState.DISCONNECTED
    }
}

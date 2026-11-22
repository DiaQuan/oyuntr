package com.oyuntr.dubbing

/** A saved character-to-Chatterbox reference or installed-device voice assignment. */
data class CharacterVoiceProfile(
    val id: String,
    val displayName: String,
    val voiceName: String? = null,
    val aiVoiceId: String? = null,
    val expressiveness: Float = 0.5f,
)

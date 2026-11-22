package com.oyuntr.remoteplay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores a completed local registration encrypted with an Android Keystore key. */
class AndroidConsoleCredentialVault(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "oyuntr_console_credentials_v1",
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun save(consoleId: String, registration: ConsoleRegistration) {
        require(consoleId.isNotBlank()) { "Console ID is required" }
        require(registration.registrationKey.size == AUTH_SIZE) { "Registration key must be 16 bytes" }
        require(registration.morning.size == AUTH_SIZE) { "Morning value must be 16 bytes" }

        val plaintext = ByteBuffer.allocate(AUTH_SIZE * 2)
            .put(registration.registrationKey)
            .put(registration.morning)
            .array()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(consoleId.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext)
            val payload = ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array()
            check(preferences.edit().putString(preferenceKey(consoleId), Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) {
                "Encrypted console registration could not be saved"
            }
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun load(consoleId: String): ConsoleRegistration? {
        require(consoleId.isNotBlank()) { "Console ID is required" }
        val encoded = preferences.getString(preferenceKey(consoleId), null) ?: return null
        try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_SIZE) { "Encrypted registration is truncated" }
            val iv = payload.copyOfRange(0, GCM_IV_SIZE)
            val ciphertext = payload.copyOfRange(GCM_IV_SIZE, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(consoleId.toByteArray(Charsets.UTF_8))
            val plaintext = cipher.doFinal(ciphertext)
            try {
                require(plaintext.size == AUTH_SIZE * 2) { "Stored registration has an invalid size" }
                return ConsoleRegistration(
                    registrationKey = plaintext.copyOfRange(0, AUTH_SIZE),
                    morning = plaintext.copyOfRange(AUTH_SIZE, AUTH_SIZE * 2),
                )
            } finally {
                plaintext.fill(0)
            }
        } catch (error: Exception) {
            throw IllegalStateException("Console registration could not be decrypted; remove it and register the console again", error)
        }
    }

    @Synchronized
    fun remove(consoleId: String) {
        require(consoleId.isNotBlank()) { "Console ID is required" }
        check(preferences.edit().remove(preferenceKey(consoleId)).commit()) {
            "Console registration could not be removed"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        synchronized(KEY_LOCK) {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        }
    }

    private fun preferenceKey(consoleId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(consoleId.toByteArray(Charsets.UTF_8))
        return "console_" + digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val AUTH_SIZE = 16
        const val GCM_IV_SIZE = 12
        const val GCM_TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "oyuntr.console.registration.v1"
        val KEY_LOCK = Any()
    }
}

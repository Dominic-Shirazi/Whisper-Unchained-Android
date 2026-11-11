// This file provides hardware-backed cryptographic encryption and decryption using the Android KeyStore.
// It is called by SettingsRepository to protect sensitive credentials (API keys, endpoints, and tokens) at rest.
// Inputs: Plaintext strings and base64-encoded encrypted payloads (prefixed with "enc:v1:").
// Outputs: Authenticated AES-256-GCM ciphertexts and decrypted plaintext strings.
// It uses hardware security modules (StrongBox / TEE / Knox) via AndroidKeyStore without third-party or deprecated libraries.

package com.example.whisperremote.util

// Reason for edit: Created per F-4 to replace deprecated androidx.security:security-crypto with direct Android KeyStore AES-256-GCM encryption.

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeyStoreCryptoHelper {

    private const val TAG = "KeyStoreCryptoHelper"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "WhisperUnchainedAES256MasterKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private val keyStore: KeyStore? by lazy {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (e: Throwable) {
            Log.w(TAG, "AndroidKeyStore unavailable: ${e.message}")
            null
        }
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        val ks = keyStore ?: return null
        return try {
            if (!ks.containsAlias(KEY_ALIAS)) {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            } else {
                ks.getKey(KEY_ALIAS, null) as? SecretKey
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get or generate hardware KeyStore key", e)
            null
        }
    }

    /**
     * Encrypts plaintext using hardware-backed AES-256-GCM.
     * Returns a string prefixed with "enc:v1:" containing the base64-encoded IV + ciphertext.
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val secretKey = getOrCreateSecretKey() ?: return plaintext
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv ?: return plaintext
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Throwable) {
            Log.e(TAG, "Encryption failed; returning unencrypted payload fallback", e)
            plaintext
        }
    }

    /**
     * Decrypts an "enc:v1:" prefixed string using hardware-backed AES-256-GCM.
     * If the payload is not encrypted with "enc:v1:", returns the string as-is.
     */
    fun decrypt(encryptedString: String): String {
        if (!encryptedString.startsWith(PREFIX)) {
            return encryptedString
        }
        val secretKey = getOrCreateSecretKey() ?: return encryptedString
        return try {
            val base64Payload = encryptedString.removePrefix(PREFIX)
            val combined = Base64.decode(base64Payload, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return encryptedString

            val iv = ByteArray(GCM_IV_LENGTH)
            val ciphertext = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.size)

            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.e(TAG, "Decryption failed; returning raw payload", e)
            encryptedString
        }
    }

    /**
     * Returns true if the Android KeyStore is functional and key generation succeeded.
     */
    fun isHardwareAvailable(): Boolean {
        return getOrCreateSecretKey() != null
    }
}

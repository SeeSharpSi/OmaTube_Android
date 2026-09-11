package dev.omatube.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Encrypts the remembered API key with an AES/GCM key held in the Android
// Keystore. Ciphertext is written to the no-backup key file; the plaintext key
// never reaches disk and is never logged.
internal class ApiKeyCrypto(
    private val keyAlias: String = DEFAULT_ALIAS,
) : ApiKeyCipher {
    override fun encrypt(plaintext: String): String = try {
        encryptWith(getOrCreateKey(), plaintext)
    } catch (firstAttempt: Exception) {
        // A Keystore entry can become permanently unusable (for example after
        // a lock screen change). The plaintext is in hand and we are already
        // replacing the ciphertext, so it is safe to drop the invalidated key
        // and retry exactly once.
        runCatching { deleteKey() }
        encryptWith(getOrCreateKey(), plaintext)
    }

    private fun encryptWith(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(1 + iv.size + ciphertext.size)
        payload[0] = iv.size.toByte()
        iv.copyInto(payload, destinationOffset = 1)
        ciphertext.copyInto(payload, destinationOffset = 1 + iv.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String? {
        if (encoded.isEmpty()) return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size < 2) return null
            val ivLength = payload[0].toInt() and 0xFF
            if (ivLength <= 0 || payload.size <= 1 + ivLength) return null
            val iv = payload.copyOfRange(1, 1 + ivLength)
            val ciphertext = payload.copyOfRange(1 + ivLength, payload.size)
            val key = existingKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (exception: Exception) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry ?: return null
        return entry.secretKey
    }

    private fun deleteKey() {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "omatube_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}

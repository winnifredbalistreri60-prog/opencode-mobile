package ru.dredd20.opencode.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Remote password at rest (run4 item 6): AES-256-GCM with a non-exportable key in Android Keystore.
 * Stored value: "v1:" + base64(iv) + ":" + base64(ciphertext+tag).
 */
object SecretPrefs {
    private const val KEY_ALIAS = "ocm_remote_password_v1"
    private const val STORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Blob.format(c.iv, ct) { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    fun decrypt(stored: String): String {
        val (iv, ct) = Blob.parse(stored) { Base64.decode(it, Base64.NO_WRAP) } ?: error("bad secret format")
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    /** Pure format helpers (unit-tested). */
    object Blob {
        fun format(iv: ByteArray, ct: ByteArray, b64: (ByteArray) -> String) = "v1:${b64(iv)}:${b64(ct)}"
        fun parse(s: String, unb64: (String) -> ByteArray): Pair<ByteArray, ByteArray>? {
            val parts = s.split(':')
            if (parts.size != 3 || parts[0] != "v1") return null
            return runCatching { unb64(parts[1]) to unb64(parts[2]) }.getOrNull()?.takeIf { it.first.size in 12..16 && it.second.size >= 16 }
        }
    }
}

package com.tyranor.next.core.translation.service.secret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException

/** Translation-domain secrets are versioned by reference and never stored in Room. */
internal class TranslationSecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun write(reference: String, value: String): Boolean {
        if (reference.isBlank() || value.isBlank()) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            if (iv.size != IV_BYTES) return false
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val output = ByteArray(iv.size + encrypted.size)
            iv.copyInto(output)
            encrypted.copyInto(output, iv.size)
            prefs.edit().putString(reference, Base64.encodeToString(output, Base64.NO_WRAP)).commit()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    fun read(reference: String): SecretRead {
        if (reference.isBlank()) return SecretRead.Missing
        val encoded = prefs.getString(reference, null) ?: return SecretRead.Missing
        return try {
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            if (encrypted.size <= IV_BYTES) return SecretRead.Unavailable
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, encrypted, 0, IV_BYTES),
            )
            SecretRead.Available(
                String(cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES), Charsets.UTF_8),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep ciphertext intact: a transient or invalidated Keystore key must not erase it.
            SecretRead.Unavailable
        }
    }

    fun delete(reference: String): Boolean = prefs.edit().remove(reference).commit()

    fun deleteProfileSecrets(profileId: String): Boolean {
        val prefix = "$profileId:"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        return editor.commit()
    }

    fun deleteUnreferencedSecrets(referencedSecrets: Set<String>): Boolean {
        val editor = prefs.edit()
        prefs.all.keys.filterNot(referencedSecrets::contains).forEach(editor::remove)
        return editor.commit()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    sealed interface SecretRead {
        class Available(val value: String) : SecretRead {
            override fun toString(): String = "Available([redacted])"
        }
        data object Missing : SecretRead
        data object Unavailable : SecretRead
    }

    companion object {
        const val PREFERENCES = "translation_service_secrets"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tyranor_translation_service_secret_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}

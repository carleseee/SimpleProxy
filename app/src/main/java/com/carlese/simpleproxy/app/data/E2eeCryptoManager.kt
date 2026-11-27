package com.carlese.simpleproxy.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object E2eeCipher {
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val AES_256_BYTES = 32

    private val secureRandom = SecureRandom()

    fun generateMasterDek(): SecretKey {
        val raw = ByteArray(AES_256_BYTES)
        secureRandom.nextBytes(raw)
        return SecretKeySpec(raw, "AES")
    }

    fun encodeDek(dek: SecretKey): String {
        val bytes = dek.encoded
        require(bytes.size == AES_256_BYTES) { "Expected 256-bit AES key" }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun decodeDek(dekB64: String): SecretKey {
        val raw = Base64.getDecoder().decode(dekB64.trim())
        require(raw.size == AES_256_BYTES) { "Invalid AES-256 key length: ${raw.size}" }
        return SecretKeySpec(raw, "AES")
    }

    fun encryptString(plaintext: String, dek: SecretKey, aadContext: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aadContext.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "${encoder.encodeToString(iv)}:${encoder.encodeToString(ciphertext)}"
    }

    fun decryptString(payload: String, dek: SecretKey, aadContext: String): String {
        val parts = payload.split(":", limit = 2)
        require(parts.size == 2) { "Malformed encrypted payload" }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[0])
        val ciphertext = decoder.decode(parts[1])
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aadContext.toByteArray(Charsets.UTF_8))
        val plainBytes = cipher.doFinal(ciphertext)
        return String(plainBytes, Charsets.UTF_8)
    }
}

class E2eeCryptoManager(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun encryptString(plaintext: String, dek: SecretKey, aadContext: String): String =
        E2eeCipher.encryptString(plaintext, dek, aadContext)

    fun decryptString(payload: String, dek: SecretKey, aadContext: String): String =
        E2eeCipher.decryptString(payload, dek, aadContext)

    fun sealDekLocally(uid: String, vaultId: String, dek: SecretKey) {
        runCatching {
            val keystoreKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey)
            cipher.updateAAD("local:$uid:$vaultId".toByteArray(Charsets.UTF_8))
            val sealed = cipher.doFinal(dek.encoded)
            val encoder = Base64.getEncoder()
            val ivB64 = encoder.encodeToString(cipher.iv)
            val sealedB64 = encoder.encodeToString(sealed)
            prefs.edit {
                putString("vault_id_$uid", vaultId)
                putString("sealed_dek_$uid", "$ivB64:$sealedB64")
            }
        }
    }

    fun loadAnySealedDek(uid: String): SecretKey? {
        val savedVaultId = prefs.getString("vault_id_$uid", null) ?: return null
        val stored = prefs.getString("sealed_dek_$uid", null) ?: return null
        return runCatching {
            val parts = stored.split(":", limit = 2)
            if (parts.size != 2) return null
            val decoder = Base64.getDecoder()
            val iv = decoder.decode(parts[0])
            val sealed = decoder.decode(parts[1])
            val keystoreKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD("local:$uid:$savedVaultId".toByteArray(Charsets.UTF_8))
            val rawDek = cipher.doFinal(sealed)
            SecretKeySpec(rawDek, "AES")
        }.getOrElse {
            clearLocalSeal(uid)
            null
        }
    }

    fun clearLocalSeal(uid: String) {
        prefs.edit {
            remove("vault_id_$uid")
            remove("sealed_dek_$uid")
        }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "simpleproxy_e2ee_local_seal_v2"
        private const val PREFS_NAME = "simpleproxy_e2ee_prefs"
    }
}

package com.example.aicode.pi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tom.rv2ide.BuildConfig
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the user's provider key encrypted by Android Keystore, never in project files. */
class ApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pi_secrets", Context.MODE_PRIVATE)

    fun hasKey(providerId: String = "opencode-zen"): Boolean = !read(providerId).isNullOrBlank()

    fun save(value: String, providerId: String = "opencode-zen") {
        val clean = value.trim()
        if (clean.isBlank()) {
            clear(providerId)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
        prefs.edit().putString(storageKey(providerId), Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun read(providerId: String = "opencode-zen"): String? {
        val encoded = prefs.getString(storageKey(providerId), null)
            ?: if (providerId == "opencode-zen") prefs.getString(KEY_VALUE, null) else null
        val stored = encoded?.let { encrypted -> runCatching {
            val packed = ByteBuffer.wrap(Base64.decode(encrypted, Base64.NO_WRAP))
            val ivSize = packed.get().toInt()
            require(ivSize in 12..32)
            val iv = ByteArray(ivSize).also { packed.get(it) }
            val encrypted = ByteArray(packed.remaining()).also { packed.get(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            }
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrNull() }
        return stored?.takeIf { it.isNotBlank() } ?: bundledKey(providerId)
    }

    fun clear(providerId: String = "opencode-zen") {
        prefs.edit().remove(storageKey(providerId)).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun storageKey(providerId: String): String = "$KEY_VALUE.$providerId"

    private fun bundledKey(providerId: String): String? = when (providerId) {
        "opencode-zen" -> BuildConfig.OPENCODE_API_KEY
        "nvidia-nim" -> BuildConfig.NVIDIA_API_KEY
        else -> null
    }?.takeIf { it.isNotBlank() }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "aicode.pi.provider.key"
        const val KEY_VALUE = "provider_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

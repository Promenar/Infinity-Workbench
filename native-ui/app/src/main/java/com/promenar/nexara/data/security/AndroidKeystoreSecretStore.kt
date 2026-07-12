package com.promenar.nexara.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidKeystoreSecretStore internal constructor(
    private val preferences: SharedPreferences,
    private val key: SecretKey,
    private val codec: AesGcmSecretCodec = AesGcmSecretCodec(),
) : SecretStore {
    constructor(
        context: Context,
        preferencesName: String = DEFAULT_PREFERENCES_NAME,
        keyAlias: String = KEY_ALIAS,
    ) : this(
        preferences = context.applicationContext.getSharedPreferences(
            preferencesName,
            Context.MODE_PRIVATE,
        ),
        key = getOrCreateKey(keyAlias),
    )

    override fun put(id: SecretId, value: ByteArray) {
        val envelope = codec.encode(value, key)
        check(preferences.edit().putString(id.value, envelope).commit()) {
            "密钥 envelope 写入失败"
        }
    }

    override fun get(id: SecretId): ByteArray? =
        preferences.getString(id.value, null)?.let { codec.decode(it, key) }

    override fun contains(id: SecretId): Boolean = preferences.contains(id.value)

    override fun remove(id: SecretId) {
        check(preferences.edit().remove(id.value).commit()) {
            "密钥 envelope 删除失败"
        }
    }

    companion object {
        internal const val KEY_ALIAS = "nexara.secrets.v1"

        private const val DEFAULT_PREFERENCES_NAME = "nexara_encrypted_secrets"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"

        @Synchronized
        private fun getOrCreateKey(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

            return KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE,
            ).run {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        }
    }
}

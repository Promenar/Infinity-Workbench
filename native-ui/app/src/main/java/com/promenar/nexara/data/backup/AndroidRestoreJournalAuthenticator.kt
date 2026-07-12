package com.promenar.nexara.data.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

class AndroidRestoreJournalAuthenticator(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : RestoreJournalAuthenticator {
    init {
        require(keyAlias.isNotBlank() && keyAlias.length <= MAX_ALIAS_LENGTH) {
            "恢复 journal HMAC alias 无效"
        }
    }

    override fun sign(payload: ByteArray): ByteArray = Mac.getInstance(ALGORITHM).run {
        init(getOrCreateKey(keyAlias))
        doFinal(payload)
    }

    override fun verify(payload: ByteArray, signature: ByteArray): Boolean {
        val expected = sign(payload)
        return try {
            MessageDigest.isEqual(expected, signature)
        } finally {
            expected.fill(0)
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "nexara.restore.journal.hmac.v1"

        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val ALGORITHM = "HmacSHA256"
        private const val MAX_ALIAS_LENGTH = 256

        @Synchronized
        private fun getOrCreateKey(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

            return KeyGenerator.getInstance(ALGORITHM, ANDROID_KEY_STORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }
    }
}

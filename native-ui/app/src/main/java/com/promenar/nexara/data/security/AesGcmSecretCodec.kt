package com.promenar.nexara.data.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AesGcmSecretCodec(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun encode(plaintext: ByteArray, key: SecretKey): String {
        val iv = ByteArray(IV_SIZE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return listOf(
            VERSION,
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(ciphertext),
        ).joinToString("|")
    }

    @Throws(GeneralSecurityException::class)
    fun decode(envelope: String, key: SecretKey): ByteArray {
        val parts = envelope.split('|')
        if (parts.size != 3 || parts[0] != VERSION) {
            throw GeneralSecurityException("不支持的密钥 envelope")
        }

        try {
            val iv = Base64.getDecoder().decode(parts[1])
            val ciphertext = Base64.getDecoder().decode(parts[2])
            if (iv.size != IV_SIZE_BYTES) {
                throw GeneralSecurityException("无效的 AES-GCM IV")
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
            return cipher.doFinal(ciphertext)
        } catch (error: IllegalArgumentException) {
            throw GeneralSecurityException("无效的密钥 envelope", error)
        }
    }

    private companion object {
        const val VERSION = "1"
        const val IV_SIZE_BYTES = 12
        const val TAG_SIZE_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

package com.promenar.nexara.data.security

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AesGcmSecretCodecTest {
    private val codec = AesGcmSecretCodec()

    @Test
    fun `同一明文使用随机 IV 生成不同密文且均可解密`() {
        val key = newKey()
        val plaintext = "fake-secret".toByteArray()

        val first = codec.encode(plaintext, key)
        val second = codec.encode(plaintext, key)

        assertThat(first).isNotEqualTo(second)
        assertThat(first.split('|')).hasSize(3)
        assertThat(first.substringBefore('|')).isEqualTo("1")
        assertThat(codec.decode(first, key)).isEqualTo(plaintext)
        assertThat(codec.decode(second, key)).isEqualTo(plaintext)
    }

    @Test
    fun `篡改认证标签后解密失败`() {
        val key = newKey()
        val parts = codec.encode("fake-secret".toByteArray(), key).split('|')
        val ciphertext = Base64.getDecoder().decode(parts[2])
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        val tampered = "${parts[0]}|${parts[1]}|${Base64.getEncoder().encodeToString(ciphertext)}"

        assertThrows<GeneralSecurityException> {
            codec.decode(tampered, key)
        }
    }

    @Test
    fun `使用错误 Key 解密失败`() {
        val envelope = codec.encode("fake-secret".toByteArray(), newKey())

        assertThrows<GeneralSecurityException> {
            codec.decode(envelope, newKey())
        }
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }
}

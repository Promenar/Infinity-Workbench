package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException

class BackupCryptoTest {
    @Test
    fun `PBKDF2 AES-GCM round trip uses an authenticated envelope`() {
        val crypto = BackupCrypto()
        val plaintext = "backup payload".toByteArray()
        val password = "correct horse battery staple".toCharArray()

        val encrypted = crypto.encrypt(plaintext, password)
        val decrypted = crypto.decrypt(encrypted, "correct horse battery staple".toCharArray())

        assertThat(encrypted).isNotEqualTo(plaintext)
        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `wrong password is rejected before plaintext is returned`() {
        val crypto = BackupCrypto()
        val encrypted = crypto.encrypt("private".toByteArray(), "right".toCharArray())

        assertThrows<GeneralSecurityException> {
            crypto.decrypt(encrypted, "wrong".toCharArray())
        }
    }

    @Test
    fun `tampered authentication tag is rejected`() {
        val crypto = BackupCrypto()
        val encrypted = crypto.encrypt("private".toByteArray(), "right".toCharArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()

        assertThrows<GeneralSecurityException> {
            crypto.decrypt(encrypted, "right".toCharArray())
        }
    }
}

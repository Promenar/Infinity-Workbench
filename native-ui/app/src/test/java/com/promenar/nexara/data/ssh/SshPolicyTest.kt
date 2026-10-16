package com.promenar.nexara.data.ssh

import com.google.common.truth.Truth.assertThat
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.KeyPair
import com.promenar.nexara.data.security.SshCredential
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class SshPolicyTest {
    @Test fun `别名拒绝地址URI凭据与控制符`() {
        listOf("127.0.0.1", "localhost:8022", "ssh://local", "u@local", "../local", "UPPER", "a\nb", "").forEach {
            assertThrows(SshFailure::class.java) { SshPolicy.alias(it) }
        }
        assertThat(SshPolicy.alias("termux-1")).isEqualTo("termux-1")
        assertThat(SshPolicy.path("./a/../b")).isEqualTo("./a/../b")
        assertThrows(SshFailure::class.java) { SshPolicy.path("a\u0000b") }
    }

    @Test fun `指纹首次未信任变化拒绝替换后匹配`() {
        val bytes = wire("ssh-ed25519", ByteArray(32) { 1 })
        val changed = wire("ssh-ed25519", ByteArray(32) { 2 })
        val host = SshHost(alias = "termux", hostname = "127.0.0.1", username = "u")
        val first = PinnedSshHostKeys(host)
        assertThat(first.check("127.0.0.1", bytes)).isEqualTo(HostKeyRepository.NOT_INCLUDED)
        assertThat(first.rejection).isEqualTo("HOST_KEY_UNTRUSTED")
        val key = SshPolicy.key(bytes)
        assertThat(key.type).isEqualTo("ssh-ed25519")
        assertThat(key.fingerprint).startsWith("SHA256:")
        val pinned = host.copy(trustedKeyType = key.type, trustedFingerprint = key.fingerprint)
        assertThat(PinnedSshHostKeys(pinned).check("127.0.0.1", bytes)).isEqualTo(HostKeyRepository.OK)
        val mismatch = PinnedSshHostKeys(pinned)
        assertThat(mismatch.check("127.0.0.1", changed)).isEqualTo(HostKeyRepository.CHANGED)
        assertThat(mismatch.rejection).isEqualTo("HOST_KEY_CHANGED")
        val updated = SshPolicy.key(changed)
        assertThat(PinnedSshHostKeys(pinned.copy(trustedFingerprint = updated.fingerprint)).check("127.0.0.1", changed))
            .isEqualTo(HostKeyRepository.OK)
        val probe = PinnedSshHostKeys(pinned, probe = true)
        assertThat(probe.check("127.0.0.1", changed)).isNotEqualTo(HostKeyRepository.OK)
        assertThat(probe.observed).isEqualTo(updated)
    }

    @Test fun `输出清除凭据及私钥正文且异常没有cause`() {
        val private = "-----BEGIN OPENSSH PRIVATE KEY-----\nprivate-line-synthetic\n-----END OPENSSH PRIVATE KEY-----"
        SshCredential(privateKey = private.toByteArray(), password = "synthetic-password".toByteArray(), passphrase = "synthetic-passphrase".toByteArray()).use {
            val result = SshPolicy.redact("synthetic-password synthetic-passphrase private-line-synthetic $private", it)
            assertThat(result).doesNotContain("synthetic-password")
            assertThat(result).doesNotContain("synthetic-passphrase")
            assertThat(result).doesNotContain("private-line-synthetic")
            assertThat(result).doesNotContain("BEGIN OPENSSH PRIVATE KEY")
        }
        assertThat(SshFailure("AUTH_FAILED").cause).isNull()
        assertThrows(SshFailure::class.java) { SshPolicy.timeout(601) }
        assertThrows(SshFailure::class.java) { SshPolicy.outputLimit(0) }
    }

    @Test fun `生成的Ed25519为可导入OpenSSH格式且公钥一致`() {
        SshKeyGenerator.generate().use {
            assertThat(it.publicKey).startsWith("ssh-ed25519 ")
            assertThat(SshKeyGenerator.publicKey(it.privateKey, byteArrayOf())).isEqualTo(it.publicKey)
            assertThat(it.toString().contains(it.privateKey.toString(Charsets.UTF_8))).isFalse()
        }
    }

    @Test fun `口令保护的OpenSSH私钥正确口令导入且错误口令只返回稳定错误`() {
        SshKeyGenerator.generate().use { generated ->
            val key = KeyPair.load(SshKeyGenerator.configuredClient(), generated.privateKey, null)
            val output = object : ByteArrayOutputStream() { fun wipe() { buf.fill(0) } }
            val passphrase = "synthetic-import-passphrase".toByteArray()
            var encrypted = byteArrayOf()
            try {
                key.writeOpenSSHv1PrivateKey(output, passphrase)
                encrypted = output.toByteArray()
                assertThat(SshKeyGenerator.publicKey(encrypted, passphrase)).isEqualTo(generated.publicKey)
                val failure = assertThrows(SshFailure::class.java) {
                    SshKeyGenerator.publicKey(encrypted, "synthetic-wrong-passphrase".toByteArray())
                }
                assertThat(failure.message).isEqualTo("INVALID_KEY_PASSPHRASE")
                assertThat(failure.cause).isNull()
            } finally {
                encrypted.fill(0); passphrase.fill(0); output.wipe(); key.dispose()
            }
        }
    }

    private fun wire(type: String, key: ByteArray): ByteArray {
        val name = type.toByteArray()
        return ByteBuffer.allocate(8 + name.size + key.size).putInt(name.size).put(name).putInt(key.size).put(key).array()
    }
}

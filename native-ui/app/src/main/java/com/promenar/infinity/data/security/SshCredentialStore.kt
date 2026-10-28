package com.promenar.infinity.data.security

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** 调用方持有字节所有权，结束使用后须 close 擦除。 */
class SshCredential(
    val privateKey: ByteArray = ByteArray(0),
    val password: ByteArray = ByteArray(0),
    val passphrase: ByteArray = ByteArray(0),
) : AutoCloseable {
    override fun close() {
        privateKey.fill(0)
        password.fill(0)
        passphrase.fill(0)
    }

    override fun toString(): String = "SshCredential([已隐藏])"
}

object SshCredentialCodec {
    private const val MAGIC = 0x4e585343 // NXSC
    private const val VERSION = 1
    private const val HEADER_BYTES = 5 * Int.SIZE_BYTES
    private const val MAX_KEY_BYTES = 256 * 1024
    private const val MAX_PASSWORD_BYTES = 16 * 1024
    private const val MAX_PASSPHRASE_BYTES = 16 * 1024
    const val MAX_RECORD_BYTES = HEADER_BYTES + MAX_KEY_BYTES + MAX_PASSWORD_BYTES + MAX_PASSPHRASE_BYTES

    fun encode(credential: SshCredential): ByteArray {
        require(credential.privateKey.size <= MAX_KEY_BYTES &&
            credential.password.size <= MAX_PASSWORD_BYTES && credential.passphrase.size <= MAX_PASSPHRASE_BYTES
        ) { "SSH 凭据大小无效" }
        return ByteBuffer.allocate(HEADER_BYTES + credential.privateKey.size + credential.password.size + credential.passphrase.size)
            .putInt(MAGIC).putInt(VERSION)
            .putInt(credential.privateKey.size).putInt(credential.password.size).putInt(credential.passphrase.size)
            .put(credential.privateKey).put(credential.password).put(credential.passphrase).array()
    }

    fun decode(encoded: ByteArray): SshCredential {
        require(encoded.size in HEADER_BYTES..MAX_RECORD_BYTES) { "SSH 凭据记录大小无效" }
        val buffer = ByteBuffer.wrap(encoded)
        require(buffer.int == MAGIC && buffer.int == VERSION) { "SSH 凭据记录版本无效" }
        val keySize = buffer.int
        val passwordSize = buffer.int
        val passphraseSize = buffer.int
        require(keySize in 0..MAX_KEY_BYTES && passwordSize in 0..MAX_PASSWORD_BYTES &&
            passphraseSize in 0..MAX_PASSPHRASE_BYTES && buffer.remaining() == keySize + passwordSize + passphraseSize
        ) { "SSH 凭据记录长度无效" }
        return SshCredential(
            privateKey = ByteArray(keySize).also(buffer::get),
            password = ByteArray(passwordSize).also(buffer::get),
            passphrase = ByteArray(passphraseSize).also(buffer::get),
        )
    }
}

/** 凭据绑定连接身份，配置与 SecretStore 分步写入时按不匹配拒绝读取。 */
class SshCredentialStore(private val secrets: SecretStore) {
    fun get(hostId: String, binding: ByteArray): SshCredential? = guarded("SSH 凭据读取失败") {
        require(binding.size == BINDING_BYTES) { "SSH 凭据绑定无效" }
        val encoded = secrets.get(SecretCatalog.sshCredential(hostId)) ?: return@guarded null
        try {
            require(encoded.size in HEADER_BYTES..(HEADER_BYTES + SshCredentialCodec.MAX_RECORD_BYTES)) { "SSH 凭据记录无效" }
            val buffer = ByteBuffer.wrap(encoded)
            require(buffer.int == MAGIC && buffer.int == VERSION) { "SSH 凭据记录版本无效" }
            val storedBinding = ByteArray(BINDING_BYTES).also(buffer::get)
            try {
                if (!MessageDigest.isEqual(storedBinding, binding)) return@guarded null
            } finally {
                storedBinding.fill(0)
            }
            val payload = ByteArray(buffer.remaining()).also(buffer::get)
            try { SshCredentialCodec.decode(payload) } finally { payload.fill(0) }
        } finally {
            encoded.fill(0)
        }
    }

    /** 先更新加密记录，普通提交失败恢复旧记录；进程中断由身份绑定保证拒绝错配。 */
    suspend fun replace(
        hostId: String,
        binding: ByteArray,
        credential: SshCredential?,
        commitConfiguration: suspend () -> Unit,
    ) {
        require(binding.size == BINDING_BYTES) { "SSH 凭据绑定无效" }
        val id = SecretCatalog.sshCredential(hostId)
        val previous = guarded("SSH 凭据读取失败") { secrets.get(id) }
        try {
            if (credential == null) {
                guarded("SSH 凭据删除失败") { secrets.remove(id) }
            } else {
                val payload = SshCredentialCodec.encode(credential)
                val encoded = try {
                    ByteBuffer.allocate(HEADER_BYTES + payload.size)
                        .putInt(MAGIC).putInt(VERSION).put(binding).put(payload).array()
                } finally { payload.fill(0) }
                try { guarded("SSH 凭据保存失败") { secrets.put(id, encoded) } } finally { encoded.fill(0) }
            }
            commitConfiguration()
        } catch (failure: Exception) {
            try {
                if (previous == null) secrets.remove(id) else secrets.put(id, previous)
            } catch (_: Exception) {
                throw IllegalStateException("SSH 配置保存失败，凭据需重新配置")
            }
            if (failure is CancellationException) throw CancellationException("SSH 配置保存已取消")
            throw IllegalStateException("SSH 配置保存失败")
        } finally {
            previous?.fill(0)
        }
    }

    private inline fun <T> guarded(message: String, block: () -> T): T = try {
        block()
    } catch (_: CancellationException) {
        throw CancellationException("SSH 凭据操作已取消")
    } catch (_: Exception) {
        // 底层实现的消息和 cause 可能携带输入值，不向调用方传播。
        throw IllegalStateException(message)
    }

    private companion object {
        const val MAGIC = 0x4e585348 // NXSH
        const val VERSION = 1
        const val BINDING_BYTES = 32
        const val HEADER_BYTES = 2 * Int.SIZE_BYTES + BINDING_BYTES
    }
}

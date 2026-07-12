package com.promenar.nexara.data.security

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** SecretStore 中 WebDAV 运行时凭据的单记录事实源。 */
class WebDavAuthRecord(
    val endpoint: String,
    val username: String,
    val passwordBytes: ByteArray,
) : AutoCloseable {
    override fun close() = passwordBytes.fill(0)
}

object WebDavAuthRecordCodec {
    private const val MAGIC = 0x4e584157 // NXAW
    private const val VERSION = 1
    const val MAX_RECORD_BYTES = 16 * 1024
    private const val MAX_ENDPOINT_BYTES = 4096
    private const val MAX_USERNAME_BYTES = 2048
    private const val MAX_PASSWORD_BYTES = 4096
    private const val HEADER_BYTES = 5 * Int.SIZE_BYTES

    fun encode(endpoint: String, username: String, password: CharArray?): ByteArray {
        var endpointBytes = ByteArray(0)
        var usernameBytes = ByteArray(0)
        var passwordBytes = ByteArray(0)
        try {
            endpointBytes = encodeStrict(endpoint)
            usernameBytes = encodeStrict(username)
            passwordBytes = password?.let(::encodeStrict) ?: ByteArray(0)
            require(endpointBytes.size <= MAX_ENDPOINT_BYTES) { "WebDAV 地址过长" }
            require(usernameBytes.size <= MAX_USERNAME_BYTES) { "WebDAV 用户名过长" }
            require(passwordBytes.size <= MAX_PASSWORD_BYTES) { "WebDAV 密码过长" }
            val size = HEADER_BYTES + endpointBytes.size + usernameBytes.size + passwordBytes.size
            require(size <= MAX_RECORD_BYTES) { "WebDAV 认证记录过大" }
            return ByteBuffer.allocate(size)
                .putInt(MAGIC)
                .putInt(VERSION)
                .putInt(endpointBytes.size)
                .putInt(usernameBytes.size)
                .putInt(passwordBytes.size)
                .put(endpointBytes)
                .put(usernameBytes)
                .put(passwordBytes)
                .array()
        } finally {
            endpointBytes.fill(0)
            usernameBytes.fill(0)
            passwordBytes.fill(0)
        }
    }

    fun decode(encoded: ByteArray): WebDavAuthRecord {
        require(encoded.size in HEADER_BYTES..MAX_RECORD_BYTES) { "WebDAV 认证记录大小无效" }
        try {
            val buffer = ByteBuffer.wrap(encoded)
            require(buffer.int == MAGIC && buffer.int == VERSION) { "WebDAV 认证记录版本无效" }
            val endpointLength = buffer.int
            val usernameLength = buffer.int
            val passwordLength = buffer.int
            require(endpointLength in 0..MAX_ENDPOINT_BYTES && usernameLength in 0..MAX_USERNAME_BYTES &&
                passwordLength in 0..MAX_PASSWORD_BYTES &&
                buffer.remaining() == endpointLength + usernameLength + passwordLength
            ) { "WebDAV 认证记录长度无效" }
            val endpointBytes = ByteArray(endpointLength).also(buffer::get)
            val usernameBytes = ByteArray(usernameLength).also(buffer::get)
            val passwordBytes = ByteArray(passwordLength).also(buffer::get)
            var transferred = false
            try {
                validateStrictUtf8(passwordBytes)
                return WebDavAuthRecord(
                    decodeStrict(endpointBytes),
                    decodeStrict(usernameBytes),
                    passwordBytes,
                ).also { transferred = true }
            } finally {
                endpointBytes.fill(0)
                usernameBytes.fill(0)
                if (!transferred) passwordBytes.fill(0)
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("WebDAV 认证记录损坏", error)
        }
    }

    private fun encodeStrict(value: String): ByteArray = encodeStrict(CharBuffer.wrap(value))
    private fun encodeStrict(value: CharArray): ByteArray = encodeStrict(CharBuffer.wrap(value))

    private fun encodeStrict(chars: CharBuffer): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(chars)
        return try {
            ByteArray(buffer.remaining()).also(buffer::get)
        } finally {
            if (buffer.hasArray()) buffer.array().fill(0)
        }
    }

    private fun decodeStrict(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun validateStrictUtf8(bytes: ByteArray) {
        val chars = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        // 只做严格编码验证，不创建不可擦除的 String。
        for (index in 0 until chars.limit()) chars.put(index, '\u0000')
    }
}

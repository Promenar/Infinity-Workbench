package com.promenar.nexara.data.security

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WebDavAuthRecordCodecTest {
    @Test
    fun `versioned record round trips strict UTF-8 and decoder owns password copy`() {
        val password = "密码🔐".toCharArray()
        val encoded = WebDavAuthRecordCodec.encode("https://例子.invalid/路径", "用户", password)
        password.fill('\u0000')

        val decoded = WebDavAuthRecordCodec.decode(encoded)
        encoded.fill(0)
        decoded.use {
            assertThat(it.endpoint).isEqualTo("https://例子.invalid/路径")
            assertThat(it.username).isEqualTo("用户")
            assertThat(it.passwordBytes.toString(Charsets.UTF_8)).isEqualTo("密码🔐")
        }
        assertThat(decoded.passwordBytes).isEqualTo(ByteArray(decoded.passwordBytes.size))
    }

    @Test
    fun `truncated unknown version invalid UTF-8 and trailing bytes fail closed`() {
        val valid = WebDavAuthRecordCodec.encode("https://dav.invalid/", "u", "p".toCharArray())
        assertThrows<IllegalArgumentException> { WebDavAuthRecordCodec.decode(valid.copyOf(8)) }
        assertThrows<IllegalArgumentException> {
            WebDavAuthRecordCodec.decode(valid.copyOf().also { it[7] = 2 })
        }
        assertThrows<IllegalArgumentException> { WebDavAuthRecordCodec.decode(valid + 0) }

        val invalidUtf8 = valid.copyOf()
        val endpointOffset = 5 * Int.SIZE_BYTES
        invalidUtf8[endpointOffset] = 0xc3.toByte()
        invalidUtf8[endpointOffset + 1] = 0x28
        assertThrows<IllegalArgumentException> { WebDavAuthRecordCodec.decode(invalidUtf8) }
    }

    @Test
    fun `all fields and total record are bounded`() {
        assertThrows<IllegalArgumentException> {
            WebDavAuthRecordCodec.encode("x".repeat(4097), "", null)
        }
        assertThrows<IllegalArgumentException> {
            WebDavAuthRecordCodec.encode("", "u".repeat(2049), null)
        }
        assertThrows<IllegalArgumentException> {
            WebDavAuthRecordCodec.encode("", "", CharArray(4097) { 'p' })
        }
        assertThrows<IllegalArgumentException> {
            WebDavAuthRecordCodec.decode(ByteArray(WebDavAuthRecordCodec.MAX_RECORD_BYTES + 1))
        }
    }
}

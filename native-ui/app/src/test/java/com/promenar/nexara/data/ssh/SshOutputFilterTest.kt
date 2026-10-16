package com.promenar.nexara.data.ssh

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.security.SshCredential
import org.junit.Test

class SshOutputFilterTest {
    @Test fun `凭据与私钥标记重叠时分片正文全部隐藏`() {
        SshOutputFilter(SshCredential(password = "OPENSSH".toByteArray())).use { filter ->
            val stream = filter.stream()
            val output = stream.feed("ok -----BEGIN OPENSSH PR") + stream.feed("IVATE KEY-----\nBODY", end = true)
            assertThat(output).isEqualTo("ok " + "█".repeat("-----BEGIN OPENSSH PRIVATE KEY-----\nBODY".length))
        }
    }

    @Test fun `私钥标记在所有字符边界分片及逐字符输入均隐藏正文`() {
        val markers = listOf("OPENSSH", "RSA", "EC", "ENCRYPTED", "")
        markers.forEach { type ->
            val marker = "-----BEGIN " + type.takeIf(String::isNotEmpty)?.let { "$it " }.orEmpty() + "PRIVATE KEY-----"
            val input = marker + "\nBODY"
            SshOutputFilter(SshCredential(password = type.toByteArray())).use { filter ->
                for (cut in 0..input.length) {
                    val stream = filter.stream()
                    assertThat(stream.feed(input.take(cut)) + stream.feed(input.drop(cut), end = true))
                        .isEqualTo("█".repeat(input.length))
                }
                val stream = filter.stream()
                val output = input.map { stream.feed(it.toString()) }.joinToString("") + stream.feed("", end = true)
                assertThat(output).isEqualTo("█".repeat(input.length))
            }
        }
    }

    @Test fun `相互交叠的凭据按原文匹配并统一遮蔽`() {
        SshOutputFilter(SshCredential(password = "abcdef".toByteArray(), passphrase = "defghi".toByteArray())).use { filter ->
            assertThat(filter.whole("ok abcdefghi end.")).isEqualTo("ok █████████ end.")
            for (cut in 0..9) {
                val stream = filter.stream()
                assertThat(stream.feed("abcdefghi".take(cut)) + stream.feed("abcdefghi".drop(cut), end = true))
                    .isEqualTo("█████████")
            }
        }
    }

    @Test fun `流结束遮蔽与已命中凭据相交的待决尾部`() {
        SshOutputFilter(SshCredential(password = "abc".toByteArray(), passphrase = "bcd".toByteArray())).use { filter ->
            val stream = filter.stream()
            assertThat(stream.feed("abc") + stream.feed("d", end = true)).isEqualTo("████")
            val unfinished = filter.stream()
            assertThat(unfinished.feed("ab") + unfinished.feed("", end = true)).isEqualTo("██")
        }
    }

    @Test fun `密集自重叠的长凭据跨分片仍统一遮蔽`() {
        val input = "b" + "a".repeat(8192)
        SshOutputFilter(SshCredential(password = "a".repeat(512).toByteArray(), passphrase = ("b" + "a".repeat(511)).toByteArray())).use { filter ->
            assertThat(filter.whole(input)).isEqualTo("█".repeat(8193))
            val stream = filter.stream()
            val output = input.chunked(257).joinToString("") { stream.feed(it) } + stream.feed("", end = true)
            assertThat(output).isEqualTo("█".repeat(8193))
        }
    }

    @Test fun `分片与分页均不发布认证口令或其待决前缀`() {
        SshCredential(password = "synthetic-password".toByteArray()).use { credential ->
            SshOutputFilter(credential).use { filter ->
                val stream = filter.stream()
                assertThat(stream.feed("ok synthetic-")).isEqualTo("ok ")
                val tail = stream.feed("password end", end = true)
                assertThat(tail).doesNotContain("password")
                assertThat(tail).endsWith(" end")
                assertThat(filter.whole("synthetic-")).doesNotContain("synthetic-")
            }
        }
    }
    @Test fun `私钥标记分片及末尾截断也隐藏正文`() {
        SshOutputFilter(SshCredential()).use { filter ->
            val stream = filter.stream()
            assertThat(stream.feed("hello -----BEGIN OPENSSH PR")).isEqualTo("hello ")
            assertThat(stream.feed("IVATE KEY-----\nopaque-private-body", end = true)).doesNotContain("opaque-private-body")
        }
    }
    @Test fun `过滤材料独立于调用方清零与后续认证更新关闭后拒绝发布原文`() {
        val credential = SshCredential(password = "synthetic-password".toByteArray())
        val filter = SshOutputFilter(credential)
        credential.close()
        assertThat(filter.whole("synthetic-password")).doesNotContain("synthetic-password")
        filter.close()
        assertThat(filter.whole("arbitrary remote text")).doesNotContain("remote")
    }
}

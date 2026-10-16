package com.promenar.nexara.data.ssh

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.security.SshCredential
import org.junit.Test

class SshOutputFilterTest {
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

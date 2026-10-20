package com.promenar.nexara.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.media.MediaFetchUrlPolicy
import org.junit.Test

class MediaFetchUrlPolicyTest {
    private val policy = MediaFetchUrlPolicy { it == "main" || it == "main.private.test" }
    @Test fun `允许https以及tailnet域名IP和回环明文`() {
        listOf("https://example.test/a", "http://main/a", "http://main.private.test/a", "http://main.tail.ts.net/a", "http://100.64.0.1/a", "http://100.127.255.255/a", "http://[fd7a:115c:a1e0::1]/a", "http://127.0.0.1/a", "http://127.1.2.3/a", "http://localhost/a", "http://[::1]/a").forEach {
            assertThat(policy.validate(it)).isNull()
        }
    }
    @Test fun `拒绝其它明文scheme凭据和无效URL`() {
        listOf("http://example.test/a", "http://100.63.255.255/a", "http://100.128.0.0/a", "http://[fd7a:115c:a1e1::1]/a", "http://192.168.1.2/a", "file:///a", "data:image/png;base64,abc", "ftp://example.test/a", "https://name:password@example.test/a", "https://name@example.test/a", "https://example.test:99999/a", "https:///a", "example.test").forEach {
            assertThat(policy.validate(it)).isNotNull()
        }
    }
    @Test fun `https重定向只可降级到tailnet且每跳仍校验`() {
        assertThat(policy.validate("http://main/a", "https://example.test/a")).isNull()
        assertThat(policy.validate("http://127.0.0.1/a", "https://example.test/a")).isNotNull()
        assertThat(policy.validate("http://example.test/a", "https://example.test/a")).isNotNull()
        assertThat(policy.validate("https://name@main/a", "https://example.test/a")).isNotNull()
    }
    @Test fun `离线时短节点名仍标识为tailnet避免直连泄漏`() {
        val offline = MediaFetchUrlPolicy { false }
        assertThat(offline.isTailnet("main")).isTrue()
        assertThat(offline.isTailnet("localhost")).isFalse()
        assertThat(offline.isTailnet("127.0.0.1")).isFalse()
    }
}

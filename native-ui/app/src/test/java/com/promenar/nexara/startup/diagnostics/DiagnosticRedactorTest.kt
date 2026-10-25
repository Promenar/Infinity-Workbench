package com.promenar.nexara.startup.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun `凭据与各种正文形态默认整段省略`() {
        val privateValues = listOf(
            "Authorization: Bearer eyJ-secret-jwt",
            "Proxy-Authorization: Basic dXNlcjpwYXNzd29yZA==",
            "Cookie: session=private-cookie; user=private-user",
            "Set-Cookie: session=private-cookie",
            "api_key=sk-proj-private-openai-key",
            "x-api-key: sk-ant-private-anthropic-key",
            "token=ghp_private-github-token",
            "AKIAABCDEFGHIJKLMNOP",
            "AIzaSyPrivateGoogleKey1234567890",
            "https://user:private-password@example.com/path?api_key=private-query#private-fragment",
            "{\"headers\":{\"Authorization\":\"private-header\"},\"body\":{\"nested\":[\"private-body\"]}}",
            "{\"message\":\"private-user-message\",\"response\":\"private-response\",\"content\":\"private-file-content\"}",
            "/data/user/0/com.promenar.nexara/files/private-absolute-name.txt",
            "/Users/private-user/private-file.txt",
            "C:\\Users\\private-user\\private-file.txt",
            "纯文本用户消息没有任何敏感字段标识",
            "PlainAlphanumericPrivateSecret123456",
            "多字节正文🔐".repeat(10_000),
        )

        privateValues.forEach { value ->
            assertThat(DiagnosticRedactor.redact(value)).isEqualTo("[内容已隐藏]")
        }
    }

    @Test
    fun `只保留完整匹配的固定安全恢复消息`() {
        val safeMessage = "移动类 journal 文件布局冲突"

        val fixedReasons = listOf(
            safeMessage, "PREPARED journal 文件布局冲突", "DB_COMMITTED journal 同时存在 source 与 target",
            "工作区 tombstone 恢复存在冲突", "多个工作区根记录指向同一规范物理目录",
            "多个 Session 指向同一规范工作区根", "工作区根不能是符号链接", "会话工作区绑定已变化",
            "会话根身份或路径不一致", "会话根路径不一致", "工作区数据库父子关系不一致",
            "会话 journal 或数据库状态发生变化，未完成安全放弃",
        )
        fixedReasons.forEach { reason ->
            assertThat(DiagnosticRedactor.redact(reason)).isEqualTo(reason)
            assertThat(DiagnosticRedactor.redact("$reason private-body")).isEqualTo("[内容已隐藏]")
        }
        assertThat(DiagnosticRedactor.redact("$safeMessage private-body")).isEqualTo("[内容已隐藏]")
        assertThat(DiagnosticRedactor.redact("private-body\n$safeMessage")).isEqualTo("[内容已隐藏]")
        assertThat(DiagnosticRedactor.summary(IllegalStateException(safeMessage)))
            .isEqualTo("IllegalStateException: 移动类 journal 文件布局冲突")
    }

    @Test
    fun `异常摘要保留类型但不展开异常正文或cause正文`() {
        val error = IllegalStateException("private-body", java.io.IOException("private-cause"))

        assertThat(DiagnosticRedactor.summary(error)).isEqualTo("IllegalStateException: [内容已隐藏]")
        assertThat(DiagnosticRedactor.safeIdentifier("private-body"))
            .doesNotContain("private-body")
        assertThat(DiagnosticRedactor.safeIdentifier("private-body"))
            .isEqualTo(DiagnosticRedactor.safeIdentifier("private-body"))
        assertThat(DiagnosticRedactor.safeIdentifier("private-body"))
            .isNotEqualTo(DiagnosticRedactor.safeIdentifier("other-body"))
    }
}

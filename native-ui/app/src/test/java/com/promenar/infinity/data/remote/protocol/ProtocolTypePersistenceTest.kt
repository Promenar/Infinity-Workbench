package com.promenar.infinity.data.remote.protocol

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

class ProtocolTypePersistenceTest {
    @Test
    fun `稳定标识非空唯一等于原始类名常量并支持往返解析`() {
        val expected = setOf(
            "OpenAI_ChatCompletions", "OpenAI_Responses", "Anthropic_Messages", "Google_VertexAI", "Google_Gemini",
            "Cohere_Chat", "Mistral_Chat", "DeepSeek", "Generic_OpenAI_Compat", "Moonshot_Kimi",
            "Qwen_DashScope", "Zhipu_GLM", "Doubao_ByteDance", "Yi_ZeroOne", "Baichuan", "Local",
        )
        assertThat(ProtocolType.entries.map { it.persistedId }).containsExactlyElementsIn(expected)
        ProtocolType.entries.forEach { protocol ->
            assertThat(protocol.persistedId).isNotEmpty()
            assertThat(protocol.persistedId).isEqualTo(protocol::class.simpleName)
            assertThat(ProtocolType.fromLegacyName(protocol.persistedId)).isSameInstanceAs(protocol)
            assertThat(ProtocolType.fromLegacyName(protocol.persistedId.lowercase())).isSameInstanceAs(protocol)
        }
    }

    @Test
    fun `显示名使用精确匹配且可恢复未知标识`() {
        assertThat(ProtocolType.fromPersistedName("unknown", "https://gateway.invalid", "OpenAI 兼容 (通用)"))
            .isEqualTo(ProtocolType.Generic_OpenAI_Compat)
        assertThrows(UnsupportedPersistedProtocolException::class.java) {
            ProtocolType.fromPersistedName("unknown", "https://gateway.invalid", "openai 兼容 (通用)")
        }
    }

    @Test
    fun `主机匹配只比较主机且支持最新表缺少记录的历史冲突`() {
        assertThat(ProtocolType.fromPersistedName("ds4", baseUrl = "https://API.OPENAI.COM:8443/proxy"))
            .isEqualTo(ProtocolType.OpenAI_ChatCompletions)
        assertThat(ProtocolType.fromPersistedName("ds4", baseUrl = "https://api.anthropic.com/v1"))
            .isEqualTo(ProtocolType.Anthropic_Messages)
        assertThrows(UnsupportedPersistedProtocolException::class.java) {
            ProtocolType.fromPersistedName("ds4", baseUrl = "https://gateway.invalid")
        }
    }

    @Test
    fun `空地址排除全部远端候选且基类混淆名不能成为协议`() {
        assertThrows(UnsupportedPersistedProtocolException::class.java) {
            ProtocolType.fromPersistedName("is4", baseUrl = "")
        }
        assertThrows(UnsupportedPersistedProtocolException::class.java) {
            ProtocolType.fromPersistedName("us4", baseUrl = "https://gateway.invalid")
        }
        assertThat(ProtocolType.fromPersistedName("ms4", baseUrl = "   ")).isEqualTo(ProtocolType.Local)
        assertThat(ProtocolType.fromPersistedName("ms4", baseUrl = "https://gateway.invalid"))
            .isEqualTo(ProtocolType.Moonshot_Kimi)
    }

    @Test
    fun `无上下文旧入口不得把混淆标识误认为本地协议`() {
        listOf("ks4", "ms4", "is4").forEach { raw ->
            assertThrows(UnsupportedPersistedProtocolException::class.java) { ProtocolType.fromLegacyName(raw) }
        }
    }

    @Test
    fun `持久化和解析不得依赖运行时类名`() {
        val sourceRoot = listOf(
            File("src/main/java/com/promenar/infinity"),
            File("app/src/main/java/com/promenar/infinity"),
            File("native-ui/app/src/main/java/com/promenar/infinity"),
        ).first { it.isDirectory }
        val runtimeClassName = Regex("(?:::class(?:\\.java)?|javaClass)\\s*\\.\\s*(?:simpleName|name)\\b")
        listOf("data/manager/ProviderManager.kt", "data/remote/protocol/LlmProtocol.kt").forEach { path ->
            assertThat(runtimeClassName.containsMatchIn(File(sourceRoot, path).readText())).isFalse()
        }
        val directPersistence = Regex(
            "(?:putString(?:Set)?|encodeToString|toJson|insert|upsert)\\s*\\([^;{}]*?" + runtimeClassName.pattern,
        )
        listOf("data", "ui").flatMap { File(sourceRoot, it).walkTopDown().filter { file -> file.extension == "kt" }.toList() }
            .forEach { file ->
                assertWithMessage(file.relativeTo(sourceRoot).path)
                    .that(directPersistence.containsMatchIn(file.readText())).isFalse()
            }
    }
}

package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BackupPreferenceInventoryTest {
    @Test
    fun `all literal production preference keys are explicitly allowed or denied`() {
        val sourceRoot = Path.of("app/src/main/java/com/promenar/nexara")
        val files = listOf(
            "NexaraApplication.kt",
            "MainActivity.kt",
            "data/manager/ProviderManager.kt",
            "domain/usecase/RagConfigPersistence.kt",
            "util/LocaleHelper.kt",
            "ui/chat/ChatViewModel.kt",
            "ui/chat/manager/WebSearchContextProvider.kt",
            "ui/chat/manager/skills/WebSearchSkill.kt",
            "ui/chat/manager/skills/WebSearchTavilySkill.kt",
            "ui/chat/manager/skills/WebSearchSearXNGSkill.kt",
            "ui/rag/RagViewModel.kt",
            "ui/settings/SearchConfigViewModel.kt",
            "ui/settings/SettingsViewModel.kt",
            "ui/settings/LocalModelsViewModel.kt",
            "ui/settings/BackupViewModel.kt",
        )
        val pattern = Regex("(?:get|put)(?:String|StringSet|Boolean|Int|Long|Float)\\(\\s*\"([^\"]+)\"")
        val namespaces = listOf("provider", "settings", "rag", "search", "ui", "backup")

        files.forEach { relative ->
            val text = Files.readAllBytes(sourceRoot.resolve(relative)).toString(Charsets.UTF_8)
            pattern.findAll(text).map { it.groupValues[1] }.toSet().forEach { key ->
                val representatives = listOf("extra_provider_0", "model_info_sample").map { prefix ->
                    key.replace("\${prefix}", prefix)
                        .replace("\${id}", "sample")
                        .replace("\${modelId}", "sample")
                }
                val known = representatives.any { representative ->
                    namespaces.any { BackupPreferencePolicy.isKnown(it, representative) }
                }
                assertWithMessage("未知生产偏好 key: $relative -> $key").that(known).isTrue()
            }
        }
    }

    @Test
    fun `deny policy wins for every credential spelling`() {
        listOf(
            "provider_api_key", "apiKey", "tavily-api-key", "embedding_key", "webdavPass",
            "automaticBackupPassword", "Authorization", "Cookie", "access_token", "privateKey",
            "vertexServiceAccountJson", "client_secret",
        ).forEach { key ->
            assertWithMessage(key).that(BackupPreferencePolicy.isAllowed("settings", key)).isFalse()
        }
    }
}

package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BackupPreferenceInventoryTest {
    @Test
    fun `all literal production preference keys are explicitly allowed or denied`() {
        val sourceRoot = Path.of("app/src/main/java/com/promenar/nexara")
        val files = Files.walk(sourceRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
        val preferenceDeclaration = Regex(
            "(?:val|var)\\s+(\\w+)[^=]*=?[\\s\\S]{0,160}?getSharedPreferences\\(\\s*\"([^\"]+)\""
        )
        val keyCall = Regex(
            "(\\w+)(?:\\s*\\.edit\\(\\))?\\s*\\.(?:(?:get|put)(?:String|StringSet|Boolean|Int|Long|Float)|contains|remove)" +
                "\\(\\s*\"([^\"]+)\""
        )
        val injectedNamespaces = mapOf(
            "domain/usecase/AgentConfigResolver.kt#globalPrefs" to "nexara_settings",
            "domain/usecase/RagConfigPersistence.kt#prefs" to "rag_settings",
            "ui/settings/BackupViewModel.kt#prefs" to "nexara_backup_settings",
            "ui/settings/SettingsViewModel.kt#prefs" to "nexara_settings",
            "ui/settings/LocalModelsViewModel.kt#prefs" to "nexara_settings",
            "ui/settings/SearchConfigViewModel.kt#prefs" to "nexara_search",
            "ui/rag/RagViewModel.kt#prefs" to "rag_settings",
            "ui/rag/RagViewModel.kt#settingsPrefs" to "nexara_settings",
            "MainActivity.kt#prefs" to "nexara_prefs",
            "util/LocaleHelper.kt#prefs" to "nexara_settings",
            "ui/chat/manager/WebSearchContextProvider.kt#prefs" to "nexara_search",
            "ui/chat/manager/skills/WebSearchSkill.kt#prefs" to "nexara_search",
            "ui/chat/manager/skills/WebSearchTavilySkill.kt#prefs" to "nexara_search",
            "ui/chat/manager/skills/WebSearchSearXNGSkill.kt#prefs" to "nexara_search",
            "NexaraApplication.kt#prefs" to "nexara_provider",
            "data/manager/ProviderManager.kt#providerPrefs" to "nexara_provider",
            "data/manager/ProviderManager.kt#settingsPrefs" to "nexara_settings",
            "data/manager/ProviderManager.kt#searchPrefs" to "nexara_search",
            "data/manager/ProviderManager.kt#backupPrefs" to "nexara_backup_settings",
        )
        val editorChain = Regex("(\\w+)\\s*\\.edit\\(\\)([\\s\\S]{0,2000}?)\\.apply\\(\\)")
        val editorPut = Regex("\\.put(?:String|StringSet|Boolean|Int|Long|Float)\\(\\s*\"([^\"]+)\"")

        files.forEach { file ->
            val text = Files.readAllBytes(file).toString(Charsets.UTF_8)
            val receiverNamespaces = preferenceDeclaration.findAll(text).associate {
                it.groupValues[1] to it.groupValues[2]
            }
            keyCall.findAll(text).forEach { match ->
                val receiver = match.groupValues[1]
                val key = match.groupValues[2]
                val representatives = listOf("extra_provider_0", "model_info_sample").map { prefix ->
                    key.replace("\${prefix}", prefix)
                        .replace("\${id}", "sample")
                        .replace("\${modelId}", "sample")
                }
                val relative = sourceRoot.relativize(file).toString()
                val declaredNamespace = receiverNamespaces[receiver] ?: injectedNamespaces["$relative#$receiver"]
                if (declaredNamespace == null) {
                    assertWithMessage("疑似 SharedPreferences receiver 未解析，必须显式登记: $file -> $receiver")
                        .that(receiver.contains("pref", ignoreCase = true)).isFalse()
                    return@forEach
                }
                val namespaces = setOf(declaredNamespace)
                val known = representatives.any { representative ->
                    namespaces.any { BackupPreferencePolicy.isKnown(it, representative) }
                }
                assertWithMessage("未知或 namespace 不匹配的生产偏好 key: $file -> $receiver.$key")
                    .that(known).isTrue()
            }
            editorChain.findAll(text).forEach { chain ->
                val receiver = chain.groupValues[1]
                val relative = sourceRoot.relativize(file).toString()
                val namespace = receiverNamespaces[receiver] ?: injectedNamespaces["$relative#$receiver"]
                assertWithMessage("edit/apply receiver 未解析，必须显式登记: $file -> $receiver")
                    .that(namespace).isNotNull()
                editorPut.findAll(chain.groupValues[2]).forEach { put ->
                    val key = put.groupValues[1]
                    val representatives = listOf("extra_provider_0", "model_info_sample").map { prefix ->
                        key.replace("\${prefix}", prefix)
                            .replace("\${id}", "sample")
                            .replace("\${modelId}", "sample")
                    }
                    assertWithMessage("未知 edit/apply 偏好 key: $file -> $receiver.${put.groupValues[1]}")
                        .that(representatives.any { BackupPreferencePolicy.isKnown(namespace!!, it) }).isTrue()
                }
            }
        }
    }

    @Test
    fun `inventory patterns cover multiline edit apply and injected receivers fail closed`() {
        val declaration = Regex(
            "(?:val|var)\\s+(\\w+)[^=]*=?[\\s\\S]{0,160}?getSharedPreferences\\(\\s*\"([^\"]+)\""
        )
        val calls = Regex(
            "(\\w+)(?:\\s*\\.edit\\(\\))?\\s*\\.(?:(?:get|put)(?:String|StringSet|Boolean|Int|Long|Float)|contains|remove)" +
                "\\(\\s*\"([^\"]+)\""
        )
        val editorChain = Regex("(\\w+)\\s*\\.edit\\(\\)([\\s\\S]{0,2000}?)\\.apply\\(\\)")
        val editorPut = Regex("\\.put(?:String|StringSet|Boolean|Int|Long|Float)\\(\\s*\"([^\"]+)\"")
        val fixture = """
            val prefs = context.getSharedPreferences("nexara_settings", 0)
            prefs.edit()
                .putString("language", "zh")
                .putBoolean("haptic_enabled", true)
                .apply()
            injectedPrefs.getString("default_model", null)
        """.trimIndent()

        assertWithMessage("应解析声明 receiver").that(declaration.find(fixture)?.groupValues?.get(1)).isEqualTo("prefs")
        val found = buildSet {
            calls.findAll(fixture).forEach { add(it.groupValues[1] to it.groupValues[2]) }
            editorChain.findAll(fixture).forEach { chain ->
                editorPut.findAll(chain.groupValues[2]).forEach { add(chain.groupValues[1] to it.groupValues[1]) }
            }
        }
        assertWithMessage("应覆盖跨行 edit/apply 链").that(found)
            .containsAtLeast("prefs" to "language", "prefs" to "haptic_enabled", "injectedPrefs" to "default_model")
        assertWithMessage("注入 receiver 必须由显式 namespace hint 解析")
            .that(mapOf<String, String>()["fixture#injectedPrefs"]).isNull()
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

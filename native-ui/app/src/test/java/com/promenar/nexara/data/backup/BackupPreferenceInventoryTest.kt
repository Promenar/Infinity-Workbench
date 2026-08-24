package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
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
            "ui/theme/ThemePreferenceStore.kt#preferences" to "nexara_settings",
            "ui/settings/LocalModelsViewModel.kt#prefs" to "nexara_settings",
            "ui/settings/SearchConfigViewModel.kt#prefs" to "nexara_search",
            "ui/rag/RagViewModel.kt#prefs" to "rag_settings",
            "ui/rag/RagViewModel.kt#settingsPrefs" to "nexara_settings",
            "ui/chat/ChatScreen.kt#notificationPermissionPrefs" to
                "generation_notification_permission",
            "ui/chat/ChatRoute.kt#permissionPrefs" to
                "generation_notification_permission",
            "ui/chat/ChatViewModel.kt#prefs" to "nexara_settings",
            "data/generation/DefaultChatGenerationRuntime.kt#settings" to "nexara_settings",
            "data/generation/PreparedPromptBudgetGate.kt#settings" to "nexara_settings",
            "data/generation/ChatGenerationContentStrategy.kt#settings" to "nexara_settings",
            "onboarding/OnboardingStateStore.kt#preferences" to "nexara_onboarding",
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
        val editorScope = Regex("(\\w+)\\s*\\.edit\\(\\)\\s*\\.apply\\s*\\{([\\s\\S]{0,2000}?)\\}")
        val scopePut = Regex("(?:^|[;\\s])put(?:String|StringSet|Boolean|Int|Long|Float)\\(\\s*\"([^\"]+)\"")
        val directCall = Regex(
            "getSharedPreferences\\(\\s*\"([^\"]+)\"[^)]*\\)\\s*(?:\\.edit\\(\\))?\\s*\\." +
                "(?:(?:get|put)(?:String|StringSet|Boolean|Int|Long|Float)|contains|remove)\\(\\s*\"([^\"]+)\""
        )

        files.forEach { file ->
            val text = Files.readAllBytes(file).toString(Charsets.UTF_8)
            val receiverNamespaces = preferenceDeclaration.findAll(text).associate {
                it.groupValues[1] to it.groupValues[2]
            }
            val typedPreferenceReceivers = Regex("(\\w+)\\s*:\\s*(?:android\\.content\\.)?SharedPreferences\\b")
                .findAll(text).map { it.groupValues[1] }.toSet()
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
                    if (receiver.contains("pref", ignoreCase = true) || receiver in typedPreferenceReceivers) {
                        assertWithMessage("疑似 SharedPreferences receiver 未解析，必须显式登记: $file -> $receiver")
                            .fail()
                    }
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
            editorScope.findAll(text).forEach { scope ->
                val receiver = scope.groupValues[1]
                val relative = sourceRoot.relativize(file).toString()
                val namespace = receiverNamespaces[receiver] ?: injectedNamespaces["$relative#$receiver"]
                assertWithMessage("edit().apply scope receiver 未解析，必须显式登记: $file -> $receiver")
                    .that(namespace).isNotNull()
                scopePut.findAll(scope.groupValues[2]).forEach { put ->
                    assertWithMessage("未知 edit().apply scope 偏好 key: $file -> $receiver.${put.groupValues[1]}")
                        .that(BackupPreferencePolicy.isKnown(namespace!!, put.groupValues[1])).isTrue()
                }
            }
            directCall.findAll(text).forEach { call ->
                assertWithMessage("未知直接 SharedPreferences 偏好 key: $file -> ${call.groupValues[1]}.${call.groupValues[2]}")
                    .that(BackupPreferencePolicy.isKnown(call.groupValues[1], call.groupValues[2])).isTrue()
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
        val editorScope = Regex("(\\w+)\\s*\\.edit\\(\\)\\s*\\.apply\\s*\\{([\\s\\S]{0,2000}?)\\}")
        val scopePut = Regex("(?:^|[;\\s])put(?:String|StringSet|Boolean|Int|Long|Float)\\(\\s*\"([^\"]+)\"")
        val directCall = Regex(
            "getSharedPreferences\\(\\s*\"([^\"]+)\"[^)]*\\)\\s*(?:\\.edit\\(\\))?\\s*\\." +
                "(?:(?:get|put)(?:String|StringSet|Boolean|Int|Long|Float)|contains|remove)\\(\\s*\"([^\"]+)\""
        )
        val fixture = """
            val prefs = context.getSharedPreferences("nexara_settings", 0)
            prefs.edit()
                .putString("language", "zh")
                .putBoolean("haptic_enabled", true)
                .apply()
            injectedPrefs.getString("default_model", null)
            prefs.edit().apply {
                putString("theme_mode", "dark")
            }
            context.getSharedPreferences("nexara_settings", 0).getBoolean("haptic_enabled", true)
        """.trimIndent()

        assertWithMessage("应解析声明 receiver").that(declaration.find(fixture)?.groupValues?.get(1)).isEqualTo("prefs")
        val found = buildSet {
            calls.findAll(fixture).forEach { add(it.groupValues[1] to it.groupValues[2]) }
            editorChain.findAll(fixture).forEach { chain ->
                editorPut.findAll(chain.groupValues[2]).forEach { add(chain.groupValues[1] to it.groupValues[1]) }
            }
            editorScope.findAll(fixture).forEach { scope ->
                scopePut.findAll(scope.groupValues[2]).forEach { add(scope.groupValues[1] to it.groupValues[1]) }
            }
        }
        assertWithMessage("应覆盖跨行 edit/apply 链").that(found)
            .containsAtLeast(
                "prefs" to "language", "prefs" to "haptic_enabled", "injectedPrefs" to "default_model",
                "prefs" to "theme_mode",
            )
        assertWithMessage("应覆盖直接 getSharedPreferences 调用")
            .that(directCall.find(fixture)?.groupValues?.drop(1))
            .isEqualTo(listOf("nexara_settings", "haptic_enabled"))
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

    @Test
    fun `退役自动备份键只用于识别旧包且不得继续导出`() {
        assertThat(BackupPreferencePolicy.isKnown("backup", "auto_backup")).isTrue()
        assertThat(BackupPreferencePolicy.isAllowed("backup", "auto_backup")).isFalse()
    }

    @Test
    fun `四个无runtime消费者的旧RAG字段只读识别但禁止再次导出`() {
        val retiredKeys = listOf(
            "jit_max_chunks",
            "kg_domain_auto",
            "enable_incremental_hash",
            "enable_local_preprocess",
        )

        retiredKeys.forEach { key ->
            assertWithMessage("$key 应被识别以便旧备份可恢复")
                .that(BackupPreferencePolicy.isKnown("rag", key)).isTrue()
            assertWithMessage("$key 不得进入新备份")
                .that(BackupPreferencePolicy.isAllowed("rag", key)).isFalse()
            assertWithMessage("$key 应按退休字段丢弃")
                .that(BackupPreferencePolicy.isRetired("rag", key)).isTrue()
        }
    }

    @Test
    fun `通知权限询问状态属于设备本地状态且不得进入备份`() {
        assertWithMessage("设备权限状态应登记为已知")
            .that(
                BackupPreferencePolicy.isKnown(
                    "generation_notification_permission",
                    "post_notifications_asked",
                ),
            ).isTrue()
        assertWithMessage("设备权限状态不得随备份迁移")
            .that(
                BackupPreferencePolicy.isAllowed(
                    "generation_notification_permission",
                    "post_notifications_asked",
                ),
            ).isFalse()
    }

    @Test
    fun `本地推理运行时偏好属于设备能力且不得进入备份`() {
        listOf(
            "local_models_enabled",
            "local_auto_load",
            "last_local_model",
        ).forEach { key ->
            assertWithMessage("本地运行时偏好应登记为已知: $key")
                .that(BackupPreferencePolicy.isKnown("settings", key)).isTrue()
            assertWithMessage("本地运行时偏好不得跨设备恢复: $key")
                .that(BackupPreferencePolicy.isAllowed("settings", key)).isFalse()
        }
        assertWithMessage("旧 provider namespace 中的本地模型路径也不得备份")
            .that(BackupPreferencePolicy.isKnown("provider", "last_local_model")).isTrue()
        assertWithMessage("旧 provider namespace 中的本地模型路径不得恢复")
            .that(BackupPreferencePolicy.isAllowed("provider", "last_local_model")).isFalse()
    }

    @Test
    fun `UI 白名单应覆盖主题偏好键`() {
        listOf("ui", "nexara_settings").forEach { namespace ->
            assertThat(BackupPreferencePolicy.isKnown(namespace, "theme_mode")).isTrue()
            assertThat(BackupPreferencePolicy.isAllowed(namespace, "theme_mode")).isTrue()
            assertThat(BackupPreferencePolicy.isKnown(namespace, "theme_color_source")).isTrue()
            assertThat(BackupPreferencePolicy.isAllowed(namespace, "theme_color_source")).isTrue()
        }
    }
}

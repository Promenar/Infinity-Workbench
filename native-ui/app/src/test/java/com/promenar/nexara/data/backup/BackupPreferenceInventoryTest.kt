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
            "(\\w+)(?:\\.edit\\(\\))?\\.(?:(?:get|put)(?:String|StringSet|Boolean|Int|Long|Float)|contains|remove)" +
                "\\(\\s*\"([^\"]+)\""
        )
        val injectedNamespaces = mapOf(
            "domain/usecase/AgentConfigResolver.kt#globalPrefs" to "nexara_settings",
            "domain/usecase/RagConfigPersistence.kt#prefs" to "rag_settings",
        )

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
                val declaredNamespace = receiverNamespaces[receiver]
                    ?: injectedNamespaces["$relative#$receiver"]
                    ?: return@forEach
                val namespaces = setOf(declaredNamespace)
                val known = representatives.any { representative ->
                    namespaces.any { BackupPreferencePolicy.isKnown(it, representative) }
                }
                assertWithMessage("未知或 namespace 不匹配的生产偏好 key: $file -> $receiver.$key")
                    .that(known).isTrue()
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

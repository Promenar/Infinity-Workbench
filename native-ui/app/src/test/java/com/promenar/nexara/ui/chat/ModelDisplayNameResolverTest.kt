package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ModelInfo
import org.junit.Test

class ModelDisplayNameResolverTest {
    @Test
    fun `稳定 ID 优先使用用户或同步后的精确名称`() {
        val models = listOf(
            modelInfo(
                id = "default::deepseek-v4-flash",
                remoteModelId = "deepseek-v4-flash",
                name = "我的 DeepSeek",
            ),
        )

        assertThat(resolveModelDisplayName("default::deepseek-v4-flash", models))
            .isEqualTo("我的 DeepSeek")
    }

    @Test
    fun `稳定 ID 可按同 provider 与原始远端 ID 命中迁移模型`() {
        val models = listOf(
            modelInfo(
                id = "legacy-local-id",
                providerId = "provider-a",
                remoteModelId = "vendor/model-v2",
                name = "Vendor Model V2",
            ),
        )

        assertThat(resolveModelDisplayName("provider-a::vendor/model-v2", models))
            .isEqualTo("Vendor Model V2")
    }

    @Test
    fun `已存名称为空时使用 catalog 精确友好名称`() {
        val models = listOf(
            modelInfo(
                id = "default::deepseek-v4-flash",
                remoteModelId = "deepseek-v4-flash",
                name = "",
            ),
        )

        assertThat(resolveModelDisplayName("default::deepseek-v4-flash", models))
            .isEqualTo("DeepSeek V4 Flash")
    }

    @Test
    fun `catalog 精确命中提供友好名称`() {
        assertThat(resolveModelDisplayName("default::deepseek-v4-flash", emptyList()))
            .isEqualTo("DeepSeek V4 Flash")
    }

    @Test
    fun `历史模型不存在时回退原始远端 ID 而非稳定前缀或系列泛称`() {
        assertThat(resolveModelDisplayName("deleted::vendor-model-2026-07", emptyList()))
            .isEqualTo("vendor-model-2026-07")
    }

    @Test
    fun `null 与空白 ID 返回空字符串`() {
        assertThat(resolveModelDisplayName(null, emptyList())).isEmpty()
        assertThat(resolveModelDisplayName("  ", emptyList())).isEmpty()
    }

    @Test
    fun `显示名 map 合并会话与消息 ID 去重且每个唯一 ID 只解析一次`() {
        val resolvedIds = mutableListOf<String>()

        val result = resolveModelDisplayNames(
            sessionModelId = "provider::session-model",
            messageModelIds = listOf(
                "provider::message-model",
                "provider::session-model",
                null,
                "",
                "provider::message-model",
            ),
            providerModels = emptyList(),
            displayNameResolver = { modelId, _ ->
                resolvedIds += modelId
                "name:$modelId"
            },
        )

        assertThat(result).containsExactly(
            "provider::session-model",
            "name:provider::session-model",
            "provider::message-model",
            "name:provider::message-model",
        ).inOrder()
        assertThat(resolvedIds).containsExactly(
            "provider::session-model",
            "provider::message-model",
        ).inOrder()
    }

    private fun modelInfo(
        id: String,
        remoteModelId: String,
        name: String,
        providerId: String? = "default",
    ) = ModelInfo(
        name = name,
        id = id,
        description = remoteModelId,
        enabled = true,
        providerId = providerId,
        remoteModelId = remoteModelId,
    )
}

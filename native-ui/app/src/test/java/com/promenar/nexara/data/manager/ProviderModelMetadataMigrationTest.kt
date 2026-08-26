package com.promenar.nexara.data.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.model.mergeResolvedMetadata
import com.promenar.nexara.data.model.migrateLegacyMetadata
import com.promenar.nexara.data.model.toLegacySupportedCapabilities
import com.promenar.nexara.data.model.toLegacyType
import com.promenar.nexara.data.model.withRecordedUserEdits
import com.promenar.nexara.data.model.catalog.ModelCapability
import com.promenar.nexara.data.model.catalog.ModelWorkload
import com.promenar.nexara.data.model.catalog.ResolvedModelMetadata
import com.promenar.nexara.data.model.catalog.SupportState
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderModelMetadataMigrationTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        listOf("nexara_provider", "nexara_settings").forEach {
            app.getSharedPreferences(it, 0).edit().clear().commit()
        }
    }

    @Test
    fun `新 ModelInfo 默认保持 unknown 且不猜测能力和 token`() {
        val model = ModelInfo(
            name = "future-model",
            id = "provider::future-model",
            description = "",
            enabled = false,
        )

        assertThat(model.type).isEqualTo("unknown")
        assertThat(model.contextLength).isEqualTo(0)
        assertThat(model.maxOutputTokens).isEqualTo(0)
        assertThat(model.capabilities).isEmpty()
        assertThat(model.chatEndpointCompatible).isEqualTo(SupportState.UNKNOWN)
    }

    @Test
    fun `解析元数据按 workload 和 SUPPORTED 能力映射兼容字段`() {
        val reasoning = resolved(
            workload = ModelWorkload.GENERATIVE_TEXT,
            capabilities = mapOf(
                ModelCapability.REASONING to SupportState.SUPPORTED,
                ModelCapability.STRUCTURED_OUTPUT to SupportState.SUPPORTED,
                ModelCapability.VISION_INPUT to SupportState.UNKNOWN,
            ),
        )
        val unknown = resolved(
            workload = ModelWorkload.UNKNOWN,
            capabilities = emptyMap(),
        )

        assertThat(reasoning.toLegacyType()).isEqualTo("reasoning")
        assertThat(reasoning.toLegacySupportedCapabilities())
            .containsExactly("chat", "reasoning", "structuredoutput")
        assertThat(unknown.toLegacyType()).isEqualTo("unknown")
        assertThat(unknown.toLegacySupportedCapabilities()).isEmpty()
    }

    @Test
    fun `用户编辑名称时目录只保护名称并刷新其它自动字段`() {
        val stored = model(
            name = "我的 DeepSeek",
            type = "chat",
            contextLength = 64000,
            capabilities = listOf("chat"),
            maxOutputTokens = 0,
            userEditedFields = setOf("name"),
        )

        val migrated = stored.mergeResolvedMetadata(resolved())

        assertThat(migrated.name).isEqualTo("我的 DeepSeek")
        assertThat(migrated.type).isEqualTo("reasoning")
        assertThat(migrated.contextLength).isEqualTo(1_000_000)
        assertThat(migrated.capabilities).containsExactly("chat", "reasoning", "structuredoutput")
        assertThat(migrated.maxOutputTokens).isEqualTo(384_000)
        assertThat(migrated.knowledgeCutoff).isEqualTo("2025-05")
    }

    @Test
    fun `resolver 端点未知时保留已持久化的合法明确状态`() {
        val stored = model(chatEndpointCompatible = SupportState.SUPPORTED)

        val migrated = stored.mergeResolvedMetadata(resolved())

        assertThat(migrated.chatEndpointCompatible).isEqualTo(SupportState.SUPPORTED)
    }

    @Test
    fun `resolver 明确端点状态覆盖已持久化状态`() {
        val stored = model(chatEndpointCompatible = SupportState.SUPPORTED)
        val unsupported = resolved(
            capabilities = mapOf(ModelCapability.CHAT_ENDPOINT to SupportState.UNSUPPORTED),
        )

        val migrated = stored.mergeResolvedMetadata(unsupported)

        assertThat(migrated.chatEndpointCompatible).isEqualTo(SupportState.UNSUPPORTED)
    }

    @Test
    fun `旧自动指纹逐字段识别用户名称而保留其它字段升级资格`() {
        val stored = model(
            name = "我的 DeepSeek",
            type = "chat",
            contextLength = 64000,
            capabilities = listOf("chat"),
            maxOutputTokens = 0,
        )

        val migrated = stored.migrateLegacyMetadata(
            resolved = resolved(),
            storedFieldPresence = setOf("name", "type", "contextLength", "capabilities", "maxOutputTokens"),
        )

        assertThat(migrated.userEditedFields).containsExactly("name")
        assertThat(migrated.name).isEqualTo("我的 DeepSeek")
        assertThat(migrated.type).isEqualTo("reasoning")
        assertThat(migrated.contextLength).isEqualTo(1_000_000)
        assertThat(migrated.maxOutputTokens).isEqualTo(384_000)
    }

    @Test
    fun `旧未知模型自动 8192 上下文不得误判为用户编辑`() {
        val stored = ModelInfo(
            name = "future-model",
            id = "default::future-model",
            description = "",
            enabled = true,
            type = "chat",
            contextLength = 8192,
            capabilities = listOf("chat"),
            providerName = "测试提供商",
            providerId = "default",
            remoteModelId = "future-model",
        )
        val unknown = resolved(
            workload = ModelWorkload.UNKNOWN,
            capabilities = emptyMap(),
        ).copy(
            remoteModelId = "future-model",
            canonicalModelId = null,
            displayName = "future-model",
            familyName = null,
            contextTokens = null,
            outputTokens = null,
            knowledgeCutoff = null,
        )

        val migrated = stored.migrateLegacyMetadata(
            resolved = unknown,
            storedFieldPresence = setOf(
                "name",
                "type",
                "contextLength",
                "capabilities",
                "maxOutputTokens",
            ),
        )

        assertThat(migrated.userEditedFields).isEmpty()
        assertThat(migrated.type).isEqualTo("unknown")
        assertThat(migrated.contextLength).isEqualTo(0)
        assertThat(migrated.capabilities).isEmpty()
    }

    @Test
    fun `新增字段 round trip 非法端点降级并在 null 时清理陈旧 key`() {
        val manager = manager()
        val info = model(
            familyName = "DeepSeek",
            canonicalModelId = "deepseek-v4-flash",
            chatEndpointCompatible = SupportState.SUPPORTED,
            autoMetadataFingerprint = "fp-v1",
            userEditedFields = setOf("name", "capabilities"),
        )
        manager.addModel(info)
        val prefix = "model_info_${info.id}"
        val prefs = app.getSharedPreferences("nexara_settings", 0)

        assertThat(prefs.getString("${prefix}_family", null)).isEqualTo("DeepSeek")
        assertThat(prefs.getString("${prefix}_canonical_id", null)).isEqualTo("deepseek-v4-flash")
        assertThat(prefs.getString("${prefix}_chat_endpoint", null)).isEqualTo("SUPPORTED")
        assertThat(prefs.getString("${prefix}_auto_fingerprint", null)).isEqualTo("fp-v1")
        assertThat(prefs.getStringSet("${prefix}_user_edited_fields", emptySet()))
            .containsExactly("name", "capabilities")

        prefs.edit().putString("${prefix}_chat_endpoint", "BROKEN").commit()
        val invalidEndpointReload = ProviderManager.createForTest(app, MemorySecretStore())
        assertThat(invalidEndpointReload.providerModels.value.single().chatEndpointCompatible)
            .isEqualTo(SupportState.UNKNOWN)

        invalidEndpointReload.replaceModelFromInternalFlow(info.copy(
            familyName = null,
            canonicalModelId = null,
            autoMetadataFingerprint = null,
        ))
        assertThat(prefs.contains("${prefix}_family")).isFalse()
        assertThat(prefs.contains("${prefix}_canonical_id")).isFalse()
        assertThat(prefs.contains("${prefix}_auto_fingerprint")).isFalse()
    }

    @Test
    fun `合法端点状态保存重载和元数据刷新后均保留`() {
        val manager = manager()
        val info = model(
            chatEndpointCompatible = SupportState.SUPPORTED,
            autoMetadataFingerprint = "legacy-fingerprint",
        )
        manager.addModel(info)

        val reloaded = ProviderManager.createForTest(app, MemorySecretStore())
        assertThat(reloaded.providerModels.value.single { it.id == info.id }.chatEndpointCompatible)
            .isEqualTo(SupportState.SUPPORTED)

        reloaded.syncModelMetadata(
            providerId = "default",
            providerName = "测试提供商",
            remoteModelId = "deepseek-v4-flash",
        )

        assertThat(reloaded.providerModels.value.single { it.id == info.id }.chatEndpointCompatible)
            .isEqualTo(SupportState.SUPPORTED)
    }

    @Test
    fun `用户字段记录在连续编辑中单调累积`() {
        val previous = model(userEditedFields = setOf("type"))

        val submitted = previous.copy(
            name = "我的 DeepSeek",
            userEditedFields = emptySet(),
        ).withRecordedUserEdits(previous)

        assertThat(submitted.userEditedFields).containsExactly("type", "name")
    }

    @Test
    fun `手动模型调用ID会应用并在重载后保留`() {
        val manager = manager()
        val original = model()
        manager.addModel(original)
        val displayed = manager.providerModels.value.single()

        manager.applyUserModelUpdate(
            displayed.copy(remoteModelId = "manual-upstream-id")
                .withRecordedUserEdits(displayed),
        )

        val reloaded = ProviderManager.createForTest(app, MemorySecretStore())
        val persisted = reloaded.providerModels.value.single()
        assertThat(persisted.id).isEqualTo(original.id)
        assertThat(persisted.remoteModelId).isEqualTo("manual-upstream-id")
        assertThat(persisted.userEditedFields).contains("remoteModelId")
    }

    @Test
    fun `元数据刷新与用户提交交错时基于最新模型原子合并`() {
        val transformEntered = CountDownLatch(1)
        val allowTransform = CountDownLatch(1)
        val blockOnce = AtomicBoolean(true)
        val concurrentManager = ProviderManager.createForTest(
            app = app,
            secretStore = MemorySecretStore(),
            beforeModelMetadataTransform = {
                if (blockOnce.compareAndSet(true, false)) {
                    transformEntered.countDown()
                    check(allowTransform.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val original = model(userEditedFields = setOf("type"))
        concurrentManager.addModel(original)
        val displayed = concurrentManager.providerModels.value.single()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val refresh = executor.submit<ModelSyncResult> {
                concurrentManager.syncModelMetadata(
                    providerId = "default",
                    providerName = "测试提供商",
                    remoteModelId = "deepseek-v4-flash",
                )
            }
            assertThat(transformEntered.await(5, TimeUnit.SECONDS)).isTrue()

            concurrentManager.applyUserModelUpdate(
                displayed.copy(name = "我的 DeepSeek").withRecordedUserEdits(displayed),
            )
            allowTransform.countDown()
            assertThat(refresh.get(5, TimeUnit.SECONDS)).isEqualTo(ModelSyncResult.UPDATED)

            val merged = concurrentManager.providerModels.value.single()
            assertThat(merged.name).isEqualTo("我的 DeepSeek")
            assertThat(merged.type).isEqualTo("chat")
            assertThat(merged.contextLength).isEqualTo(0)
            assertThat(merged.userEditedFields).containsExactly("type", "name")
        } finally {
            allowTransform.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `元数据刷新与用户提交交错后持久化最终合并状态`() {
        val persistenceEntered = CountDownLatch(1)
        val userPersistenceEntered = CountDownLatch(1)
        val allowPersistence = CountDownLatch(1)
        val blockNextPersistence = AtomicBoolean(false)
        val signalNextPersistence = AtomicBoolean(false)
        val concurrentManager = ProviderManager.createForTest(
            app = app,
            secretStore = MemorySecretStore(),
            beforeModelPersistenceLock = {
                if (signalNextPersistence.compareAndSet(true, false)) {
                    userPersistenceEntered.countDown()
                }
            },
            beforeModelPersistenceApply = {
                if (blockNextPersistence.compareAndSet(true, false)) {
                    persistenceEntered.countDown()
                    check(allowPersistence.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val original = model(userEditedFields = setOf("type"))
        concurrentManager.addModel(original)
        val displayed = concurrentManager.providerModels.value.single()
        blockNextPersistence.set(true)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val refresh = executor.submit<ModelSyncResult> {
                concurrentManager.syncModelMetadata(
                    providerId = "default",
                    providerName = "测试提供商",
                    remoteModelId = "deepseek-v4-flash",
                )
            }
            assertThat(persistenceEntered.await(5, TimeUnit.SECONDS)).isTrue()
            signalNextPersistence.set(true)

            val userUpdate = executor.submit {
                concurrentManager.applyUserModelUpdate(
                    displayed.copy(name = "我的 DeepSeek").withRecordedUserEdits(displayed),
                )
            }
            assertThat(userPersistenceEntered.await(5, TimeUnit.SECONDS)).isTrue()
            allowPersistence.countDown()
            assertThat(refresh.get(5, TimeUnit.SECONDS)).isEqualTo(ModelSyncResult.UPDATED)
            userUpdate.get(5, TimeUnit.SECONDS)

            val reloaded = ProviderManager.createForTest(app, MemorySecretStore())
            val persisted = reloaded.providerModels.value.single()
            assertThat(persisted.name).isEqualTo("我的 DeepSeek")
            assertThat(persisted.type).isEqualTo("chat")
            assertThat(persisted.contextLength).isEqualTo(0)
            assertThat(persisted.userEditedFields).containsExactly("type", "name")
        } finally {
            allowPersistence.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `持久化只保留五个受支持的用户编辑字段`() {
        val manager = manager()
        val info = model(userEditedFields = setOf("name", "unsupported"))

        manager.addModel(info)

        val stored = app.getSharedPreferences("nexara_settings", 0)
            .getStringSet("model_info_${info.id}_user_edited_fields", emptySet())
        assertThat(stored).containsExactly("name")
    }

    @Test
    fun `自定义未知模型不再猜测为 chat 或 8192`() {
        val manager = manager()

        assertThat(manager.addCustomModel("default", "future-model", "")).isTrue()

        val model = manager.providerModels.value.single { it.remoteModelId == "future-model" }
        assertThat(model.type).isEqualTo("unknown")
        assertThat(model.contextLength).isEqualTo(0)
        assertThat(model.maxOutputTokens).isEqualTo(0)
        assertThat(model.capabilities).isEmpty()
    }

    private fun manager(): ProviderManager = ProviderManager.createForTest(app, MemorySecretStore()).also {
        it.updateMainProvider(
            ProtocolType.OpenAI_ChatCompletions,
            "https://provider.invalid",
            CredentialUpdate.Replace("test-key"),
            "",
            "测试提供商",
        )
    }

    private fun model(
        name: String = "DeepSeek",
        type: String = "chat",
        contextLength: Int = 64000,
        capabilities: List<String> = listOf("chat"),
        maxOutputTokens: Int = 0,
        familyName: String? = null,
        canonicalModelId: String? = null,
        chatEndpointCompatible: SupportState = SupportState.UNKNOWN,
        autoMetadataFingerprint: String? = null,
        userEditedFields: Set<String> = emptySet(),
    ) = ModelInfo(
        name = name,
        id = "default::deepseek-v4-flash",
        description = "",
        enabled = true,
        type = type,
        contextLength = contextLength,
        capabilities = capabilities,
        providerName = "测试提供商",
        providerId = "default",
        remoteModelId = "deepseek-v4-flash",
        maxOutputTokens = maxOutputTokens,
        familyName = familyName,
        canonicalModelId = canonicalModelId,
        chatEndpointCompatible = chatEndpointCompatible,
        autoMetadataFingerprint = autoMetadataFingerprint,
        userEditedFields = userEditedFields,
    )

    private fun resolved(
        workload: ModelWorkload = ModelWorkload.GENERATIVE_TEXT,
        capabilities: Map<ModelCapability, SupportState> = mapOf(
            ModelCapability.REASONING to SupportState.SUPPORTED,
            ModelCapability.STRUCTURED_OUTPUT to SupportState.SUPPORTED,
            ModelCapability.CHAT_ENDPOINT to SupportState.UNKNOWN,
        ),
    ) = ResolvedModelMetadata(
        remoteModelId = "deepseek-v4-flash",
        canonicalModelId = "deepseek-v4-flash",
        displayName = "DeepSeek V4 Flash",
        familyName = "DeepSeek",
        workload = workload,
        capabilities = capabilities,
        contextTokens = 1_000_000,
        inputTokens = null,
        outputTokens = 384_000,
        knowledgeCutoff = "2025-05",
        sourceByField = emptyMap(),
    )
}

private class MemorySecretStore : SecretStore {
    private val values = mutableMapOf<SecretId, ByteArray>()
    override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
    override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
    override fun remove(id: SecretId) { values.remove(id)?.fill(0) }
    override fun contains(id: SecretId): Boolean = values.containsKey(id)
}

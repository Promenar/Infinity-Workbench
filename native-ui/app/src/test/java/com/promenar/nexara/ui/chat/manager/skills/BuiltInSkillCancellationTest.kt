package com.promenar.nexara.ui.chat.manager.skills

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.SkillDao
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.model.ProviderConfig
import com.promenar.nexara.data.remote.protocol.ProtocolType
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BuiltInSkillCancellationTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val executionContext = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }
    private val testSecrets = object : SecretStore {
        override fun put(id: SecretId, value: ByteArray) = Unit
        override fun get(id: SecretId): ByteArray = "test-key".toByteArray()
        override fun contains(id: SecretId) = true
        override fun remove(id: SecretId) = Unit
    }

    @Test
    fun `CreateTool FileSearch ExecJs ImageGeneration 都传播取消`() = runTest {
        val cancellation = CancellationException("built-in cancelled")
        val skillDao = mockk<SkillDao>()
        coEvery { skillDao.insertCustomSkill(any()) } throws cancellation
        val workspaceRepository = mockk<IWorkspaceRepository>()
        every { workspaceRepository.observeChildren(any(), any()) } returns flow { throw cancellation }
        val providerManager = mockk<ProviderManager>()
        every { providerManager.getMainProviderConfig() } returns ProviderConfig(
            protocolType = ProtocolType.Generic_OpenAI_Compat,
            baseUrl = "https://images.example.com",
            apiKey = "test-key",
        )
        every { providerManager.imageModelId } returns MutableStateFlow("image-model")

        val cases = listOf(
            CreateToolSkill(skillDao) to skillArgs(
                "name" to "tool",
                "description" to "test",
                "parametersSchema" to "{}",
                "code" to "return true",
            ),
            FileSearchSkill(workspaceRepository) to skillArgs("query" to "needle"),
            ExecJsSkill(appContext) { throw cancellation } to skillArgs("code" to "result = 1"),
            ImageGenerationSkill(appContext, providerManager) { _, _, _ -> throw cancellation } to
                skillArgs("prompt" to "test image"),
        )

        cases.forEach { (skill, args) ->
            assertCancellation(skill, args, cancellation.message!!)
        }
    }

    @Test
    fun `WebFetch DNS与三种搜索 Skill 都传播取消`() = runTest {
        val cancellation = CancellationException("network cancelled")
        val cancellingClient = HttpClient(MockEngine { throw cancellation })
        val cases = listOf(
            WebFetchSkill(cancellingClient) { throw cancellation } to
                skillArgs("url" to "https://example.com"),
            WebSearchSkill(appContext, cancellingClient, testSecrets) to skillArgs("query" to "query"),
            WebSearchSearXNGSkill(appContext, cancellingClient) to skillArgs("query" to "query"),
            WebSearchTavilySkill(appContext, cancellingClient, testSecrets) to skillArgs("query" to "query"),
        )

        cases.forEach { (skill, args) ->
            assertCancellation(skill, args, cancellation.message!!)
        }
        cancellingClient.close()
    }

    private suspend fun assertCancellation(
        skill: SkillDefinition,
        args: kotlinx.serialization.json.JsonObject,
        expectedMessage: String,
    ) {
        val thrown = try {
            skill.execute(args, executionContext)
            null
        } catch (error: Throwable) {
            error
        }
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(thrown).hasMessageThat().isEqualTo(expectedMessage)
    }
}

package com.promenar.nexara.ui.hub

import android.content.SharedPreferences
import app.cash.turbine.test
import com.promenar.nexara.data.agent.AgentRagConfig
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.promenar.nexara.data.repository.AgentRepository
import com.promenar.nexara.domain.model.Agent
import com.promenar.nexara.domain.model.ExecutionMode
import com.promenar.nexara.domain.usecase.RagConfigPersistence
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentEditViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var repo: AgentRepository
    private lateinit var prefs: SharedPreferences

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = mockk(relaxed = true)
        prefs = mockk {
            every { getInt(any(), any()) } returns 0
            every { getFloat(any(), any()) } returns 0.0f
            every { getBoolean(any(), any()) } returns false
            every { getString(any(), any()) } returns null
        }
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadAgent populates state from repository`() = runTest {
        val agent = Agent(
            id = "a1",
            name = "Test Agent",
            description = "desc",
            systemPrompt = "prompt",
            modelId = "gpt-4",
            icon = "🧪",
            color = "#FF0000",
            avatarPath = "/path.png",
            isPinned = true,
            temperature = 0.5,
            topP = 0.8,
            maxTokens = 2048,
            useInheritedConfig = false,
            ragConfig = AgentRagConfig(docChunkSize = 999),
            retrievalConfig = AgentRetrievalConfig(memoryLimit = 3),
            executionMode = ExecutionMode.SEMI,
            createdAt = 100L
        )
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")

        assertThat(vm.name.value).isEqualTo("Test Agent")
        assertThat(vm.description.value).isEqualTo("desc")
        assertThat(vm.systemPrompt.value).isEqualTo("prompt")
        assertThat(vm.selectedModel.value).isEqualTo("gpt-4")
        assertThat(vm.selectedIcon.value).isEqualTo("🧪")
        assertThat(vm.selectedColor.value).isEqualTo("#FF0000")
        assertThat(vm.avatarPath.value).isEqualTo("/path.png")
        assertThat(vm.isPinned.value).isTrue()
        assertThat(vm.temperature.value).isEqualTo(0.5f)
        assertThat(vm.topP.value).isEqualTo(0.8f)
    }

    @Test
    fun `loadAgent with useInheritedConfig=true loads global rag config`() = runTest {
        val agent = Agent(id = "a1", name = "n", useInheritedConfig = true)
        every { repo.observeById("a1") } returns flowOf(agent)
        every { prefs.getInt("doc_chunk_size", 800) } returns 500

        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")

        assertThat(vm.ragConfig.value.docChunkSize).isEqualTo(500)
    }

    @Test
    fun `loadAgent with useInheritedConfig=false uses agent rag config`() = runTest {
        val ragConfig = AgentRagConfig(docChunkSize = 123)
        val agent = Agent(id = "a1", name = "n", useInheritedConfig = false, ragConfig = ragConfig)
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")

        assertThat(vm.ragConfig.value.docChunkSize).isEqualTo(123)
    }

    @Test
    fun `saveAgent delegates to repository update`() = runTest {
        val agent = Agent(
            id = "a1", name = "Original", modelId = "old",
            executionMode = ExecutionMode.SEMI, skills = listOf("s1"),
            createdAt = 100L
        )
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")

        vm.setName("Updated")
        vm.saveAgent("a1")

        coVerify {
            repo.update(match { saved ->
                saved.id == "a1" &&
                saved.name == "Updated" &&
                saved.modelId == "old" &&
                saved.executionMode == ExecutionMode.SEMI &&
                saved.skills == listOf("s1") &&
                saved.createdAt == 100L
            })
        }
    }

    @Test
    fun `saveAgent updates initialAgent state`() = runTest {
        val agent = Agent(id = "a1", name = "Original", executionMode = ExecutionMode.SEMI)
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")
        vm.setName("Updated")
        vm.saveAgent("a1")

        assertThat(vm.name.value).isEqualTo("Updated")
    }

    @Test
    fun `deleteAgent delegates to repository delete`() = runTest {
        every { repo.observeById(any()) } returns flowOf(null)
        var deleted = false

        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.deleteAgent("a1") { deleted = true }

        coVerify { repo.delete("a1") }
        assertThat(deleted).isTrue()
    }

    @Test
    fun `setName updates state`() = runTest {
        every { repo.observeById(any()) } returns flowOf(null)
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.setName("New Name")
        assertThat(vm.name.value).isEqualTo("New Name")
    }

    @Test
    fun `setModel updates state`() = runTest {
        every { repo.observeById(any()) } returns flowOf(null)
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.setModel("gpt-4o")
        assertThat(vm.selectedModel.value).isEqualTo("gpt-4o")
    }

    @Test
    fun `setIcon clears avatar path`() = runTest {
        every { repo.observeById(any()) } returns flowOf(null)
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.setAvatarPath("/some/path.png")
        vm.setIcon("🧪")
        assertThat(vm.selectedIcon.value).isEqualTo("🧪")
        assertThat(vm.avatarPath.value).isNull()
    }

    @Test
    fun `hasChanges is false when no changes made`() = runTest {
        val ragConfig = AgentRagConfig()
        val retrievalConfig = AgentRetrievalConfig()
        val agent = Agent(
            id = "a1", name = "Name", executionMode = ExecutionMode.SEMI,
            useInheritedConfig = false, ragConfig = ragConfig, retrievalConfig = retrievalConfig
        )
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")

        vm.hasChanges.test {
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `hasChanges is true after modifying name`() = runTest {
        val agent = Agent(id = "a1", name = "Name", executionMode = ExecutionMode.SEMI)
        every { repo.observeById("a1") } returns flowOf(agent)

        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")
        vm.setName("Changed")

        vm.hasChanges.test {
            assertThat(awaitItem()).isTrue()
        }
    }
}

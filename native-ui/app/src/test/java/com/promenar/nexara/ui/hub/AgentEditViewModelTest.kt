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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceTimeBy
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
    fun `missing agent exposes typed not found instead of silent empty editor`() = runTest {
        every { repo.observeById("missing") } returns flowOf(null)
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))

        vm.loadAgent("missing")

        assertThat(vm.saveError.value).isEqualTo(AgentEditErrorCode.NOT_FOUND)
        assertThat(vm.name.value).isEmpty()
    }

    @Test
    fun `avatar importer empty result preserves current avatar and exposes typed failure`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", avatarPath = "/avatar/original.png")
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(
            repo,
            RagConfigPersistence(prefs),
            avatarImporter = { _, _ -> null },
        )
        vm.loadAgent("a1")

        vm.importAvatar(mockk(relaxed = true))

        assertThat(vm.avatarPath.value).isEqualTo("/avatar/original.png")
        assertThat(vm.saveError.value).isEqualTo(AgentEditErrorCode.AVATAR_IMPORT_FAILED)
        coVerify(exactly = 0) { repo.update(any()) }
    }

    @Test
    fun `successful avatar import persists before leaving editor without hasChanges collector`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", avatarPath = "/avatar/original.png")
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(
            repo,
            RagConfigPersistence(prefs),
            avatarImporter = { _, _ -> "/avatar/replacement.png" },
        )
        vm.loadAgent("a1")

        vm.importAvatar(mockk(relaxed = true))

        coVerify(exactly = 1) {
            repo.update(match { it.id == "a1" && it.avatarPath == "/avatar/replacement.png" })
        }
        assertThat(vm.avatarPath.value).isEqualTo("/avatar/replacement.png")
    }

    @Test
    fun `delayed field autosave does not depend on observing hasChanges`() = runTest {
        val agent = Agent(id = "a1", name = "Original")
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")

        vm.setName("Persisted")
        advanceTimeBy(1_001)
        runCurrent()

        coVerify(exactly = 1) { repo.update(match { it.name == "Persisted" }) }
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
    fun `setIcon immediately persists preset and clears custom avatar`() = runTest {
        val agent = Agent(
            id = "a1",
            name = "Agent",
            icon = "✨",
            avatarPath = "/avatar/custom.png",
        )
        every { repo.observeById("a1") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")

        vm.setIcon("🧪")
        runCurrent()

        assertThat(vm.selectedIcon.value).isEqualTo("🧪")
        assertThat(vm.avatarPath.value).isNull()
        coVerify(exactly = 1) {
            repo.update(match { it.id == "a1" && it.icon == "🧪" && it.avatarPath == null })
        }
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

    @Test
    fun `saveAgent persists customized text flag with text in one repository update`() = runTest {
        val agent = Agent(id = "coder", name = "Coder", executionMode = ExecutionMode.SEMI)
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("coder")
        vm.setName("My Coder")
        vm.saveAgent("coder")

        coVerify { repo.update(match { it.name == "My Coder" && it.nameCustomized }) }
    }

    @Test
    fun `saveAgent persists literal edits for non preset agents`() = runTest {
        val agent = Agent(id = "user-1", name = "Custom", executionMode = ExecutionMode.SEMI)
        every { repo.observeById("user-1") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("user-1")
        vm.setName("Renamed")
        vm.saveAgent("user-1")

        coVerify { repo.update(match { it.name == "Renamed" }) }
    }

    @Test
    fun `uncustomized preset loads current locale text and model-only save does not freeze it`() = runTest {
        val agent = Agent(
            id = "coder",
            name = "Coding Expert",
            description = "Stable fallback",
            modelId = "provider::old",
            executionMode = ExecutionMode.SEMI,
        )
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))

        vm.loadAgent("coder", "编程专家", "本地化描述")
        vm.setModel("provider::new")
        vm.saveAgent("coder")

        assertThat(vm.name.value).isEqualTo("编程专家")
        assertThat(vm.description.value).isEqualTo("本地化描述")
        coVerify {
            repo.update(match {
                it.modelId == "provider::new" &&
                    it.name == "Coding Expert" &&
                    it.description == "Stable fallback" &&
                    !it.nameCustomized && !it.descriptionCustomized
            })
        }
    }

    @Test
    fun `localized preset edit baseline has no changes before user input`() = runTest {
        val agent = Agent(
            id = "coder",
            name = "Coding Expert",
            description = "Stable fallback",
            executionMode = ExecutionMode.SEMI,
        )
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))

        vm.loadAgent("coder", "编程专家", "本地化描述")

        vm.hasChanges.test { assertThat(awaitItem()).isFalse() }
    }

    @Test
    fun `changing only preset name freezes only name and preserves localized description`() = runTest {
        val agent = Agent(
            id = "coder",
            name = "Coding Expert",
            description = "Stable fallback",
            executionMode = ExecutionMode.SEMI,
        )
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))

        vm.loadAgent("coder", "编程专家", "本地化描述")
        vm.setName("我的编程助手")
        vm.saveAgent("coder")

        coVerify {
            repo.update(match {
                it.name == "我的编程助手" && it.description == "Stable fallback" &&
                    it.nameCustomized && !it.descriptionCustomized
            })
        }
    }

    @Test
    fun `changing only preset description freezes only description and preserves localized name`() = runTest {
        val agent = Agent(
            id = "coder",
            name = "Coding Expert",
            description = "Stable fallback",
            executionMode = ExecutionMode.SEMI,
        )
        every { repo.observeById("coder") } returns flowOf(agent)
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))

        vm.loadAgent("coder", "编程专家", "本地化描述")
        vm.setDescription("我的专属描述")
        vm.saveAgent("coder")

        coVerify {
            repo.update(match {
                it.name == "Coding Expert" && it.description == "我的专属描述" &&
                    !it.nameCustomized && it.descriptionCustomized
            })
        }
    }

    @Test
    fun `room update failure leaves text and customization flags uncommitted`() = runTest {
        val agent = Agent(id = "coder", name = "Coding Expert", executionMode = ExecutionMode.SEMI)
        every { repo.observeById("coder") } returns flowOf(agent)
        coEvery { repo.update(any()) } throws IllegalStateException("room failed")
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))

        vm.loadAgent("coder", "编程专家", "本地化描述")
        vm.setName("用户文本")
        vm.saveAgent("coder")

        assertThat(vm.saveError.value).isEqualTo(AgentEditErrorCode.SAVE_FAILED)
        coVerify(exactly = 1) {
            repo.update(match { it.name == "用户文本" && it.nameCustomized })
        }
        assertThat(agent.name).isEqualTo("Coding Expert")
        assertThat(agent.nameCustomized).isFalse()

        coEvery { repo.update(any()) } returns Unit
        vm.retryLastFailure()

        assertThat(vm.saveError.value).isNull()
        coVerify(exactly = 2) {
            repo.update(match { it.name == "用户文本" && it.nameCustomized })
        }
    }

    @Test
    fun `saveSystemPrompt waits for repository before reporting success`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", systemPrompt = "before")
        every { repo.observeById("a1") } returns flowOf(agent)
        val release = CompletableDeferred<Unit>()
        coEvery { repo.update(any()) } coAnswers { release.await() }
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")

        val saving = async { vm.saveSystemPrompt("after") }
        runCurrent()

        assertThat(saving.isCompleted).isFalse()
        release.complete(Unit)
        assertThat(saving.await().isSuccess).isTrue()
        assertThat(vm.systemPrompt.value).isEqualTo("after")
        coVerify(exactly = 1) { repo.update(match { it.systemPrompt == "after" }) }
    }

    @Test
    fun `saveSystemPrompt repository failure keeps previous persisted state`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", systemPrompt = "before")
        every { repo.observeById("a1") } returns flowOf(agent)
        coEvery { repo.update(any()) } throws IllegalStateException("room failed")
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")

        val result = vm.saveSystemPrompt("after")

        assertThat(result.isFailure).isTrue()
        assertThat(vm.systemPrompt.value).isEqualTo("before")
    }

    @Test
    fun `saveSystemPrompt cancellation propagates`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", systemPrompt = "before")
        every { repo.observeById("a1") } returns flowOf(agent)
        coEvery { repo.update(any()) } throws CancellationException("cancelled")
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")

        var thrown: CancellationException? = null
        try {
            vm.saveSystemPrompt("after")
        } catch (cancelled: CancellationException) {
            thrown = cancelled
        }
        assertThat(thrown?.message).isEqualTo("cancelled")
        assertThat(vm.systemPrompt.value).isEqualTo("before")
    }

    @Test
    fun `saveRagConfig persists complete non inherited configuration before success`() = runTest {
        val agent = Agent(id = "a1", name = "Agent", useInheritedConfig = true)
        every { repo.observeById("a1") } returns flowOf(agent)
        every { prefs.getInt("doc_chunk_size", 800) } returns 800
        every { prefs.getInt("memory_limit", 5) } returns 5
        val vm = AgentEditViewModel(repo, RagConfigPersistence(prefs))
        vm.loadAgent("a1")

        val result = vm.saveRagConfig { it.copy(summaryTemplate = "new template") }

        assertThat(result.isSuccess).isTrue()
        coVerify {
            repo.update(match {
                !it.useInheritedConfig &&
                    it.ragConfig?.summaryTemplate == "new template" &&
                    it.retrievalConfig != null
            })
        }
    }
}

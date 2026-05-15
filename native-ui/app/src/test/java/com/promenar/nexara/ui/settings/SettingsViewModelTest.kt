package com.promenar.nexara.ui.settings

import android.app.Application
import android.content.SharedPreferences
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.repository.ISkillRepository
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.data.remote.mcp.McpClient
import com.promenar.nexara.data.remote.mcp.McpTool
import com.promenar.nexara.ui.chat.manager.registry.McpSkillRegistry
import com.promenar.nexara.domain.repository.ITokenStatsRepository
import com.promenar.nexara.domain.repository.IVectorRepository
import com.promenar.nexara.domain.repository.TokenUsageAggregate
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var vectorRepo: IVectorRepository
    private lateinit var tokenStatsRepo: ITokenStatsRepository
    private lateinit var mockApp: NexaraApplication
    private lateinit var prefs: SharedPreferences
    private lateinit var skillRepo: ISkillRepository

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        vectorRepo = mockk()
        tokenStatsRepo = mockk()
        mockApp = mockk(relaxed = true)
        prefs = mockk {
            every { getString(any(), any()) } answers { secondArg() }
            every { getStringSet(any(), any()) } answers { secondArg() }
            every { getInt(any(), any()) } answers { secondArg() }
            every { getBoolean(any(), any()) } answers { secondArg() }
        }
        skillRepo = mockk()

        every { mockApp.getSharedPreferences("nexara_settings", 0) } returns prefs
        every { mockApp.skillRepository } returns skillRepo
        every { skillRepo.getAllCustomSkills() } returns emptyFlow()
        every { skillRepo.getAllMcpServers() } returns emptyFlow()
        coEvery { tokenStatsRepo.getTotalUsage() } returns com.promenar.nexara.domain.repository.TokenUsageAggregate()
        coEvery { tokenStatsRepo.getUsageByModel() } returns emptyList()

        val initApp = mockk<Application>(relaxed = true)
        every { initApp.applicationContext } returns initApp
        ProviderManager.init(initApp)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadTokenStats uses tokenStatsRepository`() = runTest {
        coEvery { tokenStatsRepo.getTotalUsage() } returns TokenUsageAggregate(inputTokens = 100, outputTokens = 50)
        coEvery { tokenStatsRepo.getUsageByModel() } returns emptyList()

        SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)

        coVerify { tokenStatsRepo.getTotalUsage() }
    }

    @Test
    fun `loadTokenStats populates stats from repository`() = runTest {
        val usage = TokenUsageAggregate(inputTokens = 300, outputTokens = 200)
        coEvery { tokenStatsRepo.getTotalUsage() } returns usage
        coEvery { tokenStatsRepo.getUsageByModel() } returns listOf(
            com.promenar.nexara.domain.repository.ModelTokenStats("gpt-4", usage)
        )

        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)

        assertThat(vm.tokenStats.value).hasSize(1)
        assertThat(vm.tokenStats.value[0].totalTokens).isEqualTo(500)
    }

    @Test
    fun `loadKnowledgeStats sets zero by default`() = runTest {
        coEvery { tokenStatsRepo.getTotalUsage() } returns TokenUsageAggregate()
        coEvery { tokenStatsRepo.getUsageByModel() } returns emptyList()

        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)

        assertThat(vm.activeSourcesCount.value).isEqualTo(0)
    }

    @Test
    fun `skills list excludes deprecated current_time`() = runTest {
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        val skills = vm.skills.value
        assertThat(skills.find { it.id == "current_time" }).isNull()
    }

    @Test
    fun `skills list includes image_generation`() = runTest {
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        val skills = vm.skills.value
        val imgSkill = skills.find { it.id == "image_generation" }
        assertThat(imgSkill).isNotNull()
        assertThat(imgSkill!!.id).isEqualTo("image_generation")
    }

    @Test
    fun `skills list includes file tools`() = runTest {
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        val skills = vm.skills.value
        val toolIds = skills.map { it.id }
        assertThat(toolIds).containsAtLeast("file_read", "file_list", "file_search", "exec_js")
    }

    @Test
    fun `mcp server sync calls updateMcpTools`() = runTest {
        val testServer = McpServerEntity(
            id = "srv1", name = "TestServer", url = "http://localhost:3000"
        )
        every { skillRepo.getAllMcpServers() } returns kotlinx.coroutines.flow.flowOf(listOf(testServer))
        coEvery { tokenStatsRepo.getTotalUsage() } returns TokenUsageAggregate()
        coEvery { tokenStatsRepo.getUsageByModel() } returns emptyList()

        val mockMcpRegistry = mockk<McpSkillRegistry>(relaxed = true)
        every { mockApp.httpClient } returns mockk(relaxed = true)

        mockkConstructor(McpClient::class)
        coEvery { anyConstructed<McpClient>().listTools() } returns listOf(
            McpTool("test_tool", "A test tool", """{"type":"object"}""")
        )

        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo, mockMcpRegistry)
        advanceUntilIdle()

        vm.syncMcpServer("srv1")
        advanceUntilIdle()

        coVerify { mockMcpRegistry.updateMcpTools("TestServer", any(), "http://localhost:3000") }
        unmockkConstructor(McpClient::class)
    }
}

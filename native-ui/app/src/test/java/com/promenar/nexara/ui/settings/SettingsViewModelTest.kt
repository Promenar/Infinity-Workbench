package com.promenar.nexara.ui.settings

import android.app.Application
import android.content.SharedPreferences
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.data.repository.ISkillRepository
import com.promenar.nexara.data.local.db.entity.McpServerEntity
import com.promenar.nexara.ui.chat.manager.registry.McpSkillRegistry
import com.promenar.nexara.ui.chat.manager.registry.McpSyncResult
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraThemePreferences
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.ThemePreferenceStore
import com.promenar.nexara.domain.repository.ITokenStatsRepository
import com.promenar.nexara.domain.repository.IVectorRepository
import com.promenar.nexara.domain.repository.TokenUsageAggregate
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.mockk.verify
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
    private lateinit var themePreferenceState: MutableStateFlow<NexaraThemePreferences>
    private lateinit var themePreferenceStore: ThemePreferenceStore

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        vectorRepo = mockk()
        tokenStatsRepo = mockk()
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putBoolean(any(), any()) } returns this@mockk
            every { putStringSet(any(), any()) } returns this@mockk
            every { putInt(any(), any()) } returns this@mockk
            every { putString(any(), any()) } returns this@mockk
            every { remove(any()) } returns this@mockk
        }
        prefs = mockk {
            every { getString(any(), any()) } answers { secondArg() }
            every { getStringSet(any(), any()) } answers { secondArg() }
            every { getInt(any(), any()) } answers { secondArg() }
            every { getBoolean(any(), any()) } answers { secondArg() }
            every { edit() } returns mockEditor
        }
        mockApp = mockk(relaxed = true)
        skillRepo = mockk()
        themePreferenceState = MutableStateFlow(NexaraThemePreferences())
        themePreferenceStore = mockk(relaxed = true) {
            every { state } returns themePreferenceState
        }

        every { mockApp.getSharedPreferences("nexara_settings", 0) } returns prefs
        every { mockApp.themePreferenceStore } returns themePreferenceStore
        every { mockApp.skillRepository } returns skillRepo
        every { skillRepo.getAllCustomSkills() } returns emptyFlow()
        every { skillRepo.getAllMcpServers() } returns emptyFlow()
        coEvery { skillRepo.getMcpToolSnapshots(any()) } returns emptyList()
        coEvery { tokenStatsRepo.getTotalUsage() } returns com.promenar.nexara.domain.repository.TokenUsageAggregate()
        coEvery { tokenStatsRepo.getUsageByModel() } returns emptyList()

        val initApp = mockk<Application>(relaxed = true)
        every { initApp.applicationContext } returns initApp
        ProviderManager.init(initApp, MemorySecretStore())
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `settings asynchronous failures expose typed retry state`() {
        val getters = SettingsViewModel::class.java.methods.map { it.name }

        assertThat(getters).contains("getSettingsError")
        assertThat(getters).contains("retryLastError")
    }

    @Test
    fun `token load failure is typed and retry clears it only after success`() = runTest {
        coEvery { tokenStatsRepo.getTotalUsage() } throws IllegalStateException("runtime detail")
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        advanceUntilIdle()

        assertThat(vm.settingsError.value).isEqualTo(SettingsAsyncErrorCode.TOKEN_STATS_LOAD_FAILED)

        coEvery { tokenStatsRepo.getTotalUsage() } returns TokenUsageAggregate(inputTokens = 9)
        vm.retryLastError()
        advanceUntilIdle()

        assertThat(vm.settingsError.value).isNull()
    }

    @Test
    fun `token clear failure preserves visible state until repository succeeds`() = runTest {
        val usage = TokenUsageAggregate(inputTokens = 30, outputTokens = 2)
        coEvery { tokenStatsRepo.getTotalUsage() } returns usage
        coEvery { tokenStatsRepo.getUsageByModel() } returns listOf(
            com.promenar.nexara.domain.repository.ModelTokenStats("gpt-4", usage),
        )
        coEvery { tokenStatsRepo.resetStats() } throws IllegalStateException("runtime detail")
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        advanceUntilIdle()

        vm.clearTokenStats()
        advanceUntilIdle()

        assertThat(vm.tokenStats.value.single().totalTokens).isEqualTo(32)
        assertThat(vm.settingsError.value).isEqualTo(SettingsAsyncErrorCode.TOKEN_STATS_CLEAR_FAILED)

        coEvery { tokenStatsRepo.resetStats() } returns Unit
        vm.retryLastError()
        advanceUntilIdle()

        assertThat(vm.tokenStats.value).isEmpty()
        assertThat(vm.settingsError.value).isNull()
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
    fun `mcp server sync uses atomic registry contract`() = runTest {
        val testServer = McpServerEntity(
            id = "srv1", name = "TestServer", url = "https://mcp.example.test"
        )
        every { skillRepo.getAllMcpServers() } returns kotlinx.coroutines.flow.flowOf(listOf(testServer))
        coEvery { tokenStatsRepo.getTotalUsage() } returns TokenUsageAggregate()
        coEvery { tokenStatsRepo.getUsageByModel() } returns emptyList()

        val mockMcpRegistry = mockk<McpSkillRegistry>(relaxed = true)
        coEvery { mockMcpRegistry.syncServer("srv1") } returns McpSyncResult.Success(0)

        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo, mockMcpRegistry)
        advanceUntilIdle()

        vm.syncMcpServer("srv1")
        advanceUntilIdle()

        coVerify { mockMcpRegistry.syncServer("srv1") }
    }

    @Test
    fun `new mcp server accepts HTTPS modern transport only`() = runTest {
        coEvery { skillRepo.insertMcpServer(any()) } returns Unit
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        advanceUntilIdle()

        vm.addMcpServer("legacy-http", "http://legacy.example.test", "http")
        vm.addMcpServer("legacy-stdio", "/usr/bin/server", "stdio")
        advanceUntilIdle()
        coVerify(exactly = 0) { skillRepo.insertMcpServer(any()) }

        vm.addMcpServer("modern", "https://mcp.example.test", "http")
        advanceUntilIdle()
        coVerify(exactly = 1) {
            skillRepo.insertMcpServer(match { it.url == "https://mcp.example.test" && it.type == "http" })
        }
    }

    @Test
    fun `default loopLimit is 50`() = runTest {
        val vm = SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)
        assertThat(vm.loopLimit.value).isEqualTo(50)
    }



    @Test
    fun `preset_skills_migrated_v3 updates SharedPreferences and enables all preset skills`() = runTest {
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.getBoolean("preset_skills_migrated_v3", false) } returns false
        every { prefs.edit() } returns mockEditor
        every { mockEditor.putStringSet("enabled_skills", any()) } returns mockEditor
        every { mockEditor.putBoolean("preset_skills_migrated_v3", true) } returns mockEditor

        SettingsViewModel(mockApp, vectorRepo, tokenStatsRepo)

        verify {
            mockEditor.putStringSet("enabled_skills", any())
            mockEditor.putBoolean("preset_skills_migrated_v3", true)
            mockEditor.apply()
        }
    }

    @Test
    fun `settings view model 必须暴露 typed themePreferences StateFlow 且不保留旧属性和 direct theme writer`() {
        val vmSource = java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("app/src/main/java/com/promenar/nexara/ui/settings/SettingsViewModel.kt"),
        ).toString(Charsets.UTF_8)

        assertThat(vmSource).contains("val themePreferences:")
        assertThat(vmSource).contains("StateFlow<NexaraThemePreferences>")
        assertThat(vmSource).doesNotContain("val themeMode:")
        assertThat(vmSource).doesNotContain("val selectedSettingsTab:")
        assertThat(vmSource).doesNotContain("fun setThemeMode(")
        assertThat(vmSource).doesNotContain("fun setThemeColorSource(")
        assertThat(vmSource).doesNotContain("fun setSelectedSettingsTab(")
    }

    private class MemorySecretStore : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
        override fun get(id: SecretId): ByteArray? = values[id]?.copyOf()
        override fun contains(id: SecretId): Boolean = id in values
        override fun remove(id: SecretId) { values.remove(id) }
    }
}

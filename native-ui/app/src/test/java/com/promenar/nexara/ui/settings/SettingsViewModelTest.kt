package com.promenar.nexara.ui.settings

import android.app.Application
import android.content.SharedPreferences
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.repository.ISkillRepository
import com.promenar.nexara.domain.repository.IDocumentRepository
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    private lateinit var docRepo: IDocumentRepository
    private lateinit var tokenStatsRepo: ITokenStatsRepository
    private lateinit var mockApp: NexaraApplication
    private lateinit var prefs: SharedPreferences
    private lateinit var skillRepo: ISkillRepository

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        vectorRepo = mockk()
        docRepo = mockk()
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
        coEvery { docRepo.getCount() } returns 10

        SettingsViewModel(mockApp, vectorRepo, docRepo, tokenStatsRepo)

        coVerify { tokenStatsRepo.getTotalUsage() }
    }

    @Test
    fun `loadTokenStats populates stats from repository`() = runTest {
        val usage = TokenUsageAggregate(inputTokens = 300, outputTokens = 200)
        coEvery { tokenStatsRepo.getTotalUsage() } returns usage
        coEvery { tokenStatsRepo.getUsageByModel() } returns listOf(
            com.promenar.nexara.domain.repository.ModelTokenStats("gpt-4", usage)
        )
        coEvery { docRepo.getCount() } returns 0

        val vm = SettingsViewModel(mockApp, vectorRepo, docRepo, tokenStatsRepo)

        assertThat(vm.tokenStats.value).hasSize(1)
        assertThat(vm.tokenStats.value[0].totalTokens).isEqualTo(500)
    }

    @Test
    fun `loadKnowledgeStats uses documentRepository count`() = runTest {
        coEvery { tokenStatsRepo.getTotalUsage() } returns TokenUsageAggregate()
        coEvery { tokenStatsRepo.getUsageByModel() } returns emptyList()
        coEvery { docRepo.getCount() } returns 7

        val vm = SettingsViewModel(mockApp, vectorRepo, docRepo, tokenStatsRepo)

        coVerify { docRepo.getCount() }
        assertThat(vm.activeSourcesCount.value).isEqualTo(7)
    }

    @Test
    fun `loadKnowledgeStats sets zero when repository returns zero`() = runTest {
        coEvery { tokenStatsRepo.getTotalUsage() } returns TokenUsageAggregate()
        coEvery { tokenStatsRepo.getUsageByModel() } returns emptyList()
        coEvery { docRepo.getCount() } returns 0

        val vm = SettingsViewModel(mockApp, vectorRepo, docRepo, tokenStatsRepo)

        assertThat(vm.activeSourcesCount.value).isEqualTo(0)
    }
}

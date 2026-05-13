package com.promenar.nexara.ui.settings

import android.app.Application
import android.content.SharedPreferences
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.manager.ProviderManager
import com.promenar.nexara.data.repository.ISkillRepository
import com.promenar.nexara.domain.repository.IDocumentRepository
import com.promenar.nexara.domain.repository.IVectorRepository
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
    private lateinit var mockApp: NexaraApplication
    private lateinit var prefs: SharedPreferences
    private lateinit var skillRepo: ISkillRepository

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        vectorRepo = mockk()
        docRepo = mockk()
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

        val initApp = mockk<Application>(relaxed = true)
        every { initApp.applicationContext } returns initApp
        ProviderManager.init(initApp)
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadTokenStats uses vectorRepository count`() = runTest {
        coEvery { vectorRepo.getCount() } returns 42
        coEvery { docRepo.getCount() } returns 10

        SettingsViewModel(mockApp, vectorRepo, docRepo)

        coVerify { vectorRepo.getCount() }
    }

    @Test
    fun `loadTokenStats populates stats from repository count`() = runTest {
        coEvery { vectorRepo.getCount() } returns 5
        coEvery { docRepo.getCount() } returns 0

        val vm = SettingsViewModel(mockApp, vectorRepo, docRepo)

        assertThat(vm.tokenStats.value).hasSize(1)
        assertThat(vm.tokenStats.value[0].totalTokens).isEqualTo(5 * 500L)
        assertThat(vm.tokenCostThisMonth.value).isEqualTo("$0.10")
    }

    @Test
    fun `loadKnowledgeStats uses documentRepository count`() = runTest {
        coEvery { vectorRepo.getCount() } returns 0
        coEvery { docRepo.getCount() } returns 7

        val vm = SettingsViewModel(mockApp, vectorRepo, docRepo)

        coVerify { docRepo.getCount() }
        assertThat(vm.activeSourcesCount.value).isEqualTo(7)
    }

    @Test
    fun `loadKnowledgeStats sets zero when repository returns zero`() = runTest {
        coEvery { vectorRepo.getCount() } returns 0
        coEvery { docRepo.getCount() } returns 0

        val vm = SettingsViewModel(mockApp, vectorRepo, docRepo)

        assertThat(vm.activeSourcesCount.value).isEqualTo(0)
    }
}

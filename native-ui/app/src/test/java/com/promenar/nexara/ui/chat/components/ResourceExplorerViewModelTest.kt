package com.promenar.nexara.ui.chat.components

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ResourceExplorerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `switching session cancels old recycle collector and exposes only current root`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val aFlow = MutableSharedFlow<List<FileEntry>>()
        val bFlow = MutableSharedFlow<List<FileEntry>>()
        coEvery { repo.ensureSessionRoot("a") } returns root("root-a")
        coEvery { repo.ensureSessionRoot("b") } returns root("root-b")
        every { repo.observeRecycleBin("root-a") } returns aFlow
        every { repo.observeRecycleBin("root-b") } returns bFlow
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )

        viewModel.loadSession("a")
        advanceUntilIdle()
        assertThat(aFlow.subscriptionCount.value).isEqualTo(1)
        viewModel.loadSession("b")
        advanceUntilIdle()

        assertThat(aFlow.subscriptionCount.value).isEqualTo(0)
        assertThat(bFlow.subscriptionCount.value).isEqualTo(1)
        assertThat(viewModel.workspaceRootUuid.value).isEqualTo("root-b")
    }

    private fun root(uuid: String) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = uuid,
        parentUuid = null,
        name = "root",
        hash = "",
        isDirectory = true,
        physicalRootPath = "/tmp/$uuid",
        materializedPath = "/",
        createdAt = 1,
        updatedAt = 1,
    )
}

package com.promenar.nexara.ui.settings

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.repository.ITokenStatsRepository
import com.promenar.nexara.domain.repository.TokenUsageAggregate
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TokenUsageViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `token errors use stable type instead of repository messages`() {
        val errorField = TokenStatsState::class.java.getDeclaredField("error")
        val operationField = TokenStatsState::class.java.getDeclaredField("operation")

        assertThat(errorField.type.name)
            .isEqualTo("com.promenar.nexara.ui.settings.TokenStatsErrorCode")
        assertThat(operationField.type.name)
            .isEqualTo("com.promenar.nexara.ui.settings.TokenStatsOperation")
        assertThat(TokenStatsState::class.java.declaredFields.map { it.name })
            .doesNotContain("isLoading")
    }

    @Test
    fun `loading and clearing expose distinct typed operations and reject duplicate clear`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val clearGate = CompletableDeferred<Unit>()
        val repository = successfulRepository()
        coEvery { repository.getTotalUsage() } coAnswers {
            loadGate.await()
            TokenUsageAggregate(inputTokens = 8)
        }
        coEvery { repository.resetStats() } coAnswers { clearGate.await() }
        val viewModel = TokenUsageViewModel(mockk<Application>(relaxed = true), repository)

        assertThat(viewModel.state.value.operation).isEqualTo(TokenStatsOperation.LOADING)
        loadGate.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.state.value.operation).isEqualTo(TokenStatsOperation.READY)

        viewModel.showClearConfirm()
        viewModel.clearStats()
        viewModel.clearStats()

        assertThat(viewModel.state.value.operation).isEqualTo(TokenStatsOperation.CLEARING)
        assertThat(viewModel.state.value.showClearConfirm).isFalse()
        clearGate.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.state.value.operation).isEqualTo(TokenStatsOperation.READY)
        io.mockk.coVerify(exactly = 1) { repository.resetStats() }
    }

    @Test
    fun `load failure keeps last successful state and retry replaces typed error`() = runTest {
        val repository = successfulRepository()
        var failLoad = false
        coEvery { repository.getTotalUsage() } answers {
            if (failLoad) throw IllegalStateException("runtime detail")
            TokenUsageAggregate(inputTokens = 12, outputTokens = 3)
        }
        val viewModel = TokenUsageViewModel(mockk<Application>(relaxed = true), repository)
        advanceUntilIdle()

        failLoad = true
        viewModel.loadStats()
        advanceUntilIdle()

        assertThat(viewModel.state.value.globalInput).isEqualTo(12)
        assertThat(viewModel.state.value.error).isEqualTo(TokenStatsErrorCode.LOAD_FAILED)

        failLoad = false
        viewModel.loadStats()
        advanceUntilIdle()

        assertThat(viewModel.state.value.globalInput).isEqualTo(12)
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `clear failure preserves stats until repository succeeds`() = runTest {
        val repository = successfulRepository()
        coEvery { repository.getTotalUsage() } returns TokenUsageAggregate(inputTokens = 21, outputTokens = 4)
        coEvery { repository.resetStats() } throws IllegalStateException("runtime detail")
        val viewModel = TokenUsageViewModel(mockk<Application>(relaxed = true), repository)
        advanceUntilIdle()

        viewModel.clearStats()
        advanceUntilIdle()

        assertThat(viewModel.state.value.globalInput).isEqualTo(21)
        assertThat(viewModel.state.value.error).isEqualTo(TokenStatsErrorCode.CLEAR_FAILED)

        coEvery { repository.resetStats() } returns Unit
        viewModel.clearStats()
        advanceUntilIdle()

        assertThat(viewModel.state.value.globalInput).isEqualTo(0)
        assertThat(viewModel.state.value.error).isNull()
    }

    private fun successfulRepository(): ITokenStatsRepository = mockk(relaxed = true) {
        coEvery { getTotalUsage() } returns TokenUsageAggregate()
        coEvery { getUsageByModel() } returns emptyList()
        coEvery { getTopSessions(any()) } returns emptyList()
        coEvery { getDailyTrend(any()) } returns emptyList()
        coEvery { resetStats() } returns Unit
    }
}

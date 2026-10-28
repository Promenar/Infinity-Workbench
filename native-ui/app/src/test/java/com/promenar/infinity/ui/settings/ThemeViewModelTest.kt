package com.promenar.infinity.ui.settings

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.TouchLockUnlock
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.ThemePreferenceStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val sharedPrefsMap = mutableMapOf<String, String>()
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sharedPrefsMap.clear()

        val editor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { putString(any(), any()) } answers {
                sharedPrefsMap[firstArg()] = secondArg()
                this@mockk
            }
            every { apply() } returns Unit
        }

        sharedPreferences = mockk(relaxed = true) {
            every { getString(any(), any()) } answers {
                sharedPrefsMap[firstArg()] ?: secondArg() as String?
            }
            every { edit() } returns editor
        }

        context = mockk(relaxed = true) {
            every { getSharedPreferences("infinity_settings", Context.MODE_PRIVATE) } returns sharedPreferences
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createStore(): ThemePreferenceStore {
        return ThemePreferenceStore(context)
    }

    @Test
    fun viewModelExposesPreferencesAndDynamicColorAvailability() = runTest {
        val store = createStore()
        val viewModel = ThemeViewModel(store, dynamicColorAvailable = true)

        val state = viewModel.uiState.value
        assertThat(state.preferences).isEqualTo(
            InfinityThemePreferences(
                mode = InfinityThemeMode.DARK,
                colorSource = InfinityColorSource.INFINITY
            )
        )
        assertThat(state.dynamicColorAvailable).isTrue()
    }

    @Test
    fun setThemeModeUpdatesStoreAndViewModelStateImmediatelyAndPersists() = runTest {
        val store = createStore()
        val viewModel = ThemeViewModel(store, dynamicColorAvailable = true)

        viewModel.setThemeMode(InfinityThemeMode.LIGHT)

        // 立即更新
        assertThat(viewModel.uiState.value.preferences.mode).isEqualTo(InfinityThemeMode.LIGHT)
        assertThat(store.state.value.mode).isEqualTo(InfinityThemeMode.LIGHT)
        assertThat(sharedPrefsMap["theme_mode"]).isEqualTo("light")

        // 模拟进程重建：用同一 SharedPreferences 创建新 Store/ViewModel
        val newStore = createStore()
        val newViewModel = ThemeViewModel(newStore, dynamicColorAvailable = true)
        assertThat(newViewModel.uiState.value.preferences.mode).isEqualTo(InfinityThemeMode.LIGHT)
    }

    @Test
    fun setColorSourceWhenDynamicColorAvailable_succeeds() = runTest {
        val store = createStore()
        val viewModel = ThemeViewModel(store, dynamicColorAvailable = true)

        viewModel.setColorSource(InfinityColorSource.DYNAMIC)

        assertThat(viewModel.uiState.value.preferences.colorSource).isEqualTo(InfinityColorSource.DYNAMIC)
        assertThat(store.state.value.colorSource).isEqualTo(InfinityColorSource.DYNAMIC)
        assertThat(sharedPrefsMap["theme_color_source"]).isEqualTo("dynamic")
    }

    @Test
    fun setColorSourceWhenDynamicColorUnavailable_isRejectedAndKeepsInfinity() = runTest {
        val store = createStore()
        val viewModel = ThemeViewModel(store, dynamicColorAvailable = false)

        viewModel.setColorSource(InfinityColorSource.DYNAMIC)

        // 不可用时启用请求必须被拒绝并保持 INFINITY
        assertThat(viewModel.uiState.value.preferences.colorSource).isEqualTo(InfinityColorSource.INFINITY)
        assertThat(store.state.value.colorSource).isEqualTo(InfinityColorSource.INFINITY)
        assertThat(sharedPrefsMap["theme_color_source"]).isNull() // 没有写入持久化
    }

    @Test
    fun storeAndViewModelShareSameStoreFlowState() = runTest {
        val store = createStore()
        val viewModel = ThemeViewModel(store, dynamicColorAvailable = true)

        // 根层与 ViewModel 使用同一个 Store StateFlow，选择后两者都立即收到更新。
        // 从 Store 外部更新
        store.setMode(InfinityThemeMode.SYSTEM)
        assertThat(viewModel.uiState.value.preferences.mode).isEqualTo(InfinityThemeMode.SYSTEM)

        // 从 ViewModel 外部更新
        viewModel.setThemeMode(InfinityThemeMode.LIGHT)
        assertThat(store.state.value.mode).isEqualTo(InfinityThemeMode.LIGHT)
    }

    @Test
    fun setCollapseFinishedTurnsUpdatesStoreAndViewModelState() = runTest {
        val store = createStore()
        val viewModel = ThemeViewModel(store, dynamicColorAvailable = true)
        assertThat(viewModel.uiState.value.preferences.collapseFinishedTurns).isTrue()

        viewModel.setCollapseFinishedTurns(false)

        assertThat(viewModel.uiState.value.preferences.collapseFinishedTurns).isFalse()
        assertThat(store.state.value.collapseFinishedTurns).isFalse()
    }

    @Test
    fun touchLockUnlockDefaultsToHoldAndPersistsSlide() = runTest {
        val store = createStore()
        val viewModel = ThemeViewModel(store, dynamicColorAvailable = true)
        assertThat(viewModel.uiState.value.preferences.touchLockUnlock).isEqualTo(TouchLockUnlock.HOLD)

        viewModel.setTouchLockUnlock(TouchLockUnlock.SLIDE)

        assertThat(viewModel.uiState.value.preferences.touchLockUnlock).isEqualTo(TouchLockUnlock.SLIDE)
        // 重新读取偏好仍是滑动解锁。
        assertThat(createStore().state.value.touchLockUnlock).isEqualTo(TouchLockUnlock.SLIDE)
    }
}

package com.promenar.nexara.ui.settings

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences
import com.promenar.nexara.ui.theme.ThemePreferenceStore
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
            every { getSharedPreferences("nexara_settings", Context.MODE_PRIVATE) } returns sharedPreferences
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
            NexaraThemePreferences(
                mode = NexaraThemeMode.DARK,
                colorSource = NexaraColorSource.NEXARA
            )
        )
        assertThat(state.dynamicColorAvailable).isTrue()
    }

    @Test
    fun setThemeModeUpdatesStoreAndViewModelStateImmediatelyAndPersists() = runTest {
        val store = createStore()
        val viewModel = ThemeViewModel(store, dynamicColorAvailable = true)

        viewModel.setThemeMode(NexaraThemeMode.LIGHT)

        // 立即更新
        assertThat(viewModel.uiState.value.preferences.mode).isEqualTo(NexaraThemeMode.LIGHT)
        assertThat(store.state.value.mode).isEqualTo(NexaraThemeMode.LIGHT)
        assertThat(sharedPrefsMap["theme_mode"]).isEqualTo("light")

        // 模拟进程重建：用同一 SharedPreferences 创建新 Store/ViewModel
        val newStore = createStore()
        val newViewModel = ThemeViewModel(newStore, dynamicColorAvailable = true)
        assertThat(newViewModel.uiState.value.preferences.mode).isEqualTo(NexaraThemeMode.LIGHT)
    }

    @Test
    fun setColorSourceWhenDynamicColorAvailable_succeeds() = runTest {
        val store = createStore()
        val viewModel = ThemeViewModel(store, dynamicColorAvailable = true)

        viewModel.setColorSource(NexaraColorSource.DYNAMIC)

        assertThat(viewModel.uiState.value.preferences.colorSource).isEqualTo(NexaraColorSource.DYNAMIC)
        assertThat(store.state.value.colorSource).isEqualTo(NexaraColorSource.DYNAMIC)
        assertThat(sharedPrefsMap["theme_color_source"]).isEqualTo("dynamic")
    }

    @Test
    fun setColorSourceWhenDynamicColorUnavailable_isRejectedAndKeepsNexara() = runTest {
        val store = createStore()
        val viewModel = ThemeViewModel(store, dynamicColorAvailable = false)

        viewModel.setColorSource(NexaraColorSource.DYNAMIC)

        // 不可用时启用请求必须被拒绝并保持 NEXARA
        assertThat(viewModel.uiState.value.preferences.colorSource).isEqualTo(NexaraColorSource.NEXARA)
        assertThat(store.state.value.colorSource).isEqualTo(NexaraColorSource.NEXARA)
        assertThat(sharedPrefsMap["theme_color_source"]).isNull() // 没有写入持久化
    }

    @Test
    fun storeAndViewModelShareSameStoreFlowState() = runTest {
        val store = createStore()
        val viewModel = ThemeViewModel(store, dynamicColorAvailable = true)

        // 根层与 ViewModel 使用同一个 Store StateFlow，选择后两者都立即收到更新。
        // 从 Store 外部更新
        store.setMode(NexaraThemeMode.SYSTEM)
        assertThat(viewModel.uiState.value.preferences.mode).isEqualTo(NexaraThemeMode.SYSTEM)

        // 从 ViewModel 外部更新
        viewModel.setThemeMode(NexaraThemeMode.LIGHT)
        assertThat(store.state.value.mode).isEqualTo(NexaraThemeMode.LIGHT)
    }
}

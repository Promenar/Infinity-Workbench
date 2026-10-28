package com.promenar.nexara.ui.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences
import com.promenar.nexara.ui.theme.ThemePreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

data class ThemeUiState(
    val preferences: NexaraThemePreferences = NexaraThemePreferences(),
    val dynamicColorAvailable: Boolean = false
)

class ThemeViewModel(
    private val store: ThemePreferenceStore,
    val dynamicColorAvailable: Boolean = Build.VERSION.SDK_INT >= 31
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ThemeUiState(
            preferences = store.state.value,
            dynamicColorAvailable = dynamicColorAvailable
        )
    )
    val uiState: StateFlow<ThemeUiState> = _uiState.asStateFlow()

    init {
        store.state.onEach { prefs ->
            _uiState.value = _uiState.value.copy(preferences = prefs)
        }.launchIn(viewModelScope)
    }

    fun setThemeMode(mode: NexaraThemeMode) {
        store.setMode(mode)
    }

    fun setColorSource(source: NexaraColorSource) {
        if (source == NexaraColorSource.DYNAMIC && !dynamicColorAvailable) {
            return
        }
        store.setColorSource(source)
    }

    fun setPrimaryColor(color: Long?) {
        store.setPrimaryColor(color)
        store.setColorSource(NexaraColorSource.NEXARA)
    }

    /** 应用自定义取色：作为种子色写入，并记进最近使用。 */
    fun applyCustomColor(argb: Int) {
        store.rememberCustomColor(argb)
        setPrimaryColor(androidx.compose.ui.graphics.Color(argb).value.toLong())
    }

    fun setSchemeStyle(style: com.promenar.nexara.ui.theme.NexaraSchemeStyle) {
        store.setSchemeStyle(style)
    }

    fun setComposerGlowStyle(style: com.promenar.nexara.ui.theme.ComposerGlowStyle) {
        store.setComposerGlowStyle(style)
    }

    fun setGenerationIndicatorStyle(style: com.promenar.nexara.ui.theme.GenerationIndicatorStyle) {
        store.setGenerationIndicatorStyle(style)
    }

    fun setEmptyChatStyle(style: com.promenar.nexara.ui.theme.EmptyChatStyle) {
        store.setEmptyChatStyle(style)
    }

    fun setScopePlacement(placement: com.promenar.nexara.ui.theme.ScopeScreenPlacement) {
        store.setScopePlacement(placement)
    }

    fun setScopeDigits(enabled: Boolean) {
        store.setScopeDigits(enabled)
    }

    fun setPureBlack(enabled: Boolean) {
        store.setPureBlack(enabled)
    }

    fun setTextScaleEnabled(enabled: Boolean) {
        store.setTextScale(enabled)
    }

    fun setTextScale(scale: Float) {
        store.setTextScale(store.state.value.textScaleEnabled, scale)
    }

    fun setCollapseFinishedTurns(enabled: Boolean) {
        store.setCollapseFinishedTurns(enabled)
    }

    fun setHapticsEnabled(enabled: Boolean) {
        store.setHapticsEnabled(enabled)
    }

    fun setStatusHapticsEnabled(enabled: Boolean) {
        store.setStatusHapticsEnabled(enabled)
    }

    fun setTouchLockUnlock(unlock: com.promenar.nexara.ui.theme.TouchLockUnlock) {
        store.setTouchLockUnlock(unlock)
    }

    companion object {
        fun provideFactory(store: ThemePreferenceStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ThemeViewModel(store) as T
                }
            }
    }
}

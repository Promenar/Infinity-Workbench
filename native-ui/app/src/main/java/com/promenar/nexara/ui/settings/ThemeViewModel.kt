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

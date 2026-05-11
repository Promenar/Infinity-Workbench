package com.promenar.nexara.ui.chat

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpaViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs: SharedPreferences =
        application.getSharedPreferences("nexara_spa", android.content.Context.MODE_PRIVATE)

    private val _assistantTitle = MutableStateFlow(
        prefs.getString("assistant_title", "Nexara Prime") ?: "Nexara Prime"
    )
    val assistantTitle: StateFlow<String> = _assistantTitle.asStateFlow()

    private val _fabColor = MutableStateFlow(
        prefs.getString("fab_color", "#6366F1") ?: "#6366F1"
    )
    val fabColor: StateFlow<String> = _fabColor.asStateFlow()

    private val _fabIconIndex = MutableStateFlow(
        prefs.getInt("fab_icon_index", 0)
    )
    val fabIconIndex: StateFlow<Int> = _fabIconIndex.asStateFlow()

    private val _rotateAnimation = MutableStateFlow(
        prefs.getBoolean("rotate_animation", true)
    )
    val rotateAnimation: StateFlow<Boolean> = _rotateAnimation.asStateFlow()

    private val _glowEffect = MutableStateFlow(
        prefs.getBoolean("glow_effect", true)
    )
    val glowEffect: StateFlow<Boolean> = _glowEffect.asStateFlow()

    private val _enableKG = MutableStateFlow(
        prefs.getBoolean("enable_kg_spa", true)
    )
    val enableKG: StateFlow<Boolean> = _enableKG.asStateFlow()

    private val _contextWindow = MutableStateFlow(
        prefs.getFloat("context_window", 0.7f)
    )
    val contextWindow: StateFlow<Float> = _contextWindow.asStateFlow()

    fun updateAssistantTitle(title: String) {
        _assistantTitle.value = title
        prefs.edit().putString("assistant_title", title).apply()
    }

    fun updateFabColor(color: String) {
        _fabColor.value = color
        prefs.edit().putString("fab_color", color).apply()
    }

    fun updateFabIcon(index: Int) {
        _fabIconIndex.value = index
        prefs.edit().putInt("fab_icon_index", index).apply()
    }

    fun updateRotateAnimation(enabled: Boolean) {
        _rotateAnimation.value = enabled
        prefs.edit().putBoolean("rotate_animation", enabled).apply()
    }

    fun updateGlowEffect(enabled: Boolean) {
        _glowEffect.value = enabled
        prefs.edit().putBoolean("glow_effect", enabled).apply()
    }

    fun updateEnableKG(enabled: Boolean) {
        _enableKG.value = enabled
        prefs.edit().putBoolean("enable_kg_spa", enabled).apply()
    }

    fun updateContextWindow(value: Float) {
        _contextWindow.value = value
        prefs.edit().putFloat("context_window", value).apply()
    }

}

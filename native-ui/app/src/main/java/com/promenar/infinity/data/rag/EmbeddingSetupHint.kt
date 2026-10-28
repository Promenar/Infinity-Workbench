package com.promenar.infinity.data.rag

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 未选择嵌入模型时的一次性配置引导。
 *
 * 记忆或文档检索开启但没有可用嵌入模型时，检索静默跳过；首次遇到时进入待显示，
 * 会话页展示一次指向“默认模型”的提示后永久记住，不在每轮回复中重复提醒。
 */
class EmbeddingSetupHint(private val prefs: SharedPreferences) {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun onEmbeddingNotConfigured() {
        if (!prefs.getBoolean("embedding_setup_hint_shown", false)) _pending.value = true
    }

    fun markShown() {
        prefs.edit().putBoolean("embedding_setup_hint_shown", true).apply()
        _pending.value = false
    }
}

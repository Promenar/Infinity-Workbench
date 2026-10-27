package com.promenar.nexara.data.local

import android.content.SharedPreferences
import com.promenar.nexara.data.model.SessionHeaderMode
import com.promenar.nexara.data.model.SessionHeaderPolicy

/** 使用提供商的稳定 ID，避免额外提供商列表重排时串用配置。 */
data class ProviderSessionHeaderSettings(val enabled: Boolean = false, val name: String = SessionHeaderPolicy.DEFAULT_NAME)

class SessionHeaderPreferences(private val preferences: SharedPreferences) {
    fun provider(providerId: String) = ProviderSessionHeaderSettings(
        enabled = preferences.getBoolean(key(providerId, "enabled"), false),
        name = preferences.getString(key(providerId, "name"), SessionHeaderPolicy.DEFAULT_NAME) ?: SessionHeaderPolicy.DEFAULT_NAME,
    )

    fun saveProvider(providerId: String, enabled: Boolean, name: String) {
        require(providerId.isNotBlank()) { "提供商 ID 不能为空" }
        require((!enabled && name.isEmpty()) || SessionHeaderPolicy.isValidName(name)) { "会话请求头名称无效" }
        check(preferences.edit().putBoolean(key(providerId, "enabled"), enabled).putString(key(providerId, "name"), name).commit()) {
            "无法保存会话请求头设置"
        }
    }

    fun removeProvider(providerId: String) {
        preferences.edit().remove(key(providerId, "enabled")).remove(key(providerId, "name")).apply()
    }

    fun model(modelId: String): SessionHeaderMode =
        SessionHeaderMode.fromStored(preferences.getString("session_header_model_$modelId", null))

    fun saveModel(modelId: String, mode: SessionHeaderMode) {
        // apply 同步发布内存值，磁盘写入由 SharedPreferences 在后台执行。
        preferences.edit().putString("session_header_model_$modelId", mode.name).apply()
    }

    fun removeModel(modelId: String) {
        preferences.edit().remove("session_header_model_$modelId").apply()
    }

    private fun key(providerId: String, field: String) = "session_header_provider_${providerId}_$field"
}

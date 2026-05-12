package com.promenar.nexara.data.model

import com.promenar.nexara.data.remote.protocol.ProtocolType

/**
 * 提供商列表项 — 从 SettingsViewModel 提取为共享数据模型。
 * 扩展了 protocolType 和 apiKey 字段以支持完整的编辑回填。
 */
data class ProviderListItem(
    val id: String = "",
    val name: String = "",
    val typeName: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val protocolType: ProtocolType = ProtocolType.Generic_OpenAI_Compat,
    val apiKey: String = ""
)

/**
 * 提供商完整配置信息，用于编辑回填和持久化。
 */
data class ProviderConfig(
    val protocolType: ProtocolType = ProtocolType.Generic_OpenAI_Compat,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val name: String? = null
)

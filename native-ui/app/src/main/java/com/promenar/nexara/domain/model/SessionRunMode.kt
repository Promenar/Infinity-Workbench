package com.promenar.nexara.domain.model

import kotlinx.serialization.Serializable

/** 会话固定的执行环境；空值由调用方保留为尚未选择，不回退为内置模式。 */
@Serializable
enum class SessionRunMode {
    LINUX,
    BUILTIN;

    companion object {
        fun fromStored(value: String?): SessionRunMode? = entries.firstOrNull { it.name == value }
    }
}

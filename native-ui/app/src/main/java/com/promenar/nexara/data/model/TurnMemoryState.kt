package com.promenar.nexara.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一轮回复写入对话记忆的状态，以 JSON 存放在该轮最后一条助手消息的 `vectorization_status` 列。
 * 该列早期存放过纯文本状态（如 "success"），解析不出时按无状态处理。
 */
@Serializable
data class TurnMemoryState(
    val status: Status,
    /** 已写入的记忆片段数，仅 [Status.STORED] 有意义。 */
    val chunkCount: Int = 0,
    val updatedAt: Long = 0,
) {
    @Serializable
    enum class Status {
        @SerialName("writing") WRITING,
        @SerialName("stored") STORED,
        @SerialName("failed") FAILED,
        @SerialName("removed") REMOVED,
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(raw: String?): TurnMemoryState? {
            if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
            return runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
        }
    }
}

val Message.turnMemory: TurnMemoryState?
    get() = TurnMemoryState.decode(vectorizationStatus)

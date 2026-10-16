package com.promenar.nexara.domain.script

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable
data class ScriptRequest(
    val code: String,
    val entryPath: String? = null,
    val args: JsonArray = JsonArray(emptyList()),
    val allowNetwork: Boolean = false,
    val timeoutSeconds: Int = 30,
)

@Serializable
data class ScriptLog(val level: String, val text: String)

@Serializable
data class ScriptResult(
    val value: JsonElement = JsonNull,
    val logs: List<ScriptLog> = emptyList(),
    val logsTruncated: Boolean = false,
    val errorCode: String? = null,
    val error: String? = null,
)

fun interface ScriptCapabilities {
    suspend fun invoke(operation: String, arguments: JsonObject): JsonElement
}

/** 引擎只能访问显式注入的能力，不持有 Android Context 或任何仓库。 */
interface ScriptEngine {
    val language: String
    suspend fun execute(request: ScriptRequest, capabilities: ScriptCapabilities): ScriptResult
    fun cancel()
}

fun interface ScriptRuntime {
    suspend fun execute(sessionId: String, request: ScriptRequest, capabilities: ScriptCapabilities): ScriptResult
}

/** 主进程持有的隔离服务句柄；终止不等待脚本线程释放锁。 */
interface ScriptProcess {
    val alive: Boolean
    suspend fun execute(request: ScriptRequest, capabilities: ScriptCapabilities): ScriptResult
    fun terminate()
}

class ScriptFailure(val code: String, override val message: String) : Exception(message)

object ScriptLimits {
    const val MAX_CODE_CHARS = 50_000
    const val MAX_TIMEOUT_SECONDS = 300
    const val MEMORY_BYTES = 64L * 1024 * 1024
    const val STACK_BYTES = 512L * 1024
    const val OUTPUT_BYTES = 64 * 1024
    const val LOG_BYTES = 16 * 1024
    const val FILE_BYTES = 64 * 1024
    const val NETWORK_BODY_BYTES = 64 * 1024
    const val NETWORK_TOTAL_BYTES = 1024 * 1024
    const val NETWORK_REQUESTS = 20
    const val CAPABILITY_CALLS = 128
    const val IPC_BYTES = 256 * 1024
}

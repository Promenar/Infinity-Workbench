package com.promenar.nexara.domain.script

import com.promenar.nexara.domain.storage.ExternalFolderPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object ScriptModules {
    fun root(entryPath: String): String = ExternalFolderPaths.normalize(entryPath).substringBeforeLast('/').ifEmpty { "/" }

    /** 只折叠模块相对路径；最终路径继续经过工作区的统一保留名校验。 */
    fun resolve(root: String, base: String, requested: String): String {
        if (!(requested.startsWith("./") || requested.startsWith("../")) || '\\' in requested || '\u0000' in requested) {
            throw ScriptFailure("IMPORT_DENIED", "模块必须使用根目录内的相对路径")
        }
        val canonicalRoot = ExternalFolderPaths.normalize(root)
        val parts = base.substringBeforeLast('/').split('/').filter(String::isNotEmpty).toMutableList()
        requested.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) throw ScriptFailure("IMPORT_DENIED", "模块导入越出根目录") else parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        val path = try { ExternalFolderPaths.normalize(parts.joinToString("/", prefix = "/")) }
        catch (_: Exception) { throw ScriptFailure("IMPORT_DENIED", "模块路径无效或包含保留名") }
        if (canonicalRoot != "/" && !path.startsWith("$canonicalRoot/") ||
            canonicalRoot == "/" && (path == "/mnt" || path.startsWith("/mnt/") || path == "/skills" || path.startsWith("/skills/")) ||
            !path.endsWith(".js") && !path.endsWith(".mjs")
        ) throw ScriptFailure("IMPORT_DENIED", "模块导入越出根目录或扩展名不支持")
        return path
    }
}

class ScriptOutput(private val logBytes: Int = ScriptLimits.LOG_BYTES, private val outputBytes: Int = ScriptLimits.OUTPUT_BYTES) {
    private val entries = mutableListOf<ScriptLog>()
    private var remaining = logBytes
    private var truncated = false

    fun log(level: String, text: String) {
        if (remaining <= 0 || entries.size >= 256) { truncated = true; return }
        val clipped = clipUtf8(text, minOf(remaining, 2048))
        entries.add(ScriptLog(level.take(8), clipped))
        remaining -= clipped.toByteArray(Charsets.UTF_8).size
        if (clipped != text) { truncated = true; remaining = 0 }
    }

    fun finish(value: JsonElement): ScriptResult {
        val result = ScriptResult(value, entries.toList(), truncated)
        if (Json.encodeToString(result).toByteArray(Charsets.UTF_8).size > outputBytes) {
            throw ScriptFailure("OUTPUT_LIMIT", "脚本输出超过总量上限")
        }
        return result
    }

    companion object {
        fun clipUtf8(text: String, maxBytes: Int): String {
            if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
            var end = 0
            var used = 0
            while (end < text.length) {
                val point = text.codePointAt(end)
                val bytes = when { point < 0x80 -> 1; point < 0x800 -> 2; point < 0x10000 -> 3; else -> 4 }
                if (used + bytes > maxBytes) break
                used += bytes
                end += Character.charCount(point)
            }
            return text.substring(0, end)
        }
    }
}

/** 最多保留一个绑定，跨会话切换时销毁，避免后台隔离进程无限累积。 */
class SupervisedScriptRuntime(private val createProcess: suspend (String) -> ScriptProcess) : ScriptRuntime {
    private val mutex = Mutex()
    private var session: String? = null
    private var process: ScriptProcess? = null

    override suspend fun execute(sessionId: String, request: ScriptRequest, capabilities: ScriptCapabilities): ScriptResult = mutex.withLock {
        if (request.timeoutSeconds !in 1..ScriptLimits.MAX_TIMEOUT_SECONDS || request.code.length > ScriptLimits.MAX_CODE_CHARS ||
            request.language !in ScriptLanguages.all || request.stdin.length > ScriptLimits.MAX_STDIN_CHARS
        ) {
            return@withLock ScriptResult(errorCode = "INVALID_ARGUMENT", error = "代码、标准输入、语言或超时参数超过允许范围")
        }
        try {
            withTimeoutOrNull(ScriptLimits.hostTimeoutMillis(request)) {
                if (session != sessionId || process?.alive != true) {
                    discard()
                    process = createProcess(sessionId)
                    session = sessionId
                }
                val result = process!!.execute(request, capabilities)
                if (result.errorCode == "TIMEOUT" || result.errorCode == "PROCESS_DIED" || result.errorCode?.endsWith("_LIMIT") == true) discard()
                if (Json.encodeToString(result).toByteArray(Charsets.UTF_8).size > ScriptLimits.OUTPUT_BYTES) {
                    discard()
                    ScriptResult(errorCode = "OUTPUT_LIMIT", error = "隔离服务输出超过总量上限")
                } else result
            } ?: run {
                discard()
                ScriptResult(errorCode = "TIMEOUT", error = "脚本执行超时，隔离进程已终止")
            }
        } catch (cancelled: CancellationException) {
            discard()
            throw cancelled
        } catch (failure: Exception) {
            discard()
            ScriptResult(errorCode = (failure as? ScriptFailure)?.code ?: "PROCESS_DIED", error = "隔离服务不可用，后续调用将重新建立")
        }
    }

    private fun discard() { process?.terminate(); process = null; session = null }
}

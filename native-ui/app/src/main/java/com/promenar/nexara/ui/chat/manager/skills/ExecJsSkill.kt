package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.script.ScriptToolRunner
import com.promenar.nexara.domain.script.*
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class ExecJsSkill(private val runner: ScriptToolRunner) : SkillDefinition {
    override val id = "exec_js"
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SCRIPT
    override val description = "在隔离进程执行 JavaScript，返回 result 变量（可为 Promise），未定义时返回最后表达式。支持 console、fs 工作区能力和 fetch；联网必须显式 allow_network=true。默认 30 秒，上限 300 秒。"
    override val parametersSchema = """{"type":"object","properties":{"code":{"type":"string","description":"JavaScript 代码，返回 result 变量或最后表达式"},"allow_network":{"type":"boolean","default":false},"timeout_seconds":{"type":"integer","minimum":1,"maximum":300,"default":30}},"required":["code"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = scriptToolResult(id) {
        val code = args.stringArgument("code") ?: throw ScriptFailure("INVALID_ARGUMENT", "缺少必填参数 code")
        if (code.length > ScriptLimits.MAX_CODE_CHARS) throw ScriptFailure("INVALID_ARGUMENT", "代码超过 50,000 字符上限")
        runner.execute(scriptRequest(args, code), context)
    }
}

class RunScriptSkill(private val runner: ScriptToolRunner) : SkillDefinition {
    override val id = "run_script"
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SCRIPT
    override val description = "执行工作区或 /mnt/<别名>/ 中的 .js/.mjs 文件。args 为参数数组；相对模块只允许在入口文件所在目录内导入。模块通过 result 变量返回值。fs.read 返回 content/sha256，覆盖写入需 expected_sha256。联网默认关闭。"
    override val parametersSchema = """{"type":"object","properties":{"path":{"type":"string"},"args":{"type":"array"},"allow_network":{"type":"boolean","default":false},"timeout_seconds":{"type":"integer","minimum":1,"maximum":300,"default":30}},"required":["path"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = scriptToolResult(id) {
        val path = args.stringArgument("path")?.takeIf(String::isNotBlank) ?: throw ScriptFailure("INVALID_ARGUMENT", "缺少必填参数 path")
        val normalized = ExternalFolderPaths.normalize(path)
        if (!normalized.endsWith(".js") && !normalized.endsWith(".mjs")) throw ScriptFailure("INVALID_ARGUMENT", "只支持 .js/.mjs 文件")
        val arguments = args["args"]?.let { it as? JsonArray ?: throw ScriptFailure("INVALID_ARGUMENT", "args 必须为数组") } ?: JsonArray(emptyList())
        runner.execute(scriptRequest(args, "").copy(entryPath = normalized, args = arguments), context)
    }
}

private fun scriptRequest(args: JsonObject, code: String): ScriptRequest {
    val timeout = args["timeout_seconds"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull ?: throw ScriptFailure("INVALID_ARGUMENT", "timeout_seconds 必须为整数") } ?: 30
    val network = args["allow_network"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: throw ScriptFailure("INVALID_ARGUMENT", "allow_network 必须为布尔值") } ?: false
    if (timeout !in 1..ScriptLimits.MAX_TIMEOUT_SECONDS) throw ScriptFailure("INVALID_ARGUMENT", "timeout_seconds 必须在 1–300 秒之间")
    return ScriptRequest(code, allowNetwork = network, timeoutSeconds = timeout)
}

private suspend fun scriptToolResult(id: String, block: suspend () -> ScriptResult): ToolResult = try {
    val result = block()
    ToolResult("${id}_${System.currentTimeMillis()}", buildString {
        if (result.errorCode == null) append("Result: ${result.value}")
        else append("${result.errorCode}：${result.error}")
        result.logs.forEach { append("\n[${it.level}] ${it.text}") }
        if (result.logsTruncated) append("\n日志已截断")
    }, if (result.errorCode == null) "success" else "error")
} catch (cancelled: CancellationException) { throw cancelled }
catch (failure: Exception) { ToolResult("${id}_error", "${(failure as? ScriptFailure)?.code ?: "INVALID_ARGUMENT"}：${failure.message?.take(512) ?: "参数无效"}", "error") }

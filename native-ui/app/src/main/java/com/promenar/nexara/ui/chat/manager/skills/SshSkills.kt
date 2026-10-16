package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.data.ssh.SshFailure
import com.promenar.nexara.data.ssh.SshPolicy
import com.promenar.nexara.data.ssh.SshRuntime
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.domain.tool.ToolSchemaValidation
import com.promenar.nexara.domain.tool.ToolSchemaValidator
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.registry.toProtocolTool
import com.promenar.nexara.domain.tool.ToolExecutionTargets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

fun sshTools(runtime: SshRuntime): List<SkillDefinition> = SshOperation.entries.map { SshSkill(runtime, it) }

private enum class SshOperation(val tool: String, val risk: ToolRisk, val summary: String, val fields: String, val required: String) {
    EXEC("ssh_exec", ToolRisk.PROCESS, "在已配置的主机别名上执行非交互命令；默认60秒，最多600秒，输出有上限。",
        """"host":{"type":"string"},"command":{"type":"string"},"timeout_seconds":{"type":"integer","minimum":1,"maximum":600},"max_output_chars":{"type":"integer","minimum":1,"maximum":256000}""", "\"host\",\"command\""),
    LIST("sftp_list", ToolRisk.SAFE_READ, "列出远端目录，最多512项，返回截断标记。",
        """"host":{"type":"string"},"path":{"type":"string"}""", "\"host\",\"path\""),
    READ("sftp_read", ToolRisk.SAFE_READ, "分段读取UTF-8文本；offset和limit使用UTF-16单元，二进制及超过16MiB的文件拒绝。",
        """"host":{"type":"string"},"path":{"type":"string"},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":2,"maximum":65536}""", "\"host\",\"path\""),
    WRITE("sftp_write", ToolRisk.EXTERNAL_WRITE, "写入远端UTF-8文件；expected_sha256存在时校验原文件，不一致拒绝。SFTP不提供跨客户端原子比较写入。",
        """"host":{"type":"string"},"path":{"type":"string"},"content":{"type":"string"},"expected_sha256":{"type":"string"}""", "\"host\",\"path\",\"content\""),
    OPEN("ssh_session_open", ToolRisk.PROCESS, "为当前聊天生成任务打开常驻PTY；任务结束即释放，空闲10分钟关闭。",
        """"host":{"type":"string"}""", "\"host\""),
    SEND("ssh_session_send", ToolRisk.PROCESS, "向当前聊天所属PTY发送原始输入；执行回车需在input中明确包含换行。审批绑定真实主机别名及完整输入。",
        """"session_id":{"type":"string"},"input":{"type":"string"}""", "\"session_id\",\"input\""),
    READ_SESSION("ssh_session_read", ToolRisk.SESSION_STATE, "读取当前聊天所属PTY的有界输出缓冲；wait_ms最多5000毫秒。",
        """"session_id":{"type":"string"},"wait_ms":{"type":"integer","minimum":0,"maximum":5000}""", "\"session_id\""),
    CLOSE("ssh_session_close", ToolRisk.SESSION_STATE, "关闭当前聊天所属PTY并释放其连接。",
        """"session_id":{"type":"string"}""", "\"session_id\""),
}

private class SshSkill(private val runtime: SshRuntime, private val operation: SshOperation) : SkillDefinition {
    override val id = operation.tool
    override val name = operation.tool
    override val risk = operation.risk
    override val mcpServerId: String? = null
    override val description = operation.summary + " 仅使用用户在设置中创建的主机别名，禁止地址、用户名、密码、私钥或口令参数；未信任或指纹变化需用户在设置中处理。"
    override val parametersSchema = """{"type":"object","properties":{${operation.fields}},"required":[${operation.required}],"additionalProperties":false}"""
    private val validator = ToolSchemaValidator()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        if (validator.validate(parametersSchema, args) is ToolSchemaValidation.Invalid) return failure("INVALID_ARGUMENTS")
        val owner = "agent:${context.sessionId}"
        return try {
            if ("host" in args) SshPolicy.alias(args.text("host"))
            val snapshot = when (operation) {
                SshOperation.EXEC, SshOperation.LIST, SshOperation.READ, SshOperation.WRITE, SshOperation.OPEN ->
                    runtime.hostSnapshot(args.text("host"))
                SshOperation.SEND -> runtime.sessionSnapshot(owner, args.text("session_id"))
                else -> null
            }
            if (snapshot != null && (context.isApprovalBoundExecution || context.approvedExecutionTarget != null)) {
                val resolved = if (operation == SshOperation.SEND) mapOf("ssh_session:${args.text("session_id")}" to snapshot.alias) else emptyMap()
                val target = ToolExecutionTargets.extract(toProtocolTool(), args.toString(), resolved, mapOf(snapshot.alias to snapshot.executionIdentity()))
                if (target == null || target != context.approvedExecutionTarget) throw SshFailure("HOST_AUTHORIZATION_CHANGED")
            }
            // 协程局部快照贯穿连接获取与实际操作，禁止按别名换源或跨等待窗口复用旧连接。
            val result = runtime.withHostSnapshot(snapshot) { when (operation) {
                SshOperation.EXEC -> {
                    val value = runtime.exec(owner, args.text("host"), args.text("command"), args.integer("timeout_seconds", 60), args.integer("max_output_chars", 32_000))
                    buildJsonObject { put("exit_code", value.exitCode); put("stdout", value.stdout); put("stderr", value.stderr); put("truncated", value.truncated); put("timed_out", value.timedOut) }
                }
                SshOperation.LIST -> {
                    val value = runtime.list(owner, args.text("host"), args.text("path"))
                    buildJsonObject {
                        put("entries", JsonArray(value.entries.map { entry -> buildJsonObject { put("name", entry.name); put("size", entry.size); put("directory", entry.directory) } }))
                        put("truncated", value.truncated)
                    }
                }
                SshOperation.READ -> {
                    val value = runtime.read(owner, args.text("host"), args.text("path"), args.number("offset", 0), args.integer("limit", 16_384))
                    buildJsonObject { put("content", value.content); put("next_offset", value.nextOffset); put("eof", value.eof); put("offset_unit", "UTF-16") }
                }
                SshOperation.WRITE -> buildJsonObject {
                    put("sha256", runtime.write(owner, args.text("host"), args.text("path"), args.text("content"), args["expected_sha256"]?.let { args.text("expected_sha256") }))
                }
                SshOperation.OPEN -> {
                    val value = runtime.openSession(owner, args.text("host"))
                    buildJsonObject { put("session_id", value.sessionId); put("host", value.host) }
                }
                SshOperation.SEND -> { runtime.sendSession(owner, args.text("session_id"), args.text("input")); buildJsonObject { put("sent", true) } }
                SshOperation.READ_SESSION -> {
                    val value = runtime.readSession(owner, args.text("session_id"), args.integer("wait_ms", 0))
                    buildJsonObject { put("output", value.output); put("closed", value.closed); put("truncated", value.truncated) }
                }
                SshOperation.CLOSE -> { runtime.closeSession(owner, args.text("session_id")); buildJsonObject { put("closed", true) } }
            } }
            ToolResult(id = "ssh_${UUID.randomUUID()}", content = result.toString())
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: SshFailure) { failure(failure.code)
        } catch (_: Exception) { failure("SSH_OPERATION_FAILED") }
    }
    private fun failure(code: String) = ToolResult(id = "ssh_${UUID.randomUUID()}", content = "SSH_ERROR:$code；请检查设置中的主机配置、凭据和指纹信任。", status = "error")
    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw SshFailure("INVALID_ARGUMENTS")
    private fun JsonObject.integer(key: String, default: Int): Int = if (key !in this) default else (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw SshFailure("INVALID_ARGUMENTS")
    private fun JsonObject.number(key: String, default: Long): Long = if (key !in this) default else (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: throw SshFailure("INVALID_ARGUMENTS")
}

package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.artifact.*
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.nexara.ui.chat.manager.registry.stringArgument
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

class ArtifactPublishSkill(private val repository: SessionArtifactRepository) : SkillDefinition {
    override val id = "artifact_publish"
    override val name = id
    override val description = "交付给用户的报告、文档、图片、数据表、压缩包或导出网页等成果文件完成后调用；同一路径修改后再次调用会追加版本。页面与原型请用画布工具，不必再发布。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val parametersSchema = """{"type":"object","properties":{"path":{"type":"string"},"title":{"type":"string","description":"可选标题，1 到 120 字符"},"kind":{"type":"string","enum":["document","image","page","other"]}},"required":["path"],"additionalProperties":false}"""
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = artifactExecution(id) {
        val path = args.stringArgument("path") ?: throw ArtifactException("INVALID_ARGUMENTS", "path 必须为工作区路径。")
        val title = if (args.containsKey("title")) args.stringArgument("title") ?: throw ArtifactException("INVALID_ARGUMENTS", "title 必须是字符串。") else null
        val kind = if (args.containsKey("kind")) when (args.stringArgument("kind")) {
            "document" -> ArtifactKind.DOCUMENT
            "image" -> ArtifactKind.IMAGE
            "page" -> ArtifactKind.PAGE
            "other" -> ArtifactKind.OTHER
            else -> throw ArtifactException("INVALID_ARGUMENTS", "kind 只能为 document、image、page 或 other。")
        } else null
        val messageId = context.assistantMessageId?.takeIf(String::isNotBlank) ?: throw ArtifactException("MISSING_MESSAGE_CONTEXT", "发布文件需要当前回复的消息上下文。")
        val item = repository.publishFile(context.sessionId, path, title, kind, ArtifactOrigin.DECLARED, messageId)
        artifactResult(id) { putSummary(item) }
    }
}

class ArtifactListSkill(private val repository: SessionArtifactRepository) : SkillDefinition {
    override val id = "artifact_list"
    override val name = id
    override val description = "列出当前会话全部产物摘要，不返回内容。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{},"additionalProperties":false}"""
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = artifactExecution(id) {
        val items = repository.observe(context.sessionId).first()
        artifactResult(id) { put("artifacts", JsonArray(items.map { buildJsonObject { putSummary(it) } })) }
    }
}

private fun JsonObjectBuilder.putSummary(item: ArtifactSummary) {
    put("artifactId", item.id); put("title", item.title); put("kind", item.kind.name.lowercase()); put("version", item.currentVersion)
    put("origin", item.origin.name.lowercase()); put("source_path", item.sourcePath?.let(::JsonPrimitive) ?: JsonNull)
    put("file_name", item.fileName?.let(::JsonPrimitive) ?: JsonNull); put("mime_type", item.mimeType?.let(::JsonPrimitive) ?: JsonNull)
    put("size_bytes", item.sizeBytes?.let(::JsonPrimitive) ?: JsonNull)
    put("created_at", item.createdAt); put("updated_at", item.updatedAt); put("screen_count", item.screenCount)
    put("created_by_message_id", item.createdByMessageId?.let(::JsonPrimitive) ?: JsonNull)
}
private fun artifactResult(id: String, body: JsonObjectBuilder.() -> Unit) = ToolResult(id = "${id}_${UUID.randomUUID()}", content = buildJsonObject(body).toString())
private suspend fun artifactExecution(id: String, block: suspend () -> ToolResult): ToolResult = try { block() }
catch (e: ArtifactException) { artifactFailure(id, e) }
catch (e: kotlinx.coroutines.CancellationException) { throw e }
catch (e: Exception) { artifactFailure(id, ArtifactException("STORAGE_ERROR", "产物读取或保存失败。")) }

private fun artifactFailure(id: String, e: ArtifactException): ToolResult =
    ToolResult(id = "${id}_${UUID.randomUUID()}", status = "error", content = buildJsonObject {
        put("status", "error"); put("error", e.code); put("message", e.message.take(600))
        put("suggestion", when (e.code) {
            "FILE_TOO_LARGE" -> "请选择不超过 25 MiB 的文件。"
            "SESSION_QUOTA_EXCEEDED" -> "会话快照已达容量上限，请减少快照占用。"
            "NOT_FOUND", "IS_DIRECTORY", "INVALID_PATH", "UNREADABLE" -> "检查工作区文件路径、类型与访问权限。"
            "MISSING_MESSAGE_CONTEXT" -> "请从当前会话的正常回复中调用工具。"
            "SESSION_DELETED" -> "请在有效会话中发布文件。"
            "STORAGE_ERROR" -> "检查应用存储空间后重试。"
            else -> "检查路径、标题与产物类别参数。"
        })
    }.toString())

package com.promenar.nexara.ui.chat

import com.promenar.nexara.data.model.ApprovalCallIdentity
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.domain.tool.ToolArgumentsValidation
import com.promenar.nexara.domain.tool.ToolArgumentsValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 审批卡片的写入预览：从助手消息中取出与审批身份摘要完全一致的原始参数，
 * 生成目标与内容片段。摘要不一致或无法解析时返回 null，界面回退到参数摘要。
 */
object ApprovalPreview {
    private const val MAX_PREVIEW_LINES = 12
    private const val MAX_LINE_CHARS = 120

    enum class Action { OVERWRITE, CREATE_FILE, EDIT, MOVE, DELETE, CREATE_DIRECTORY, SCRIPT }

    /** 与语言无关的预览数据；动作标题由界面按资源本地化。 */
    data class Preview(val action: Action, val target: String, val detail: String?)

    fun forCall(call: ApprovalCallIdentity, messages: List<Message>, assistantMessageId: String?): Preview? {
        val arguments = messages
            .firstOrNull { it.id == assistantMessageId }
            ?.toolCalls
            ?.firstOrNull { it.id == call.toolCallId }
            ?.arguments
            ?: return null
        val validated = ToolArgumentsValidator().validate(arguments) as? ToolArgumentsValidation.Valid ?: return null
        if (validated.sha256 != call.argumentsDigest) return null
        return format(call.toolName, validated.arguments)
    }

    fun format(toolName: String, args: JsonObject): Preview? {
        val target = args.string("path") ?: args.string("uuid").orEmpty()
        val newChild = (args.string("parentPath") ?: args.string("parentUuid") ?: "").trimEnd('/') +
            "/" + args.string("name").orEmpty()
        return when (toolName) {
            "write_file" -> Preview(Action.OVERWRITE, target, args.string("content")?.let { excerpt(it, "") })
            "create_file" -> Preview(Action.CREATE_FILE, newChild, args.string("content")?.let { excerpt(it, "") })
            "create_directory" -> Preview(Action.CREATE_DIRECTORY, newChild, null)
            "delete_file" -> Preview(Action.DELETE, target, null)
            "exec_js" -> Preview(Action.SCRIPT, "", args.string("code")?.let { excerpt(it, "") })
            "move_file" -> Preview(
                Action.MOVE,
                target,
                buildString {
                    (args.string("newParentPath") ?: args.string("newParentUuid"))?.let { append("→ $it/") }
                    args.string("newName")?.let { if (isNotEmpty()) append(' '); append("→ $it") }
                }.ifEmpty { null },
            )
            "patch_file" -> Preview(
                Action.EDIT,
                target,
                buildString {
                    (args["operations"] as? JsonArray).orEmpty().take(MAX_PREVIEW_LINES).forEach { element ->
                        val op = element as? JsonObject ?: return@forEach
                        appendLine(
                            when (op.string("action")) {
                                "insert_after" -> "+ @L${op.int("afterLine")}"
                                "delete_lines" -> "- L${op.int("startLine")}–${op.int("endLine")}"
                                else -> "~ L${op.int("startLine")}–${op.int("endLine")}"
                            },
                        )
                        op.string("newContent")?.let { appendLine(excerpt(it, prefix = "  + ")) }
                    }
                }.trimEnd().ifEmpty { null },
            )
            else -> null
        }
    }

    private fun excerpt(text: String, prefix: String): String {
        val lines = text.lines()
        val shown = lines.take(MAX_PREVIEW_LINES).joinToString("\n") { prefix + it.take(MAX_LINE_CHARS) }
        return if (lines.size > MAX_PREVIEW_LINES) "$shown\n$prefix… (+${lines.size - MAX_PREVIEW_LINES})" else shown
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)

    private fun JsonObject.int(key: String): String =
        (this[key] as? JsonPrimitive)?.intOrNull?.toString() ?: "?"
}

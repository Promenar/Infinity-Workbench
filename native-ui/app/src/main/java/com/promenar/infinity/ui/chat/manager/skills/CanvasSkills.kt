package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.canvas.CanvasKind
import com.promenar.infinity.data.canvas.CanvasScreenSource
import com.promenar.infinity.data.canvas.PrototypeUpdate
import com.promenar.infinity.data.canvas.CanvasDocument
import com.promenar.infinity.data.canvas.CanvasException
import com.promenar.infinity.data.canvas.CanvasPatch
import com.promenar.infinity.data.canvas.CanvasStore
import com.promenar.infinity.data.canvas.CanvasWorkspaceSync
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.intArgument
import com.promenar.infinity.ui.chat.manager.registry.stringArgument
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class CanvasCreateSkill(private val store: CanvasStore) : SkillDefinition {
    override val id = "canvas_create"
    override val name = id
    override val description = "原型用于多屏、可点击跳转的 App 或网页，屏间用 <a href=\"infinity-screen:<屏幕 ID>\"> 链接；每屏遵守下述视口和暗色要求。在当前会话创建可反复修改的 HTML 画布。适合图表、看板、计算器和交互小工具；页面可运行 JavaScript，但不能联网。页面须在 320–420 CSS px 宽的视口内可读；宽表格放进 overflow-x: auto 的容器或允许换行；不要使用超过容器的固定宽度。页面随应用浅色或暗色主题显示：要自行适配暗色时声明 <meta name=\"color-scheme\" content=\"light dark\"> 并用 prefers-color-scheme 成对设置背景与文字颜色；未声明的页面按浅色排版，暗色主题下由系统自动变暗。给元素设背景色时同时设文字颜色。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val parametersSchema = """{"type":"object","properties":{"kind":{"type":"string","enum":["page","prototype"]},"screens":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"title":{"type":"string"},"html":{"type":"string"}},"required":["id","title","html"],"additionalProperties":false}},"title":{"type":"string","description":"1 到 120 字符的非空画布标题"},"html":{"type":"string","description":"完整 HTML 或 HTML 片段，UTF-8 不超过 1 MiB"}},"required":["title"],"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = canvasExecution(id) {
        val title = args.requiredString("title")
        val kind = if (args.containsKey("kind")) args.requiredString("kind") else "page"
        if (kind !in setOf("page", "prototype")) throw CanvasException("INVALID_ARGUMENTS", "kind 只能是 page 或 prototype。")
        if ((kind == "page" && (!args.containsKey("html") || args.containsKey("screens"))) || (kind == "prototype" && (!args.containsKey("screens") || args.containsKey("html")))) throw CanvasException("INVALID_ARGUMENTS", "页面提供 html；原型提供 screens，二者不能同时提供。")
        val messageId = context.assistantMessageId?.takeIf(String::isNotBlank)
            ?: throw CanvasException("MISSING_MESSAGE_CONTEXT", "创建画布需要当前回复的消息上下文。")
        val document = if (kind == "page") store.create(context.sessionId, title, args.requiredString("html"), messageId)
            else store.createPrototype(context.sessionId, title, args.screenSources(), messageId)
        canvasResult(id) { putDocument(document) }
    }
}

class CanvasUpdateSkill(private val store: CanvasStore) : SkillDefinition {
    override val id = "canvas_update"
    override val name = id
    override val description = "原型以 screens 整体替换，或以 screen_id 配合 html、patches 或 screen_title 修改单屏。更新当前会话的画布，html 整体替换与 patches 文本补丁二选一。小改动优先补丁；find 必须在前一条补丁处理后的源码中唯一出现，任一失败整批不写入。expected_version 可防止覆盖其它更新。页面须在 320–420 CSS px 宽的视口内可读；宽表格放进 overflow-x: auto 的容器或允许换行；不要使用超过容器的固定宽度。页面随应用浅色或暗色主题显示：要自行适配暗色时声明 <meta name=\"color-scheme\" content=\"light dark\"> 并用 prefers-color-scheme 成对设置背景与文字颜色；未声明的页面按浅色排版，暗色主题下由系统自动变暗。给元素设背景色时同时设文字颜色。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val parametersSchema = """{"type":"object","properties":{"screen_id":{"type":"string"},"screen_title":{"type":"string"},"screens":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"title":{"type":"string"},"html":{"type":"string"}},"required":["id","title","html"],"additionalProperties":false}},"canvas_id":{"type":"string"},"html":{"type":"string","description":"替换后的完整 HTML；与 patches 必须且只能提供其中一种"},"patches":{"type":"array","description":"至少一条顺序执行的文本补丁；与 html 必须且只能提供其中一种","items":{"type":"object","properties":{"find":{"type":"string","description":"非空查找串，在应用前一条补丁后的源码中必须唯一出现"},"replace":{"type":"string"}},"required":["find","replace"],"additionalProperties":false}},"expected_version":{"type":"integer","description":"可选的预期当前版本，必须是正整数"}},"required":["canvas_id"],"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = canvasExecution(id) {
        val canvasId = args.requiredString("canvas_id")
        if (args.containsKey("html") && args.containsKey("patches")) throw CanvasException("INVALID_ARGUMENTS", "html 与 patches 不能同时提供。")
        val html = if (args.containsKey("html")) args.requiredString("html") else null
        val patches = if (args.containsKey("patches")) {
            val raw = args["patches"] as? JsonArray ?: throw CanvasException("INVALID_ARGUMENTS", "patches 必须为数组。")
            raw.mapIndexed { index, value ->
                val patch = value as? JsonObject ?: throw CanvasException("INVALID_ARGUMENTS", "第 ${index + 1} 条补丁必须是对象。")
                CanvasPatch(patch.requiredString("find"), patch.requiredString("replace"))
            }
        } else null
        val version = args.optionalPositiveInt("expected_version")
        val document = if (args.containsKey("screens")) {
            if (args.keys.any { it in setOf("screen_id", "screen_title", "html", "patches") }) throw CanvasException("INVALID_ARGUMENTS", "screens 不能与单屏更新参数同时提供。")
            store.updatePrototype(context.sessionId, canvasId, PrototypeUpdate.ReplaceAll(args.screenSources()), version)
        } else if (args.containsKey("screen_id")) {
            val screenId = args.requiredString("screen_id")
            val title = if (args.containsKey("screen_title")) args.requiredString("screen_title") else null
            store.updatePrototype(context.sessionId, canvasId, PrototypeUpdate.EditScreen(screenId, html, patches, title), version)
        } else {
            if (args.containsKey("screen_title") || (html == null && patches == null)) throw CanvasException("INVALID_ARGUMENTS", "页面提供 html 或 patches；原型单屏更新须提供 screen_id。")
            store.update(context.sessionId, canvasId, html, patches, version)
        }
        canvasResult(id) { putDocument(document) }
    }
}

class CanvasReadSkill(private val store: CanvasStore) : SkillDefinition {
    override val id = "canvas_read"
    override val name = id
    override val description = "读取当前会话画布的完整 HTML 和元数据。默认读取最新版，可用 version 读取仍保留的历史版本。补丁匹配失败时先读取源码再修正 find。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"screen_id":{"type":"string"},"canvas_id":{"type":"string"},"version":{"type":"integer","description":"可选的历史版本号，必须是正整数；省略时读取最新版"}},"required":["canvas_id"],"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = canvasExecution(id) {
        val canvasId = args.requiredString("canvas_id")
        val version = args.optionalPositiveInt("version")
        val document = (if (version == null) store.syncFromWorkspace(context.sessionId, canvasId)
            else store.read(context.sessionId, canvasId, version))
            ?: throw CanvasException("NOT_FOUND", "本会话中不存在该画布或所选版本已不再保留。")
        val screenId = if (args.containsKey("screen_id")) args.requiredString("screen_id") else null
        if (screenId != null && document.metadata.kind != CanvasKind.PROTOTYPE) throw CanvasException("NOT_PROTOTYPE", "只有原型可按 screen_id 读取。")
        val selected = screenId?.let { key -> document.screens.find { it.id == key } ?: throw CanvasException("NOT_FOUND", "找不到指定屏幕。") }
        canvasResult(id) {
            putDocument(document)
            put("html", selected?.html ?: document.html)
            if (document.metadata.kind == CanvasKind.PROTOTYPE) put("screens", kotlinx.serialization.json.JsonArray((selected?.let(::listOf) ?: document.screens).map { screen -> buildJsonObject { put("id", screen.id); put("title", screen.title); put("html", screen.html) } }))
        }
    }
}

class CanvasListSkill(private val store: CanvasStore) : SkillDefinition {
    override val id = "canvas_list"
    override val name = id
    override val description = "列出当前会话所有画布的元数据，不返回 HTML 源码。用 canvas_read 读取需要修改的画布。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{},"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = canvasExecution(id) {
        val canvases = store.list(context.sessionId)
        canvasResult(id) { put("canvases", Json.encodeToJsonElement(canvases)) }
    }
}

private fun JsonObject.screenSources(): List<CanvasScreenSource> {
    val raw = this["screens"] as? JsonArray ?: throw CanvasException("INVALID_ARGUMENTS", "screens 必须为数组。")
    return raw.map { value ->
        val item = value as? JsonObject ?: throw CanvasException("INVALID_ARGUMENTS", "屏幕必须是对象。")
        CanvasScreenSource(item.requiredString("id"), item.requiredString("title"), item.requiredString("html"))
    }
}

private fun JsonObject.requiredString(key: String): String = stringArgument(key)
    ?: throw CanvasException("INVALID_ARGUMENTS", "$key 必须是字符串。")

private fun JsonObject.optionalPositiveInt(key: String): Int? {
    if (!containsKey(key)) return null
    return intArgument(key)?.takeIf { it > 0 }
        ?: throw CanvasException("INVALID_ARGUMENTS", "$key 必须是正整数。")
}

private suspend fun canvasExecution(id: String, block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (failure: CanvasException) {
    ToolResult(
        id = "${id}_${UUID.randomUUID()}",
        content = buildJsonObject {
            put("status", "error")
            put("error", failure.code)
            put("message", failure.message.take(600))
            put("suggestion", when (failure.code) {
                "PATCH_NOT_UNIQUE", "VERSION_CONFLICT" -> "先用 canvas_read 读取最新源码和版本，再以唯一 find 或正确 expected_version 重试。"
                "IS_PROTOTYPE", "NOT_PROTOTYPE" -> "请按画布类型使用原型屏幕参数或页面参数。"
                "NOT_FOUND" -> "用 canvas_list 确认本会话画布 ID，并读取仍保留的版本。"
                "SOURCE_TOO_LARGE" -> "精简 HTML 源码，使 UTF-8 编码大小不超过 1 MiB。"
                "SESSION_DELETED" -> "当前会话已删除，请在有效会话中创建画布。"
                "MISSING_MESSAGE_CONTEXT" -> "请从当前会话的正常回复中调用画布工具。"
                "STORAGE_ERROR" -> "检查应用存储空间后重试；用 canvas_read 确认已保存的版本。"
                else -> "检查参数类型与约束：标题不超过 120 字符，html 与非空 patches 二选一，find 必须非空。"
            })
        }.toString(),
        status = "error",
    )
}

private fun canvasResult(id: String, body: JsonObjectBuilder.() -> Unit) = ToolResult(
    id = "${id}_${UUID.randomUUID()}",
    content = buildJsonObject(body).toString(),
)

private fun JsonObjectBuilder.putDocument(document: CanvasDocument) {
    put("canvasId", document.metadata.id)
    put("title", document.metadata.title)
    put("version", document.version)
    put("kind", if (document.metadata.kind == CanvasKind.PROTOTYPE) "prototype" else "page")
    if (document.metadata.kind == CanvasKind.PROTOTYPE) put("screens", kotlinx.serialization.json.JsonArray(document.screens.map { screen -> buildJsonObject { put("id", screen.id); put("title", screen.title) } }))
    put("metadata", Json.encodeToJsonElement(document.metadata))
    put("workspace_path", document.metadata.workspacePath?.let(::JsonPrimitive) ?: JsonNull)
    if (document.metadata.workspaceSync == CanvasWorkspaceSync.FAILED) {
        put("workspace_warning", "画布已保存到私有版本，但未同步到工作区；请检查目录权限后重新读取或更新画布。")
    }
}

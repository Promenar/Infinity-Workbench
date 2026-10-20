package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.generation.MediaViewRequest
import com.promenar.nexara.data.generation.MediaViewTarget
import com.promenar.nexara.data.generation.WorkspaceMediaViewer
import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.*

class ViewMediaSkill(
    private val viewer: WorkspaceMediaViewer,
    private val target: suspend (String) -> MediaViewTarget,
) : SkillDefinition {
    override val id = "view_media"
    override val name = "view_media"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val description = "查看工作区的图片、音频或视频。路径规则与文件工具相同：工作区虚拟路径或相对路径，不接受本机真实绝对路径。paths 为 1–8 个路径；不支持的模态会得到明确说明。视频可用 as_frames 强制关键帧；frames 与 interval_seconds 二选一，start_seconds/end_seconds 限定时间段，默认均匀取 8 帧中点，单次最多 32 帧。每帧带时间戳，不包含声音，可针对某一时间段再次提高密度查看。媒体只在当前轮发送。"
    override val parametersSchema = """{"type":"object","properties":{"paths":{"type":"array","items":{"type":"string"},"description":"1–8 个工作区虚拟路径或相对路径"},"question":{"type":"string","description":"查看目的"},"as_frames":{"type":"boolean","description":"强制将视频作为不含声音的关键帧查看"},"frames":{"type":"integer","description":"正整数，均匀分段中点取帧；与 interval_seconds 二选一，默认 8 帧，整个调用最多 32 帧"},"interval_seconds":{"type":"number","description":"大于 0 的秒数，从起点按间隔取帧；与 frames 二选一"},"start_seconds":{"type":"number","description":"视频起点秒数，夹到有效时长范围"},"end_seconds":{"type":"number","description":"视频终点秒数，必须大于起点"}},"required":["paths"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val paths = (args["paths"] as? JsonArray)?.map { value ->
            val path = value as? JsonPrimitive
            require(path != null && path.isString && path.content.isNotBlank()) { "paths 必须是工作区路径字符串数组。" }
            path.content
        } ?: throw IllegalArgumentException("缺少 paths 路径数组。")
        fun number(key: String): Double? = args[key]?.let {
            val value = it as? JsonPrimitive
            require(value != null && !value.isString && value.doubleOrNull?.isFinite() == true) { "$key 必须是有限数。" }
            value.double
        }
        val frames = args["frames"]?.let {
            val value = it as? JsonPrimitive
            require(value != null && !value.isString && value.intOrNull != null) { "frames 必须是整数。" }
            value.int
        }
        val force = args["as_frames"]?.let {
            val value = it as? JsonPrimitive
            require(value != null && !value.isString && value.booleanOrNull != null) { "as_frames 必须是布尔值。" }
            value.boolean
        } ?: false
        val question = args["question"]?.let {
            val value = it as? JsonPrimitive
            require(value != null && value.isString) { "question 必须是字符串。" }
            value.content
        }
        return viewer.view(context.sessionId, MediaViewRequest(paths, question, force, frames, number("interval_seconds"), number("start_seconds"), number("end_seconds")), target(context.sessionId))
    }
}

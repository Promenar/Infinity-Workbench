package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.domain.repository.IFileOperationRepository
import com.promenar.nexara.domain.repository.PatchError
import com.promenar.nexara.domain.repository.PatchOperation
import com.promenar.nexara.domain.repository.PatchResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FilePatchSkill(
    private val fileOpRepo: IFileOperationRepository
) : SkillDefinition {
    override val id = "patch_file"
    override val name = "patch_file"
    override val description = "应用 JSON diff 指令到文件。支持 replace_lines、insert_after、delete_lines 操作，带乐观锁冲突检测。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"uuid":{"type":"string","description":"文件UUID"},"expectedHash":{"type":"string","description":"乐观锁基础版本hash"},"operations":{"type":"array","items":{"type":"object","properties":{"action":{"type":"string","enum":["replace_lines","insert_after","delete_lines"]},"startLine":{"type":"integer"},"endLine":{"type":"integer"},"afterLine":{"type":"integer"},"newContent":{"type":"string"}},"required":["action"]}}},"required":["uuid","expectedHash","operations"]}"""

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: Map<String, Any>, context: SkillExecutionContext): ToolResult {
        val uuid = args["uuid"] as? String
            ?: return ToolResult("err", "缺少 uuid", "error")
        val expectedHash = args["expectedHash"] as? String
            ?: return ToolResult("err", "缺少 expectedHash", "error")

        val operationsRaw = args["operations"]
        if (operationsRaw == null) {
            return ToolResult("err", "缺少 operations 参数", "error")
        }

        val operations = parseOperations(operationsRaw)
            ?: return ToolResult("err", "operations 格式无效，请检查 JSON 结构", "error")

        if (operations.isEmpty()) {
            return ToolResult("err", "operations 不能为空", "error")
        }

        return when (val result = fileOpRepo.patchFile(uuid, operations, expectedHash)) {
            is PatchResult.Success -> ToolResult(
                "patch_file_${System.currentTimeMillis()}",
                "补丁应用成功。新 Hash: ${result.newHash}，已应用 ${result.appliedOperations} 个操作。"
            )
            is PatchResult.Failure -> ToolResult(
                "patch_file_${System.currentTimeMillis()}",
                formatPatchError(result.error),
                "error"
            )
        }
    }

    private fun parseOperations(raw: Any): List<PatchOperation>? {
        return try {
            val jsonString = when (raw) {
                is String -> raw
                is List<*> -> {
                    val elements = raw.map { item ->
                        when (item) {
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                val map = item as Map<String, Any?>
                                val pairs = map.mapNotNull { (k, v) ->
                                    when (v) {
                                        is String -> k to JsonPrimitive(v)
                                        is Number -> k to JsonPrimitive(v.toInt())
                                        is Boolean -> k to JsonPrimitive(v)
                                        null -> null
                                        else -> k to JsonPrimitive(v.toString())
                                    }
                                }
                                JsonObject(pairs.toMap())
                            }
                            else -> return null
                        }
                    }
                    JsonArray(elements).toString()
                }
                else -> raw.toString()
            }

            val array = json.parseToJsonElement(jsonString).jsonArray
            array.map { element ->
                val obj = element.jsonObject
                PatchOperation(
                    action = obj["action"]?.jsonPrimitive?.content ?: return null,
                    startLine = obj["startLine"]?.jsonPrimitive?.intOrNull,
                    endLine = obj["endLine"]?.jsonPrimitive?.intOrNull,
                    afterLine = obj["afterLine"]?.jsonPrimitive?.intOrNull,
                    newContent = obj["newContent"]?.jsonPrimitive?.content
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatPatchError(error: PatchError): String {
        val base = buildString {
            appendLine("补丁应用失败！")
            appendLine("错误码: ${error.code}")
            appendLine("详细信息: ${error.message}")
            if (error.operationIndex >= 0) {
                appendLine("失败的操作索引: ${error.operationIndex}")
            }
        }

        val suggestion = when (error.code) {
            "LINE_OUT_OF_RANGE" ->
                "行号超出文件范围（当前共 ${error.totalLines ?: "?"} 行）。" +
                    "请先调用 read_file(uuid=\"$error.fileUuid\", mode=\"page\") 获取当前行数后重试。"

            "HASH_MISMATCH" ->
                "乐观锁冲突：文件已被修改。当前 Hash 与你提供的基准不一致。" +
                    "请先调用 diff_file(uuid=\"$error.fileUuid\") 获取最新差异，再重新规划操作。"

            "INVALID_JSON" ->
                "JSON 格式错误。请检查 operations 数组中每个对象的 action/startLine/endLine/newContent 字段是否正确。"

            "EMPTY_FILE" ->
                "目标文件为空，无法应用 patch。请使用 write_file 全量写入内容。"

            "BINARY_FILE" ->
                "目标文件为二进制文件，不支持 patch 操作。请使用 write_file 全量写入。"

            "LOCKED_BY_OTHER" ->
                "文件被其他会话锁定（${error.suggestion ?: "请等待锁释放或通知用户"}）。"

            else -> error.suggestion ?: "请检查参数后重试。"
        }

        return "$base\n建议: $suggestion"
    }
}

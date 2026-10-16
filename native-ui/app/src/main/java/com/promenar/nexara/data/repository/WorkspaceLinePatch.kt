package com.promenar.nexara.data.repository

import com.promenar.nexara.domain.repository.PatchError
import com.promenar.nexara.domain.repository.PatchOperation

internal sealed interface WorkspaceLinePatchResult {
    data class Applied(val content: String) : WorkspaceLinePatchResult
    data class Rejected(val error: PatchError) : WorkspaceLinePatchResult
}

/** 所有区间都引用原文；插入点位于原第 afterLine 行之后，区间端点允许相邻。 */
internal fun applyWorkspaceLinePatch(
    content: String,
    operations: List<PatchOperation>,
    fileUuid: String,
): WorkspaceLinePatchResult {
    val original = workspaceTextLines(content)
    fun reject(code: String, message: String, index: Int, suggestion: String? = null) =
        WorkspaceLinePatchResult.Rejected(PatchError(
            code = code,
            message = message,
            operationIndex = index,
            fileUuid = fileUuid,
            totalLines = if (code == "INVALID_ACTION") null else original.size,
            suggestion = suggestion,
        ))

    val edits = mutableListOf<OriginalLineEdit>()
    operations.forEachIndexed { index, operation ->
        val start: Int
        val end: Int
        when (operation.action) {
            "replace_lines", "delete_lines" -> {
                val first = operation.startLine
                val last = operation.endLine
                if (first == null || last == null || first < 1 || last < first || last > original.size) {
                    return reject(
                        "LINE_OUT_OF_RANGE",
                        "操作 ${operation.action} 超出文件行数（共 ${original.size} 行）。",
                        index,
                        "请先读取当前文件内容后重试。",
                    )
                }
                start = first - 1
                end = last
                if (operation.expectedContent != null &&
                    original.subList(start, end).joinToString("\n") != operation.expectedContent
                ) {
                    return reject(
                        "CONTEXT_MISMATCH",
                        "patch 上下文与当前文件不一致，已拒绝修改。",
                        index,
                        "请重新读取文件后生成补丁。",
                    )
                }
            }
            "insert_after" -> {
                val after = operation.afterLine
                if (after == null || after !in 0..original.size) {
                    return reject(
                        "LINE_OUT_OF_RANGE",
                        "操作 ${operation.action} 超出文件行数（共 ${original.size} 行）。",
                        index,
                        "请先读取当前文件内容后重试。",
                    )
                }
                start = after
                end = after
            }
            else -> return reject("INVALID_ACTION", "未知的 patch 操作类型: ${operation.action}", index)
        }
        edits += OriginalLineEdit(
            start = start,
            end = end,
            replacement = if (operation.action == "delete_lines") emptyList()
                else workspaceTextLines(operation.newContent.orEmpty()),
            operationIndex = index,
        )
    }

    // 同起点的零宽插入先于非空区间输出；终点插入自然位于该区间的替换结果之后。
    val ordered = edits.sortedWith(compareBy<OriginalLineEdit> { it.start }.thenBy { it.end })
    val output = mutableListOf<String>()
    var cursor = 0
    var previous: OriginalLineEdit? = null
    for (edit in ordered) {
        val duplicateInsertion = edit.start == edit.end && previous?.let {
            it.start == it.end && it.start == edit.start
        } == true
        if (edit.start < cursor || duplicateInsertion) {
            return reject(
                "OVERLAPPING_OPERATIONS",
                if (duplicateInsertion) "同一坐标存在多个插入操作，已拒绝整批修改。"
                else "patch 操作坐标重叠，已拒绝整批修改。",
                edit.operationIndex,
                if (duplicateInsertion) null else "请合并重叠操作并基于同一原始文件坐标重试。",
            )
        }
        output.addAll(original.subList(cursor, edit.start))
        output.addAll(edit.replacement)
        cursor = edit.end
        previous = edit
    }
    output.addAll(original.subList(cursor, original.size))
    return WorkspaceLinePatchResult.Applied(output.joinToString("\n"))
}

/** 使用从零开始的半开区间；start 等于 end 表示插入。 */
private data class OriginalLineEdit(
    val start: Int,
    val end: Int,
    val replacement: List<String>,
    val operationIndex: Int,
)

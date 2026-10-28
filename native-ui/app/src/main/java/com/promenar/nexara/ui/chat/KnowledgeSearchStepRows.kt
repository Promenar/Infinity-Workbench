package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.promenar.nexara.R
import com.promenar.nexara.data.knowledge.KnowledgeSearchData
import com.promenar.nexara.ui.chat.components.RagDetailsSheet
import com.promenar.nexara.ui.chat.components.parseKnowledgeSearchData
import com.promenar.nexara.ui.chat.components.toDetails

/** 一次知识检索工具调用的步骤行内容；[data] 为空表示仍在运行。 */
@Immutable
internal data class KnowledgeSearchStep(
    val callId: String,
    val running: Boolean,
    val data: KnowledgeSearchData?,
)

/**
 * 只有成功或仍在运行的调用才逐行显示；失败、无结构化数据（超出预算或旧数据）时返回 null，
 * 交给通用工具摘要展开查看原始结果。
 */
internal fun knowledgeSearchStep(invocation: ToolInvocationPresentation): KnowledgeSearchStep? {
    if (invocation.name != KNOWLEDGE_SEARCH_TOOL || invocation.failed) return null
    val callId = invocation.call?.id ?: invocation.steps.first().id
    if (!invocation.complete) return KnowledgeSearchStep(callId, running = true, data = null)
    val data = parseKnowledgeSearchData(invocation.results.firstOrNull { it.type == "tool_result" }?.data) ?: return null
    return KnowledgeSearchStep(callId, running = false, data = data)
}

/** 知识检索逐行显示“检索了知识库 · N 条结果”，点按打开检索详情（查询词、引用编号与读取路径）。 */
@Composable
internal fun KnowledgeSearchStepRows(steps: List<KnowledgeSearchStep>, fontSize: Int, modifier: Modifier = Modifier) {
    var opened by remember { mutableStateOf<KnowledgeSearchData?>(null) }
    Column(modifier.fillMaxWidth().testTag(KNOWLEDGE_SEARCH_STEPS_TAG)) {
        steps.forEach { step ->
            key(step.callId) {
                val data = step.data
                CompactStepRow(
                    segments = if (data == null) listOf(stringResource(R.string.chat_step_knowledge_running))
                    else listOf(
                        pluralStringResource(R.plurals.chat_step_knowledge_done, 1, 1),
                        pluralStringResource(R.plurals.chat_inline_retrieval_result_count, data.references.size, data.references.size),
                    ),
                    icon = Icons.AutoMirrored.Rounded.LibraryBooks,
                    onClick = { if (data != null) opened = data },
                    fontSize = fontSize,
                    active = step.running,
                    clickLabel = stringResource(R.string.chat_rag_open_details),
                    tag = KNOWLEDGE_SEARCH_STEP_TAG,
                )
            }
        }
    }
    opened?.let { data ->
        RagDetailsSheet(
            references = data.references,
            kgPaths = data.kgPaths,
            onDismissRequest = { opened = null },
            knowledgeSearch = data.toDetails(),
        )
    }
}

internal const val KNOWLEDGE_SEARCH_TOOL = "knowledge_search"
internal const val KNOWLEDGE_SEARCH_STEPS_TAG = "CHAT_KNOWLEDGE_SEARCH_STEPS"
internal const val KNOWLEDGE_SEARCH_STEP_TAG = "CHAT_KNOWLEDGE_SEARCH_STEP"

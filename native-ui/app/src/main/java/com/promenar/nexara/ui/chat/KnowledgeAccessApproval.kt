package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.knowledge.KnowledgeAccessPreview
import com.promenar.nexara.data.knowledge.KnowledgeAccessTarget
import com.promenar.nexara.ui.common.NexaraIdentifierText
import com.promenar.nexara.ui.theme.NexaraMonospace
import com.promenar.nexara.ui.theme.forReading
import com.promenar.nexara.ui.theme.nexaraDomainColors
import kotlinx.serialization.json.Json

/** 知识库访问授权审批卡的内容：申请原因、要挂载的知识库，以及预览给出的不可挂载原因。 */
@Immutable
data class KnowledgeAccessApproval(
    val reason: String?,
    val targets: List<KnowledgeAccessTarget>,
    val allowed: Boolean,
    val deniedReason: String? = null,
)

internal const val REQUEST_KNOWLEDGE_ACCESS_TOOL = "request_knowledge_access"

private val knowledgeAccessJson = Json { ignoreUnknownKeys = true }

/**
 * 授权目标即完整的 [KnowledgeAccessPreview] JSON（批准与执行时都按它重验），界面直接解码，不另调接口。
 * 不是访问授权工具、或目标无法解码时返回 null，审批卡按通用工具显示。
 */
internal fun knowledgeAccessApproval(toolName: String, executionTarget: String?, reason: String?): KnowledgeAccessApproval? {
    if (toolName != REQUEST_KNOWLEDGE_ACCESS_TOOL) return null
    val preview = executionTarget?.takeIf(String::isNotBlank)
        ?.let { runCatching { knowledgeAccessJson.decodeFromString<KnowledgeAccessPreview>(it) }.getOrNull() }
        ?: return null
    return KnowledgeAccessApproval(
        reason = reason?.trim()?.takeIf(String::isNotEmpty),
        targets = preview.knowledgeBases,
        allowed = preview.allowed,
        deniedReason = preview.reason?.takeIf { !preview.allowed && it.isNotBlank() },
    )
}

/** 正文依次为申请原因（引用样式）、知识库名称与文档数、挂载路径、只读说明。 */
@Composable
internal fun KnowledgeAccessApprovalBody(access: KnowledgeAccessApproval) {
    Column(Modifier.fillMaxWidth().testTag(KNOWLEDGE_ACCESS_BODY_TAG), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        access.reason?.let { reason ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(
                    Modifier.width(3.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                ) {}
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium.forReading(),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 10.dp).testTag(KNOWLEDGE_ACCESS_REASON_TAG),
                )
            }
        }
        access.targets.forEach { target ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KnowledgeAccessField(stringResource(R.string.knowledge_access_label_kb)) {
                    Text(
                        text = pluralStringResource(R.plurals.knowledge_access_documents, target.documentCount, target.title, target.documentCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                KnowledgeAccessField(stringResource(R.string.knowledge_access_label_mount)) {
                    NexaraIdentifierText(
                        text = if (target.alreadyMounted) stringResource(R.string.knowledge_access_already_mounted, target.path) else target.path,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = NexaraMonospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (access.allowed) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.nexaraDomainColors.info,
                    modifier = Modifier.padding(top = 1.dp).size(16.dp),
                )
                Text(
                    text = stringResource(R.string.knowledge_access_read_only),
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(KNOWLEDGE_ACCESS_READ_ONLY_TAG),
                )
            }
        } else {
            access.deniedReason?.let { ApprovalRiskLine(it) }
        }
    }
}

@Composable
private fun KnowledgeAccessField(label: String, value: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        value()
    }
}

internal const val KNOWLEDGE_ACCESS_BODY_TAG = "CHAT_APPROVAL_KNOWLEDGE_ACCESS"
internal const val KNOWLEDGE_ACCESS_REASON_TAG = "CHAT_APPROVAL_KNOWLEDGE_REASON"
internal const val KNOWLEDGE_ACCESS_READ_ONLY_TAG = "CHAT_APPROVAL_KNOWLEDGE_READ_ONLY"

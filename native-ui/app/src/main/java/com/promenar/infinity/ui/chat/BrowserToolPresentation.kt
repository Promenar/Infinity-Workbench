package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.browser.BrowserHandoffReason
import com.promenar.infinity.data.browser.BrowserMode
import com.promenar.infinity.data.browser.BrowserSensitiveKind
import com.promenar.infinity.data.browser.BrowserUiState
import com.promenar.infinity.ui.chat.manager.skills.BROWSER_CLICK
import com.promenar.infinity.ui.chat.manager.skills.BROWSER_CLICKED_PREFIX
import com.promenar.infinity.ui.chat.manager.skills.BROWSER_ELEMENTS_PREFIX
import com.promenar.infinity.ui.chat.manager.skills.BROWSER_OPEN
import com.promenar.infinity.ui.chat.manager.skills.BROWSER_SNAPSHOT
import com.promenar.infinity.ui.chat.manager.skills.BROWSER_TYPE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI

/** 会话页对本会话 Agent 浏览器的访问：步骤行据此显示交接状态并打开浏览器。 */
@Immutable
internal class ChatBrowserAccess(val state: BrowserUiState?, val open: () -> Unit)

internal val LocalChatBrowser = compositionLocalOf<ChatBrowserAccess?> { null }

internal enum class BrowserStepKind { OPEN, SNAPSHOT, CLICK, TYPE, HANDOFF }

/** 一次浏览器工具调用的步骤行内容；[detail] 是主机名、元素名称或输入的文本。 */
@Immutable
internal data class BrowserStep(
    val callId: String,
    val kind: BrowserStepKind,
    val detail: String? = null,
    val count: Int? = null,
    val handoff: BrowserHandoffReason? = null,
    val running: Boolean = false,
    val failed: Boolean = false,
)

private val elementCountPattern = Regex("""^${Regex.escape(BROWSER_ELEMENTS_PREFIX)}(\d+)""", RegexOption.MULTILINE)
private val clickedPattern = Regex("""^${Regex.escape(BROWSER_CLICKED_PREFIX)}e\d+ "(.*)"\.$""", RegexOption.MULTILINE)

internal fun browserStep(invocation: ToolInvocationPresentation): BrowserStep? {
    val name = invocation.name ?: return null
    val args = runCatching { Json.parseToJsonElement(invocation.call?.toolArgs ?: "{}") as? JsonObject }.getOrNull()
    fun arg(key: String) = (args?.get(key) as? JsonPrimitive)?.contentOrNull
    val result = invocation.results.firstOrNull { it.type == "tool_result" }?.content.orEmpty()
    val base = BrowserStep(
        callId = invocation.call?.id ?: invocation.steps.first().id,
        kind = BrowserStepKind.OPEN,
        running = !invocation.complete,
        failed = invocation.failed,
    )
    return when (name) {
        BROWSER_OPEN -> base.copy(detail = arg("url")?.let(::browserHost))
        BROWSER_SNAPSHOT -> base.copy(kind = BrowserStepKind.SNAPSHOT, count = elementCountPattern.find(result)?.groupValues?.get(1)?.toIntOrNull())
        BROWSER_CLICK -> base.copy(kind = BrowserStepKind.CLICK, detail = clickedPattern.find(result)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: arg("ref"))
        BROWSER_TYPE -> {
            val handoff = BrowserSensitiveKind.entries.firstOrNull { result.startsWith("The ${it.modelLabel} field") }?.handoff
            if (handoff != null) base.copy(kind = BrowserStepKind.HANDOFF, handoff = handoff)
            else base.copy(kind = BrowserStepKind.TYPE, detail = arg("text"))
        }
        else -> null
    }
}

/** 网址只取主机名；无法解析时原样显示。 */
internal fun browserHost(url: String): String {
    val withScheme = if (url.contains("://")) url else "https://$url"
    return runCatching { URI(withScheme).host }.getOrNull()?.removePrefix("www.") ?: url
}

/** 只有浏览器调用的工具组按调用逐行显示；点按打开浏览器。 */
@Composable
internal fun BrowserStepRows(steps: List<BrowserStep>, fontSize: Int, modifier: Modifier = Modifier) {
    val browser = LocalChatBrowser.current
    Column(modifier.fillMaxWidth().testTag(BROWSER_STEPS_TAG)) {
        steps.forEach { step -> key(step.callId) { BrowserStepRow(step, browser, fontSize) } }
    }
}

@Composable
private fun BrowserStepRow(step: BrowserStep, browser: ChatBrowserAccess?, fontSize: Int) {
    // 输入调用遇到敏感字段时挂起等待用户，期间显示为“需要你处理”。
    val waitingReason = browser?.state?.takeIf { step.running && step.kind == BrowserStepKind.TYPE && it.mode == BrowserMode.HANDOFF }?.handoff
    val segments = when {
        waitingReason != null -> listOf(stringResource(R.string.browser_step_handoff_waiting), stringResource(handoffTarget(waitingReason)))
        step.kind == BrowserStepKind.HANDOFF -> listOf(stringResource(R.string.browser_step_handoff_done), stringResource(handoffTarget(step.handoff ?: BrowserHandoffReason.LOGIN)))
        step.kind == BrowserStepKind.OPEN -> listOfNotNull(stringResource(R.string.browser_step_open), step.detail)
        step.kind == BrowserStepKind.SNAPSHOT -> listOfNotNull(
            stringResource(R.string.browser_step_snapshot),
            step.count?.let { pluralStringResource(R.plurals.browser_step_snapshot_count, it, it) },
        )
        step.kind == BrowserStepKind.CLICK -> listOfNotNull(stringResource(R.string.browser_step_click), step.detail)
        else -> listOfNotNull(stringResource(R.string.browser_step_type), step.detail?.let { "“${it.take(40)}${if (it.length > 40) "…" else ""}”" })
    }
    CompactStepRow(
        segments = segments,
        icon = Icons.Rounded.Public,
        onClick = { browser?.open?.invoke() },
        fontSize = fontSize,
        active = step.running && waitingReason == null,
        clickLabel = stringResource(R.string.browser_cd_open),
        tag = BROWSER_STEP_TAG,
        tone = if (waitingReason != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        failureText = if (step.failed) pluralStringResource(R.plurals.chat_step_failed_count, 1, 1) else null,
    )
}

private fun handoffTarget(reason: BrowserHandoffReason): Int = when (reason) {
    BrowserHandoffReason.LOGIN -> R.string.browser_handoff_target_login
    BrowserHandoffReason.PAYMENT -> R.string.browser_handoff_target_payment
    BrowserHandoffReason.VERIFICATION -> R.string.browser_handoff_target_verification
}

internal const val BROWSER_STEPS_TAG = "CHAT_BROWSER_STEPS"
internal const val BROWSER_STEP_TAG = "CHAT_BROWSER_STEP"

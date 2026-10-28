package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.domain.branch.BranchActivity
import com.promenar.infinity.domain.branch.BranchContextMode
import com.promenar.infinity.domain.branch.BranchDecision
import com.promenar.infinity.domain.branch.BranchPlan
import com.promenar.infinity.domain.branch.BranchPlanAnswer
import com.promenar.infinity.domain.branch.BranchWorkMode
import com.promenar.infinity.domain.branch.BranchWorktree
import com.promenar.infinity.domain.branch.PendingBranchPlan
import com.promenar.infinity.domain.branch.SessionMessageEntry
import com.promenar.infinity.domain.branch.WorktreeEntry
import com.promenar.infinity.ui.git.worktreeSection
import androidx.compose.foundation.layout.heightIn
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "分支方案卡 worktree 沿用主会话 深色", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun branchPlanCardDarkPreview() = BranchFrame {
    BranchPlanCard(pendingPlan(zh = true, approvalMode = "full_access"), onResolve = {})
}

@PreviewTest
@Preview(name = "分支方案卡 展开 审批不同 浅色", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun branchPlanCardExpandedLightPreview() = BranchFrame(dark = false) {
    BranchPlanCard(pendingPlan(zh = true, approvalMode = "workspace"), onResolve = {}, initiallyExpanded = true)
}

@PreviewTest
@Preview(name = "Branch plan card English 360dp font130", widthDp = 360, heightDp = 1250, locale = "en", fontScale = 1.3f)
@Composable
fun branchPlanCardEnglishPreview() = BranchFrame(dark = false) {
    BranchPlanCard(pendingPlan(zh = false, approvalMode = "workspace"), onResolve = {}, initiallyExpanded = true)
}

@PreviewTest
@Preview(name = "分支卡片 消息行 与提示 深色", widthDp = 411, heightDp = 1100, locale = "zh-rCN")
@Composable
fun branchCardsDarkPreview() = BranchFrame { BranchShowcase(zh = true) }

@PreviewTest
@Preview(name = "Branch cards and messages English 360dp font130", widthDp = 360, heightDp = 1400, locale = "en", fontScale = 1.3f)
@Composable
fun branchCardsEnglishPreview() = BranchFrame(dark = false) { BranchShowcase(zh = false) }

@PreviewTest
@Preview(name = "Git worktree 列表 与删除分支确认 深色", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun branchWorktreeDarkPreview() = BranchFrame { WorktreeShowcase(zh = true) }

@PreviewTest
@Preview(name = "Git worktrees and delete branch English 360dp font130", widthDp = 360, heightDp = 1100, locale = "en", fontScale = 1.3f)
@Composable
fun branchWorktreeEnglishPreview() = BranchFrame(dark = false) { WorktreeShowcase(zh = false) }

@Composable
private fun WorktreeShowcase(zh: Boolean) {
    val entries = listOf(
        WorktreeEntry("/root/billing-service", "B-native-refactor", "a1b2c3d4", isMain = true, sessionId = "s1", sessionTitle = if (zh) "拆分计费模块" else "Split billing module"),
        WorktreeEntry("/root/billing-tests", "feat/billing-tests", "b2c3d4e5", isMain = false, sessionId = "s2", sessionTitle = if (zh) "补齐计费模块单元测试" else "Add billing unit tests"),
        WorktreeEntry("/root/rounding", null, "c3d4e5f6a7", isMain = false),
    )
    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 520.dp)) {
        worktreeSection(entries, currentPath = entries[0].path, onSelect = {})
    }
    com.promenar.infinity.ui.workspace.DeleteSessionDialogContent(
        summary = null,
        keep = true,
        onKeepChange = {},
        onConfirm = {},
        onCancel = {},
        worktreeBranch = "feat/billing-tests",
        removeWorktree = true,
    )
}

@Composable
private fun BranchShowcase(zh: Boolean) {
    val title = if (zh) "补齐计费模块单元测试" else "Add billing unit tests"
    BranchCardContent(title, BranchActivity.GENERATING, 0, "feat/billing-tests", onOpen = {})
    BranchCardContent(if (zh) "整理退款文档" else "Tidy refund docs", BranchActivity.WAITING_INPUT, 0, null, onOpen = {})
    BranchCardContent(title, BranchActivity.ROUND_DONE, 2, "feat/billing-tests-with-a-long-name", onOpen = {})
    BranchCardContent(if (zh) "迁移旧接口" else "Migrate old API", BranchActivity.QUEUED, 0, null, onOpen = {})
    BranchPlanResolvedView(BranchPlanResolved(title, BranchPlanAnswer(BranchDecision.HERE, BranchWorkMode.SHARED, BranchContextMode.FRESH, "workspace")))
    BranchPlanResolvedView(BranchPlanResolved(title, BranchPlanAnswer(BranchDecision.DECLINE, BranchWorkMode.SHARED, BranchContextMode.FRESH, "workspace")))
    SessionMessageRow(
        SessionMessageEntry(
            SessionMessageEntry.Direction.RECEIVED, "s-branch", SessionMessageEntry.PeerRelation.BRANCH, title,
            if (zh) "新增 18 个用例，16 个通过。2 个失败都指向折扣金额的舍入方式：满减后按分截断，退款按四舍五入。"
            else "Added 18 cases, 16 pass. Both failures point to discount rounding: truncation after promotions, half-up on refunds.",
            NOW,
        ),
        onOpenPeer = {},
    )
    SessionMessageRow(
        SessionMessageEntry(
            SessionMessageEntry.Direction.SENT, "s-branch", SessionMessageEntry.PeerRelation.BRANCH, title,
            if (zh) "舍入规则已统一到 billing-discount，请重跑两个失败用例。" else "Rounding now lives in billing-discount; please rerun the two failing cases.",
            NOW + 60_000,
        ),
    )
    BranchAutoWakePausedNotice()
    BranchOriginCard(
        if (zh) "拆分计费模块" else "Split billing module",
        if (zh) "为 billing/ 下折扣、税费、退款三条路径补单元测试" else "Add unit tests for discount, tax and refund paths under billing/",
        onOpenParent = {},
    )
    BranchOriginCard(null, if (zh) "为 billing/ 下折扣、税费、退款三条路径补单元测试" else "Add unit tests for billing paths")
}

private fun pendingPlan(zh: Boolean, approvalMode: String) = PendingBranchPlan(
    sessionId = "s-main",
    assistantMessageId = "m1",
    toolCallId = "call-$approvalMode-$zh",
    parentApprovalMode = "full_access",
    plan = BranchPlan(
        title = if (zh) "补齐计费模块单元测试" else "Add billing unit tests",
        goal = if (zh) "为折扣、税费、退款三条路径补单元测试。验收：./gradlew :billing:test 跑通，失败用例保留并注明原因。"
        else "Add unit tests for discount, tax and refund. Done when ./gradlew :billing:test passes; keep failing cases with reasons.",
        steps = if (zh) listOf("读折扣、税费、退款三个包的公开接口", "每条路径补正常、边界、异常用例", "跑 :billing:test，失败用例写明原因")
        else listOf("Read the public APIs of the three packages", "Add normal, edge and error cases per path", "Run :billing:test and explain failures"),
        exclusions = if (zh) "不调整模块拆分，不改业务代码，不动 build.gradle。" else "No module split, no business code, no build.gradle changes.",
        workMode = BranchWorkMode.WORKTREE,
        worktree = BranchWorktree(
            branch = "feat/billing-tests",
            base = "a1b2c3d",
            directory = "../billing-tests",
            reason = if (zh) "主会话同时在改 billing/ 下的代码，分开目录避免互相覆盖。" else "The main session is editing billing/ too; a separate directory avoids overwrites.",
        ),
        communication = if (zh) "每轮结束发新增用例数、失败用例与疑似缺陷。" else "After each round, report new cases, failures and suspected bugs.",
        contextMode = BranchContextMode.INHERIT,
        approvalMode = approvalMode,
    ),
)

private const val NOW = 1_761_632_000_000L

@Composable
private fun BranchFrame(dark: Boolean = true, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

package com.promenar.infinity.ui.startup

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.startup.StartupIssue
import com.promenar.infinity.startup.StartupIssueScope
import com.promenar.infinity.startup.StartupScopeKind
import com.promenar.infinity.startup.StartupSeverity
import com.promenar.infinity.startup.StartupStep
import com.promenar.infinity.startup.StartupSubsystem
import org.junit.Test

class StartupIssuesUiTest {
    private fun issue(kind: StartupScopeKind, id: String) = StartupIssue(
        "i-$id", StartupStep.WORKSPACE_JOURNAL, StartupSeverity.ISOLATED,
        StartupIssueScope(kind, id, id), "摘要", emptySet(), 0,
    )

    @Test
    fun `会话本身或其工作区被隔离时显示暂停写入`() {
        val issues = listOf(issue(StartupScopeKind.WORKSPACE_ROOT, "root-a"), issue(StartupScopeKind.SESSION, "s-b"))

        assertThat(issues.affectsSession("s-x", "root-a")).isTrue()
        assertThat(issues.affectsSession("s-b", null)).isTrue()
        assertThat(issues.affectsSession("s-x", "root-x")).isFalse()
        assertThat(issues.affectsSession(null, null)).isFalse()
    }

    @Test
    fun `子系统问题不让单个会话显示暂停写入`() {
        val issues = listOf(StartupIssue(
            "v", StartupStep.VECTOR_QUEUE, StartupSeverity.ISOLATED,
            StartupIssueScope.subsystem(StartupSubsystem.VECTOR_QUEUE), "摘要", emptySet(), 0,
        ))

        assertThat(issues.affectsSession("s", "root")).isFalse()
    }

    @Test
    fun `全局关键步骤共用数据标题，可隔离步骤各有标题`() {
        val (critical, isolatable) = StartupStep.entries.partition { it.critical }

        assertThat(critical.map { it.titleRes() }.toSet()).hasSize(1)
        assertThat(isolatable.map { it.titleRes() }.toSet()).hasSize(isolatable.size)
        assertThat(isolatable.map { it.titleRes() }).doesNotContain(critical.first().titleRes())
    }
}

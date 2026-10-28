package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.TaskStep
import org.junit.Test

class TaskPanelStateTest {
    private val tree = listOf(
        TaskStep(
            id = "root",
            title = "完成发布检查",
            children = listOf(
                TaskStep(id = "done", title = "安全检查", status = "done", sortOrder = 0),
                TaskStep(id = "current", title = "验证视觉回归", status = "doing", sortOrder = 1),
            ),
        ),
    )

    @Test
    fun `real generation shows pulsing generating capsule`() {
        val state = taskPanelUiState(tree, isGenerating = true)

        assertThat(state).isNotNull()
        assertThat(state!!.capsuleMode).isEqualTo(TaskCapsuleMode.GENERATING)
        assertThat(state.shouldPulse).isTrue()
        assertThat(state.currentStepTitle).isEqualTo("验证视觉回归")
        assertThat(state.doneCount).isEqualTo(1)
        assertThat(state.totalCount).isEqualTo(2)
    }

    @Test
    fun `stopped generation keeps static pending capsule`() {
        val state = taskPanelUiState(tree, isGenerating = false)

        assertThat(state).isNotNull()
        assertThat(state!!.capsuleMode).isEqualTo(TaskCapsuleMode.PENDING)
        assertThat(state.shouldPulse).isFalse()
        assertThat(state.pendingCount).isEqualTo(1)
    }

    @Test
    fun `completed tree keeps a completed card without pulsing`() {
        val completed = tree.map { root ->
            root.copy(children = root.children.map { it.copy(status = "done") })
        }

        val state = taskPanelUiState(completed, isGenerating = true)!!
        assertThat(state.isCompleted).isTrue()
        assertThat(state.pendingCount).isEqualTo(0)
        assertThat(state.doneCount).isEqualTo(2)
        assertThat(state.shouldPulse).isFalse()
    }

    @Test
    fun `checklist lists group and leaves in plan order with depth and status`() {
        val state = taskPanelUiState(tree, isGenerating = false)!!

        assertThat(state.steps.map { Triple(it.id, it.depth, it.status) }).containsExactly(
            Triple("root", 0, TaskPanelStepStatus.TODO),
            Triple("done", 1, TaskPanelStepStatus.DONE),
            Triple("current", 1, TaskPanelStepStatus.DOING),
        ).inOrder()
        assertThat(state.steps.first().isGroup).isTrue()
        assertThat(state.steps.drop(1).none { it.isGroup }).isTrue()
    }

    @Test
    fun `empty tree has no panel`() {
        assertThat(taskPanelUiState(emptyList(), isGenerating = false)).isNull()
    }

    @Test
    fun `doing leaf wins over earlier todo leaf`() {
        val reordered = tree.map { root ->
            root.copy(
                children = listOf(
                    TaskStep(id = "todo", title = "较早待办", status = "todo", sortOrder = 0),
                    TaskStep(id = "doing", title = "当前进行", status = "doing", sortOrder = 1),
                ),
            )
        }

        assertThat(taskPanelUiState(reordered, false)!!.currentStepTitle).isEqualTo("当前进行")
    }

    @Test
    fun `manual completion targets every unfinished leaf only`() {
        val ids = unfinishedLeaves(
            tree + TaskStep(id = "dropped", title = "已放弃", status = "dropped"),
        )

        assertThat(ids.map { it.id }).containsExactly("current")
    }

    @Test
    fun `hidden plan bar stays hidden until plan progress changes`() {
        val state = taskPanelUiState(tree, isGenerating = false)!!
        val hidden = state.visibilitySignature()

        assertThat(isTaskPlanBarVisible(state, hiddenSignature = null)).isTrue()
        assertThat(isTaskPlanBarVisible(state, hidden)).isFalse()
        // 重新生成相同内容（如重组或生成状态变化）不应让隐藏的计划条重新出现。
        assertThat(isTaskPlanBarVisible(taskPanelUiState(tree, isGenerating = true), hidden)).isFalse()

        val advanced = taskPanelUiState(
            tree.map { root -> root.copy(children = root.children.map { it.copy(status = "done") }) },
            isGenerating = false,
        )
        assertThat(isTaskPlanBarVisible(advanced, hidden)).isTrue()
        assertThat(isTaskPlanBarVisible(null, hiddenSignature = null)).isFalse()
    }

    @Test
    fun `completed plan hides after linger only when completion was observed and generation ended`() {
        val running = taskPanelUiState(tree, isGenerating = false)!!
        val completed = taskPanelUiState(
            tree.map { root -> root.copy(children = root.children.map { it.copy(status = "done") }) },
            isGenerating = false,
        )!!

        assertThat(completedPlanAutoHideDelay(null, isGenerating = false, observedIncomplete = true)).isNull()
        assertThat(completedPlanAutoHideDelay(running, isGenerating = false, observedIncomplete = true)).isNull()
        // 生成尚未结束时保留“全部完成”，避免模型收尾时计划区闪退。
        assertThat(completedPlanAutoHideDelay(completed, isGenerating = true, observedIncomplete = true)).isNull()
        assertThat(completedPlanAutoHideDelay(completed, isGenerating = false, observedIncomplete = true))
            .isEqualTo(COMPLETED_PLAN_LINGER_MS)
        assertThat(completedPlanAutoHideDelay(completed, isGenerating = false, observedIncomplete = false)).isEqualTo(0L)
    }
}

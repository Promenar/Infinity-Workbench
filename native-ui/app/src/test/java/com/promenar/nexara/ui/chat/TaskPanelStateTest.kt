package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.TaskStep
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
    fun `completed tree removes task card and capsule`() {
        val completed = tree.map { root ->
            root.copy(children = root.children.map { it.copy(status = "done") })
        }

        assertThat(taskPanelUiState(completed, isGenerating = false)).isNull()
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
        val ids = unfinishedTaskLeafIds(
            tree + TaskStep(id = "dropped", title = "已放弃", status = "dropped"),
        )

        assertThat(ids).containsExactly("current")
    }
}

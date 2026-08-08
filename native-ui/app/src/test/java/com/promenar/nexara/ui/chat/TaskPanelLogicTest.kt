package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.TaskStep
import com.promenar.nexara.ui.chat.components.TaskCapsuleMode
import com.promenar.nexara.ui.chat.components.taskPanelState
import org.junit.Test

class TaskPanelLogicTest {

    private val tree = listOf(
        TaskStep(
            id = "root",
            title = "完成发布准备",
            children = listOf(
                TaskStep(id = "done", title = "整理资料", status = "done"),
                TaskStep(id = "doing", title = "验证发布包", status = "doing"),
                TaskStep(id = "todo", title = "通知团队", status = "todo")
            )
        )
    )

    @Test
    fun `generating task capsule uses live generation state and pulses`() {
        val state = taskPanelState(tree, isGenerating = true)

        assertThat(state?.capsuleMode).isEqualTo(TaskCapsuleMode.GENERATING)
        assertThat(state?.shouldPulse).isTrue()
        assertThat(state?.unfinishedLeafCount).isEqualTo(2)
    }

    @Test
    fun `stopped unfinished task capsule is pending and never pulses`() {
        val state = taskPanelState(tree, isGenerating = false)

        assertThat(state?.capsuleMode).isEqualTo(TaskCapsuleMode.PENDING)
        assertThat(state?.shouldPulse).isFalse()
        assertThat(state?.firstUnfinishedLeaf?.id).isEqualTo("doing")
    }

    @Test
    fun `doing leaf takes display priority over an earlier todo leaf`() {
        val reordered = tree.map { root ->
            root.copy(
                children = listOf(
                    TaskStep(id = "todo-first", title = "稍后处理", status = "todo", sortOrder = 0),
                    TaskStep(id = "doing-second", title = "当前处理", status = "doing", sortOrder = 1)
                )
            )
        }

        val state = taskPanelState(reordered, isGenerating = false)

        assertThat(state?.firstUnfinishedLeaf?.id).isEqualTo("doing-second")
    }

    @Test
    fun `completed tree no longer produces a visible task panel`() {
        val complete = tree.map { root ->
            root.copy(children = root.children.map { it.copy(status = "done") })
        }

        assertThat(taskPanelState(complete, isGenerating = false)).isNull()
    }
}

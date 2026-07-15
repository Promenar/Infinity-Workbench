package com.promenar.nexara.ui.rag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RagHomeInteractionTest {

    @Test
    fun `移动失败会保留原选择并保持移动弹层`() {
        val selected = mutableListOf("doc-1", "doc-2")

        val closeSheet = applyMoveSelectionResult(
            selectedIds = selected,
            movingIds = setOf("doc-1", "doc-2"),
            failedIds = setOf("doc-1", "doc-2"),
            succeeded = false,
        )

        assertThat(closeSheet).isFalse()
        assertThat(selected).containsExactly("doc-1", "doc-2").inOrder()
    }

    @Test
    fun `移动成功才清除本次选择并关闭移动弹层`() {
        val selected = mutableListOf("doc-1", "doc-2", "new-selection")

        val closeSheet = applyMoveSelectionResult(
            selectedIds = selected,
            movingIds = setOf("doc-1", "doc-2"),
            failedIds = emptySet(),
            succeeded = true,
        )

        assertThat(closeSheet).isTrue()
        assertThat(selected).containsExactly("new-selection")
    }

    @Test
    fun `部分移动失败只保留失败文档的选择上下文`() {
        val selected = mutableListOf("doc-1", "doc-2", "new-selection")

        val closeSheet = applyMoveSelectionResult(
            selectedIds = selected,
            movingIds = setOf("doc-1", "doc-2"),
            failedIds = setOf("doc-2"),
            succeeded = false,
        )

        assertThat(closeSheet).isFalse()
        assertThat(selected).containsExactly("new-selection", "doc-2").inOrder()
    }
}

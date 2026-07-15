package com.promenar.nexara.ui.rag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RagFolderUiStateTest {

    @Test
    fun `首个数据库快照到达前保持加载态`() {
        assertThat(
            resolveRagFolderContentState(
                hasLoaded = false,
                hasLoadError = false,
                documentCount = 0,
            )
        ).isEqualTo(RagFolderContentState.Loading)
    }

    @Test
    fun `首个快照为空时才显示空文档状态`() {
        assertThat(
            resolveRagFolderContentState(
                hasLoaded = true,
                hasLoadError = false,
                documentCount = 0,
            )
        ).isEqualTo(RagFolderContentState.Empty)
    }

    @Test
    fun `文件夹加载失败时显示可恢复错误`() {
        assertThat(
            resolveRagFolderContentState(
                hasLoaded = false,
                hasLoadError = true,
                documentCount = 0,
            )
        ).isEqualTo(RagFolderContentState.Error)
    }

    @Test
    fun `零文档永远不算全选`() {
        assertThat(isAllRagFolderDocumentsSelected(documentCount = 0, selectedCount = 0)).isFalse()
    }

    @Test
    fun `非空文档全部选中时才算全选`() {
        assertThat(isAllRagFolderDocumentsSelected(documentCount = 2, selectedCount = 2)).isTrue()
        assertThat(isAllRagFolderDocumentsSelected(documentCount = 2, selectedCount = 1)).isFalse()
    }

    @Test
    fun `单文档初始状态始终暴露可操作选择控件`() {
        assertThat(shouldShowRagDocumentCheckbox()).isTrue()
    }

    @Test
    fun `选择操作栏出现时为正文预留底部空间`() {
        assertThat(ragFolderSelectionBarReservedHeight(hasSelection = false)).isEqualTo(0)
        assertThat(ragFolderSelectionBarReservedHeight(hasSelection = true)).isAtLeast(160)
    }
}

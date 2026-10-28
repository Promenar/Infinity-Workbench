package com.promenar.infinity.ui.chat.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FileNodeActivationTest {
    @Test
    fun `多选模式下目录和文件都只切换选择`() {
        assertThat(
            resolveFileNodeActivation(
                isMultiSelectMode = true,
                isDirectory = true,
                hasFolderClick = true,
                hasFileClick = false,
            ),
        ).isEqualTo(FileNodeActivation.ToggleSelection)
        assertThat(
            resolveFileNodeActivation(
                isMultiSelectMode = true,
                isDirectory = false,
                hasFolderClick = false,
                hasFileClick = true,
            ),
        ).isEqualTo(FileNodeActivation.ToggleSelection)
    }

    @Test
    fun `无目录回调时目录保持内联展开`() {
        assertThat(
            resolveFileNodeActivation(
                isMultiSelectMode = false,
                isDirectory = true,
                hasFolderClick = false,
                hasFileClick = false,
            ),
        ).isEqualTo(FileNodeActivation.ToggleExpansion)
    }

    @Test
    fun `有目录回调时导航而不是展开`() {
        assertThat(
            resolveFileNodeActivation(
                isMultiSelectMode = false,
                isDirectory = true,
                hasFolderClick = true,
                hasFileClick = false,
            ),
        ).isEqualTo(FileNodeActivation.NavigateFolder)
    }

    @Test
    fun `有打开回调时普通文件打开文件`() {
        assertThat(
            resolveFileNodeActivation(
                isMultiSelectMode = false,
                isDirectory = false,
                hasFolderClick = false,
                hasFileClick = true,
            ),
        ).isEqualTo(FileNodeActivation.OpenFile)
    }

    @Test
    fun `没有打开回调时普通文件不提供主激活动作`() {
        assertThat(
            resolveFileNodeActivation(
                isMultiSelectMode = false,
                isDirectory = false,
                hasFolderClick = false,
                hasFileClick = false,
            ),
        ).isNull()
    }

    @Test
    fun `没有操作回调时不提供更多菜单或多选入口`() {
        assertThat(
            hasFileNodeMenuActions(
                isDirectory = false,
                hasReindex = false,
                hasDelete = false,
                hasRename = false,
                hasMove = false,
                hasExtractKG = false,
                hasViewKG = false,
                hasCopy = false,
            ),
        ).isFalse()
        assertThat(
            supportsFileMultiSelect(
                hasReindex = false,
                hasDelete = false,
            ),
        ).isFalse()
    }

    @Test
    fun `单项操作可显示更多菜单但不会伪造批量入口`() {
        assertThat(
            hasFileNodeMenuActions(
                isDirectory = false,
                hasReindex = false,
                hasDelete = false,
                hasRename = true,
                hasMove = false,
                hasExtractKG = false,
                hasViewKG = false,
                hasCopy = false,
            ),
        ).isTrue()
        assertThat(
            supportsFileMultiSelect(
                hasReindex = false,
                hasDelete = false,
            ),
        ).isFalse()
    }

    @Test
    fun `选择由外部接管时面板不再叠出自己的批量栏`() {
        // 工作区浏览器自带选择栏；面板自身的批量栏读的是内部空选择集，会多出一条“已选 0 项”。
        assertThat(
            showsFilesPanelBatchBar(showSelectionOverlay = true, selectionOwnedExternally = true, isMultiSelectMode = true),
        ).isFalse()
        assertThat(
            showsFilesPanelBatchBar(showSelectionOverlay = true, selectionOwnedExternally = false, isMultiSelectMode = true),
        ).isTrue()
        assertThat(
            showsFilesPanelBatchBar(showSelectionOverlay = false, selectionOwnedExternally = false, isMultiSelectMode = true),
        ).isFalse()
        assertThat(
            showsFilesPanelBatchBar(showSelectionOverlay = true, selectionOwnedExternally = false, isMultiSelectMode = false),
        ).isFalse()
    }
}

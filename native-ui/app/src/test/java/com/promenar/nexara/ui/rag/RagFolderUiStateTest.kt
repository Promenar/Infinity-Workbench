package com.promenar.nexara.ui.rag

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

class RagFolderUiStateTest {

    private fun source(relativePath: String): String {
        val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        return projectRoot.resolve(relativePath).readText()
    }

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
    fun `选择操作栏不使用固定高度预留空间`() {
        assertThat(ragFolderSelectionBarReservedHeight(hasSelection = false)).isEqualTo(0)
        assertThat(ragFolderSelectionBarReservedHeight(hasSelection = true)).isEqualTo(0)
    }

    @Test
    fun `文件夹页和文档行使用单层Material表面`() {
        val screen = source("src/main/java/com/promenar/nexara/ui/rag/RagFolderScreen.kt")
        val row = source("src/main/java/com/promenar/nexara/ui/rag/components/RagDocItem.kt")

        assertThat(screen).doesNotContain("NexaraGlassCard")
        assertThat(row).doesNotContain("NexaraGlassCard")
        assertThat(row).contains("ListItem(")
    }

    @Test
    fun `顶部全选使用文字动作或大字体菜单`() {
        val screen = source("src/main/java/com/promenar/nexara/ui/rag/RagFolderScreen.kt")

        assertThat(screen).contains("TextButton(")
        assertThat(screen).contains("DropdownMenuItem(")
        assertThat(screen).contains("fontScale >= 1.5f")
    }

    @Test
    fun `移动目录使用有界懒列表且二十项末项可达`() {
        val screen = source("src/main/java/com/promenar/nexara/ui/rag/RagFolderScreen.kt")

        assertThat(screen).contains("RAG_FOLDER_MOVE_LIST")
        assertThat(screen).contains("LazyColumn(")
        assertThat(screen).contains("heightIn(max = 420.dp)")
        assertThat(screen).doesNotContain("folders.forEach")
    }

    @Test
    fun `状态标签不使用通用五十圆角`() {
        val status = source("src/main/java/com/promenar/nexara/ui/rag/components/RagStatusChip.kt")

        assertThat(status).doesNotContain("RoundedCornerShape(50)")
        assertThat(status).contains("MaterialTheme.colorScheme")
        assertThat(status).contains("stateDescription")
    }

    @Test
    fun `文件夹内容具有可测试状态缝并由Scaffold实测选择栏高度`() {
        val screen = source("src/main/java/com/promenar/nexara/ui/rag/RagFolderScreen.kt")

        assertThat(screen).contains("internal fun RagFolderScreenContent(")
        assertThat(screen).contains("bottomBar =")
        assertThat(screen).contains("RAG_FOLDER_SELECTION_BAR")
        assertThat(screen).doesNotContain("align(Alignment.BottomCenter)")
    }

    @Test
    fun `文档行在二倍字体下把状态移出标题尾部`() {
        val row = source("src/main/java/com/promenar/nexara/ui/rag/components/RagDocItem.kt")

        assertThat(row).contains("fontScale >= 1.5f")
        assertThat(row).contains("RagDocItemLargeFont(")
    }
}

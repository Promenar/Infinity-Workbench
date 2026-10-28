package com.promenar.infinity.ui.chat.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

/** 会话检视器文件页的长按操作沿用目录工作区的选择栏、粘贴栏与对话框。 */
class PrivateWorkspaceFileOpsTest {
    @get:Rule
    val rule = createComposeRule()

    private val folder = entry("folder", "资料", "/资料", isDirectory = true)
    private val note = entry("note", "笔记.md", "/笔记.md")

    @Test
    fun 长按进入多选后可改名与删除且不提供复制() {
        var state by mutableStateOf(PrivateFileOpsState())
        val renames = CopyOnWriteArrayList<Pair<String, String>>()
        var deletes = 0
        setContent({ state }, actions(
            onToggleSelect = { entry ->
                state = state.copy(selection = if (entry.uuid in state.selection) state.selection - entry.uuid else state.selection + (entry.uuid to entry))
            },
            onRename = { uuid, name, done -> renames += uuid to name; done(null) },
            onDelete = { deletes++ },
        ))

        rule.onNodeWithText("笔记.md").performTouchInput { longClick() }
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_PRIVATE_SELECTION_BAR).assertExists()
        rule.onNodeWithTag("directory_action_copy").assertDoesNotExist()
        rule.onNodeWithTag("directory_action_rename").assertIsEnabled().performClick()
        rule.onNodeWithTag("workspace_name_input").performTextReplacement("改名.md")
        rule.onNodeWithTag("workspace_name_confirm").performClick()
        rule.waitForIdle()
        assertThat(renames).containsExactly("note" to "改名.md")

        // 多选期间点按另一条目只切换选择，改名不可用。
        rule.onNodeWithText("资料").performClick()
        rule.onNodeWithTag("directory_action_rename").assertIsNotEnabled()
        rule.onNodeWithTag("directory_action_delete").performClick()
        rule.onNodeWithTag("directory_delete_confirm").performClick()
        rule.waitForIdle()
        assertThat(deletes).isEqualTo(1)
    }

    @Test
    fun 剪切后在原目录不可粘贴() {
        val state = PrivateFileOpsState(clipboard = PrivateFileClipboard(listOf("note"), listOf("/笔记.md")))
        val pastes = CopyOnWriteArrayList<String>()
        setContent({ state }, actions(onPaste = { pastes += it }))
        rule.onNodeWithTag("directory_paste").assertIsNotEnabled()
        assertThat(pastes).isEmpty()
    }

    private fun setContent(state: () -> PrivateFileOpsState, actions: PrivateFileActions) {
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                WorkspaceFolderBrowser(
                    workspaceRootUuid = ROOT,
                    workspaceRepo = repository(),
                    sessionId = null,
                    externalFolderManager = null,
                    searchQuery = "",
                    rootFiles = listOf(folder, note),
                    isLoading = false,
                    hasLoadError = false,
                    onRetryLoad = {},
                    onClearSearch = {},
                    fileOps = state(),
                    fileActions = actions,
                )
            }
        }
    }

    private fun actions(
        onToggleSelect: (FileEntry) -> Unit = {},
        onPaste: (String) -> Unit = {},
        onDelete: () -> Unit = {},
        onRename: (String, String, (com.promenar.infinity.domain.workspace.DirectoryFileOpCode?) -> Unit) -> Unit = { _, _, _ -> },
    ) = PrivateFileActions(
        onToggleSelect = onToggleSelect,
        onClearSelection = {},
        onCut = {},
        onPaste = onPaste,
        onCancelClipboard = {},
        onDelete = onDelete,
        onRename = onRename,
        onDismissOutcome = {},
    )

    private fun repository(): IWorkspaceRepository = Proxy.newProxyInstance(
        IWorkspaceRepository::class.java.classLoader,
        arrayOf(IWorkspaceRepository::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "observeChildren" -> flowOf(emptyList<FileEntry>())
            "searchByName" -> flowOf(emptyList<FileEntry>())
            "toString" -> "PrivateWorkspaceFileOpsTestRepository"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw UnsupportedOperationException(method.name)
        }
    } as IWorkspaceRepository

    private fun entry(uuid: String, name: String, path: String, isDirectory: Boolean = false) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = ROOT,
        parentUuid = ROOT,
        name = name,
        hash = "hash-$uuid",
        mimeType = if (isDirectory) null else "text/markdown",
        isDirectory = isDirectory,
        physicalRootPath = "/fixture",
        materializedPath = path,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private companion object {
        const val ROOT = "workspace-root"
    }
}

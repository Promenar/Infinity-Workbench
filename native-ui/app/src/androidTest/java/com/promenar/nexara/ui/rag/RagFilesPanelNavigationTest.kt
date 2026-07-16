package com.promenar.nexara.ui.rag

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.chat.components.FilesPanel
import com.promenar.nexara.ui.rag.components.RagDocItem
import com.promenar.nexara.ui.rag.components.RagStatus
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class RagFilesPanelNavigationTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun 根目录递归目录文件和多选模式按契约分流() {
        val rootFolder = entry("root-folder", "根目录", isDirectory = true, parentUuid = ROOT)
        val childFolder = entry("child-folder", "子目录", isDirectory = true, parentUuid = rootFolder.uuid)
        val childFile = entry("child-file", "子文件.md", parentUuid = rootFolder.uuid)
        val rootFile = entry("root-file", "根文件.md", parentUuid = ROOT)
        val repo = repository(
            mapOf(rootFolder.uuid to listOf(childFolder, childFile)),
        )
        val folderClicks = CopyOnWriteArrayList<Pair<String, String>>()
        val fileClicks = CopyOnWriteArrayList<String>()
        val reindexClicks = CopyOnWriteArrayList<String>()
        val selectedIds = mutableStateListOf<String>()

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                FilesPanel(
                    workspaceRootUuid = ROOT,
                    workspaceRepo = repo,
                    rootFiles = listOf(rootFolder, rootFile),
                    externalSelectedIds = selectedIds,
                    onReindex = { reindexClicks += it },
                    onFolderClick = { uuid, name -> folderClicks += uuid to name },
                    onFileClick = { fileClicks += it },
                )
            }
        }

        rule.onNodeWithText("根目录").performClick()
        rule.onNodeWithText("子目录").performClick()
        rule.onNodeWithText("子文件.md").performClick()
        rule.onNodeWithText("根文件.md").performClick()
        rule.runOnIdle {
            assertThat(folderClicks).containsExactly(
                "root-folder" to "根目录",
                "child-folder" to "子目录",
            ).inOrder()
            assertThat(fileClicks).containsExactly("child-file", "root-file").inOrder()
            folderClicks.clear()
            fileClicks.clear()
        }

        rule.onNodeWithText("根文件.md").performTouchInput { longClick() }
        rule.onNodeWithTag(UiTags.fileNodeReindex("root-file")).performClick()
        rule.runOnIdle {
            assertThat(reindexClicks).containsExactly("root-file")
        }

        rule.onNodeWithText("根目录").performTouchInput { longClick() }
        rule.onNodeWithTag(UiTags.fileNodeMultiSelect("root-folder")).performClick()
        rule.onNodeWithText("子目录").performClick()
        rule.onNodeWithText("根文件.md").performClick()
        rule.runOnIdle {
            assertThat(folderClicks).isEmpty()
            assertThat(fileClicks).isEmpty()
            assertThat(selectedIds).containsAtLeast("root-folder", "child-folder", "root-file")
        }
    }

    @Test
    fun 未提供打开和操作能力时文件没有伪点击语义或更多入口() {
        val file = entry("read-only", "只读文档.md", parentUuid = ROOT)

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                FilesPanel(
                    workspaceRootUuid = ROOT,
                    workspaceRepo = repository(emptyMap()),
                    rootFiles = listOf(file),
                    onFileClick = null,
                )
            }
        }

        rule.onNodeWithText("只读文档.md").assert(!hasClickAction())
        rule.onNodeWithTag(UiTags.fileNodeOptions("read-only")).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.fileNodeMultiSelect("read-only")).assertDoesNotExist()
    }

    @Test
    fun 未提供目录回调时保留内联展开和收起() {
        val rootFolder = entry("root-folder", "根目录", isDirectory = true, parentUuid = ROOT)
        val childFile = entry("child-file", "子文件.md", parentUuid = rootFolder.uuid)

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                FilesPanel(
                    workspaceRootUuid = ROOT,
                    workspaceRepo = repository(mapOf(rootFolder.uuid to listOf(childFile))),
                    rootFiles = listOf(rootFolder),
                )
            }
        }

        rule.onNodeWithText("子文件.md").assertExists()
        rule.onNodeWithText("根目录").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("子文件.md").assertDoesNotExist()
        rule.onNodeWithText("根目录").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("子文件.md").assertExists()
    }

    @Test
    fun 根节点重排后展开状态仍绑定UUID() {
        val folderA = entry("folder-a", "目录 A", isDirectory = true, parentUuid = ROOT)
        val folderB = entry("folder-b", "目录 B", isDirectory = true, parentUuid = ROOT)
        val childA = entry("child-a", "A 子文件.md", parentUuid = folderA.uuid)
        var reorder: (() -> Unit)? = null

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                var roots by remember { mutableStateOf(listOf(folderA, folderB)) }
                reorder = { roots = listOf(folderB, folderA) }
                FilesPanel(
                    workspaceRootUuid = ROOT,
                    workspaceRepo = repository(mapOf(folderA.uuid to listOf(childA))),
                    rootFiles = roots,
                )
            }
        }

        rule.onNodeWithText("A 子文件.md").assertExists()
        rule.onNodeWithText("目录 A").performClick()
        rule.onNodeWithText("A 子文件.md").assertDoesNotExist()
        rule.runOnIdle { reorder?.invoke() }
        rule.onNodeWithText("A 子文件.md").assertDoesNotExist()
        rule.onNodeWithText("目录 A").performClick()
        rule.onNodeWithText("A 子文件.md").assertExists()
    }

    @Test
    fun 移动目录选择器可滚动并选择最后一项() {
        val file = entry("move-source", "待移动.md", parentUuid = ROOT)
        val folders = (1..20).map { index ->
            entry(
                uuid = "destination-$index",
                name = "目录 $index",
                isDirectory = true,
                parentUuid = ROOT,
            )
        }
        val moveTargets = CopyOnWriteArrayList<Pair<String, String>>()

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                FilesPanel(
                    workspaceRootUuid = ROOT,
                    workspaceRepo = repository(emptyMap()),
                    rootFiles = listOf(file),
                    folders = folders,
                    onMove = { source, target -> moveTargets += source to target },
                )
            }
        }

        rule.onNodeWithTag(UiTags.fileNodeOptions(file.uuid)).performClick()
        rule.onNodeWithTag("files_panel_move_action_${file.uuid}").performClick()
        rule.onNodeWithTag("files_panel_move_list")
            .performScrollToNode(hasTestTag("files_panel_move_destination_destination-20"))
        rule.onNodeWithTag("files_panel_move_destination_destination-20").performClick()

        rule.runOnIdle {
            assertThat(moveTargets).containsExactly("move-source" to "destination-20")
        }
    }

    @Test
    fun 文档复选框与卡片主体事件相互隔离() {
        val checkboxClicks = AtomicInteger(0)
        val documentClicks = AtomicInteger(0)

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                var selected by remember { mutableStateOf(false) }
                RagDocItem(
                    title = "隔离测试文档.md",
                    status = RagStatus.READY,
                    isSelected = selected,
                    showCheckbox = true,
                    onCheckedChange = {
                        selected = it
                        checkboxClicks.incrementAndGet()
                    },
                    onClick = { documentClicks.incrementAndGet() },
                )
            }
        }

        rule.onNode(isToggleable()).performClick()
        rule.runOnIdle {
            assertThat(checkboxClicks.get()).isEqualTo(1)
            assertThat(documentClicks.get()).isEqualTo(0)
        }

        rule.onNodeWithText("隔离测试文档.md").performClick()
        rule.runOnIdle {
            assertThat(checkboxClicks.get()).isEqualTo(1)
            assertThat(documentClicks.get()).isEqualTo(1)
        }
    }

    private fun repository(children: Map<String, List<FileEntry>>): IWorkspaceRepository =
        Proxy.newProxyInstance(
            IWorkspaceRepository::class.java.classLoader,
            arrayOf(IWorkspaceRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "observeChildren" -> flowOf(children[args?.get(1) as String].orEmpty())
                "searchByName" -> flowOf(emptyList<FileEntry>())
                "toString" -> "RagFilesPanelNavigationTestRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException(method.name)
            }
        } as IWorkspaceRepository

    private fun entry(
        uuid: String,
        name: String,
        isDirectory: Boolean = false,
        parentUuid: String,
    ) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = ROOT,
        parentUuid = parentUuid,
        name = name,
        hash = "hash-$uuid",
        mimeType = if (isDirectory) null else "text/markdown",
        isDirectory = isDirectory,
        physicalRootPath = "/fixture",
        materializedPath = "/$name",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private companion object {
        const val ROOT = "workspace-root"
    }
}

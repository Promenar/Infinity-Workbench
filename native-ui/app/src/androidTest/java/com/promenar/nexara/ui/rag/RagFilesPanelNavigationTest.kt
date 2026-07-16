package com.promenar.nexara.ui.rag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.ui.chat.components.FilesPanel
import com.promenar.nexara.ui.chat.components.FileBatchOperationResult
import com.promenar.nexara.ui.rag.components.RagDocItem
import com.promenar.nexara.ui.rag.components.RagStatus
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
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
    fun 大量目录只订阅实际组合行且滚动折叠会取消collector() {
        val directoryCount = 80
        val activeCollectorBound = 32
        val peakCollectorBound = 40
        val roots = (0 until directoryCount).map { index ->
            entry(
                uuid = "bounded-directory-$index",
                name = "有界目录 $index",
                isDirectory = true,
                parentUuid = ROOT,
            )
        }
        val activeCollectors = AtomicInteger(0)
        val maximumCollectors = AtomicInteger(0)

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                FilesPanel(
                    workspaceRootUuid = ROOT,
                    workspaceRepo = trackingRepository(activeCollectors, maximumCollectors),
                    rootFiles = roots,
                )
            }
        }

        rule.waitUntil(timeoutMillis = 5_000) { activeCollectors.get() > 0 }
        rule.runOnIdle {
            assertThat(activeCollectors.get()).isLessThan(activeCollectorBound)
            assertThat(maximumCollectors.get()).isLessThan(peakCollectorBound)
        }

        rule.onNodeWithTag("files_panel_tree_list")
            .performScrollToNode(hasTestTag("files_panel_node_bounded-directory-79"))
        rule.waitUntil(timeoutMillis = 5_000) {
            activeCollectors.get() in 1 until activeCollectorBound
        }
        rule.runOnIdle {
            assertThat(maximumCollectors.get()).isLessThan(peakCollectorBound)
        }

        rule.onNodeWithTag("files_panel_tree_list")
            .performScrollToNode(hasTestTag("files_panel_node_bounded-directory-0"))
        rule.waitUntil(timeoutMillis = 5_000) { activeCollectors.get() > 0 }
        val beforeCollapse = activeCollectors.get()
        rule.onNodeWithTag("files_panel_node_bounded-directory-0").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { activeCollectors.get() < beforeCollapse }
        rule.runOnIdle {
            assertThat(maximumCollectors.get()).isLessThan(peakCollectorBound)
        }
    }

    @Test
    fun 文件菜单重命名图谱复制删除回调按UUID分流() {
        val file = entry("all-actions", "全部操作.md", parentUuid = ROOT)
        val renamed = CopyOnWriteArrayList<Pair<String, String>>()
        val extracted = CopyOnWriteArrayList<String>()
        val viewed = CopyOnWriteArrayList<String>()
        val copied = CopyOnWriteArrayList<String>()
        val deleted = CopyOnWriteArrayList<List<String>>()

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                FilesPanel(
                    workspaceRootUuid = ROOT,
                    workspaceRepo = repository(emptyMap()),
                    rootFiles = listOf(file),
                    onRename = { uuid, name -> renamed += uuid to name },
                    onExtractKG = { extracted += it },
                    onViewKG = { viewed += it },
                    onCopy = { copied += it },
                    onDelete = { ids, onComplete ->
                        deleted += ids.toList()
                        onComplete(FileBatchOperationResult(ids.toList()))
                    },
                )
            }
        }

        rule.onNodeWithTag(UiTags.fileNodeOptions(file.uuid)).performClick()
        rule.onNodeWithTag("files_panel_extract_kg_action_${file.uuid}").performClick()
        rule.onNodeWithTag(UiTags.fileNodeOptions(file.uuid)).performClick()
        rule.onNodeWithTag("files_panel_view_kg_action_${file.uuid}").performClick()
        rule.onNodeWithTag(UiTags.fileNodeOptions(file.uuid)).performClick()
        rule.onNodeWithTag("files_panel_copy_action_${file.uuid}").performClick()
        rule.onNodeWithTag(UiTags.fileNodeOptions(file.uuid)).performClick()
        rule.onNodeWithTag("files_panel_rename_action_${file.uuid}").performClick()
        rule.onNodeWithTag("files_panel_rename_input_${file.uuid}").performTextReplacement("改名后.md")
        rule.onNodeWithTag("files_panel_rename_confirm_${file.uuid}").performClick()
        rule.onNodeWithTag(UiTags.fileNodeOptions(file.uuid)).performClick()
        rule.onNodeWithTag("files_panel_delete_action_${file.uuid}").performClick()

        rule.runOnIdle {
            assertThat(extracted).containsExactly(file.uuid)
            assertThat(viewed).containsExactly(file.uuid)
            assertThat(copied).containsExactly(file.uuid)
            assertThat(renamed).containsExactly(file.uuid to "改名后.md")
            assertThat(deleted).containsExactly(listOf(file.uuid))
        }
    }

    @Test
    fun 非滚动文件树置于父级纵向滚动时最后一项可达() {
        val files = (0 until 30).map { index ->
            entry("parent-scroll-$index", "父滚动文件 $index.md", parentUuid = ROOT)
        }

        rule.setContent {
            NexaraTheme(dynamicColor = false) {
                Box(modifier = Modifier.height(360.dp)) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        FilesPanel(
                            workspaceRootUuid = ROOT,
                            workspaceRepo = repository(emptyMap()),
                            rootFiles = files,
                            useScroll = false,
                        )
                    }
                }
            }
        }

        rule.onNodeWithText("父滚动文件 29.md").performScrollTo().assertIsDisplayed()
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

    private fun trackingRepository(
        activeCollectors: AtomicInteger,
        maximumCollectors: AtomicInteger,
    ): IWorkspaceRepository = Proxy.newProxyInstance(
        IWorkspaceRepository::class.java.classLoader,
        arrayOf(IWorkspaceRepository::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "observeChildren" -> flow<List<FileEntry>> {
                val active = activeCollectors.incrementAndGet()
                maximumCollectors.updateAndGet { previous -> maxOf(previous, active) }
                try {
                    awaitCancellation()
                } finally {
                    activeCollectors.decrementAndGet()
                }
            }
            "searchByName" -> flowOf(emptyList<FileEntry>())
            "toString" -> "RagFilesPanelNavigationTrackingRepository"
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

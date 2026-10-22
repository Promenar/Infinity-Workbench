package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** 锁定目录浏览页头、外部文件夹面板与全站层级切换动效的共享约定。 */
class NavigationMotionContractTest {
    private val sourceRoot = Path.of("app/src/main/java/com/promenar/nexara/ui")

    private fun source(relativePath: String): String = Files.readAllBytes(
        sourceRoot.resolve(relativePath),
    ).toString(Charsets.UTF_8)

    @Test
    fun `动效基准提供层级、同级与局部显隐三类过渡`() {
        val motion = source("theme/Motion.kt")

        listOf("fun sharedAxisX(", "fun sharedAxisY(", "fun fadeThrough(", "val expandIn", "val shrinkOut",
            "fun <S> AnimatedContentTransitionScope<S>.hierarchicalTransition(").forEach { api ->
            assertThat(motion).contains(api)
        }
        // 层级切换深度相同时退化为交叉淡入，不做方向性滑动。
        assertThat(motion).contains("if (from == to) NexaraMotion.fadeThrough()")
    }

    @Test
    fun `目录页头只保留一个返回按钮并由根目录回到外层`() {
        val header = source("common/FolderBreadcrumbs.kt")

        assertThat(header.split("Icons.AutoMirrored.Rounded.ArrowBack").size - 1).isEqualTo(1)
        assertThat(header).contains("onBack = if (atRoot) onExitRoot else onNavigateBack")
        assertThat(header).contains("AnimatedContent(")
        assertThat(header).doesNotContain("\"›\"")

        val knowledge = source("workspace/KnowledgeWorkspaces.kt")
        val directory = source("chat/components/DirectoryWorkspaceBrowser.kt")
        assertThat(knowledge).contains("onExitRoot = onBackToList")
        for ((name, text) in listOf("KnowledgeWorkspaces" to knowledge, "DirectoryWorkspaceBrowser" to directory)) {
            // 文件页与目录页都用统一页头，不再叠加单独的“返回”文字按钮。
            assertWithMessage(name).that(text).doesNotContain("workspace_kb_back")
            assertWithMessage(name).that(text).doesNotContain("resource_explorer_directory_back")
            assertWithMessage(name).that(text).contains("NexaraNavigationHeader(")
        }
    }

    @Test
    fun `所有目录浏览都按层级方向切换内容`() {
        listOf(
            "common/FolderEntriesList.kt",
            "rag/RagHomeScreen.kt",
            "chat/components/WorkspaceFolderBrowser.kt",
            "chat/components/DirectoryWorkspaceBrowser.kt",
            "chat/components/ExternalFoldersPanel.kt",
            "workspace/KnowledgeWorkspaces.kt",
            "workspace/WorkspacePicker.kt",
        ).forEach { path ->
            val text = source(path)
            assertWithMessage(path).that(text).contains("AnimatedContent(")
            assertWithMessage(path).that(text).contains("hierarchicalTransition {")
        }
    }

    @Test
    fun `外部文件夹在同一个全高面板内逐页切换而不是叠放对话框`() {
        val panel = source("chat/components/ExternalFoldersPanel.kt")

        assertThat(panel).doesNotContain("AlertDialog")
        assertThat(panel).contains("height = NexaraSheetHeight.Full")
        assertThat(panel).contains("ExternalMountPage.Manager -> ExternalMountManagerContent(")
        assertThat(panel).contains("is ExternalMountPage.Form -> ExternalMountFormContent(")
        assertThat(panel).contains("ExternalMountPage.Recycle -> ExternalMountRecyclePage(")
        // 管理页按已挂载、添加方式、回收区分组，刷新在页头。
        val manager = panel.substringAfter("internal fun ExternalMountManagerContent(").substringBefore("private fun MountRefreshAction(")
        assertThat(manager).contains("R.string.mount_section_mounted")
        assertThat(manager).contains("R.string.agent_panels_mount_add")
        assertThat(manager).contains("R.string.mount_recycle_title")
        assertThat(manager).contains("MountRefreshAction(")
        assertThat(panel).contains("BackHandler(")
    }

    @Test
    fun `高频路径的状态切换带过渡`() {
        val tabs = source("MainTabScaffold.kt")
        assertThat(tabs).contains("transitionSpec = { NexaraMotion.fadeThrough() }")
        assertThat(tabs).contains("animateColorAsState(")
        assertThat(tabs).contains("expandHorizontally(")

        val rag = source("rag/RagHomeScreen.kt")
        assertThat(rag).contains("label = \"rag_portal_tab\"")
        assertThat(rag).contains("label = \"rag_documents_search\"")

        val pipeline = source("chat/PipelineBubble.kt")
        assertThat(pipeline).contains("visible = internalExpanded && reasoning.isNotBlank()")
        assertThat(pipeline).contains("AnimatedVisibility(visible = isExpanded, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut)")
        assertThat(pipeline).contains("visible = !isGenerating")
        assertThat(pipeline).doesNotContain("if (isGenerating) return")

        val chat = source("chat/ChatScreen.kt")
        assertThat(chat).contains("label = \"chat_send_icon\"")
        assertThat(chat).contains("AnimatedVisibility(visible = progress != null")

        val files = source("chat/components/FilesPanel.kt")
        assertThat(files).contains("label = \"files_panel_state\"")
        assertThat(files).contains("Modifier.animateItem()")
    }

    @Test
    fun `调用方关闭面板时先播放下滑退场`() {
        val sheet = source("common/NexaraBottomSheet.kt")

        assertThat(sheet).contains("sheetState.hide()")
        assertThat(sheet).contains("if (!show && !composed) return")
    }
}

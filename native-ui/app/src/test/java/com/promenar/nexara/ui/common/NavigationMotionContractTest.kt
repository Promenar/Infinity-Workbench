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
        assertThat(chat).contains("visible = dock != null")

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

    @Test
    fun `页面路由统一使用共享 X 轴基准`() {
        val nav = Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/navigation/NavGraph.kt")).toString(Charsets.UTF_8)

        assertThat(nav).contains("enterTransition = { NexaraMotion.sharedAxisXEnter(forward = true) }")
        assertThat(nav).contains("popEnterTransition = { NexaraMotion.sharedAxisXEnter(forward = false) }")
        assertThat(nav).doesNotContain("tween(300)")
    }

    @Test
    fun `聊天消息只淡入不做位移以免干扰跟随滚动`() {
        val chat = source("chat/ChatScreen.kt")

        assertThat(chat).contains(".animateItem(fadeInSpec = ChatItemFadeIn, placementSpec = null, fadeOutSpec = null)")
        // 压缩卡与审批卡同样不做位移。
        assertThat(chat.split("placementSpec = null").size - 1).isAtLeast(3)
        assertThat(chat).doesNotContain("if (panel == null && postProcessTasks.isEmpty() && retry == null) return")
    }

    @Test
    fun `运行信息收为浮岛顶部一行运行坞，统计挂在回复下方`() {
        val dock = source("chat/components/RunDock.kt")
        // 运行坞读屏读完整一句；本次运行统计绑定在回复下方，运行详情与侧栏不再显示。
        assertThat(dock).contains("semantics(mergeDescendants = true)")
        assertThat(source("chat/components/RunDetailSheet.kt")).doesNotContain("GenerationProgress")
        assertThat(source("chat/components/RunSidePanel.kt")).doesNotContain("GenerationProgress")
        assertThat(source("chat/PipelineBubble.kt")).contains("replyRunStats(group.messages)")
        val chat = source("chat/ChatScreen.kt")
        assertThat(chat).doesNotContain("R.string.p0_progress")
        assertThat(chat).doesNotContain("ChatGenerationProgressBar")
        assertThat(chat).doesNotContain("GenerationActivityRow(")
        assertThat(chat).doesNotContain("TaskFloatingPanel(")
        assertThat(chat).doesNotContain("StateBoardPanel(")
        assertThat(chat).contains("RunDockRow(")
        assertThat(chat).contains("RunDetailSheet(")
        // 用时取生成任务自身的开始时间；用户消息时间可能早于本次运行（定时触发、继续处理、旧会话）。
        assertThat(chat).contains("state.generationStartedAt?.takeIf { uiState.isGenerating }")
        assertThat(source("chat/ChatRoute.kt")).contains("generationStartedAt = generationProgress?.startedAt")
        // 生成中输入框仍可编辑。
        assertThat(chat).contains("enabled = true")
        // 宽屏运行信息常驻右侧侧栏，浮岛同时隐藏运行坞；侧栏与运行坞共用同一份运行状态与运行详情。
        assertThat(chat).contains("maxWidth >= com.promenar.nexara.ui.chat.components.RunSidePanelMinWindowWidth")
        assertThat(chat).contains("maxHeight >= com.promenar.nexara.ui.chat.components.RunSidePanelMinWindowHeight")
        assertThat(chat).contains("visible = dock != null && !showRunSidePanel")
        assertThat(chat).contains("RunSidePanel(")
        assertThat(chat.split("RunDetailSheet(").size - 1).isEqualTo(1)
    }

    @Test
    fun `低频页面的标签、状态与列表增删带过渡`() {
        mapOf(
            "git/GitChangesPanel.kt" to listOf("label = \"git_panel_page\"", "label = \"git_panel_tab\"", "label = \"git_panel_title\"", "GitLoadContent("),
            "chat/components/RagDetailsSheet.kt" to listOf("label = \"rag_details_tab\""),
            "settings/SkillsScreen.kt" to listOf("label = \"skills_tab\""),
            "welcome/WelcomeScreen.kt" to listOf("label = \"onboarding_language\"", "label = \"onboarding_step\""),
            "rag/DocEditorScreen.kt" to listOf("label = \"doc_editor_state\"", "exit = ExitTransition.None"),
            "rag/KnowledgeGraphScreen.kt" to listOf("label = \"kg_canvas_phase\"", "label = \"kg_search_phase\""),
            "chat/components/RecycleBinPanel.kt" to listOf("Modifier.animateItem()", "label = \"recycle_operation_icon\""),
            "chat/ResourceExplorerSheet.kt" to listOf("label = \"resource_explorer_recycle_phase\""),
            "common/UnifiedPromptEditor.kt" to listOf("label = \"prompt_editor_tab\""),
            "settings/ProviderModelsScreen.kt" to listOf("Box(Modifier.animateItem())", "label = \"provider_models_sync_icon\""),
            "settings/ProviderListScreen.kt" to listOf(".animateItem()", ".animateContentSize("),
            "settings/ProviderFormScreen.kt" to listOf("animatedFormItem(\"cloud-section\")"),
            "rag/RagFolderScreen.kt" to listOf("Box(Modifier.animateItem())"),
        ).forEach { (path, markers) ->
            val text = source(path)
            markers.forEach { marker -> assertWithMessage(path).that(text).contains(marker) }
        }
        // 文档编辑器切换窗格时不得重建编辑面板：两个窗格仍各自只出现一次。
        val editor = source("rag/DocEditorScreen.kt")
        assertThat(editor.split("EditorPane(\n").size - editor.split("fun EditorPane(\n").size).isEqualTo(1)
    }
}

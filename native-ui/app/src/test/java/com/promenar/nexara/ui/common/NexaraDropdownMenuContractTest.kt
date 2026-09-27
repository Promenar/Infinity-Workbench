package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * 契约测试：锁定全站统一悬浮菜单组件规范与各页面使用契约。
 * 1. NexaraDropdownMenu 必须满足 16dp 大圆角、surfaceContainer 底色、1dp outlineVariant 0.5f 描边与 minWidth 规范；
 * 2. FilesPanel 的悬浮菜单必须锚定在行右侧 trailingContent 的 Box 内，杜绝屏幕左侧 (x=0) 误弹；
 * 3. FilesPanel、RagHomeScreen、RagFolderScreen、ProviderListScreen、ProviderModelsScreen 等统一收敛至 NexaraDropdownMenu。
 */
class NexaraDropdownMenuContractTest {
    private val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }

    private fun commonSource(name: String): String = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/common/$name.kt",
    ).readText()

    private fun screenSource(subpath: String): String = projectRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/$subpath.kt",
    ).readText()

    @Test
    fun `NexaraDropdownMenu 满足 16dp 圆角、表面底色与半透明微描边规范`() {
        val source = commonSource("NexaraDropdownMenu")
        assertThat(source).contains("RoundedCornerShape(16.dp)")
        assertThat(source).contains("MaterialTheme.colorScheme.surfaceContainer")
        assertThat(source).contains("outlineVariant.copy(alpha = 0.5f)")
        assertThat(source).contains("minWidth: Dp = 160.dp")
        assertThat(source).contains("DropdownMenu(")
    }

    @Test
    fun `FilesPanel 悬浮菜单在 trailingContent 局域 Box 中锚定且使用 NexaraDropdownMenu`() {
        val source = screenSource("chat/components/FilesPanel")
        // 验证 FileRow 的 trailingContent 包含包裹 IconButton 与 menuContent 的 Box
        val fileRow = source.substringAfter("private fun FileRow(").substringBefore("\n@Composable\nprivate fun RenameDialog(")
        assertThat(fileRow).contains("trailingContent = onOpenMenu?.let { openMenu ->")
        assertThat(fileRow).contains("Box {")
        assertThat(fileRow).contains("IconButton(")
        assertThat(fileRow).contains("menuContent?.invoke()")

        // 验证 FileNodeRow 将 NexaraDropdownMenu 传给 menuContent
        val fileNodeRow = source.substringAfter("fun FileNodeRow(").substringBefore("\n@Composable\nprivate fun FileRow(")
        assertThat(fileNodeRow).contains("menuContent = {")
        assertThat(fileNodeRow).contains("NexaraDropdownMenu(")
        assertThat(fileNodeRow).contains("Icons.Rounded.Refresh")
        assertThat(fileNodeRow).contains("Icons.Rounded.Hub")
        assertThat(fileNodeRow).contains("Icons.Rounded.AccountTree")
        assertThat(fileNodeRow).contains("Icons.AutoMirrored.Rounded.DriveFileMove")
        assertThat(fileNodeRow).contains("Icons.Rounded.DeleteOutline")
        assertThat(fileNodeRow).contains("NexaraTypography.labelLarge")
        assertThat(fileNodeRow).contains("color = MaterialTheme.colorScheme.error")
    }

    @Test
    fun `知识库与设置页面全部收敛至 NexaraDropdownMenu`() {
        val ragHome = screenSource("rag/RagHomeScreen")
        val ragFolder = screenSource("rag/RagFolderScreen")
        val providerList = screenSource("settings/ProviderListScreen")
        val providerModels = screenSource("settings/ProviderModelsScreen")
        val agentHub = screenSource("hub/AgentHubScreen")
        val pipelineBubble = screenSource("chat/PipelineBubble")
        val chatScreen = screenSource("chat/ChatScreen")

        assertThat(ragHome).contains("NexaraDropdownMenu(")
        assertThat(ragFolder).contains("NexaraDropdownMenu(")
        assertThat(providerList).contains("NexaraDropdownMenu(")
        assertThat(providerModels).contains("NexaraDropdownMenu(")
        assertThat(agentHub).contains("NexaraDropdownMenu(")
        assertThat(pipelineBubble).contains("NexaraDropdownMenu(")
        assertThat(chatScreen).contains("NexaraDropdownMenu(")
    }
}

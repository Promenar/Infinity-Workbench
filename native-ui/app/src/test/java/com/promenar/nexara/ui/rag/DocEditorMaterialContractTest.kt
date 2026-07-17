package com.promenar.nexara.ui.rag

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class DocEditorMaterialContractTest {
    private val moduleRoot: Path = Path.of(System.getProperty("user.dir") ?: ".").let { root ->
        if (Files.isDirectory(root.resolve("src/main"))) root else root.resolve("app")
    }

    private val screenSource: String by lazy {
        String(
            Files.readAllBytes(
                moduleRoot.resolve("src/main/java/com/promenar/nexara/ui/rag/DocEditorScreen.kt"),
            ),
            Charsets.UTF_8,
        )
    }

    private val tagSource: String by lazy {
        String(
            Files.readAllBytes(
                moduleRoot.resolve("src/main/java/com/promenar/nexara/ui/testing/UiTags.kt"),
            ),
            Charsets.UTF_8,
        )
    }

    @Test
    fun `DocEditor 不再直接或间接使用 Glass 表面`() {
        listOf(
            "NexaraGlassCard",
            "GlassSurface",
            "GlassBorder",
            // 该遗留封装内部仍使用 GlassBorder，保留调用会绕过本页的 Glass 字面量扫描。
            "NexaraConfirmDialog",
        ).forEach { forbidden ->
            assertThat(screenSource).doesNotContain(forbidden)
        }
    }

    @Test
    fun `DocEditor 视觉角色全部从 MaterialTheme 获取`() {
        listOf(
            "NexaraColors",
            "NexaraShapes",
            "NexaraTypography",
        ).forEach { forbidden ->
            assertThat(screenSource).doesNotContain(forbidden)
        }

        assertThat(screenSource).contains("MaterialTheme.colorScheme")
        assertThat(screenSource).contains("MaterialTheme.typography")
        assertThat(screenSource).contains("MaterialTheme.shapes")
    }

    @Test
    fun `DocEditor 页面不保留固定字号和手绘描边`() {
        val fixedSp = Regex("""\b\d+(?:\.\d+)?\.sp\b""")
        val markdownFixedFontSize = Regex("""fontSize\s*=\s*\d+""")

        assertThat(fixedSp.findAll(screenSource).toList()).isEmpty()
        assertThat(markdownFixedFontSize.findAll(screenSource).toList()).isEmpty()
        assertThat(screenSource).doesNotContain(".border(")
    }

    @Test
    fun `ViewModel 状态按生命周期收集且不误禁本地预览控制器`() {
        val routeSource = screenSource
            .substringAfter("fun DocEditorScreen(")
            .substringBefore("fun DocEditorRouteContent(")

        assertThat(routeSource).contains("collectAsStateWithLifecycle()")
        assertThat(routeSource).doesNotContain("collectAsState()")
        // 扫描范围刻意只覆盖 Route；本地预览快照控制器不受该 ViewModel 契约约束。
    }

    @Test
    fun `可保存状态按工作区和文档复合身份隔离`() {
        val routeSource = screenSource
            .substringAfter("fun DocEditorScreen(")
            .substringBefore("fun DocEditorScreenContent(")

        assertThat(routeSource).contains("stateKey = workspaceRootUuid to docId")
        assertThat(routeSource).contains("rememberSaveable(stateKey)")
    }

    @Test
    fun `Material 编辑器骨架注册并使用稳定结构锚点`() {
        val requiredTags = listOf(
            "DOC_EDITOR_IDENTITY",
            "DOC_EDITOR_MODE_SELECTOR",
            "DOC_EDITOR_MAIN_PANE",
            "DOC_EDITOR_SPLIT_DIVIDER",
            "DOC_EDITOR_METADATA",
            "DOC_EDITOR_STATISTICS",
        )

        requiredTags.forEach { tag ->
            assertThat(tagSource).contains("const val $tag")
            assertThat(screenSource).contains("UiTags.$tag")
        }
    }

    @Test
    fun `文档元数据与编辑统计使用不同结构锚点`() {
        val statusBarSource = screenSource
            .substringAfter("private fun EditorStatusBar(")
            .substringBefore("private fun EditorStatusIndicator(")

        assertThat(statusBarSource).contains("UiTags.DOC_EDITOR_STATISTICS")
        assertThat(statusBarSource).doesNotContain("UiTags.DOC_EDITOR_METADATA")
    }
}

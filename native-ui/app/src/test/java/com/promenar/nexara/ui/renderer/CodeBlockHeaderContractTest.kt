package com.promenar.nexara.ui.renderer

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class CodeBlockHeaderContractTest {
    private val moduleRoot: Path = Path.of(System.getProperty("user.dir") ?: ".").let { root ->
        if (Files.isDirectory(root.resolve("src/main"))) root else root.resolve("app")
    }

    private val source: String by lazy {
        String(
            Files.readAllBytes(
                moduleRoot.resolve("src/main/java/com/promenar/nexara/ui/renderer/CodeBlockHeader.kt"),
            ),
            Charsets.UTF_8,
        )
    }

    private val english: String by lazy {
        String(
            Files.readAllBytes(moduleRoot.resolve("src/main/res/values/strings.xml")),
            Charsets.UTF_8,
        )
    }

    private val chinese: String by lazy {
        String(
            Files.readAllBytes(moduleRoot.resolve("src/main/res/values-zh-rCN/strings.xml")),
            Charsets.UTF_8,
        )
    }

    @Test
    fun `代码块工具栏的每个动作都使用48dp触控目标`() {
        assertThat(source).contains("private val CodeBlockActionTouchTarget = 48.dp")
        assertThat(
            Regex("Modifier\\.size\\(CodeBlockActionTouchTarget\\)").findAll(source).count(),
        ).isEqualTo(4)
        assertThat(source).doesNotContain("modifier = Modifier.size(28.dp)")
    }

    @Test
    fun `代码块及工具栏动作只从本地化资源取得无障碍描述`() {
        listOf(
            "code_block_description",
            "code_block_plain_text",
            "code_block_full_screen",
            "code_block_export_png",
            "code_block_edit",
            "code_block_save",
            "code_block_copy",
            "code_block_copied",
        ).forEach { name ->
            assertThat(english).contains("name=\"$name\"")
            assertThat(chinese).contains("name=\"$name\"")
            assertThat(source).contains("R.${if (name == "code_block_description") "plurals" else "string"}.$name")
        }

        listOf(
            "\"Full screen\"",
            "\"Export PNG\"",
            "\"Edit code\"",
            "\"Save\"",
            "\"Copy\"",
            "\"Copied\"",
            "\"Code block in",
        ).forEach { hardCodedDescription ->
            assertThat(source).doesNotContain(hardCodedDescription)
        }
    }

    @Test
    fun `代码块表面使用Material3主题角色且行号沟槽不进入无障碍焦点`() {
        assertThat(source).contains("MaterialTheme.colorScheme")
        assertThat(source).contains("MaterialTheme.typography")
        assertThat(source).contains("MaterialTheme.shapes")
        assertThat(source).doesNotContain("NexaraColors")
        assertThat(source).doesNotContain("NexaraTypography")
        assertThat(source).doesNotContain("NexaraShapes")
        assertThat(Regex("""\.clearAndSetSemantics \{ \}""").findAll(source).count())
            .isAtLeast(2)
    }
}

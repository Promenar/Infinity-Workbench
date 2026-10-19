package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class SshEditorSheetContractTest {
    private val moduleRoot: Path = Path.of(System.getProperty("user.dir") ?: ".").let { root ->
        if (Files.isDirectory(root.resolve("src/main"))) root else root.resolve("app")
    }

    private fun source(relativePath: String): String = String(
        Files.readAllBytes(moduleRoot.resolve(relativePath)),
        Charsets.UTF_8,
    )

    @Test
    fun `主机编辑表单关闭拖拽手势，避免内容滚动到顶时与面板拖拽交接抖动`() {
        val sheetSource = source("src/main/java/com/promenar/nexara/ui/common/NexaraBottomSheet.kt")
        val editorSource = source("src/main/java/com/promenar/nexara/ui/settings/SshSettingsScreen.kt")

        assertThat(sheetSource).contains("sheetGesturesEnabled = dismissEnabled && dragGesturesEnabled")
        assertThat(editorSource).contains("dragGesturesEnabled = false")
    }
}

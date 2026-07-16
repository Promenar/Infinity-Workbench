package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

class ResourceExplorerMaterialContractTest {

    private fun source(relativePath: String): String {
        val projectRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
            if (root.resolve("src/main").isDirectory) root else root.resolve("app")
        }
        return projectRoot.resolve(relativePath).readText()
    }

    @Test
    fun `资源浏览器与回收站不恢复Glass表面`() {
        val explorer = source("src/main/java/com/promenar/nexara/ui/chat/ResourceExplorerSheet.kt")
        val recycle = source("src/main/java/com/promenar/nexara/ui/chat/components/RecycleBinPanel.kt")

        assertThat(explorer).doesNotContain("NexaraGlassCard")
        assertThat(recycle).doesNotContain("NexaraGlassCard")
    }

    @Test
    fun `资源状态不使用固定微字号`() {
        val explorer = source("src/main/java/com/promenar/nexara/ui/chat/ResourceExplorerSheet.kt")
        val recycle = source("src/main/java/com/promenar/nexara/ui/chat/components/RecycleBinPanel.kt")

        assertThat(explorer).doesNotContain("NexaraTypography.labelSmall")
        assertThat(recycle).doesNotContain("NexaraTypography.labelSmall")
    }

    @Test
    fun `回收站状态不复用RAG装饰色`() {
        val recycle = source("src/main/java/com/promenar/nexara/ui/chat/components/RecycleBinPanel.kt")

        assertThat(recycle).doesNotContain("NexaraColors.RagPending")
    }
}

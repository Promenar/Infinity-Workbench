package com.promenar.nexara.ui.theme

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ComposeMaterialBaselineContractTest {
    private fun repositoryRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(userDir) { it.parentFile }
            .first { it.resolve("native-ui/app/build.gradle.kts").isFile }
    }

    @Test
    fun `app 与 e2e 使用同一稳定 Material3 基线`() {
        val root = repositoryRoot()
        val app = root.resolve("native-ui/app/build.gradle.kts").readText()
        val e2e = root.resolve("native-ui/mainactivity-e2e/build.gradle.kts").readText()

        assertThat(app).contains("compose-bom:2026.06.00")
        assertThat(e2e).contains("compose-bom:2026.06.00")
        assertThat(app).doesNotContain("compose-bom:2026.05.00")
        assertThat(e2e).doesNotContain("compose-bom:2026.05.00")
        assertThat(app).doesNotContain("1.5.0-alpha")
        assertThat(e2e).doesNotContain("1.5.0-alpha")
    }
}

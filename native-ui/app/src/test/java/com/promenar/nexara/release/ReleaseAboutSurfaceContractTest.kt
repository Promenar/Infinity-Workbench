package com.promenar.nexara.release

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class ReleaseAboutSurfaceContractTest {
    @Test
    fun `发行版关于入口保持纯标题且不进入开发者面板`() {
        val settings = source("ui/hub/UserSettingsHomeScreen.kt")
        val navGraph = source("navigation/NavGraph.kt")

        assertThat(settings).contains("R.string.settings_about_nexara")
        assertThat(settings).doesNotContain("R.string.settings_version, state.versionName")
        assertThat(settings).doesNotContain("onNavigateToSecondary(\"developer_panel\")")
        assertThat(navGraph).doesNotContain("DEVELOPER_PANEL")
    }

    private fun source(relative: String): String = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/nexara/$relative"),
    ).toString(Charsets.UTF_8)
}

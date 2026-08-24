package com.promenar.nexara.navigation

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class ReleaseNavigationDeadRouteContractTest {

    @Test
    fun `发布导航图不保留无法进入的占位路由`() {
        val source = Files.readAllBytes(
            Path.of("app/src/main/java/com/promenar/nexara/navigation/NavGraph.kt"),
        ).toString(Charsets.UTF_8)

        assertThat(source).doesNotContain("SESSION_SETTINGS_SHEET")
        assertThat(source).doesNotContain("WORKSPACE_SHEET")
        assertThat(source).doesNotContain("session_settings_sheet/{sessionId}")
        assertThat(source).doesNotContain("workspace_sheet/{sessionId}")
        assertThat(source).doesNotContain("PlaceholderScreen")
        assertThat(source).doesNotContain("SESSION_SETTINGS")
        assertThat(source).doesNotContain("SEARCH_CONFIG")
        assertThat(source).doesNotContain("DEVELOPER_PANEL")
        assertThat(source).doesNotContain("SessionSettingsScreen")
        assertThat(source).doesNotContain("SearchConfigScreen")
        assertThat(source).doesNotContain("DeveloperScreen")
    }
}

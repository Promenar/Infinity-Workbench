package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class BackupContentUiContractTest {
    @Test
    fun `core backup content cannot be toggled while keys remain optional`() {
        val initial = BackupUiState()

        assertThat(BackupUiState::class.java.declaredFields.map { it.name }).containsNoneOf(
            "sessionsChecked", "libraryChecked", "filesChecked", "settingsChecked",
        )
        assertThat(initial.withKeysIncluded(true).keysChecked).isTrue()
        assertThat(initial.keysChecked).isFalse()
    }

    @Test
    fun `screen has no interactive core category toggles`() {
        val source = String(
            Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/ui/settings/BackupSettingsScreen.kt")),
            Charsets.UTF_8,
        )

        listOf("sessions", "library", "files", "settings").forEach { type ->
            assertThat(source).doesNotContain("toggleCheck(\"$type\"")
        }
        assertThat(source).contains("setIncludeKeys(it)")
        assertThat(source).contains("backup_core_content_fixed")
    }

    @Test
    fun `unfinished cloud restore and key export cannot act as fake entry points`() {
        val source = String(
            Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/ui/settings/BackupSettingsScreen.kt")),
            Charsets.UTF_8,
        )

        assertThat(source).doesNotContain("stringResource(R.string.backup_restore_cloud)")
        assertThat(Regex("if \\(\\!uiState\\.includeKeys\\) \\{").findAll(source).count()).isEqualTo(2)
        assertThat(source).doesNotContain("viewModel.listRemote()")
    }

    @Test
    fun `WebDAV sheet consumes async save result and exposes canonical recovery without optimistic dismissal`() {
        val source = String(
            Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/ui/settings/BackupSettingsScreen.kt")),
            Charsets.UTF_8,
        )

        assertThat(source).contains("saveAndTestWebDavConfig(")
        assertThat(source).contains("val accepted = viewModel.saveWebDavConfig(")
        assertThat(source).contains("if (accepted) tempWebdavPass = \"\"")
        assertThat(source).contains("viewModel.resetWebDavAuth()")
        assertThat(source).doesNotContain("if (accepted) showWebdavSheet = false")
    }
}

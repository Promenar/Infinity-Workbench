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
    fun `key export and cloud restore are wired to real gated entry points`() {
        val source = String(
            Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/ui/settings/BackupSettingsScreen.kt")),
            Charsets.UTF_8,
        )

        assertThat(source).contains("stringResource(R.string.backup_restore_cloud)")
        assertThat(source).contains("showExportPasswordDialog = true")
        assertThat(source).contains("showUploadPasswordDialog = true")
        assertThat(source).contains("viewModel.listRemote()")
        assertThat(source).contains("viewModel.restoreSelectedRemote(")
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
        assertThat(source).contains(
            "                            }\n" +
                "                        }\n" +
                "                        ActionButton(\n" +
                "                            label = stringResource(R.string.backup_config_webdav)",
        )
        val resetSection = source.substring(
            source.indexOf("val blockedCode ="),
            source.indexOf("private fun ExportButton"),
        )
        assertThat(resetSection).contains("BackupErrorCode.CONNECTION_FAILED")
        assertThat(resetSection).contains("BackupErrorCode.CONFIGURATION_MISSING")
        assertThat(resetSection).contains("stringResource(R.string.backup_reset_webdav_security)")
        assertThat(source).doesNotContain("\"Exporting...\"")
        assertThat(source).doesNotContain("\"Importing...\"")
        assertThat(source).doesNotContain(", \"user\")")
        assertThat(source).contains("stringResource(R.string.backup_exporting)")
        assertThat(source).contains("stringResource(R.string.backup_importing)")
        assertThat(source).contains("stringResource(R.string.backup_webdav_user_hint)")
    }

    @Test
    fun `production BackupRepository construction is deferred behind IO lazy operations`() {
        val source = String(
            Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/ui/settings/BackupViewModel.kt")),
            Charsets.UTF_8,
        )

        assertThat(source).contains("operations = LazyBackupOperations(Dispatchers.IO)")
        assertThat(source).contains("RepositoryBackupOperations(BackupRepository(application))")
        assertThat(source).doesNotContain("operations = RepositoryBackupOperations(BackupRepository(application))")
    }
}

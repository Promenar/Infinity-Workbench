package com.promenar.nexara.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.promenar.nexara.R
import com.promenar.nexara.data.backup.BackupExportOptions
import com.promenar.nexara.data.backup.PendingRestoreMetadata
import com.promenar.nexara.data.backup.PendingRestorePhase
import com.promenar.nexara.data.remote.webdav.RemoteBackup
import com.promenar.nexara.data.remote.webdav.WebDavConfig
import com.promenar.nexara.data.repository.BackupUploadReceipt
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import com.promenar.nexara.ui.theme.NexaraTheme
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BackupTestActivity : ComponentActivity() {
    companion object {
        val listGate = AtomicReference(CompletableDeferred(Unit))
        lateinit var viewModel: BackupViewModel
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = BackupViewModel(
            operations = FakeBackupOperations,
            settings = MemoryBackupSettings(),
            secrets = MemorySecrets(),
            restartRequester = BackupRestartRequester {},
            synchronousIoForTests = true,
        )
        viewModel.saveWebDavConfig("https://dav.example.test/", "user", "password".toCharArray())
        viewModel.setWebdavEnabled(true)
        setContent { NexaraTheme { BackupSettingsScreen(onNavigateBack = {}, viewModel = viewModel) } }
    }

    private object FakeBackupOperations : BackupOperations {
        private val remote = RemoteBackup("remote-full-id.nexara", 42, 1234, "strong-etag")
        override suspend fun export(options: BackupExportOptions) = byteArrayOf(1)
        override suspend fun upload(config: WebDavConfig, options: BackupExportOptions) = BackupUploadReceipt("remote", null)
        override suspend fun testRemote(config: WebDavConfig) = Result.success(Unit)
        override suspend fun listRemote(config: WebDavConfig): List<RemoteBackup> {
            listGate.get().await()
            return listOf(remote)
        }
        override suspend fun stageLocalRestore(operationId: String, input: InputStream, password: CharArray?) = metadata(operationId)
        override suspend fun stageRemoteRestore(operationId: String, config: WebDavConfig, selected: RemoteBackup, password: CharArray?) = metadata(operationId)
        override suspend fun discardPendingRestore(operationId: String) = Unit
        override suspend fun authorizePendingRestore(operationId: String) = Unit
        private fun metadata(id: String) = PendingRestoreMetadata(id, byteArrayOf(1), PendingRestorePhase.STAGED)
    }

    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<SecretId, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id] = value.copyOf() }
        override fun get(id: SecretId) = values[id]?.copyOf()
        override fun contains(id: SecretId) = id in values
        override fun remove(id: SecretId) { values.remove(id) }
    }

    private class MemoryBackupSettings : BackupSettingsStore {
        override var webDavEnabled = false
        override var autoBackup = false
        override var webDavUrl = ""
        override var webDavUser = ""
        override var lastBackupTime = 0L
        override var webDavPasswordPlaintext: String? = null
    }
}

class BackupRealScreenTest {
    @get:Rule val rule = createAndroidComposeRule<BackupTestActivity>()

    @Before fun resetGate() {
        BackupTestActivity.listGate.set(CompletableDeferred(Unit))
    }

    @Test
    fun realScreen_refreshesSelectsExactRemoteAndOpensRestorePassword() {
        val refresh = rule.activity.getString(R.string.backup_remote_refresh)
        val restore = rule.activity.getString(R.string.backup_restore_cloud)
        rule.onNodeWithText(refresh).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("remote-full-id.nexara").performScrollTo().performClick()
        rule.onNodeWithText(restore).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.backup_password_remote_restore_title)).assertIsDisplayed()
    }

    @Test
    fun realScreen_showsCancelOnlyWhileRemoteListIsActuallyCancellable() {
        val gate = CompletableDeferred<Unit>()
        BackupTestActivity.listGate.set(gate)
        rule.onNodeWithText(rule.activity.getString(R.string.backup_remote_refresh)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.common_btn_cancel)).performScrollTo().assertIsDisplayed()
        gate.complete(Unit)
        rule.waitForIdle()
    }

    @Test
    fun realScreen_webDavSheetKeepsSaveReachable() {
        rule.onNodeWithText(rule.activity.getString(R.string.backup_config_webdav)).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.backup_webdav_config_title)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.backup_save_config)).performScrollTo().assertIsDisplayed()
    }
}

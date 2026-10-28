package com.promenar.infinity.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.backup.AndroidPendingRestoreStore
import com.promenar.infinity.data.backup.BackupExportOptions
import com.promenar.infinity.data.backup.BackupStartupState
import com.promenar.infinity.data.backup.DefaultBackupPackageCodec
import com.promenar.infinity.data.backup.PendingRestoreMetadata
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.remote.webdav.RemoteBackup
import com.promenar.infinity.data.remote.webdav.WebDavConfig
import com.promenar.infinity.data.repository.BackupRepository
import com.promenar.infinity.data.repository.BackupUploadReceipt
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 使用真实 ActivityResultContract.parseResult、ContentResolver 与 content:// provider。
 * 为保证确定性不启动系统 DocumentsUI，因此这是 contract callback E2E，不宣称系统 DocumentsUI E2E。
 */
class BackupComposeSafRoundTripTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: InfinityApplication
    private lateinit var repository: BackupRepository

    @Before
    fun setUp() = runBlocking {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InfinityApplication
        withTimeout(30_000) { app.startupState.first { it == BackupStartupState.Ready } }
        repository = BackupRepository(app)
        clearPending()
        app.secretStore.remove(SECRET_ID)
    }

    @After
    fun tearDown() = runBlocking {
        clearPending()
        app.secretStore.remove(SECRET_ID)
        app.database.agentDao().deleteById(SENTINEL_AGENT)
    }

    @Test
    fun includeKeysPassword_exportAndImport_roundTripsThroughContractAndContentResolver() = runBlocking {
        putSecret(EXPORTED_SECRET)
        val uri = fixtureUri("round-trip.infinity")
        val registry = ImmediateDocumentRegistry(uri)
        var restartCount = 0
        val viewModel = realViewModel { restartCount += 1 }.also { it.setIncludeKeys(true) }
        showScreen(viewModel, registry)

        rule.onNodeWithTag(UiTags.BACKUP_EXPORT).performScrollTo().performClick()
        enterExportPassword(PASSWORD)
        waitUntil { viewModel.uiState.value.operation == BackupOperation.Success(BackupSuccessCode.EXPORTED) }
        waitUntil { viewModel.uiState.value.canExecute }

        val packageBytes = readDocument(uri)
        assertThat(packageBytes).isNotEmpty()
        val decodePassword = PASSWORD.toCharArray()
        try {
            DefaultBackupPackageCodec().decode(packageBytes, decodePassword).use { decoded ->
                assertThat(decoded.manifest.encrypted).isTrue()
                assertThat(decoded.manifest.containsSecrets).isTrue()
                assertThat(decoded.secrets.getValue(SECRET_ID).toString(Charsets.UTF_8))
                    .isEqualTo(EXPORTED_SECRET)
            }
        } finally {
            decodePassword.fill('\u0000')
            packageBytes.fill(0)
        }

        rule.onNodeWithTag(UiTags.BACKUP_IMPORT).performScrollTo().performClick()
        enterRestorePassword(PASSWORD)
        waitUntil { restartCount == 1 }

        AndroidPendingRestoreStore(app).read()!!.use { pending ->
            assertThat(pending.password?.concatToString()).isEqualTo(PASSWORD)
            assertThat(pending.packageBytes).isNotEmpty()
        }
        assertThat(registry.launchedContracts)
            .containsExactly(
                ActivityResultContracts.CreateDocument::class.java,
                ActivityResultContracts.GetContent::class.java,
            )
            .inOrder()
    }

    @Test
    fun wrongPassword_importShowsErrorAndLeavesOperationalDataUntouched() = runBlocking {
        putSecret(EXPORTED_SECRET)
        app.database.agentDao().insert(AgentEntity(SENTINEL_AGENT, "saf-sentinel", createdAt = 77))
        val beforeAgents = app.database.agentDao().getAll().map { it.id }
        val uri = fixtureUri("wrong-password.infinity")
        val exportPassword = PASSWORD.toCharArray()
        val confirmation = PASSWORD.toCharArray()
        val bytes = try {
            repository.export(BackupExportOptions(true, exportPassword, confirmation))
        } finally {
            exportPassword.fill('\u0000')
            confirmation.fill('\u0000')
        }
        try {
            writeDocument(uri, bytes)
        } finally {
            bytes.fill(0)
        }

        var restartCount = 0
        val viewModel = realViewModel { restartCount += 1 }
        showScreen(viewModel, ImmediateDocumentRegistry(uri))
        rule.onNodeWithTag(UiTags.BACKUP_IMPORT).performScrollTo().performClick()
        enterRestorePassword("wrong-password")
        waitUntil {
            viewModel.uiState.value.operation == BackupOperation.Error(BackupErrorCode.RESTORE_FAILED)
        }

        rule.onNodeWithTag(UiTags.BACKUP_OPERATION_STATUS).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.backup_error_restore)).assertIsDisplayed()
        assertThat(app.database.agentDao().getAll().map { it.id }).containsExactlyElementsIn(beforeAgents)
        assertThat(restartCount).isEqualTo(0)
        assertThat(app.noBackupFilesDir.resolve(AndroidPendingRestoreStore.FILE_NAME).exists()).isFalse()
    }

    private fun showScreen(viewModel: BackupViewModel, registry: ImmediateDocumentRegistry) {
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                InfinityTheme { BackupSettingsScreen(onNavigateBack = {}, viewModel = viewModel) }
            }
        }
        rule.waitForIdle()
    }

    private fun enterExportPassword(password: String) {
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.backup_password_hint))
            .performTextInput(password)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.backup_password_confirm_hint))
            .performTextInput(password)
        rule.onNodeWithText(rule.activity.getString(R.string.common_btn_confirm)).performClick()
    }

    private fun enterRestorePassword(password: String) {
        rule.onNodeWithText(rule.activity.getString(R.string.backup_password_restore_title))
            .assertIsDisplayed()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.backup_password_hint))
            .performTextInput(password)
        rule.onNodeWithText(rule.activity.getString(R.string.common_btn_confirm)).performClick()
    }

    private fun realViewModel(onRestart: () -> Unit) = BackupViewModel(
        operations = RealOperations(repository),
        settings = MemorySettings(),
        secrets = app.secretStore,
        restartRequester = BackupRestartRequester(onRestart),
        synchronousIoForTests = true,
    )

    private fun putSecret(value: String) {
        val bytes = value.toByteArray()
        try {
            app.secretStore.put(SECRET_ID, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun fixtureUri(name: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(com.promenar.infinity.test.BuildConfig.APPLICATION_ID + ".backupsaffixture")
        .appendPath(name)
        .build()

    private fun readDocument(uri: Uri): ByteArray =
        requireNotNull(rule.activity.contentResolver.openInputStream(uri)).use(InputStream::readBytes)

    private fun writeDocument(uri: Uri, bytes: ByteArray) {
        requireNotNull(rule.activity.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
    }

    private fun waitUntil(predicate: () -> Boolean) = rule.waitUntil(30_000, condition = predicate)

    private suspend fun clearPending() {
        val payload = AndroidPendingRestoreStore(app).read() ?: return
        payload.use { repository.discardPendingRestore(it.metadata.txId) }
    }

    private class ImmediateDocumentRegistry(private val resultUri: Uri) : ActivityResultRegistry(),
        ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        val launchedContracts = mutableListOf<Class<*>>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: androidx.core.app.ActivityOptionsCompat?,
        ) {
            launchedContracts += contract.javaClass
            dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(resultUri))
        }
    }

    private class RealOperations(private val repository: BackupRepository) : BackupOperations {
        override suspend fun export(options: BackupExportOptions) = repository.export(options)
        override suspend fun upload(config: WebDavConfig, options: BackupExportOptions): BackupUploadReceipt =
            repository.upload(config, options)
        override suspend fun testRemote(config: WebDavConfig) = repository.testRemote(config)
        override suspend fun listRemote(config: WebDavConfig): List<RemoteBackup> = repository.listRemote(config)
        override suspend fun stageLocalRestore(operationId: String, input: InputStream, password: CharArray?): PendingRestoreMetadata =
            repository.stageLocalRestore(operationId, input, password)
        override suspend fun stageRemoteRestore(
            operationId: String,
            config: WebDavConfig,
            selected: RemoteBackup,
            password: CharArray?,
        ): PendingRestoreMetadata = repository.stageRemoteRestore(operationId, config, selected, password)
        override suspend fun discardPendingRestore(operationId: String) = repository.discardPendingRestore(operationId)
        override suspend fun authorizePendingRestore(operationId: String) {
            repository.authorizePendingRestore(operationId)
        }
    }

    private class MemorySettings : BackupSettingsStore {
        override var webDavEnabled = false
        override var webDavUrl = ""
        override var webDavUser = ""
        override var lastBackupTime = 0L
        override var webDavPasswordPlaintext: String? = null
    }

    private companion object {
        const val PASSWORD = "Backup-SAF-E2E-Password-2026!"
        const val EXPORTED_SECRET = "e2e-fictional-saf-provider-key"
        const val SENTINEL_AGENT = "backup-saf-sentinel-agent"
        val SECRET_ID = SecretCatalog.providerApiKey("default")
    }
}

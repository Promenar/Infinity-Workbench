package com.promenar.nexara.share.core

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.MainActivity
import com.promenar.nexara.ShareEnqueueResult
import com.promenar.nexara.ShareIntentQueue
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.repository.SecureWorkspaceFileOps
import com.promenar.nexara.data.repository.WorkspaceFileTooLargeException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShareImportAndroidEndToEndTest {
    @Test
    fun activityDispatchStagesBeforeClearingIntentAndFreshMainActivityMountsSheet() {
        val authority = "${com.promenar.nexara.test.BuildConfig.APPLICATION_ID}.sharefixture"
        val uri = Uri.parse("content://$authority/${ShareFixtureProvider.VALID_FILE}")
        val context = ApplicationProvider.getApplicationContext<NexaraApplication>()
        val intent = Intent(
            Intent.ACTION_SEND,
            uri,
            context,
            MainActivity::class.java,
        ).apply {
            type = "text/plain"
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var firstActivityIdentity = 0
        var firstActivityDestroyed = false
        val firstActivity = instrumentation.startActivitySync(intent) as MainActivity
        instrumentation.waitForIdleSync()
        try {
            assertThat(waitForShareSheet(instrumentation, firstActivity)).isTrue()
            instrumentation.runOnMainSync {
                firstActivityIdentity = System.identityHashCode(firstActivity)
                assertThat(firstActivity.intent.action).isEqualTo(Intent.ACTION_MAIN)
                assertThat(firstActivity.shareImportStateForTesting().visible).isTrue()
            }
        } finally {
            instrumentation.runOnMainSync {
                if (!firstActivity.isFinishing && !firstActivity.isDestroyed) firstActivity.finish()
            }
            firstActivityDestroyed = waitForDestroyed(instrumentation, firstActivity)
        }
        assertThat(firstActivityDestroyed).isTrue()

        val mainIntent = Intent(context, MainActivity::class.java).apply { action = Intent.ACTION_MAIN }
        ActivityScenario.launch<MainActivity>(mainIntent).use { mainScenario ->
            assertThat(waitForShareSheet(mainScenario)).isTrue()
            mainScenario.onActivity { activity ->
                assertThat(System.identityHashCode(activity)).isNotEqualTo(firstActivityIdentity)
                assertThat(activity.intent.action).isEqualTo(Intent.ACTION_MAIN)
                assertThat(activity.intent.data).isNull()
                assertThat(activity.intent.hasExtra(Intent.EXTRA_STREAM)).isFalse()
                assertThat(activity.shareImportStateForTesting().visible).isTrue()
            }
        }
        runBlocking { drainInbox(DurableShareInbox.get(context.noBackupFilesDir)) }
    }

    @Test
    fun rapidConsecutiveSendUsesSingleActivityAndStagesBothRequestsOnce() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<NexaraApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val authority = "${com.promenar.nexara.test.BuildConfig.APPLICATION_ID}.sharefixture"
        val firstUri = Uri.parse("content://$authority/${ShareFixtureProvider.BLOCKING_FILE}")
        val secondUri = Uri.parse("content://$authority/${ShareFixtureProvider.SECOND_FILE}")
        val inbox = DurableShareInbox.get(app.noBackupFilesDir)
        drainInbox(inbox)
        callFixture(app, authority, ShareFixtureProvider.METHOD_RESET_BLOCKING_READ)
        val firstIntent = shareActivityIntent(app, firstUri)
        val firstActivity = instrumentation.startActivitySync(firstIntent) as MainActivity
        val firstIdentity = System.identityHashCode(firstActivity)
        try {
            assertThat(callFixture(
                app,
                authority,
                ShareFixtureProvider.METHOD_AWAIT_BLOCKING_READ,
            ).getBoolean(ShareFixtureProvider.RESULT_READY)).isTrue()
            app.startActivity(shareActivityIntent(app, secondUri))
            val delivered = waitForDeliveredShare(
                instrumentation = instrumentation,
                expectedActivityIdentity = firstIdentity,
                expectedStream = secondUri,
            )
            assertThat(delivered.resumedCount).isEqualTo(1)
            assertThat(delivered.activityIdentity).isEqualTo(firstIdentity)
            assertThat(delivered.action).isEqualTo(Intent.ACTION_SEND)
            assertThat(delivered.stream).isEqualTo(secondUri)
            assertThat(delivered.clipUris).containsExactly(secondUri)

            callFixture(app, authority, ShareFixtureProvider.METHOD_RELEASE_BLOCKING_READ)
            assertThat(waitForStagedNames(
                inbox,
                setOf(ShareFixtureProvider.BLOCKING_FILE, ShareFixtureProvider.SECOND_FILE),
            )).isTrue()
            assertThat(waitForShareSheet(instrumentation, firstActivity)).isTrue()
            instrumentation.runOnMainSync {
                assertThat(firstActivity.shareImportStateForTesting().pendingCount).isAtLeast(2)
            }
        } finally {
            callFixture(app, authority, ShareFixtureProvider.METHOD_RELEASE_BLOCKING_READ)
            instrumentation.runOnMainSync {
                if (!firstActivity.isFinishing && !firstActivity.isDestroyed) firstActivity.finish()
            }
            waitForDestroyed(instrumentation, firstActivity)
            drainInbox(inbox)
        }
    }

    @Test
    fun contentResolverBatchPersistsOnceAndOversizeFailureLeavesNoTemporaryFile() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<NexaraApplication>()
        val authority = "${com.promenar.nexara.test.BuildConfig.APPLICATION_ID}.sharefixture"
        val uri = Uri.parse("content://$authority/${ShareFixtureProvider.VALID_FILE}")
        val invalidPdf = Uri.parse("content://$authority/${ShareFixtureProvider.INVALID_PDF}")
        val sessionId = "__share_e2e_${UUID.randomUUID()}__"
        val now = System.currentTimeMillis()
        app.database.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                agentId = "__test__",
                title = "Share E2E",
                createdAt = now,
                updatedAt = now,
            )
        )
        val root = app.workspaceRepository.ensureSessionRoot(sessionId)
        val inboxRoot = File(app.noBackupFilesDir, "share-e2e-${UUID.randomUUID()}").apply { mkdirs() }
        val inbox = DurableShareInbox(inboxRoot)
        val queue = ShareIntentQueue(inbox)
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri, invalidPdf))
        }
        assertThat(queue.stageDurably(intent, app.contentResolver)).isEqualTo(ShareEnqueueResult.Accepted)
        val lease = checkNotNull(queue.claimNextDurably())

        try {
            val result = SharedFileImporter(
                source = inbox.contentSource(),
                workspace = app.workspaceRepository,
                indexScheduler = AndroidShareIndexScheduler(app),
            ).import(lease.request, root.uuid)

            val created = result.created.single().created!!
            val rejected = result.rejected.single()
            // DurableInbox 会把原始 content URI 映射为同顺序的应用私有 nexara-stage URI。
            assertThat(rejected.uri).isEqualTo(lease.request.uris[1])
            assertThat(rejected.uri.scheme).isEqualTo("nexara-stage")
            assertThat(rejected.displayName).isEqualTo(ShareFixtureProvider.INVALID_PDF)
            assertThat(rejected.reason).isEqualTo(ShareRejectReason.MimeMismatch)
            val physical = File(created.physicalRootPath, created.materializedPath.trimStart('/'))
            assertThat(created.name).isEqualTo(ShareFixtureProvider.VALID_FILE)
            assertThat(physical.readBytes()).isEqualTo(ShareFixtureProvider.CONTENT)
            assertThat(app.workspaceRepository.getByUuid(root.uuid, created.uuid)).isEqualTo(created)
            assertThat(app.database.vectorizationTaskDao().getByWorkspaceFile(
                root.uuid,
                created.uuid,
                com.promenar.nexara.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            )).isNotNull()
            inbox.recordCreated(lease.request.requestId, result.created)
            assertThat(queue.nackDurably(lease.token)).isTrue()

            val recreatedQueue = ShareIntentQueue(DurableShareInbox(inboxRoot))
            val recreatedLease = recreatedQueue.claimNextDurably()!!
            assertThat(DurableShareInbox(inboxRoot).contentSource()
                .preflightItem(recreatedLease.request.uris.first())?.status)
                .isEqualTo(ShareImportStatus.Created)
            assertThat(app.workspaceRepository.getByMaterializedPath(root.uuid, "/${created.name}"))
                .isEqualTo(created)

            val rootPath = File(root.physicalRootPath).toPath()
            val oversizeFailure = runCatching {
                SecureWorkspaceFileOps().createFileStreaming(
                    root = rootPath,
                    relative = listOf("too-large.txt"),
                    maxBytes = 4,
                ) { output -> output.write(byteArrayOf(1, 2, 3, 4, 5)) }
            }.exceptionOrNull()
            assertThat(oversizeFailure).isInstanceOf(WorkspaceFileTooLargeException::class.java)
            assertThat(File(root.physicalRootPath, "too-large.txt").exists()).isFalse()
            assertThat(
                File(root.physicalRootPath).list().orEmpty().any { it.startsWith(".create-") }
            ).isFalse()
            assertThat(recreatedQueue.dropDurably(recreatedLease.token)).isTrue()
            app.workspaceRepository.moveToRecycleBin(root.uuid, created.uuid)
            app.workspaceRepository.permanentDelete(root.uuid, created.uuid)
        } finally {
            app.database.sessionDao().deleteById(sessionId)
            inboxRoot.deleteRecursively()
        }
    }

    private fun waitForShareSheet(scenario: ActivityScenario<MainActivity>): Boolean {
        repeat(200) {
            var visible = false
            scenario.onActivity { activity -> visible = activity.shareImportStateForTesting().visible }
            if (visible) return true
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private fun waitForShareSheet(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
    ): Boolean {
        repeat(200) {
            var visible = false
            instrumentation.runOnMainSync {
                if (!activity.isDestroyed) visible = activity.shareImportStateForTesting().visible
            }
            if (visible) return true
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private fun waitForDestroyed(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
    ): Boolean {
        repeat(200) {
            var destroyed = false
            instrumentation.runOnMainSync { destroyed = activity.isDestroyed }
            if (destroyed) return true
            android.os.SystemClock.sleep(50)
        }
        return false
    }

    private fun shareActivityIntent(context: NexaraApplication, uri: Uri) = Intent(
        Intent.ACTION_SEND,
        uri,
        context,
        MainActivity::class.java,
    ).apply {
        type = "text/plain"
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(Intent.EXTRA_STREAM, uri)
    }

    private fun callFixture(context: NexaraApplication, authority: String, method: String) =
        checkNotNull(context.contentResolver.call(Uri.parse("content://$authority"), method, null, null))

    private fun waitForDeliveredShare(
        instrumentation: android.app.Instrumentation,
        expectedActivityIdentity: Int,
        expectedStream: Uri,
    ): DeliveredShareSnapshot {
        var last = DeliveredShareSnapshot(resumedCount = 0)
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (true) {
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                val activity = resumed.singleOrNull()
                @Suppress("DEPRECATION")
                last = DeliveredShareSnapshot(
                    resumedCount = resumed.size,
                    activityIdentity = activity?.let { System.identityHashCode(it) },
                    action = activity?.intent?.action,
                    stream = activity?.intent?.getParcelableExtra(Intent.EXTRA_STREAM),
                    clipUris = buildList {
                        activity?.intent?.clipData?.let { clip ->
                            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
                        }
                    },
                )
            }
            if (last.resumedCount == 1 &&
                last.activityIdentity == expectedActivityIdentity &&
                last.stream == expectedStream
            ) return last
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) return last
            android.os.SystemClock.sleep(minOf(50L, remaining))
        }
    }

    private data class DeliveredShareSnapshot(
        val resumedCount: Int,
        val activityIdentity: Int? = null,
        val action: String? = null,
        val stream: Uri? = null,
        val clipUris: List<Uri> = emptyList(),
    )

    private suspend fun waitForStagedNames(inbox: DurableShareInbox, expected: Set<String>): Boolean {
        repeat(200) {
            val source = inbox.contentSource()
            val names = inbox.snapshot().flatMap { request ->
                request.uris.map { uri -> source.metadata(uri).displayName }
            }
            if (expected.all { expectedName -> names.count { it == expectedName } == 1 }) return true
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private suspend fun drainInbox(inbox: DurableShareInbox) {
        repeat(64) {
            val lease = inbox.claimNext() ?: return
            check(inbox.drop(lease.token))
        }
        error("测试收件箱超过清理上限")
    }
}

package com.promenar.nexara.data.exec

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.nexara.BuildConfig
import com.promenar.nexara.background.exec.AndroidExecKeepAlive
import com.promenar.nexara.background.exec.ExecForegroundService
import com.promenar.nexara.background.exec.ExecRunRegistryOwner
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.repository.SecureWorkspaceTreeScanner
import com.promenar.nexara.data.repository.WorkspaceDeletionTransaction
import com.promenar.nexara.data.repository.WorkspaceRepository
import com.promenar.nexara.data.repository.WorkspaceScanKind
import com.promenar.nexara.domain.tool.CommandToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** 在设备上以真实 toybox setsid、系统 sh、打包 Python 与安全目录遍历运行内置执行后端。 */
@RunWith(AndroidJUnit4::class)
class BuiltinExecDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: NexaraDatabase
    private lateinit var repo: WorkspaceRepository
    private lateinit var base: File
    private lateinit var workspace: File
    private lateinit var service: BuiltinExecService
    private lateinit var root: FileEntry

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java).allowMainThreadQueries().build()
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), deleteCommitter = WorkspaceDeletionTransaction(db)::delete)
        base = File(context.filesDir, "exec-device-${UUID.randomUUID()}").apply { mkdirs() }
        workspace = File(base, "ws").apply { mkdirs() }
        val now = System.currentTimeMillis()
        db.sessionDao().insert(SessionEntity(id = "s", agentId = "agent", workspacePath = workspace.path, createdAt = now, updatedAt = now))
        root = repo.ensureSessionRoot("s")
        service = BuiltinExecService(
            workspace = repo,
            runner = BuiltinCommandRunner(
                groupLauncher = listOf("/system/bin/setsid"),
                shell = "/system/bin/sh",
                signaller = AndroidProcessGroupSignaller,
                scratchDirectory = { File(base, "scratch") },
            ),
            environment = BuiltinExecEnvironment(
                python = PackagedPython.from(context),
                homeDirectory = File(base, "home"),
                tempDirectory = File(base, "tmp"),
                caBundle = SystemCaBundle(File(base, "ca"))::ensure,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun shellOutput(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
        }

    /** 只取当前持有的唤醒锁段；dumpsys power 另有包含已释放记录的历史日志。 */
    private fun heldWakeLocks(): List<String> = shellOutput("dumpsys power").lines()
        .dropWhile { !it.trimStart().startsWith("Wake Locks: size=") }.drop(1)
        .takeWhile { it.startsWith(" ") && it.isNotBlank() }

    @Suppress("DEPRECATION")
    private fun execServiceInForeground(): Boolean = context.getSystemService(android.app.ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == ExecForegroundService::class.java.name && it.foreground }

    private fun execNotification() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == ExecForegroundService.NOTIFICATION_ID }?.notification

    private fun CommandToolResult.json(): JsonObject = Json.parseToJsonElement(toJson().toString()).jsonObject
    private fun JsonObject.paths(key: String) =
        getValue("workspace_changes").jsonObject.getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private suspend fun shell(command: String, timeout: Int = 60) =
        service.runShell(root.uuid, "s", command, null, timeout)

    @Test
    fun shellCommandChangesAreReconciledIntoIndex() = runBlocking<Unit> {
        repo.createFileInWorkspace(root.uuid, "keep", "keep.txt", "k", root.uuid, "/keep.txt").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }
        repo.createFileInWorkspace(root.uuid, "gone", "gone.txt", "g", root.uuid, "/gone.txt").also { repo.confirmCreatedEntry(root.uuid, it.uuid) }

        val result = shell(
            "pwd; mkdir -p out && printf 'a,b\\n1,2\\n' > out/r.csv && rm gone.txt && mv keep.txt kept.txt && " +
                "ln -s /system out/link && python3 -c 'import sys; print(sys.version.split()[0])'",
        )

        val value = result.json()
        assertWithMessage(value.toString()).that(result.succeeded).isTrue()
        val stdout = value.getValue("stdout").jsonPrimitive.content.lines()
        assertThat(stdout[0]).isEqualTo("\$NEXARA_WORKSPACE")
        assertThat(stdout[1]).isEqualTo(BuildConfig.PACKAGED_PYTHON_VERSION)
        assertThat(value.paths("added")).containsExactly("/out", "/out/r.csv").inOrder()
        assertThat(value.paths("removed")).containsExactly("/gone.txt")
        assertThat(value.getValue("workspace_changes").jsonObject.getValue("moved").jsonArray.single().jsonObject
            .getValue("to").jsonPrimitive.content).isEqualTo("/kept.txt")
        val warning = value.getValue("workspace_warnings").jsonArray.single().jsonObject
        assertThat(warning.getValue("code").jsonPrimitive.content).isEqualTo("WORKSPACE_UNSUPPORTED_ENTRY")
        assertThat(warning.getValue("paths").jsonArray.map { it.jsonPrimitive.content }).containsExactly("/out/link")

        assertThat(repo.getByUuid(root.uuid, "keep")?.materializedPath).isEqualTo("/kept.txt")
        assertThat(repo.getByUuid(root.uuid, "gone")).isNull()
        val csv = checkNotNull(repo.getByMaterializedPath(root.uuid, "/out/r.csv"))
        assertThat(csv.mimeType).isEqualTo("text/csv")
        assertThat(csv.parentUuid).isEqualTo(repo.getByMaterializedPath(root.uuid, "/out")?.uuid)
    }

    @Test
    fun pythonRunsInWorkspaceWithSystemCertificates() = runBlocking<Unit> {
        val result = service.runPython(
            root.uuid, "s",
            """
            import json, os, ssl
            open('note.txt', 'w').write('中文')
            ctx = ssl.create_default_context()
            print(json.dumps({'cwd': os.getcwd() == os.environ['NEXARA_WORKSPACE'], 'ca': ctx.cert_store_stats()['x509_ca'],
                              'ld': 'LD_LIBRARY_PATH' in os.environ}))
            """.trimIndent(),
            null, 60,
        )
        val value = result.json()
        assertWithMessage(value.toString()).that(result.succeeded).isTrue()
        val report = Json.parseToJsonElement(value.getValue("stdout").jsonPrimitive.content.trim()).jsonObject
        assertThat(report.getValue("cwd").jsonPrimitive.content).isEqualTo("true")
        assertThat(report.getValue("ca").jsonPrimitive.int).isGreaterThan(50)
        assertThat(value.paths("added")).containsExactly("/note.txt")
        assertThat(File(workspace, "note.txt").readText()).isEqualTo("中文")
    }

    @Test
    fun shellEnvironmentDoesNotLeakLibraryPathToSystemCommands() = runBlocking<Unit> {
        val result = shell("echo \"[\${LD_LIBRARY_PATH:-}]\"; echo \"\$NEXARA_BACKEND\"; ls /system/bin/toybox")
        val stdout = result.json().getValue("stdout").jsonPrimitive.content.lines()
        assertThat(result.succeeded).isTrue()
        assertThat(stdout[0]).isEqualTo("[]")
        assertThat(stdout[1]).isEqualTo("builtin")
    }

    @Test
    fun timeoutKillsWholeProcessGroup() = runBlocking<Unit> {
        val result = shell("sleep 60 & echo \$! > bg.pid; sleep 60", timeout = 2)
        val value = result.json()
        assertThat(value.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_TIMEOUT")
        assertThat(result.exitCode).isNull()
        val pid = File(workspace, "bg.pid").readText().trim()
        withTimeout(5_000) { while (File("/proc/$pid").exists()) delay(50) }
        assertThat(value.paths("added")).containsExactly("/bg.pid")
    }

    @Test
    fun cancellationKillsCommandAndReleasesWorkspace() = runBlocking<Unit> {
        val job = async { shell("echo \$\$ > main.pid; sleep 60") }
        val pidFile = File(workspace, "main.pid")
        withTimeout(10_000) { while (!pidFile.isFile || pidFile.readText().isBlank()) delay(50) }
        val pid = pidFile.readText().trim()
        job.cancel()
        runCatching { job.await() }
        withTimeout(5_000) { while (File("/proc/$pid").exists()) delay(50) }
        // 写锁已释放，取消后的对账已把 main.pid 写入索引。
        val next = shell("true", timeout = 10)
        assertThat(next.succeeded).isTrue()
        assertThat(repo.getByMaterializedPath(root.uuid, "/main.pid")).isNotNull()
    }

    @Test
    fun rootIdentityRemovedByCommandIsRestored() = runBlocking<Unit> {
        val result = shell("rm -f .nexara_root_identity && mkdir .nexara_planted")
        val warning = result.json().getValue("workspace_warnings").jsonArray.single().jsonObject
        assertThat(warning.getValue("code").jsonPrimitive.content).isEqualTo("WORKSPACE_RESERVED_PATH")
        assertThat(warning.getValue("paths").jsonArray.map { it.jsonPrimitive.content })
            .containsAtLeast("/.nexara_root_identity", "/.nexara_planted")
        repo.createDirectoryInWorkspace(root.uuid, "after", "after", root.uuid, "/after")
        assertThat(repo.getByMaterializedPath(root.uuid, "/after")).isNotNull()
    }

    @Test
    fun secureScannerDoesNotFollowSymbolicLinks() {
        val outside = File(base, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("s")
        File(workspace, "docs").mkdirs()
        File(workspace, "docs/a.txt").writeText("abc")
        Files.createSymbolicLink(File(workspace, "link").toPath(), outside.toPath())
        val snapshot = SecureWorkspaceTreeScanner.scan(workspace.toPath())
        assertThat(snapshot.nodes.keys).containsExactly("/docs", "/docs/a.txt", "/link")
        assertThat(snapshot.nodes.getValue("/link").kind).isEqualTo(WorkspaceScanKind.UNSUPPORTED)
        assertThat(snapshot.nodes.getValue("/docs/a.txt").sizeBytes).isEqualTo(3)
        assertThat(snapshot.reserved.keys).contains("/.nexara_root_identity")
    }

    @Test
    fun notificationStopEndsCommandAndReleasesWakeLock() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT >= 33) {
            // ColorOS 未开“USB 调试（安全设置）”时 shell 无权授予；此时改从运行中服务列表核对前台状态。
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val notificationsVisible = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val registry = (context.applicationContext as ExecRunRegistryOwner).execRunRegistry
        val kept = BuiltinExecService(
            workspace = repo,
            runner = BuiltinCommandRunner(
                groupLauncher = listOf("/system/bin/setsid"),
                shell = "/system/bin/sh",
                signaller = AndroidProcessGroupSignaller,
                scratchDirectory = { File(base, "scratch") },
                clockNanos = android.os.SystemClock::elapsedRealtimeNanos,
            ),
            environment = BuiltinExecEnvironment(
                python = PackagedPython.from(context),
                homeDirectory = File(base, "home"),
                tempDirectory = File(base, "tmp"),
                caBundle = { null },
            ),
            keepAlive = AndroidExecKeepAlive(context, registry, foregroundServiceAvailable = true),
        )
        val job = async(kotlinx.coroutines.Dispatchers.IO) { kept.runShell(root.uuid, "s", "echo \$\$ > main.pid; sleep 60", null, 120) }
        val pidFile = File(workspace, "main.pid")
        withTimeout(10_000) { while (!pidFile.isFile || pidFile.readText().isBlank()) delay(50) }
        val pid = pidFile.readText().trim()

        withTimeout(10_000) { while (!execServiceInForeground()) delay(100) }
        assertThat(heldWakeLocks().any { "Nexara:builtin-exec" in it }).isTrue()
        if (notificationsVisible) {
            val notification = withTimeout(10_000) { var found = execNotification(); while (found == null) { delay(100); found = execNotification() }; found }
            assertThat(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
                .isEqualTo("Shell · echo \$\$ > main.pid; sleep 60")
            notification.actions.single().actionIntent.send()
        } else {
            context.startService(ExecForegroundService.stopIntent(context, registry.runs.value.map { it.info.id }))
        }
        val result = withTimeout(15_000) { job.await() }
        val value = result.json()
        assertWithMessage(value.toString()).that(value.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_STOPPED")
        withTimeout(5_000) { while (File("/proc/$pid").exists()) delay(50) }
        withTimeout(5_000) { while (execNotification() != null || execServiceInForeground()) delay(100) }
        assertThat(registry.runs.value).isEmpty()
        assertThat(heldWakeLocks().none { "Nexara:builtin-exec" in it }).isTrue()
        assertThat(value.paths("added")).containsExactly("/main.pid")
    }
}

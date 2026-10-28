package com.promenar.infinity.data.linux

import com.promenar.infinity.data.exec.BuiltinCommandOutput
import com.promenar.infinity.domain.linux.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LinuxPackageServiceTest {
    private val directory = Files.createTempDirectory("linux-packages").toFile()
    private val runtime = LinuxPackageTestRuntime(File(directory, "rootfs"))
    private val metadata get() = LinuxPackageMetadata(runtime, File(directory, "meta/packages.json"))

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun provenanceWritesOnlyNewWorldPackagesAndPreservesExistingOrigins(): Unit = runBlocking {
        val store = metadata
        runtime.world("bash\n")
        val before = store.worldSnapshot()!!
        runtime.world("bash\ngit>=2\npython3\n")
        val agent = LinuxPackageOrigin.Agent("session-1", "安装工具")
        store.attributeNewPackages(before, agent)
        assertEquals(mapOf("git" to agent, "python3" to agent), metadata.origins())
        store.attributeNewPackages(emptySet(), LinuxPackageOrigin.User)
        assertEquals(agent, metadata.origins()["git"])
        assertEquals(LinuxPackageOrigin.User, metadata.origins()["bash"])
    }

    @Test fun staleRecordsDoNotReappearAfterWorldRemovalOrRootfsReplacement(): Unit = runBlocking {
        runtime.world("git\n")
        metadata.record(setOf("git"), LinuxPackageOrigin.Preset)
        runtime.world("")
        assertTrue(metadata.origins().isEmpty())
        runtime.world("git\n")
        assertTrue(metadata.origins().isEmpty())
        metadata.record(setOf("git"), LinuxPackageOrigin.User)
        runtime.paths.rootfs.renameTo(File(directory, "old-rootfs"))
        runtime.world("git\n")
        File(runtime.paths.rootfs, "etc/alpine-release").writeText("3.24.3\n")
        assertTrue(metadata.origins().isEmpty())
    }

    @Test fun unavailableSnapshotIsNullAndDoesNotAttribute(): Unit = runBlocking {
        runtime.world("bash\n")
        runtime.state.value = LinuxRuntimeState.NotInstalled(0)
        assertNull(metadata.worldSnapshot())
        metadata.attributeNewPackages(emptySet(), LinuxPackageOrigin.User)
        runtime.ready()
        assertTrue(metadata.origins().isEmpty())
    }

    @Test fun successfulInstallAndRemovalPersistOriginsOnlyForTopLevelPackages(): Unit = runBlocking {
        val store = metadata
        val commands = mutableListOf<List<String>>()
        val manager = LinuxPackageService(runtime, store, LinuxPackageCommandExecutor { launch, output ->
            commands += launch.arguments
            output("(1/2) Installing git (2.52.0-r0)\n")
            assertEquals(1, managerOperationStep())
            runtime.world(if (launch.arguments.contains("add")) "git\n" else "")
            packageOutput()
        })
        managerOperationStep = { manager.operation.value?.step }
        assertTrue(manager.install(listOf("git"), LinuxPackageOrigin.User).success)
        assertEquals(LinuxPackageOrigin.User, store.origins()["git"])
        assertTrue(manager.remove(listOf("git")).success)
        assertTrue(store.origins().isEmpty())
        assertTrue(commands[0].containsAll(listOf("add", "--", "git")))
        assertTrue(commands[1].containsAll(listOf("del", "--", "git")))
        assertNull(manager.operation.value)
    }
    private var managerOperationStep: () -> Int? = { null }

    @Test fun invalidNamesFailBeforeStartingAProcess(): Unit = runBlocking {
        var called = false
        val manager = LinuxPackageService(runtime, metadata, LinuxPackageCommandExecutor { _, _ -> called = true; packageOutput() })
        assertFalse(manager.install(listOf("--allow-untrusted"), LinuxPackageOrigin.User).success)
        assertFalse(manager.remove(listOf("../bash")).success)
        assertFalse(manager.upgrade(listOf("git;id")).success)
        assertFalse(manager.install(emptyList(), LinuxPackageOrigin.User).success)
        assertFalse(manager.remove(emptyList()).success)
        assertFalse(called)
    }

    @Test fun installedCachesUpgradesUntilRefreshAndSearchMarksInstalled(): Unit = runBlocking {
        runtime.world("git\n")
        File(runtime.paths.rootfs, "lib/apk/db").mkdirs()
        File(runtime.paths.rootfs, "lib/apk/db/installed").writeText("P:git\nV:2.50.0-r0\nI:2048\n")
        var lists = 0
        val manager = LinuxPackageService(runtime, metadata, LinuxPackageCommandExecutor { launch, _ ->
            when {
                "list" in launch.arguments -> { lists++; packageOutput("git-2.52.0-r0 aarch64 {git} (GPL-2.0) [upgradable from: git-2.50.0-r0]\n") }
                "search" in launch.arguments -> packageOutput("git-2.52.0-r0 - Distributed version control\nbash-5.3.0-r0 - Shell\n")
                else -> packageOutput()
            }
        })
        assertEquals("2.52.0-r0", manager.installed().single().availableVersion)
        manager.installed()
        assertEquals(1, lists)
        val found = manager.search("--help", 1).single()
        assertTrue(found.installed)
        assertTrue(found.explicit)
        assertEquals(2048L, found.installedSizeBytes)
        assertTrue(manager.refreshIndex().success)
        manager.installed()
        assertEquals(2, lists)
    }

    @Test fun failedInstallReconcilesPartialWorldAndKeepsFailureDetail(): Unit = runBlocking {
        val manager = LinuxPackageService(runtime, metadata, LinuxPackageCommandExecutor { _, _ ->
            runtime.world("git\n")
            packageOutput("", "ERROR: No space left on device", 1)
        })
        val result = manager.install(listOf("git", "bash"), LinuxPackageOrigin.User)
        assertEquals(LinuxFailureCode.INSUFFICIENT_STORAGE, result.failure?.code)
        assertTrue(result.failure!!.detail!!.contains("No space"))
        assertEquals(mapOf("git" to LinuxPackageOrigin.User), metadata.origins())
    }

    @Test fun failedInstallCannotReattributeAnExistingPackage(): Unit = runBlocking {
        runtime.world("git\n")
        metadata.record(setOf("git"), LinuxPackageOrigin.Preset)
        val manager = LinuxPackageService(runtime, metadata, LinuxPackageCommandExecutor { _, _ ->
            packageOutput("", "ERROR: missing (no such package)", 1)
        })
        assertFalse(manager.install(listOf("git", "missing"), LinuxPackageOrigin.User).success)
        assertEquals(LinuxPackageOrigin.Preset, metadata.origins()["git"])
    }

    @Test fun cancelledInstallCannotReattributeAnExistingPackage() = runTest {
        runtime.world("git\n")
        metadata.record(setOf("git"), LinuxPackageOrigin.Preset)
        val entered = CompletableDeferred<Unit>()
        val manager = LinuxPackageService(runtime, metadata, LinuxPackageCommandExecutor { _, _ ->
            entered.complete(Unit); awaitCancellation()
        })
        val job = launch { manager.install(listOf("git"), LinuxPackageOrigin.User) }
        entered.await()
        job.cancelAndJoin()
        assertEquals(LinuxPackageOrigin.Preset, metadata.origins()["git"])
    }

    @Test fun installedTimestampInvalidatesAnInPlaceReset(): Unit = runBlocking {
        runtime.world("git\n")
        metadata.record(setOf("git"), LinuxPackageOrigin.User)
        val ready = runtime.state.value as LinuxRuntimeState.Ready
        runtime.state.value = ready.copy(info = ready.info.copy(installedAtMs = 2))
        assertTrue(metadata.origins().isEmpty())
    }

    @Test fun replacingReleaseAndAddingRootDirectoriesPreservesOrigins(): Unit = runBlocking {
        runtime.world("git\n")
        metadata.record(setOf("git"), LinuxPackageOrigin.User)
        File(runtime.paths.rootfs, "opt").mkdir()
        val replacement = File(runtime.paths.rootfs, "etc/release.tmp").apply { writeText("3.24.4\n") }
        Files.move(replacement.toPath(), File(runtime.paths.rootfs, "etc/alpine-release").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(LinuxPackageOrigin.User, metadata.origins()["git"])
    }

    @Test fun installingOriginsBindToTheFirstReadyTimestamp(): Unit = runBlocking {
        runtime.state.value = LinuxRuntimeState.Installing(LinuxInstallProgress(emptyList(), null))
        runtime.world("bash\n")
        metadata.record(setOf("bash"), LinuxPackageOrigin.Preset)
        runtime.ready()
        assertEquals(LinuxPackageOrigin.Preset, metadata.origins()["bash"])
        val ready = runtime.state.value as LinuxRuntimeState.Ready
        runtime.state.value = ready.copy(info = ready.info.copy(installedAtMs = 2))
        assertTrue(metadata.origins().isEmpty())
    }

    @Test fun lateOutputCannotResurrectACompletedOperation(): Unit = runBlocking {
        var output: ((String) -> Unit)? = null
        val manager = LinuxPackageService(runtime, metadata, LinuxPackageCommandExecutor { _, emit -> output = emit; packageOutput() })
        manager.upgrade()
        assertNull(manager.operation.value)
        output!!("late output")
        assertNull(manager.operation.value)
    }

    @Test fun writesQueueAndCancellationReleasesTheLockAndOperation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var peak = 0
        var calls = 0
        val manager = LinuxPackageService(runtime, metadata, LinuxPackageCommandExecutor { _, _ ->
            running++; calls++; peak = maxOf(peak, running)
            try { entered.complete(Unit); gate.await(); packageOutput() } finally { running-- }
        })
        val first = launch { manager.upgrade() }
        entered.await()
        val second = async { manager.refreshIndex() }
        yield()
        assertEquals(1, calls)
        first.cancelAndJoin()
        gate.complete(Unit)
        assertTrue(second.await().success)
        assertEquals(1, peak)
        assertEquals(2, calls)
        assertNull(manager.operation.value)
    }
}

internal class LinuxPackageTestRuntime(root: File) : LinuxRuntime by UnavailableLinuxRuntime(
    LinuxUnavailableReason.DISTRIBUTION, LinuxPaths(root, File(root, "root")),
) {
    override val paths = LinuxPaths(root, File(root, "root"))
    override val state = MutableStateFlow<LinuxRuntimeState>(LinuxRuntimeState.NotInstalled(0))
    init { world(""); File(root, "etc/alpine-release").writeText("3.24.3\n"); ready() }
    fun ready() { state.value = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "3.24.3", "aarch64", "5", 1, null)) }
    fun world(text: String) { File(paths.rootfs, "etc/apk").mkdirs(); File(paths.rootfs, "etc/apk/world").writeText(text) }
    override suspend fun prepareLaunch(spec: LinuxLaunchSpec): LinuxProcessLaunch {
        if (state.value !is LinuxRuntimeState.Ready) throw LinuxUnavailableException(state.value)
        return LinuxProcessLaunch(spec.argv.first(), spec.argv.drop(1), spec.environment, paths.rootfs)
    }
}

internal fun packageOutput(stdout: String = "", stderr: String = "", exit: Int = 0) = BuiltinCommandOutput(
    exit, false, stdout, stderr, stdout.length.toLong(), stderr.length.toLong(), false, false,
)

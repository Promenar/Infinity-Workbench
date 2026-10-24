package com.promenar.nexara.ui.linux.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.linux.LinuxDiagnostics
import com.promenar.nexara.domain.linux.LinuxEnvironmentInfo
import com.promenar.nexara.domain.linux.LinuxInstallOptions
import com.promenar.nexara.domain.linux.LinuxLaunchSpec
import com.promenar.nexara.domain.linux.LinuxMirrorChoice
import com.promenar.nexara.domain.linux.LinuxMirrorId
import com.promenar.nexara.domain.linux.LinuxMirrorSettings
import com.promenar.nexara.domain.linux.LinuxPackage
import com.promenar.nexara.domain.linux.LinuxPackageAction
import com.promenar.nexara.domain.linux.LinuxPackageManager
import com.promenar.nexara.domain.linux.LinuxPackageOperation
import com.promenar.nexara.domain.linux.LinuxPackageOrigin
import com.promenar.nexara.domain.linux.LinuxPackageResult
import com.promenar.nexara.domain.linux.LinuxPaths
import com.promenar.nexara.domain.linux.LinuxProcessLaunch
import com.promenar.nexara.domain.linux.LinuxRuntime
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxStorageUsage
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinuxSettingsViewModelsTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `installed filter keeps explicit first and matches name or description`() {
        val packages = listOf(
            pkg("zlib", explicit = false),
            pkg("python3", explicit = true, available = "3.13.10-r0", origin = LinuxPackageOrigin.Agent("s1", "数据清洗")),
            pkg("git", explicit = true, description = "Distributed version control"),
        )
        assertThat(filterInstalled(packages, LinuxPackageFilter.ALL, "").map { it.name }).containsExactly("git", "python3", "zlib").inOrder()
        assertThat(filterInstalled(packages, LinuxPackageFilter.INSTALLED, "").map { it.name }).containsExactly("git", "python3").inOrder()
        assertThat(filterInstalled(packages, LinuxPackageFilter.UPDATES, "").map { it.name }).containsExactly("python3")
        assertThat(filterInstalled(packages, LinuxPackageFilter.AGENT, "").map { it.name }).containsExactly("python3")
        assertThat(filterInstalled(packages, LinuxPackageFilter.ALL, "VERSION").map { it.name }).containsExactly("git")
    }

    @Test
    fun `search results drop packages already listed as installed`() {
        val installed = listOf(pkg("nodejs", explicit = true))
        val results = listOf(pkg("nodejs", installed = false), pkg("npm", installed = false), pkg("nodejs-dev", installed = true))
        assertThat(searchOnlyResults(results, installed)!!.map { it.name }).containsExactly("npm")
        assertThat(searchOnlyResults(null, installed)).isNull()
    }

    @Test
    fun `custom mirror accepts only https with host`() {
        assertThat(isValidCustomMirror("https://mirror.tail1234.ts.net/alpine")).isTrue()
        assertThat(isValidCustomMirror(" https://example.org ")).isTrue()
        assertThat(isValidCustomMirror("http://example.org")).isFalse()
        assertThat(isValidCustomMirror("https://")).isFalse()
        assertThat(isValidCustomMirror("mirror example")).isFalse()
        assertThat(customMirrorHost("https://mirror.example.org/alpine")).isEqualTo("mirror.example.org")
        assertThat(customMirrorHost(null)).isNull()
    }

    @Test
    fun `search is debounced, skips short queries, and refreshes after an install`() = runTest(dispatcher) {
        val packages = FakePackages(installed = mutableListOf(pkg("git", explicit = true)))
        val viewModel = LinuxPackagesViewModel(packages)
        advanceUntilIdle()
        assertThat(packages.refreshCount).isEqualTo(1)
        assertThat(viewModel.uiState.value.installed!!.map { it.name }).containsExactly("git")

        viewModel.setQuery("n")
        advanceUntilIdle()
        assertThat(packages.searches).isEmpty()
        assertThat(viewModel.uiState.value.searchResults).isNull()

        viewModel.setQuery("no")
        viewModel.setQuery("node")
        advanceTimeBy(100)
        assertThat(packages.searches).isEmpty()
        advanceUntilIdle()
        assertThat(packages.searches).containsExactly("node")
        assertThat(viewModel.uiState.value.searchResults!!.map { it.name }).containsExactly("nodejs")

        viewModel.install("nodejs")
        advanceUntilIdle()
        assertThat(packages.installs).containsExactly(listOf("nodejs") to LinuxPackageOrigin.User)
        assertThat(viewModel.uiState.value.installed!!.map { it.name }).contains("nodejs")
        assertThat(viewModel.uiState.value.searchResults!!.single().installed).isTrue()
    }

    @Test
    fun `failed package operation is reported once`() = runTest(dispatcher) {
        val packages = FakePackages(installed = mutableListOf(), failInstall = true)
        val viewModel = LinuxPackagesViewModel(packages)
        advanceUntilIdle()
        viewModel.install("missing")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.failure!!.action).isEqualTo(LinuxPackageAction.INSTALL)
        viewModel.consumeFailure()
        assertThat(viewModel.uiState.value.failure).isNull()
    }

    @Test
    fun `environment refreshes usage when ready and serialises maintenance`() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val viewModel = LinuxEnvironmentViewModel(runtime, FakeMirrors())
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.usage).isNull()

        runtime.state.value = ready()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.usage!!.totalBytes).isEqualTo(100)

        val gate = CompletableDeferred<Unit>()
        runtime.resetGate = gate
        viewModel.reset()
        assertThat(viewModel.uiState.value.busy).isEqualTo(LinuxBusyAction.RESET)
        viewModel.uninstall()
        assertThat(runtime.uninstalls).isEqualTo(0)
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.busy).isNull()
        assertThat(runtime.resets).isEqualTo(1)

        viewModel.clearDownloadCache()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.notice).isEqualTo(LinuxEnvironmentNotice.CacheCleared(7))
        viewModel.consumeNotice()

        runtime.failDiagnostics = true
        viewModel.runDiagnostics()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.notice).isEqualTo(LinuxEnvironmentNotice.Failed("boom"))
    }

    @Test
    fun `install passes the common tools choice`() = runTest(dispatcher) {
        val runtime = FakeRuntime()
        val viewModel = LinuxEnvironmentViewModel(runtime, FakeMirrors())
        viewModel.setInstallCommonTools(false)
        viewModel.install()
        assertThat(runtime.installs).containsExactly(LinuxInstallOptions(installCommonTools = false))
    }

    @Test
    fun `mirrors view model rejects invalid custom address`() = runTest(dispatcher) {
        val mirrors = FakeMirrors()
        val viewModel = LinuxMirrorsViewModel(mirrors)
        viewModel.select(LinuxMirrorChoice(LinuxMirrorId.CUSTOM, "http://insecure"))
        viewModel.select(LinuxMirrorChoice(LinuxMirrorId.USTC))
        advanceUntilIdle()
        assertThat(mirrors.selections).containsExactly(LinuxMirrorChoice(LinuxMirrorId.USTC))
        viewModel.speedTest()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.latencies).containsEntry(LinuxMirrorId.TUNA, 30L)
        assertThat(viewModel.uiState.value.testing).isFalse()
    }

    private fun pkg(
        name: String,
        explicit: Boolean = false,
        installed: Boolean = true,
        available: String? = null,
        description: String? = null,
        origin: LinuxPackageOrigin? = null,
    ) = LinuxPackage(name, "1.0-r0", description, 1024, installed, available, explicit, origin)

    private fun ready() = LinuxRuntimeState.Ready(
        LinuxEnvironmentInfo("Alpine", "3.24.3", "aarch64", "5.1.107", 0, lastDiagnostics = null),
    )

    private inner class FakePackages(
        val installed: MutableList<LinuxPackage>,
        val failInstall: Boolean = false,
    ) : LinuxPackageManager {
        override val operation = MutableStateFlow<LinuxPackageOperation?>(null)
        var refreshCount = 0
        val searches = mutableListOf<String>()
        val installs = mutableListOf<Pair<List<String>, LinuxPackageOrigin>>()
        override suspend fun installed() = installed.toList()
        override suspend fun search(query: String, limit: Int): List<LinuxPackage> {
            searches += query
            return listOf(pkg("nodejs", installed = installed.any { it.name == "nodejs" }))
        }
        override suspend fun refreshIndex() = LinuxPackageResult(true, 0, "").also { refreshCount++ }
        override suspend fun install(names: List<String>, origin: LinuxPackageOrigin): LinuxPackageResult {
            installs += names to origin
            if (failInstall) return LinuxPackageResult(false, 1, "ERROR: unable to select packages")
            names.forEach { installed += pkg(it, explicit = true, origin = origin) }
            return LinuxPackageResult(true, 0, "")
        }
        override suspend fun remove(names: List<String>) = LinuxPackageResult(true, 0, "")
        override suspend fun upgrade(names: List<String>) = LinuxPackageResult(true, 0, "")
    }

    private class FakeMirrors : LinuxMirrorSettings {
        override val selected = MutableStateFlow(LinuxMirrorChoice(LinuxMirrorId.TUNA))
        override val latencies = MutableStateFlow<Map<LinuxMirrorId, Long?>>(emptyMap())
        val selections = mutableListOf<LinuxMirrorChoice>()
        override suspend fun select(choice: LinuxMirrorChoice) {
            selections += choice
            selected.value = choice
        }
        override fun speedTest(): Flow<Pair<LinuxMirrorId, Long?>> = flow {
            latencies.value = mapOf(LinuxMirrorId.TUNA to 30L, LinuxMirrorId.OFFICIAL to null)
            emit(LinuxMirrorId.TUNA to 30L)
        }
    }

    private class FakeRuntime : LinuxRuntime {
        override val state = MutableStateFlow<LinuxRuntimeState>(LinuxRuntimeState.NotInstalled(1L shl 30))
        override val agentEnabled = MutableStateFlow(true)
        override val paths = LinuxPaths(File("rootfs"), File("rootfs/root"))
        val installs = mutableListOf<LinuxInstallOptions>()
        var resetGate: CompletableDeferred<Unit>? = null
        var resets = 0
        var uninstalls = 0
        var failDiagnostics = false
        override suspend fun setAgentEnabled(enabled: Boolean) { agentEnabled.value = enabled }
        override fun startInstall(options: LinuxInstallOptions) { installs += options }
        override fun cancelInstall() = Unit
        override suspend fun storageUsage() = LinuxStorageUsage(10, 60, 20, 10)
        override suspend fun clearDownloadCache() = 7L
        override suspend fun runDiagnostics(): LinuxDiagnostics {
            if (failDiagnostics) error("boom")
            return LinuxDiagnostics(true, 0, 50, emptyList())
        }
        override suspend fun reset() {
            resetGate?.await()
            resets++
        }
        override suspend fun uninstall() { uninstalls++ }
        override suspend fun prepareLaunch(spec: LinuxLaunchSpec): LinuxProcessLaunch = error("unused")
    }
}

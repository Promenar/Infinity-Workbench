package com.promenar.infinity.ui.linux.files

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.linux.LinuxFileErrorCode
import com.promenar.infinity.domain.linux.LinuxInstallProgress
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.ui.chat.components.DirectoryClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinuxFilesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val files = FakeLinuxFileSystem().apply {
        dir("/root")
        dir("/root/projects", modifiedMs = 900_000)
        dir("/root/projects/scraper", modifiedMs = 990_000)
        file("/root/projects/run.sh", "#!/bin/sh\necho hi\n", modifiedMs = 980_000, mode = "755".toInt(8))
        dir("/root/projects/.venv", modifiedMs = 970_000)
        link("/root/projects/data", "/workspace/data", targetIsDirectory = false)
        link("/root/projects/shared", "../shared", targetIsDirectory = true)
        dir("/root/shared")
        dir("/etc")
    }
    private val runtime = FakeLinuxRuntime()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(path: String = "/root/projects"): LinuxFilesViewModel =
        LinuxFilesViewModel(files, runtime, path, io = dispatcher, clock = { 1_000_000L }).also { advanceUntilIdle() }

    @Test
    fun `not ready runtime shows empty state and loads once ready`() = runTest(dispatcher) {
        runtime.state.value = LinuxRuntimeState.Installing(LinuxInstallProgress(emptyList(), null))
        val vm = viewModel()
        assertThat(vm.state.value.ready).isFalse()
        assertThat(files.listCalls).isEmpty()

        vm.refresh()
        advanceUntilIdle()
        assertThat(files.listCalls).isEmpty()

        runtime.state.value = READY
        advanceUntilIdle()
        assertThat(vm.state.value.ready).isTrue()
        assertThat(vm.state.value.entries.map { it.name }).contains("run.sh")
    }

    @Test
    fun `runtime leaving ready clears entries`() = runTest(dispatcher) {
        val vm = viewModel()
        assertThat(vm.state.value.entries).isNotEmpty()
        runtime.state.value = LinuxRuntimeState.NotInstalled(0)
        advanceUntilIdle()
        assertThat(vm.state.value.ready).isFalse()
        assertThat(vm.state.value.entries).isEmpty()
    }

    @Test
    fun `default sort is newest first with hidden entries shown`() = runTest(dispatcher) {
        val vm = viewModel()
        assertThat(vm.state.value.visibleEntries.take(3).map { it.name }).containsExactly("data", "shared", "scraper").inOrder()
        vm.toggleShowHidden()
        assertThat(vm.state.value.visibleEntries.map { it.name }).doesNotContain(".venv")
        vm.setSort(LinuxFileSort.NAME)
        assertThat(vm.state.value.visibleEntries.first().name).isEqualTo("scraper")
    }

    @Test
    fun `opening folders, folder links and files`() = runTest(dispatcher) {
        val vm = viewModel()
        val state = vm.state.value
        assertThat(vm.open(state.entries.first { it.name == "run.sh" })).isEqualTo("/root/projects/run.sh")

        assertThat(vm.open(state.entries.first { it.name == "shared" })).isNull()
        advanceUntilIdle()
        assertThat(vm.state.value.path).isEqualTo("/root/shared")

        vm.navigate("/root/projects")
        advanceUntilIdle()
        assertThat(vm.open(vm.state.value.entries.first { it.name == "scraper" })).isNull()
        advanceUntilIdle()
        assertThat(vm.state.value.path).isEqualTo("/root/projects/scraper")
    }

    @Test
    fun `link into a runtime binding shows a notice instead of navigating`() = runTest(dispatcher) {
        val vm = viewModel()
        assertThat(vm.open(vm.state.value.entries.first { it.name == "data" })).isNull()
        assertThat(vm.state.value.path).isEqualTo("/root/projects")
        assertThat(vm.state.value.notice).isEqualTo(LinuxFilesNotice.RuntimeBinding("data", "/workspace/data"))
        vm.dismissNotice()
        assertThat(vm.state.value.notice).isNull()
    }

    @Test
    fun `entering a folder keeps the previous listing until the new one is read`() = runTest(dispatcher) {
        val vm = viewModel()
        val before = vm.state.value.entries
        assertThat(vm.state.value.listedPath).isEqualTo("/root/projects")
        vm.navigate("/root/shared")
        // 读取完成前仍显示上一目录，避免列表先闪成空白再出现。
        assertThat(vm.state.value.path).isEqualTo("/root/shared")
        assertThat(vm.state.value.entries).isEqualTo(before)
        assertThat(vm.state.value.stale).isTrue()
        vm.selectAll()
        vm.toggleSelection(before.first().path)
        assertThat(vm.state.value.selection).isEmpty()
        advanceUntilIdle()
        assertThat(vm.state.value.listedPath).isEqualTo("/root/shared")
        assertThat(vm.state.value.stale).isFalse()
        assertThat(vm.state.value.entries).isNotEqualTo(before)
    }

    @Test
    fun `navigate up stops at root and go home returns to the home folder`() = runTest(dispatcher) {
        val vm = viewModel("/root")
        assertThat(vm.state.value.atHome).isTrue()
        assertThat(vm.navigateUp()).isTrue()
        advanceUntilIdle()
        assertThat(vm.state.value.path).isEqualTo("/")
        assertThat(vm.state.value.atHome).isFalse()
        assertThat(vm.state.value.entries.map { it.name }).containsAtLeast("root", "etc")
        assertThat(vm.navigateUp()).isFalse()
        vm.goHome()
        advanceUntilIdle()
        assertThat(vm.state.value.path).isEqualTo("/root")
    }

    @Test
    fun `query filters the current folder and clears when navigating`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setQuery("DAT")
        assertThat(vm.state.value.visibleEntries.map { it.name }).containsExactly("data")
        vm.selectAll()
        assertThat(vm.state.value.selection).containsExactly("/root/projects/data")
        vm.clearSelection()
        vm.navigate("/root")
        advanceUntilIdle()
        assertThat(vm.state.value.query).isEmpty()
        assertThat(vm.state.value.visibleEntries.map { it.name }).contains("projects")
    }

    @Test
    fun `list failure maps to error code`() = runTest(dispatcher) {
        files.listError = LinuxFileErrorCode.OUTSIDE_ROOTFS
        val vm = viewModel()
        assertThat(vm.state.value.error).isEqualTo(LinuxFileErrorCode.OUTSIDE_ROOTFS)
        assertThat(vm.state.value.loading).isFalse()
        files.listError = null
        vm.refresh()
        advanceUntilIdle()
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.entries).isNotEmpty()
    }

    @Test
    fun `clicking while selecting toggles selection`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.toggleSelection("/root/projects/run.sh")
        val folder = vm.state.value.entries.first { it.name == "scraper" }
        assertThat(vm.open(folder)).isNull()
        assertThat(vm.state.value.path).isEqualTo("/root/projects")
        assertThat(vm.state.value.selection).containsExactly("/root/projects/run.sh", "/root/projects/scraper")
        vm.open(folder)
        assertThat(vm.state.value.selection).containsExactly("/root/projects/run.sh")
    }

    @Test
    fun `hiding hidden files drops them from the selection`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.selectAll()
        assertThat(vm.state.value.selection).contains("/root/projects/.venv")
        vm.toggleShowHidden()
        assertThat(vm.state.value.selection).doesNotContain("/root/projects/.venv")
        vm.selectAll()
        assertThat(vm.state.value.selection).hasSize(vm.state.value.visibleEntries.size)
    }

    @Test
    fun `copy then paste into another folder clears the clipboard`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.toggleSelection("/root/projects/run.sh")
        vm.toggleSelection("/root/projects/scraper")
        vm.copySelection(cut = false)
        assertThat(vm.state.value.selection).isEmpty()
        assertThat(vm.state.value.clipboard?.cut).isFalse()

        vm.navigate("/root/shared")
        advanceUntilIdle()
        assertThat(vm.state.value.canPaste).isTrue()
        vm.paste()
        advanceUntilIdle()
        assertThat(files.copyCalls.single().second).isEqualTo("/root/shared")
        assertThat(files.copyCalls.single().first).containsExactly("/root/projects/scraper", "/root/projects/run.sh")
        assertThat(vm.state.value.outcome).isEqualTo(LinuxFileOpOutcome(LinuxFileOp.COPY, 2, 0, null))
        assertThat(vm.state.value.clipboard).isNull()
        assertThat(vm.state.value.operating).isFalse()
    }

    @Test
    fun `moving a folder into itself is blocked`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.toggleSelection("/root/projects/scraper")
        vm.copySelection(cut = true)
        vm.navigate("/root/projects/scraper")
        advanceUntilIdle()
        assertThat(vm.state.value.canPaste).isFalse()
        vm.paste()
        advanceUntilIdle()
        assertThat(files.moveCalls).isEmpty()
        // 剪切到原目录同样无意义。
        vm.navigate("/root/projects")
        advanceUntilIdle()
        assertThat(vm.state.value.canPaste).isFalse()
    }

    @Test
    fun `failed move keeps the clipboard for another try`() = runTest(dispatcher) {
        files.failures["/root/projects/run.sh"] = LinuxFileErrorCode.NAME_EXISTS
        val vm = viewModel()
        vm.toggleSelection("/root/projects/run.sh")
        vm.copySelection(cut = true)
        vm.navigate("/root/shared")
        advanceUntilIdle()
        vm.paste()
        advanceUntilIdle()
        assertThat(vm.state.value.outcome).isEqualTo(LinuxFileOpOutcome(LinuxFileOp.MOVE, 0, 1, LinuxFileErrorCode.NAME_EXISTS))
        assertThat(vm.state.value.clipboard).isEqualTo(DirectoryClipboard(listOf("/root/projects/run.sh"), cut = true))
    }

    @Test
    fun `delete asks for confirmation and reports partial failure`() = runTest(dispatcher) {
        files.failures["/root/projects/scraper"] = LinuxFileErrorCode.IO
        val vm = viewModel()
        vm.toggleSelection("/root/projects/run.sh")
        vm.toggleSelection("/root/projects/scraper")
        vm.requestDelete()
        assertThat(vm.state.value.dialog).isInstanceOf(LinuxFilesDialog.ConfirmDelete::class.java)
        assertThat(files.deleteCalls).isEmpty()

        vm.confirmDelete()
        advanceUntilIdle()
        assertThat(files.deleteCalls.single()).hasSize(2)
        assertThat(vm.state.value.dialog).isNull()
        assertThat(vm.state.value.outcome).isEqualTo(LinuxFileOpOutcome(LinuxFileOp.DELETE, 1, 1, LinuxFileErrorCode.IO))
        assertThat(vm.state.value.entries.map { it.name }).doesNotContain("run.sh")
        assertThat(vm.state.value.selection).isEmpty()
    }

    @Test
    fun `creating an existing name keeps the dialog open with the error`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.requestCreate(folder = true)
        vm.confirmName("scraper")
        advanceUntilIdle()
        assertThat(vm.state.value.dialog).isEqualTo(LinuxFilesDialog.CreateFolder("/root/projects"))
        assertThat(vm.state.value.dialogError).isEqualTo(LinuxFileErrorCode.NAME_EXISTS)
        vm.clearDialogError()
        vm.confirmName("  notes  ")
        advanceUntilIdle()
        assertThat(vm.state.value.dialog).isNull()
        assertThat(vm.state.value.outcome).isEqualTo(LinuxFileOpOutcome(LinuxFileOp.CREATE, 1, 0, null, "notes"))
        assertThat(vm.state.value.entries.map { it.name }).contains("notes")
    }

    @Test
    fun `rename only applies to a single selection and skips unchanged names`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.toggleSelection("/root/projects/run.sh")
        vm.toggleSelection("/root/projects/scraper")
        vm.requestRename()
        assertThat(vm.state.value.dialog).isNull()
        vm.toggleSelection("/root/projects/scraper")
        vm.requestRename()
        vm.confirmName("run.sh")
        assertThat(vm.state.value.dialog).isNull()
        vm.requestRename()
        vm.confirmName("start.sh")
        advanceUntilIdle()
        assertThat(vm.state.value.entries.map { it.name }).contains("start.sh")
        assertThat(vm.state.value.outcome?.name).isEqualTo("start.sh")
    }

    @Test
    fun `compress suggests a name and enforces the tar gz suffix`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.toggleSelection("/root/projects/scraper")
        vm.requestCompress()
        assertThat((vm.state.value.dialog as LinuxFilesDialog.Compress).defaultName).isEqualTo("scraper.tar.gz")
        vm.confirmName("backup")
        advanceUntilIdle()
        assertThat(files.compressCalls.single()).isEqualTo(listOf("/root/projects/scraper") to "backup.tar.gz")
        assertThat(vm.state.value.outcome).isEqualTo(LinuxFileOpOutcome(LinuxFileOp.COMPRESS, 1, 0, null, "backup.tar.gz"))
    }

    @Test
    fun `chmod skips unchanged modes and links`() = runTest(dispatcher) {
        val vm = viewModel()
        val script = vm.state.value.entries.first { it.name == "run.sh" }
        vm.requestChmod(script)
        vm.confirmChmod(script.mode)
        assertThat(files.chmodCalls).isEmpty()
        assertThat(vm.state.value.dialog).isNull()

        vm.requestChmod(script)
        vm.confirmChmod("700".toInt(8))
        advanceUntilIdle()
        assertThat(files.chmodCalls.single()).isEqualTo("/root/projects/run.sh" to "700".toInt(8))

        vm.requestChmod(vm.state.value.entries.first { it.name == "data" })
        assertThat(vm.state.value.dialog).isNull()
    }

    @Test
    fun `terminal opens in the selected folder or the current one`() = runTest(dispatcher) {
        val vm = viewModel()
        assertThat(vm.terminalDirectory()).isEqualTo("/root/projects")
        vm.toggleSelection("/root/projects/scraper")
        assertThat(vm.terminalDirectory()).isEqualTo("/root/projects/scraper")
        vm.clearSelection()
        vm.toggleSelection("/root/projects/shared")
        assertThat(vm.terminalDirectory()).isEqualTo("/root/shared")
        vm.clearSelection()
        vm.toggleSelection("/root/projects/run.sh")
        assertThat(vm.terminalDirectory()).isEqualTo("/root/projects")
    }
}

package com.promenar.infinity.ui.linux.files

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.linux.LinuxFileErrorCode
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
class LinuxFileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val files = FakeLinuxFileSystem().apply {
        dir("/root")
        file("/root/run.sh", "#!/bin/bash\nset -e\necho done\n", modifiedMs = 500_000, mode = "755".toInt(8))
        file("/root/notes.md", "# Notes\n", modifiedMs = 600_000)
        binary("/root/model.bin", ByteArray(LINUX_HEX_PAGE_BYTES * 2 + 10) { if (it == 0) 0 else 0x41 })
        file("/root/big.log", "x".repeat(LINUX_TEXT_PREVIEW_BYTES + 100))
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(path: String): LinuxFileViewModel =
        LinuxFileViewModel(files, path, io = dispatcher, clock = { 1_000_000L }).also { advanceUntilIdle() }

    @Test
    fun `script loads as editable shell text and can run`() = runTest(dispatcher) {
        val vm = viewModel("/root/run.sh")
        val state = vm.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.name).isEqualTo("run.sh")
        assertThat(state.text?.kind).isEqualTo(LinuxFileKind.SHELL)
        assertThat(state.text?.lineCount).isEqualTo(3)
        assertThat(state.canEdit).isTrue()
        assertThat(state.canRun).isTrue()
        assertThat(viewModel("/root/notes.md").state.value.canRun).isFalse()
    }

    @Test
    fun `showing the page again rereads the file`() = runTest(dispatcher) {
        val vm = viewModel("/root/notes.md")
        vm.onShown()
        advanceUntilIdle()
        files.touch("/root/notes.md", "# Changed\n", modifiedMs = 800_000)
        assertThat(vm.state.value.text?.text).isEqualTo("# Notes\n")
        vm.onShown()
        advanceUntilIdle()
        assertThat(vm.state.value.text?.text).isEqualTo("# Changed\n")
        assertThat(vm.state.value.text?.modifiedMs).isEqualTo(800_000L)
    }

    @Test
    fun `missing file reports not found`() = runTest(dispatcher) {
        val vm = viewModel("/root/missing")
        assertThat(vm.state.value.error).isEqualTo(LinuxFileErrorCode.NOT_FOUND)
    }

    @Test
    fun `large file is truncated and read only`() = runTest(dispatcher) {
        val vm = viewModel("/root/big.log")
        val text = vm.state.value.text!!
        assertThat(text.truncated).isTrue()
        assertThat(text.shownBytes).isEqualTo(LINUX_TEXT_PREVIEW_BYTES)
        assertThat(vm.state.value.canEdit).isFalse()
        vm.startEditing()
        assertThat(vm.state.value.editing).isFalse()
    }

    @Test
    fun `binary file is not editable and hex pages read in fixed chunks`() = runTest(dispatcher) {
        val vm = viewModel("/root/model.bin")
        assertThat(vm.state.value.text?.isBinary).isTrue()
        assertThat(vm.state.value.canEdit).isFalse()
        assertThat(vm.state.value.hexPageCount).isEqualTo(3)
        assertThat(files.readBytesCalls).isEmpty()

        vm.selectTab(LinuxFileTab.HEX)
        advanceUntilIdle()
        assertThat(files.readBytesCalls.single()).isEqualTo(0L to LINUX_HEX_PAGE_BYTES)
        assertThat(vm.state.value.hex?.page).isEqualTo(0)

        vm.loadHexPage(2)
        advanceUntilIdle()
        assertThat(files.readBytesCalls.last()).isEqualTo(2L * LINUX_HEX_PAGE_BYTES to LINUX_HEX_PAGE_BYTES)
        assertThat(vm.state.value.hex?.bytes?.size).isEqualTo(10)

        vm.loadHexPage(9)
        advanceUntilIdle()
        assertThat(vm.state.value.hex?.page).isEqualTo(2)
    }

    @Test
    fun `save checks the modified time from when the file was opened`() = runTest(dispatcher) {
        val vm = viewModel("/root/run.sh")
        vm.startEditing()
        assertThat(vm.state.value.editing).isTrue()

        val result = vm.save("echo one\n")
        assertThat(result.isSuccess).isTrue()
        assertThat(files.writeCalls.single().third).isEqualTo(500_000L)
        assertThat(vm.state.value.editing).isFalse()
        assertThat(vm.state.value.text?.text).isEqualTo("echo one\n")
        val savedModified = vm.state.value.entry!!.modifiedMs
        assertThat(vm.state.value.text?.modifiedMs).isEqualTo(savedModified)

        // 第二次保存以上次保存后的修改时间为期望值。
        vm.startEditing()
        assertThat(vm.save("echo two\n").isSuccess).isTrue()
        assertThat(files.writeCalls.last().third).isEqualTo(savedModified)
    }

    @Test
    fun `conflict keeps edits and allows reload`() = runTest(dispatcher) {
        val vm = viewModel("/root/run.sh")
        vm.startEditing()
        files.touch("/root/run.sh", "echo changed elsewhere\n", modifiedMs = 700_000)

        val result = vm.save("echo mine\n")
        assertThat(result.isFailure).isTrue()
        assertThat(vm.state.value.conflict).isEqualTo(LinuxEditConflict("echo mine\n"))
        assertThat(vm.state.value.editing).isTrue()
        assertThat(vm.state.value.saving).isFalse()

        vm.reloadAfterConflict()
        advanceUntilIdle()
        assertThat(vm.state.value.conflict).isNull()
        assertThat(vm.state.value.editing).isFalse()
        assertThat(vm.state.value.text?.text).isEqualTo("echo changed elsewhere\n")
        assertThat(vm.state.value.text?.modifiedMs).isEqualTo(700_000L)
    }

    @Test
    fun `conflict overwrite saves without the time check`() = runTest(dispatcher) {
        val vm = viewModel("/root/run.sh")
        vm.startEditing()
        files.touch("/root/run.sh", "other\n", modifiedMs = 700_000)
        vm.save("mine\n")
        vm.overwriteAfterConflict()
        advanceUntilIdle()
        assertThat(files.writeCalls.last().third).isNull()
        assertThat(files.contents.getValue("/root/run.sh").decodeToString()).isEqualTo("mine\n")
        assertThat(vm.state.value.conflict).isNull()
        assertThat(vm.state.value.editing).isFalse()
    }

    @Test
    fun `other save errors stay in the editor without a conflict`() = runTest(dispatcher) {
        val vm = viewModel("/root/run.sh")
        vm.startEditing()
        files.failures["/root/run.sh"] = LinuxFileErrorCode.IO
        assertThat(vm.save("x").isFailure).isTrue()
        assertThat(vm.state.value.saveError).isEqualTo(LinuxFileErrorCode.IO)
        assertThat(vm.state.value.conflict).isNull()
        assertThat(vm.state.value.editing).isTrue()
    }

    @Test
    fun `chmod updates the entry and skips unchanged modes`() = runTest(dispatcher) {
        val vm = viewModel("/root/run.sh")
        vm.requestChmod()
        assertThat(vm.state.value.chmodOpen).isTrue()
        vm.confirmChmod("755".toInt(8))
        assertThat(files.chmodCalls).isEmpty()
        assertThat(vm.state.value.chmodOpen).isFalse()

        vm.requestChmod()
        vm.confirmChmod("644".toInt(8))
        advanceUntilIdle()
        assertThat(vm.state.value.entry?.mode).isEqualTo("644".toInt(8))
        assertThat(vm.state.value.canRun).isFalse()
        assertThat(vm.state.value.chmodOpen).isFalse()
    }
}

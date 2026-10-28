package com.promenar.infinity.ui.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.tool.CommandToolResult
import com.promenar.infinity.ui.linux.output.CommandOutputEvent
import com.promenar.infinity.ui.linux.output.CommandOutputLoad
import com.promenar.infinity.ui.linux.output.CommandOutputParser
import com.promenar.infinity.ui.linux.output.CommandOutputPhase
import com.promenar.infinity.ui.linux.output.CommandOutputRepository
import com.promenar.infinity.ui.linux.output.CommandOutputSource
import com.promenar.infinity.ui.linux.output.CommandOutputViewModel
import com.promenar.infinity.ui.linux.output.OutputSegment
import com.promenar.infinity.ui.linux.output.WorkspaceSaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CommandOutputViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val source = CommandOutputSource("session-1", "msg-1", "call-1")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(
        var load: () -> CommandOutputLoad,
        var save: WorkspaceSaveResult = WorkspaceSaveResult.Saved("/out.txt"),
    ) : CommandOutputRepository {
        val saved = mutableListOf<Triple<String, String, String>>()
        override suspend fun load(source: CommandOutputSource) = load()
        override suspend fun saveToWorkspace(sessionId: String, fileName: String, text: String): WorkspaceSaveResult {
            saved += Triple(sessionId, fileName, text)
            return save
        }
    }

    private fun loaded(stdout: String = "Line one\nERROR two\n", stderr: String = "\u001B[31merror\u001B[0m: x\n") = CommandOutputLoad.Loaded(
        CommandOutputParser.parse(
            "shell_exec",
            """{"command":"make"}""",
            CommandToolResult(exitCode = 1, stdout = stdout, stderr = stderr).toJson().toString(),
            2_000,
        ),
    )

    private fun viewModel(repository: CommandOutputRepository) =
        CommandOutputViewModel(source, repository, "── stderr ──", compute = dispatcher, clock = { 0L })

    @Test
    fun `加载后默认显示合并分段`() {
        val vm = viewModel(FakeRepository({ loaded() }))

        val state = vm.state.value
        assertThat(state.phase).isEqualTo(CommandOutputPhase.LOADED)
        assertThat(state.segment).isEqualTo(OutputSegment.MERGED)
        assertThat(state.document.text).isEqualTo("Line one\nERROR two\n── stderr ──\nerror: x\n")
        assertThat(state.segmentsAvailable).isTrue()
    }

    @Test
    fun `切换分段后搜索结果跟随当前分段`() {
        val vm = viewModel(FakeRepository({ loaded() }))

        vm.setQuery("error")
        assertThat(vm.state.value.search.matches).hasSize(2)

        vm.selectSegment(OutputSegment.STDERR)
        assertThat(vm.state.value.document.text).isEqualTo("error: x\n")
        assertThat(vm.state.value.search.matches).containsExactly(0..4)
        assertThat(vm.currentText()).isEqualTo("error: x\n")

        vm.selectSegment(OutputSegment.STDOUT)
        assertThat(vm.state.value.search.matches).containsExactly(9..13)
    }

    @Test
    fun `跳转命中循环`() {
        val vm = viewModel(FakeRepository({ loaded(stdout = "a a a") }))
        vm.selectSegment(OutputSegment.STDOUT)
        vm.setQuery("a")

        assertThat(vm.state.value.search.current).isEqualTo(0)
        vm.nextMatch()
        vm.nextMatch()
        assertThat(vm.state.value.search.current).isEqualTo(2)
        vm.nextMatch()
        assertThat(vm.state.value.search.current).isEqualTo(0)
        vm.previousMatch()
        assertThat(vm.state.value.search.current).isEqualTo(2)
    }

    @Test
    fun `截断说明只列出当前分段相关的输出`() {
        val truncated = CommandOutputLoad.Loaded(
            CommandOutputParser.parse(
                "shell_exec",
                """{"command":"x"}""",
                CommandToolResult(exitCode = 0, stdout = "tail", stdoutOriginalLength = 90_000, stdoutTruncated = true, stderr = "e").toJson().toString(),
                null,
            ),
        )
        val vm = viewModel(FakeRepository({ truncated }))

        assertThat(vm.state.value.truncatedStreams.map { it.first }).containsExactly(OutputSegment.STDOUT)
        vm.selectSegment(OutputSegment.STDERR)
        assertThat(vm.state.value.truncatedStreams).isEmpty()
    }

    @Test
    fun `保存当前分段为去掉控制序列的文本`() = runTest(dispatcher) {
        val repository = FakeRepository({ loaded() })
        val vm = viewModel(repository)
        vm.selectSegment(OutputSegment.STDERR)
        val events = mutableListOf<CommandOutputEvent>()
        val collector = launch { vm.events.collect { events += it } }

        vm.saveToWorkspace()
        collector.cancel()

        assertThat(events).containsExactly(CommandOutputEvent.Saved("/out.txt"))
        val (session, name, text) = repository.saved.single()
        assertThat(session).isEqualTo("session-1")
        assertThat(name).startsWith("command-")
        assertThat(name).endsWith("-stderr.txt")
        assertThat(text).isEqualTo("error: x\n")
        assertThat(vm.state.value.saving).isFalse()
    }

    @Test
    fun `自选目录不支持保存时交给分享，失败时报告`() = runTest(dispatcher) {
        val repository = FakeRepository({ loaded() }, save = WorkspaceSaveResult.Unsupported)
        val vm = viewModel(repository)
        val events = mutableListOf<CommandOutputEvent>()
        val collector = launch { vm.events.collect { events += it } }

        vm.saveToWorkspace()
        repository.save = WorkspaceSaveResult.Failed
        vm.saveToWorkspace()
        collector.cancel()

        assertThat(events).containsExactly(
            CommandOutputEvent.SaveUnsupported(vm.currentText()),
            CommandOutputEvent.SaveFailed,
        ).inOrder()
    }

    @Test
    fun `找不到、等待中与读取失败`() {
        val repository = FakeRepository({ CommandOutputLoad.NotFound })
        val vm = viewModel(repository)
        assertThat(vm.state.value.phase).isEqualTo(CommandOutputPhase.NOT_FOUND)

        repository.load = { CommandOutputLoad.Pending }
        vm.reload()
        assertThat(vm.state.value.phase).isEqualTo(CommandOutputPhase.PENDING)

        repository.load = { throw IOException("db") }
        vm.reload()
        assertThat(vm.state.value.phase).isEqualTo(CommandOutputPhase.FAILED)

        repository.load = { loaded() }
        vm.reload()
        assertThat(vm.state.value.phase).isEqualTo(CommandOutputPhase.LOADED)
    }

    @Test
    fun `后台任务日志只有合并分段`() {
        val job = CommandOutputLoad.Loaded(
            CommandOutputParser.parse("job_status", """{"job_id":"j"}""", """{"summary":"make","log_tail":"log\n","log_truncated":false}""", null),
        )
        val vm = viewModel(FakeRepository({ job }))

        assertThat(vm.state.value.segmentsAvailable).isFalse()
        vm.selectSegment(OutputSegment.STDERR)
        assertThat(vm.state.value.segment).isEqualTo(OutputSegment.MERGED)
        assertThat(vm.state.value.document.text).isEqualTo("log\n")
    }
}

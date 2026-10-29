package com.promenar.infinity.ui.chat.export

import android.app.Application
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.export.SessionExportEstimate
import com.promenar.infinity.domain.export.SessionExportFile
import com.promenar.infinity.domain.export.SessionExportFormat
import com.promenar.infinity.domain.export.SessionExportOptions
import com.promenar.infinity.domain.export.SessionExportResult
import com.promenar.infinity.domain.export.SessionExporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SessionExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeExporter : SessionExporter {
        val estimateCalls = mutableListOf<Pair<SessionExportFormat, SessionExportOptions>>()
        val exportCalls = mutableListOf<Pair<SessionExportFormat, SessionExportOptions>>()
        val released = mutableListOf<SessionExportResult>()
        var estimateFailure: Exception? = null
        var exportFailure: Exception? = null
        var exportGate: CompletableDeferred<Unit>? = null

        override suspend fun estimate(sessionId: String, format: SessionExportFormat, options: SessionExportOptions): SessionExportEstimate {
            estimateCalls += format to options
            estimateFailure?.let { throw it }
            return SessionExportEstimate(primaryFileCount = 1, attachmentArchive = options.includeAttachments, approxBytes = 1024)
        }

        override suspend fun export(sessionId: String, format: SessionExportFormat, options: SessionExportOptions): SessionExportResult {
            exportCalls += format to options
            exportGate?.await()
            exportFailure?.let { throw it }
            val count = if (format == SessionExportFormat.PNG) 2 else 1
            return SessionExportResult(
                files = (1..count).map { SessionExportFile(File("out_$it.${format.extension}"), "会话_$it.${format.extension}", format.mimeType) },
                workDir = File("work"),
            )
        }

        override fun release(result: SessionExportResult) {
            released += result
        }
    }

    private val exporter = FakeExporter()
    private val written = mutableListOf<Pair<SessionExportResult, Uri>>()
    private var writeFailure: Exception? = null
    private lateinit var viewModel: SessionExportViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = SessionExportViewModel("s1", exporter) { result, target ->
            writeFailure?.let { throw it }
            written += result to target
        }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `切换格式与开关重新估算，估算失败显示无法估算`() = runTest(dispatcher) {
        viewModel.refreshEstimate()
        advanceUntilIdle()
        assertThat(viewModel.state.value.estimate).isInstanceOf(SessionExportEstimateState.Ready::class.java)

        viewModel.selectFormat(SessionExportFormat.PDF)
        assertThat(viewModel.state.value.estimate).isEqualTo(SessionExportEstimateState.Loading)
        viewModel.updateOptions(SessionExportOptions(includeThinking = true))
        advanceUntilIdle()
        assertThat(exporter.estimateCalls.last()).isEqualTo(SessionExportFormat.PDF to SessionExportOptions(includeThinking = true))

        exporter.estimateFailure = IllegalStateException("boom")
        viewModel.selectFormat(SessionExportFormat.HTML)
        advanceUntilIdle()
        assertThat(viewModel.state.value.estimate).isEqualTo(SessionExportEstimateState.Unavailable)
    }

    @Test fun `保存到：生成后请求选位置，写入后删除临时文件并提示`() = runTest(dispatcher) {
        viewModel.updateOptions(SessionExportOptions(includeToolCalls = true))
        viewModel.save()
        assertThat(viewModel.state.value.running).isEqualTo(SessionExportAction.SAVE)
        val pick = viewModel.events.first() as SessionExportEvent.PickSaveTarget
        assertThat(viewModel.state.value.running).isNull()
        assertThat(exporter.exportCalls.single()).isEqualTo(SessionExportFormat.MARKDOWN to SessionExportOptions(includeToolCalls = true))

        val target = Uri.parse("content://docs/1")
        viewModel.onSaveTarget(target)
        assertThat(viewModel.events.first()).isEqualTo(SessionExportEvent.Saved("会话_1.md", 1))
        assertThat(written.single()).isEqualTo(pick.result to target)
        assertThat(exporter.released).containsExactly(pick.result)
    }

    @Test fun `取消选择位置时删除临时文件且不写入`() = runTest(dispatcher) {
        viewModel.save()
        val pick = viewModel.events.first() as SessionExportEvent.PickSaveTarget
        viewModel.onSaveTarget(null)
        advanceUntilIdle()
        assertThat(written).isEmpty()
        assertThat(exporter.released).containsExactly(pick.result)
        // 重复回调不再处理
        viewModel.onSaveTarget(Uri.parse("content://docs/2"))
        advanceUntilIdle()
        assertThat(written).isEmpty()
    }

    @Test fun `写入失败提示保存失败并删除临时文件`() = runTest(dispatcher) {
        writeFailure = IOException("disk full")
        viewModel.save()
        val pick = viewModel.events.first() as SessionExportEvent.PickSaveTarget
        viewModel.onSaveTarget(Uri.parse("content://docs/1"))
        assertThat(viewModel.events.first()).isEqualTo(SessionExportEvent.SaveFailed)
        assertThat(exporter.released).containsExactly(pick.result)
    }

    @Test fun `分享多张长图交给分享面板，不立即删除`() = runTest(dispatcher) {
        viewModel.selectFormat(SessionExportFormat.PNG)
        viewModel.share()
        val share = viewModel.events.first() as SessionExportEvent.Share
        assertThat(share.result.files.map { it.displayName }).containsExactly("会话_1.png", "会话_2.png").inOrder()
        assertThat(exporter.released).isEmpty()
    }

    @Test fun `生成中取消：恢复空闲、不发事件；生成中不能换格式或重复开始`() = runTest(dispatcher) {
        exporter.exportGate = CompletableDeferred()
        viewModel.share()
        advanceUntilIdle()
        assertThat(viewModel.state.value.running).isEqualTo(SessionExportAction.SHARE)

        viewModel.selectFormat(SessionExportFormat.PDF)
        viewModel.save()
        advanceUntilIdle()
        assertThat(viewModel.state.value.format).isEqualTo(SessionExportFormat.MARKDOWN)
        assertThat(exporter.exportCalls).hasSize(1)

        viewModel.cancel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.running).isNull()
        exporter.exportGate = null
        viewModel.save()
        assertThat(viewModel.events.first()).isInstanceOf(SessionExportEvent.PickSaveTarget::class.java)
    }

    @Test fun `生成失败提示导出失败`() = runTest(dispatcher) {
        exporter.exportFailure = IllegalStateException("render timeout")
        viewModel.share()
        assertThat(viewModel.events.first()).isEqualTo(SessionExportEvent.ExportFailed)
        assertThat(viewModel.state.value.running).isNull()
    }

    @Test fun `斜杠命令直接导出使用指定格式与默认开关`() = runTest(dispatcher) {
        viewModel.updateOptions(SessionExportOptions(includeThinking = true, includeAttachments = false))
        viewModel.exportDirectly(SessionExportFormat.PDF)
        assertThat(viewModel.events.first()).isInstanceOf(SessionExportEvent.PickSaveTarget::class.java)
        assertThat(exporter.exportCalls.single()).isEqualTo(SessionExportFormat.PDF to SessionExportOptions())
    }

    @Test fun `分享类型：同一 MIME、同一大类通配、混合时任意类型`() {
        assertThat(sessionExportShareMimeType(listOf("image/png", "image/png"))).isEqualTo("image/png")
        assertThat(sessionExportShareMimeType(listOf("text/markdown", "text/plain"))).isEqualTo("text/*")
        assertThat(sessionExportShareMimeType(listOf("text/markdown", "application/zip"))).isEqualTo("*/*")
    }
}

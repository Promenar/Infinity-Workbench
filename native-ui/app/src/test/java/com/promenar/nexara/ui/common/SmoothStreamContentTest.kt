package com.promenar.nexara.ui.common

import android.os.Trace
import android.util.Log
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmoothStreamContentTest {

    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit

        override fun insertTopDown(index: Int, instance: Unit) = Unit

        override fun move(from: Int, to: Int, count: Int) = Unit

        override fun remove(index: Int, count: Int) = Unit

        override fun onClear() = Unit
    }

    @Test
    fun `首次组合已有流式正文时立即显示完整内容`() = runTest {
        mockAndroidComposeRuntime()
        val frameClock = BroadcastFrameClock()
        val compositionContext = coroutineContext + frameClock
        val recomposer = Recomposer(compositionContext)
        val composition = Composition(NoOpApplier(), recomposer)
        val recomposition = launch(compositionContext, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        var rendered: String? = null

        try {
            composition.setContent {
                rendered = rememberSmoothStreamContent(
                    content = "首个流式片段",
                    isStreaming = true,
                    cps = StreamSpeed.SMOOTH.cps
                )
            }
            runCurrent()

            assertThat(rendered).isEqualTo("首个流式片段")
        } finally {
            composition.dispose()
            recomposer.cancel()
            recomposition.join()
            unmockkStatic(Log::class, Trace::class)
        }
    }

    @Test
    fun `后续追加内容按设定速率平滑推进`() {
        val rendered = reduceSmoothStreamContent(
            displayed = "首",
            target = "首个流式片段",
            isStreaming = true,
            cps = StreamSpeed.SMOOTH.cps
        )

        assertThat(rendered).isEqualTo("首个")
    }

    @Test
    fun `流式目标发生等长非追加替换时从新正文起点重新推进`() {
        val rendered = reduceSmoothStreamContent(
            displayed = "旧内容",
            target = "新答案",
            isStreaming = true,
            cps = StreamSpeed.SMOOTH.cps
        )

        assertThat(rendered).isEqualTo("新")
    }

    @Test
    fun `流式目标截短时直接同步避免显示旧尾部`() {
        val rendered = reduceSmoothStreamContent(
            displayed = "已显示的旧尾部",
            target = "已显示",
            isStreaming = true,
            cps = StreamSpeed.SMOOTH.cps
        )

        assertThat(rendered).isEqualTo("已显示")
    }

    @Test
    fun `流式结束时立即同步最新完整正文`() {
        val rendered = reduceSmoothStreamContent(
            displayed = "首个",
            target = "首个流式片段（完成）",
            isStreaming = false,
            cps = StreamSpeed.SMOOTH.cps
        )

        assertThat(rendered).isEqualTo("首个流式片段（完成）")
    }

    private fun mockAndroidComposeRuntime() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } answers { throw thirdArg<Throwable>() }
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } returns Unit
        every { Trace.endSection() } returns Unit
    }
}

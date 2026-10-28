package com.promenar.infinity.ui.common

import android.os.Trace
import android.util.Log
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmoothStreamCompositionTest {
    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test
    fun `非流式正文不依赖Android上下文或动画设置即可直接显示`() = runTest {
        mockkStatic(Log::class, Trace::class)
        every { Log.e(any(), any(), any()) } answers { throw thirdArg<Throwable>() }
        every { Trace.beginSection(any()) } returns Unit
        every { Trace.endSection() } returns Unit
        val context = coroutineContext + BroadcastFrameClock()
        val recomposer = Recomposer(context)
        val composition = Composition(NoOpApplier(), recomposer)
        val running = launch(context, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        var observed: StreamRevealFrame? = null
        try {
            composition.setContent { observed = rememberSmoothStreamContent("历史👩🏽‍💻正文", false) }
            runCurrent()
            assertEquals("历史👩🏽‍💻正文", observed?.text)
            assertFalse(observed?.hasFadingText ?: true)
        } finally {
            composition.dispose()
            recomposer.cancel()
            running.join()
            unmockkStatic(Log::class, Trace::class)
        }
    }

    @Test
    fun `流式初始合成无需等待动画状态或首个时钟帧就显示当前全文`() = runTest {
        mockkStatic(Log::class, Trace::class)
        every { Log.e(any(), any(), any()) } answers { throw thirdArg<Throwable>() }
        every { Trace.beginSection(any()) } returns Unit
        every { Trace.endSection() } returns Unit
        val context = coroutineContext + BroadcastFrameClock()
        val recomposer = Recomposer(context)
        val composition = Composition(NoOpApplier(), recomposer)
        val running = launch(context, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val observed = mutableListOf<StreamRevealFrame>()
        try {
            composition.setContent {
                observed += rememberSmoothStreamingContent("已读👩🏽‍💻正文", null)
                observed += rememberSmoothStreamingContent("已读👩🏽‍💻正文", true)
            }
            runCurrent()
            assertEquals(2, observed.size)
            assertEquals(listOf("已读👩🏽‍💻正文", "已读👩🏽‍💻正文"), observed.map { it.text })
            assertFalse(observed.any { it.hasFadingText })
        } finally {
            composition.dispose()
            recomposer.cancel()
            running.join()
            unmockkStatic(Log::class, Trace::class)
        }
    }

    @Test
    fun `已挂载流式结束后保持平滑排空且300毫秒内显示全文`() = runTest {
        mockkStatic(Log::class, Trace::class)
        every { Log.e(any(), any(), any()) } answers { throw thirdArg<Throwable>() }
        every { Trace.beginSection(any()) } returns Unit
        every { Trace.endSection() } returns Unit
        val clock = BroadcastFrameClock()
        val context = coroutineContext + clock
        val recomposer = Recomposer(context)
        val composition = Composition(NoOpApplier(), recomposer)
        val running = launch(context, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val content = mutableStateOf("已读")
        val streaming = mutableStateOf(true)
        var observed = StreamRevealFrame("", 0L)
        suspend fun frame(now: Long) {
            repeat(2) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(now)
                runCurrent()
            }
        }
        try {
            composition.setContent { observed = rememberSmoothStreamContent(content.value, streaming.value) { true } }
            runCurrent()
            frame(0L)
            content.value += "新".repeat(300)
            frame(100_000_000L)
            assertTrue(observed.text.length < content.value.length)

            streaming.value = false
            frame(100_000_000L)
            assertTrue("结束态不能在同一帧放出全部积压", observed.text.length < content.value.length)
            frame(200_000_000L)
            assertTrue(observed.text.length > 2)
            assertTrue(observed.text.length < content.value.length)
            frame(400_000_000L)
            assertEquals(content.value, observed.text)
        } finally {
            composition.dispose()
            recomposer.cancel()
            running.join()
            unmockkStatic(Log::class, Trace::class)
        }
    }
}

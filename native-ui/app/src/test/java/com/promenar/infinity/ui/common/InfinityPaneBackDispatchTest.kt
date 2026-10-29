package com.promenar.infinity.ui.common

import android.os.Looper
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 窄窗口整页二级页面的返回派发：手势松手后走完再弹出，取消不弹出，按键照常弹出；
 * 二级页面内部的返回处理仍然优先，让出覆盖时返回交还给外层。
 * 拖动中的位移按实际绘制位置核对：多层时只移最上层、下层视差，只有根层时整页右移、主内容视差，取消后都回到原位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InfinityPaneBackDispatchTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val dispatcher get() = activity.onBackPressedDispatcher

    private val calls = mutableListOf<String>()
    private val levels = mutableStateOf(listOf("explorer"))
    private val innerIntercepts = mutableStateOf(false)
    private val suspended = mutableStateOf(false)
    private val placed = mutableMapOf<String, LayoutCoordinates>()

    private fun Modifier.track(name: String) = fillMaxSize().onGloballyPositioned { placed[name] = it }

    /** 当前绘制位置的横坐标（像素），含 graphicsLayer 位移。 */
    private fun x(name: String) = placed.getValue(name).positionInRoot().x

    private fun width(name: String) = placed.getValue(name).size.width.toFloat()

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
    }

    private fun show() {
        activity.setContent {
            BackHandler { calls += "outer" }
            InfinityAdaptivePaneHost(
                paneVisible = levels.value.isNotEmpty(),
                onBack = {
                    calls += "pop"
                    levels.value = levels.value.dropLast(1)
                },
                coverSuspended = suspended.value,
                pane = {
                    levels.value.forEach { key ->
                        androidx.compose.runtime.key(key) {
                            InfinityPaneLevel(key = key, visible = true, top = key == levels.value.last()) {
                                BackHandler(enabled = innerIntercepts.value) { calls += "inner:$key" }
                                Box(Modifier.track(key))
                            }
                        }
                    }
                },
            ) { Box(Modifier.track("content")) }
        }
        settle()
    }

    private fun drag(progress: Float) {
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(progress * 1000f, 0f, progress, BackEventCompat.EDGE_LEFT))
        settle()
    }

    @Test
    fun `手势取消后不弹出，再次拖动松手后走完并弹出一层`() {
        levels.value = listOf("explorer", "git")
        show()

        drag(0.4f)
        dispatcher.dispatchOnBackCancelled()
        settle()
        assertThat(calls).isEmpty()
        assertThat(levels.value).containsExactly("explorer", "git").inOrder()

        drag(0.6f)
        dispatcher.onBackPressed()
        settle()
        assertThat(calls).containsExactly("pop")
        assertThat(levels.value).containsExactly("explorer")

        // 弹出后回到静止，只剩根层时再拖动关闭整个二级页面。
        drag(0.3f)
        dispatcher.onBackPressed()
        settle()
        assertThat(calls).containsExactly("pop", "pop").inOrder()
        assertThat(levels.value).isEmpty()

        // 二级页面关闭后返回交还给外层。
        dispatcher.onBackPressed()
        settle()
        assertThat(calls).containsExactly("pop", "pop", "outer").inOrder()
    }

    @Test
    fun `按键返回没有进度，直接弹出`() {
        levels.value = listOf("explorer", "git")
        show()

        dispatcher.onBackPressed()
        settle()
        assertThat(calls).containsExactly("pop")
        assertThat(levels.value).containsExactly("explorer")
    }

    @Test
    fun `二级页面内部的返回处理优先，只交给最上层`() {
        levels.value = listOf("explorer", "git")
        innerIntercepts.value = true
        show()

        drag(0.5f)
        dispatcher.onBackPressed()
        settle()
        assertThat(calls).containsExactly("inner:git")
        assertThat(levels.value).containsExactly("explorer", "git").inOrder()
    }

    @Test
    fun `让出覆盖时不拦截返回，层级保留`() {
        show()
        suspended.value = true
        settle()

        drag(0.5f)
        dispatcher.onBackPressed()
        settle()
        assertThat(calls).containsExactly("outer")
        assertThat(levels.value).containsExactly("explorer")
    }

    @Test
    fun `多层时只有最上层跟手右移，下层从左侧视差回位，取消后回到原位`() {
        levels.value = listOf("explorer", "git")
        show()
        val w = width("git")
        assertThat(x("git")).isEqualTo(0f)

        drag(0.5f)
        assertThat(x("git")).isWithin(1f).of(w * 0.5f)
        assertThat(x("explorer")).isWithin(1f).of(-w / 8f)
        assertThat(x("content")).isEqualTo(0f)

        dispatcher.dispatchOnBackCancelled()
        settle()
        assertThat(x("git")).isEqualTo(0f)
        assertThat(x("explorer")).isEqualTo(0f)
    }

    @Test
    fun `只有根层时整个二级页面跟手右移，主内容从左侧视差回位`() {
        show()
        val w = width("explorer")

        drag(0.5f)
        assertThat(x("explorer")).isWithin(1f).of(w * 0.5f)
        assertThat(x("content")).isWithin(1f).of(-w / 8f)

        dispatcher.dispatchOnBackCancelled()
        settle()
        assertThat(x("explorer")).isEqualTo(0f)
        assertThat(x("content")).isEqualTo(0f)
    }
}

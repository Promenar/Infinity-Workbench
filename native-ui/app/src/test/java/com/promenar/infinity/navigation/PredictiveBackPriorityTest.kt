package com.promenar.infinity.navigation

import android.os.Looper
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.common.InfinityBackScope
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 系统返回的派发顺序：后注册的返回处理优先，NavHost 只在栈里多于一页时拦截，
 * 被遮住的层（[InfinityBackScope] 未激活）不接收返回，跟手返回取消后栈不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PredictiveBackPriorityTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
    }

    private fun back() {
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
    }

    private fun NavHostController.routes() = currentBackStack.value.mapNotNull { it.destination.route }

    @Test
    fun `未激活的层不接收返回，重新激活后排在已有处理之前`() {
        val calls = mutableListOf<String>()
        val active = mutableStateOf(false)
        activity.setContent {
            BackHandler { calls += "outer" }
            InfinityBackScope(active = active.value) { BackHandler { calls += "inner" } }
        }
        settle()

        back()
        assertThat(calls).containsExactly("outer")

        active.value = true
        settle()
        back()
        assertThat(calls).containsExactly("outer", "inner").inOrder()

        active.value = false
        settle()
        back()
        assertThat(calls).containsExactly("outer", "inner", "outer").inOrder()
    }

    @Test
    fun `内层导航只有一页时不拦截，有子页时先弹内层，页面内的返回处理优先于导航`() {
        val calls = mutableListOf<String>()
        val pageIntercepts = mutableStateOf(false)
        lateinit var inner: NavHostController
        activity.setContent {
            BackHandler { calls += "outer" }
            inner = rememberNavController()
            InfinityPageNavHost(navController = inner, startDestination = "root") {
                composablePage("root") { Box {} }
                composablePage("child") {
                    BackHandler(enabled = pageIntercepts.value) { calls += "page" }
                    Box {}
                }
            }
        }
        settle()

        back()
        assertThat(calls).containsExactly("outer")
        assertThat(inner.routes()).containsExactly("root")

        inner.navigate("child")
        settle()
        back()
        assertThat(inner.routes()).containsExactly("root")
        assertThat(calls).containsExactly("outer")

        inner.navigate("child")
        pageIntercepts.value = true
        settle()
        back()
        assertThat(calls).containsExactly("outer", "page").inOrder()
        assertThat(inner.routes()).containsExactly("root", "child").inOrder()
    }

    @Test
    fun `跟手返回取消后停在原页，再次拖动松手后完成返回`() {
        lateinit var inner: NavHostController
        activity.setContent {
            inner = rememberNavController()
            InfinityPageNavHost(navController = inner, startDestination = "root") {
                composablePage("root") { Box {} }
                composablePage("child") { Box {} }
            }
        }
        settle()
        inner.navigate("child")
        settle()

        val dispatcher = activity.onBackPressedDispatcher
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(400f, 0f, 0.4f, BackEventCompat.EDGE_LEFT))
        settle()
        dispatcher.dispatchOnBackCancelled()
        settle()
        assertThat(inner.routes()).containsExactly("root", "child").inOrder()

        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(600f, 0f, 0.6f, BackEventCompat.EDGE_LEFT))
        settle()
        dispatcher.onBackPressed()
        settle()
        assertThat(inner.routes()).containsExactly("root")
    }
}

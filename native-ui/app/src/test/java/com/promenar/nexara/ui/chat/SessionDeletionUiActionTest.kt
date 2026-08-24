package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionDeletionUiActionTest {
    @Test
    fun `删除完成后才触发导航`() = runTest {
        val events = mutableListOf<String>()
        val action = SessionDeletionUiAction { events += "delete" }

        val failure = action.run { events += "navigate" }

        assertThat(failure).isNull()
        assertThat(events).containsExactly("delete", "navigate").inOrder()
    }

    @Test
    fun `删除失败保留当前页面且返回可重试错误`() = runTest {
        val expected = IllegalStateException("retry")
        var navigated = false
        val action = SessionDeletionUiAction { throw expected }

        val failure = action.run { navigated = true }

        assertThat(failure).isSameInstanceAs(expected)
        assertThat(navigated).isFalse()
    }
}

package com.promenar.nexara.ui.hub

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.Session
import org.junit.Test

class HubBranchNestingTest {
    private fun session(id: String, parent: String? = null, createdAt: Long = 0) =
        Session(id = id, agentId = "a", parentSessionId = parent, createdAt = createdAt)

    @Test fun `分支按创建先后紧跟主会话并标为嵌套`() {
        val items = listOf(session("b2", "p", 2), session("x"), session("p"), session("b1", "p", 1))
        val nested = nestBranchSessions(items) { it }
        assertThat(nested.map { it.first.id to it.second })
            .containsExactly("x" to false, "p" to false, "b1" to true, "b2" to true).inOrder()
    }

    @Test fun `主会话不在列表中时分支留在原位且不嵌套`() {
        val items = listOf(session("b1", "gone"), session("y"))
        assertThat(nestBranchSessions(items) { it }.map { it.first.id to it.second })
            .containsExactly("b1" to false, "y" to false).inOrder()
    }
}

package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.KgNode
import com.promenar.nexara.data.model.KgPath
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import org.junit.jupiter.api.Test

class ChatScreenRagSelectionTest {
    private val kgPath = KgPath(
        queryKeywords = listOf("Nexara"),
        nodes = listOf(KgNode("n1", "Nexara", "project")),
        edges = emptyList(),
    )

    @Test
    fun `multiple assistant messages select the one containing only kgPaths`() {
        val plain = Message(id = "plain", role = MessageRole.ASSISTANT, content = "plain")
        val kgOnly = Message(
            id = "kg",
            role = MessageRole.ASSISTANT,
            content = "graph answer",
            kgPaths = listOf(kgPath),
        )
        val last = Message(id = "last", role = MessageRole.ASSISTANT, content = "last")

        assertThat(selectRagActiveMessage(listOf(plain, kgOnly, last))).isEqualTo(kgOnly)
    }

    @Test
    fun `pure kg message reports rag artifacts and shows progress card entry`() {
        val message = Message(
            id = "kg",
            role = MessageRole.ASSISTANT,
            content = "graph answer",
            kgPaths = listOf(kgPath),
        )

        assertThat(message.hasRagArtifacts()).isTrue()
    }

    @Test
    fun `empty kgPaths do not create a fake rag card`() {
        val message = Message(
            id = "empty",
            role = MessageRole.ASSISTANT,
            content = "answer",
            kgPaths = emptyList(),
        )

        assertThat(message.hasRagArtifacts()).isFalse()
    }
}

package com.promenar.nexara.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.InferenceParams
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.ToolCall
import com.promenar.nexara.data.model.MessageDocumentAttachment
import com.promenar.nexara.data.remote.protocol.ProtocolTool
import com.promenar.nexara.data.remote.protocol.ProtocolToolFunction
import com.promenar.nexara.domain.tool.ToolRisk
import com.promenar.nexara.ui.chat.manager.registry.SkillRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class ChatGenerationContentStrategyTest {
    @Test
    fun `文本文档只作为完整用户内容进入协议历史`() {
        val document = MessageDocumentAttachment(
            id = "doc-1",
            name = "history.md",
            mimeType = "text/markdown",
            content = "# 旧会话\n完整内容",
            sizeBytes = 22,
            sha256 = "abc",
            estimatedTokens = 5,
        )
        val session = Session(
            id = "s1",
            agentId = "agent",
            messages = listOf(
                Message(
                    id = "u1",
                    role = MessageRole.USER,
                    content = "继续分析",
                    userDocuments = listOf(document),
                ),
            ),
        )

        val prompt = strategy().buildProtocolMessages(session, "system contract")

        assertThat(prompt).hasSize(2)
        assertThat(prompt[0].content).isEqualTo("system contract")
        assertThat(prompt[0].content).doesNotContain(document.content)
        assertThat(prompt[1].content).contains("继续分析")
        assertThat(prompt[1].content).contains(document.content)
        assertThat(prompt[1].content).contains("<<<NEXARA_DOCUMENT_BEGIN abc>>>")
    }

    @Test
    fun `当前完整文档用户消息即使超出活动窗口仍被固定进Prompt`() {
        val document = MessageDocumentAttachment(
            "doc", "reference.txt", "text/plain", "must remain", 11, "hash", 11,
        )
        val messages = buildList {
            add(Message("pinned", MessageRole.USER, "analyze", userDocuments = listOf(document)))
            repeat(8) { index ->
                add(Message("u-$index", MessageRole.USER, "question-$index"))
                add(Message("a-$index", MessageRole.ASSISTANT, "answer-$index"))
            }
        }
        val session = Session(
            id = "s1",
            agentId = "agent",
            inferenceParams = InferenceParams(activeContextWindow = 4),
            messages = messages,
        )

        val prompt = strategy().buildProtocolMessages(session, "system", "pinned")

        val pinned = prompt.first { it.content.contains("must remain") }
        assertThat(pinned.content).contains("analyze")
        assertThat(pinned.content).contains("<<<NEXARA_DOCUMENT_BEGIN hash>>>")
        assertThat(prompt.takeLast(4).map { it.content })
            .containsExactly("question-6", "answer-6", "question-7", "answer-7").inOrder()
    }

    @Test
    fun `重试时旧助手回复不进入新Prompt且不占活动窗口`() {
        val session = Session(
            id = "s1",
            agentId = "agent",
            inferenceParams = InferenceParams(activeContextWindow = 2),
            messages = listOf(
                Message("u1", MessageRole.USER, "question"),
                Message("old", MessageRole.ASSISTANT, "old answer"),
                Message("new", MessageRole.ASSISTANT, ""),
            ),
        )

        val prompt = strategy().buildProtocolMessages(
            session,
            "system",
            pinnedUserMessageId = "u1",
            excludedMessageIds = setOf("old"),
        )

        assertThat(prompt.map { it.content })
            .containsExactly("system", "question", "")
            .inOrder()
        assertThat(prompt.map { it.content }).doesNotContain("old answer")
    }

    @Test
    fun `多轮工具消息构造Prompt时不丢assistant与tool配对`() {
        val strategy = strategy()
        val firstCall = ToolCall("call-1", "search", "{}")
        val secondCall = ToolCall("call-2", "read_file", "{}")
        val session = Session(
            id = "s1",
            agentId = "agent",
            inferenceParams = InferenceParams(activeContextWindow = 6),
            messages = listOf(
                Message("u1", MessageRole.USER, "question"),
                Message("a1", MessageRole.ASSISTANT, "", toolCalls = listOf(firstCall)),
                Message("t1", MessageRole.TOOL, "result-1", toolCallId = "call-1", name = "search"),
                Message("a2", MessageRole.ASSISTANT, "", toolCalls = listOf(secondCall)),
                Message("t2", MessageRole.TOOL, "result-2", toolCallId = "call-2", name = "read_file"),
            ),
        )

        val prompt = strategy.buildProtocolMessages(session, "system")

        assertThat(prompt.map { it.role }).containsExactly(
            "system", "user", "assistant", "tool", "assistant", "tool",
        ).inOrder()
        assertThat(prompt[2].toolCalls!!.single().id).isEqualTo("call-1")
        assertThat(prompt[3].toolCallId).isEqualTo("call-1")
        assertThat(prompt[4].toolCalls!!.single().id).isEqualTo("call-2")
        assertThat(prompt[5].toolCallId).isEqualTo("call-2")
    }

    @Test
    fun `fallback仅解析已知强标签且不误删Markdown表格`() {
        val strategy = strategy(setOf("search"))
        val content = """
            |说明
            |---|---
            |a|b
            |<FunctionCall>{"name":"search","arguments":{"q":"x"}}</FunctionCall>
        """.trimMargin()

        val calls = strategy.extractFallbackToolCalls(content)
        val stripped = strategy.stripToolCallMarkup(content)

        assertThat(calls.map { it.name }).containsExactly("search")
        assertThat(stripped).contains("---|---")
        assertThat(stripped).doesNotContain("FunctionCall")
    }

    @Test
    fun `审批策略由工具风险元数据驱动且未知风险fail closed`() {
        val strategy = strategy(
            mapOf(
                "safe_alias_not_hardcoded" to ToolRisk.SAFE_READ,
                "write_alias_not_hardcoded" to ToolRisk.FILE_WRITE,
                "unknown_legacy_tool" to ToolRisk.UNKNOWN,
            ),
        )
        val calls = listOf(
            ToolCall("safe", "safe_alias_not_hardcoded", "{}"),
            ToolCall("risk", "write_alias_not_hardcoded", "{}"),
            ToolCall("unknown", "unknown_legacy_tool", "{}"),
        )

        assertThat(strategy.pendingApprovalIds(calls, "auto")).isEmpty()
        assertThat(strategy.pendingApprovalIds(calls, "manual"))
            .containsExactly("safe", "risk", "unknown").inOrder()
        assertThat(strategy.pendingApprovalIds(calls, "semi"))
            .containsExactly("risk", "unknown").inOrder()
        assertThat(strategy.pendingApprovalIds(calls, "invalid-mode"))
            .containsExactly("risk", "unknown").inOrder()
    }

    @Test
    fun `工具禁用后清除旧风险快照并按未知风险审批`() {
        var enabled = setOf("safe_alias")
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } answers { enabled }
        val registry = mockk<SkillRegistry>()
        val safeTool = ProtocolTool(
            function = ProtocolToolFunction(
                name = "safe_alias",
                description = "",
                parameters = "{}",
            ),
            risk = ToolRisk.SAFE_READ,
        )
        every { registry.getAllTools(any()) } returns listOf(safeTool)
        val strategy = DefaultChatGenerationContentStrategy(settings, registry)
        val session = Session(id = "risk-session", agentId = "agent")
        val calls = listOf(ToolCall("safe", "safe_alias", "{}"))

        strategy.buildTools(session)
        assertThat(strategy.pendingApprovalIds(calls, "semi")).isEmpty()

        enabled = emptySet()
        strategy.buildTools(session)
        assertThat(strategy.pendingApprovalIds(calls, "semi")).containsExactly("safe")
    }

    private fun strategy(knownNames: Set<String>): DefaultChatGenerationContentStrategy =
        strategy(knownNames.associateWith { ToolRisk.UNKNOWN })

    private fun strategy(
        knownTools: Map<String, ToolRisk> = emptyMap(),
    ): DefaultChatGenerationContentStrategy {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns knownTools.keys
        val registry = mockk<SkillRegistry>()
        every { registry.getAllTools() } returns knownTools.map { (name, risk) ->
            ProtocolTool(
                function = ProtocolToolFunction(name = name, description = "", parameters = "{}"),
                risk = risk,
            )
        }
        every { registry.getAllTools(any()) } returns knownTools.map { (name, risk) ->
            ProtocolTool(
                function = ProtocolToolFunction(name = name, description = "", parameters = "{}"),
                risk = risk,
            )
        }
        return DefaultChatGenerationContentStrategy(settings, registry).also {
            it.buildTools(Session(id = "risk-session", agentId = "agent"))
        }
    }
}

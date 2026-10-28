package com.promenar.infinity.ui.hub

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.model.Agent
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.jupiter.api.Test

class AgentHubSectionsTest {
    private val coder = AgentDisplayItem(Agent(id = "coder", name = "Coder"), "代码助手", "写代码")
    private val writer = AgentDisplayItem(Agent(id = "writer", name = "Writer"), "写作助手", "")

    private fun session(id: String, agentId: String, updatedAt: Long, pinned: Boolean = false, title: String = id, last: String? = null) =
        Session(id = id, agentId = agentId, title = title, lastMessage = last, isPinned = pinned, createdAt = 0, updatedAt = updatedAt)

    @Test
    fun `未搜索时置顶会话单列一组，最近按更新时间倒序且最多 5 条`() {
        val coderSessions = (1..6).map { session("c$it", "coder", updatedAt = it * 10L) } +
            session("pinned-old", "coder", updatedAt = 1, pinned = true)
        val writerSessions = (1..5).map { session("w$it", "writer", updatedAt = it * 10L + 5) }

        val sections = buildHubSections(
            agents = listOf(coder, writer),
            sessionsByAgent = mapOf("coder" to coderSessions, "writer" to writerSessions),
            query = "",
        )

        assertThat(sections.pinnedSessions.map { it.session.id }).containsExactly("pinned-old")
        assertThat(sections.sessions.map { it.session.id })
            .containsExactly("c6", "w5", "c5", "w4", "c4").inOrder()
        assertThat(sections.sessions.first().agentTitle).isEqualTo("代码助手")
        assertThat(sections.sessions[1].agentTitle).isEqualTo("写作助手")
        assertThat(sections.agents.map { it.item.agent.id to it.sessionCount })
            .containsExactly("coder" to 7, "writer" to 5).inOrder()
    }

    @Test
    fun `不在助手列表中的会话不出现，例如系统助手名下的资料会话`() {
        val sections = buildHubSections(
            agents = listOf(coder),
            sessionsByAgent = mapOf(
                "coder" to listOf(session("c1", "coder", 1)),
                "__system__" to listOf(session("rag", "__system__", 99)),
            ),
            query = "",
        )

        assertThat(sections.sessions.map { it.session.id }).containsExactly("c1")
        assertThat(sections.agents.map { it.item.agent.id }).containsExactly("coder")
    }

    @Test
    fun `搜索按标题或最后一条消息匹配会话，置顶在前，并筛选助手`() {
        val sections = buildHubSections(
            agents = listOf(coder, writer),
            sessionsByAgent = mapOf(
                "coder" to listOf(
                    session("k1", "coder", 30, title = "Kotlin 协程"),
                    session("k2", "coder", 10, pinned = true, title = "杂项", last = "kotlin flow 怎么用"),
                    session("x", "coder", 50, title = "无关"),
                ),
                "writer" to listOf(session("w", "writer", 40, title = "周报")),
            ),
            query = " kotlin ",
        )

        assertThat(sections.pinnedSessions).isEmpty()
        assertThat(sections.sessions.map { it.session.id }).containsExactly("k2", "k1").inOrder()
        assertThat(sections.agents).isEmpty()

        val byAgent = buildHubSections(listOf(coder, writer), emptyMap(), "写作")
        assertThat(byAgent.agents.map { it.item.agent.id }).containsExactly("writer")
        assertThat(byAgent.isEmpty).isFalse()
        assertThat(buildHubSections(listOf(coder), emptyMap(), "不存在").isEmpty).isTrue()
    }

    @Test
    fun `会话时间当天显示时刻，当年显示月日，更早带年份`() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun millis(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
        val now = millis("2026-10-22T09:30:00")

        assertThat(formatHubSessionTime(millis("2026-10-22T00:05:00"), now, zone, Locale.CHINA)).isEqualTo("00:05")
        assertThat(formatHubSessionTime(millis("2026-10-21T23:59:00"), now, zone, Locale.CHINA)).isEqualTo("10/21")
        assertThat(formatHubSessionTime(millis("2025-12-31T12:00:00"), now, zone, Locale.CHINA)).isEqualTo("2025/12/31")
    }
}

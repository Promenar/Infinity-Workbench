package com.promenar.nexara.data.session

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import org.junit.Test

class BranchSessionPlannerTest {
    @Test
    fun `分支只复制到目标消息并清空运行态与工作区`() {
        val source = session()
        val messages = listOf(
            message("m1", "user", "问题", files = "[{\"name\":\"history.md\"}]"),
            message("m2", "assistant", "回答", parentMessageId = "m1", status = "success"),
            message("m3", "user", "后续"),
        )

        val result = BranchSessionPlanner.plan(
            source = source,
            sourceMessages = messages,
            targetMessageId = "m2",
            newSessionId = "branch-session",
            now = 200L,
            newMessageId = { original -> "copy-$original" },
        )

        assertThat(result).isInstanceOf(BranchSessionPlanResult.Ready::class.java)
        val plan = (result as BranchSessionPlanResult.Ready).plan
        assertThat(plan.session.id).isEqualTo("branch-session")
        assertThat(plan.session.modelId).isEqualTo(source.modelId)
        assertThat(plan.session.customPrompt).isEqualTo(source.customPrompt)
        assertThat(plan.session.ragOptions).isEqualTo(source.ragOptions)
        assertThat(plan.session.inferenceParams).isEqualTo(source.inferenceParams)
        assertThat(plan.session.options).isEqualTo(source.options)
        assertThat(plan.session.activeMcpServerIds).isEqualTo(source.activeMcpServerIds)
        assertThat(plan.session.activeSkillIds).isEqualTo(source.activeSkillIds)
        assertThat(plan.session.draft).isNull()
        assertThat(plan.session.scrollOffset).isNull()
        assertThat(plan.session.loopStatus).isEqualTo("idle")
        assertThat(plan.session.pendingIntervention).isNull()
        assertThat(plan.session.approvalRequest).isNull()
        assertThat(plan.session.activeTask).isNull()
        assertThat(plan.session.stats).isNull()
        assertThat(plan.session.workspacePath).isNull()
        assertThat(plan.session.workspaceRootUuid).isNull()
        assertThat(plan.session.activeTaskTreeId).isNull()
        assertThat(plan.messages.map { it.id }).containsExactly("copy-m1", "copy-m2").inOrder()
        assertThat(plan.messages.map { it.sessionId }).containsExactly("branch-session", "branch-session")
        assertThat(plan.messages[0].files).isEqualTo(messages[0].files)
        assertThat(plan.messages[1].parentMessageId).isEqualTo("copy-m1")
        assertThat(plan.messages[1].pendingApprovalToolIds).isNull()
        assertThat(plan.messages[1].ragProgress).isNull()
        assertThat(plan.messages[1].ragReferencesLoading).isEqualTo(0)
        assertThat(plan.messages[1].planningTask).isNull()
        assertThat(plan.messages[1].layoutHeight).isNull()
        assertThat(plan.messages.map { it.tokens }).containsExactly(null, null)
        assertThat(plan.messages.map { it.isArchived }).containsExactly(0, 0)
        assertThat(plan.messages.map { it.vectorizationStatus }).containsExactly(null, null)
    }

    @Test
    fun `不存在或仍在生成的目标消息不能分支`() {
        val source = session()
        val generating = message("m1", "assistant", "partial", status = "streaming")
        val retrieving = message("m2", "assistant", "partial", ragReferencesLoading = 1)

        assertThat(
            BranchSessionPlanner.plan(source, listOf(generating), "missing", "new", 2L) { it },
        ).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotFound))
        assertThat(
            BranchSessionPlanner.plan(source, listOf(generating), "m1", "new", 2L) { it },
        ).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
        assertThat(
            BranchSessionPlanner.plan(source, listOf(retrieving), "m2", "new", 2L) { it },
        ).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
    }

    @Test
    fun `目标稳定但前缀仍有生成或审批状态时拒绝分支`() {
        val source = session()
        val stableTarget = message("m2", "user", "后续", parentMessageId = "m1")

        assertThat(
            BranchSessionPlanner.plan(
                source,
                listOf(message("m1", "assistant", "partial", status = "streaming"), stableTarget),
                "m2",
                "new",
                2L,
            ) { it },
        ).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
        assertThat(
            BranchSessionPlanner.plan(
                source,
                listOf(
                    message("m1", "assistant", "answer", pendingApprovalToolIds = "[\"call-1\"]"),
                    stableTarget,
                ),
                "m2",
                "new",
                2L,
            ) { it },
        ).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
    }

    @Test
    fun `工具调用与结果必须成对落在分支前缀中`() {
        val source = session()
        val assistant = message(
            "m1",
            "assistant",
            "",
            toolCalls = "[{\"id\":\"call-1\",\"name\":\"search\",\"arguments\":\"{}\"}]",
        )
        val tool = message("m2", "tool", "result", toolCallId = "call-1")

        assertThat(
            BranchSessionPlanner.plan(source, listOf(assistant), "m1", "new", 2L) { it },
        ).isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
        assertThat(
            BranchSessionPlanner.plan(source, listOf(assistant, tool), "m2", "new", 2L) { it },
        ).isInstanceOf(BranchSessionPlanResult.Ready::class.java)
    }

    @Test
    fun `父消息不在复制前缀时拒绝分支`() {
        val result = BranchSessionPlanner.plan(
            session(),
            listOf(message("m2", "user", "orphan", parentMessageId = "missing")),
            "m2",
            "new",
            2L,
        ) { it }

        assertThat(result)
            .isEqualTo(BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable))
    }

    private fun session() = SessionEntity(
        id = "source",
        agentId = "agent",
        title = "原会话",
        lastMessage = "旧尾注",
        time = "now",
        unread = 3,
        modelId = "model",
        customPrompt = "prompt",
        isPinned = 1,
        scrollOffset = 100.0,
        draft = "draft",
        executionMode = "manual",
        loopStatus = "waiting_for_approval",
        pendingIntervention = "pending",
        approvalRequest = "approval",
        ragOptions = "rag",
        inferenceParams = "inference",
        activeTask = "task",
        stats = "stats",
        options = "options",
        activeMcpServerIds = "mcp",
        activeSkillIds = "skills",
        workspacePath = "/old/workspace",
        workspaceRootUuid = "root",
        activeTaskTreeId = "tree",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun message(
        id: String,
        role: String,
        content: String,
        parentMessageId: String? = null,
        status: String? = null,
        files: String? = null,
        ragReferencesLoading: Int = 0,
        pendingApprovalToolIds: String? = null,
        toolCalls: String? = null,
        toolCallId: String? = null,
    ) = MessageEntity(
        id = id,
        sessionId = "source",
        role = role,
        content = content,
        status = status,
        parentMessageId = parentMessageId,
        files = files,
        tokens = "{\"input\":12,\"output\":34}",
        pendingApprovalToolIds = pendingApprovalToolIds,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
        ragProgress = "progress",
        ragReferencesLoading = ragReferencesLoading,
        planningTask = "planning",
        isArchived = 1,
        vectorizationStatus = "completed",
        layoutHeight = 88.0,
        createdAt = id.last().digitToInt().toLong(),
    )
}

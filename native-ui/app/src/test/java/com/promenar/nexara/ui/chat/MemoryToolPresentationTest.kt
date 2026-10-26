package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.memory.LongTermMemory
import com.promenar.nexara.data.memory.LongTermMemoryKind
import com.promenar.nexara.data.memory.LongTermMemoryRepository
import com.promenar.nexara.data.memory.LongTermMemorySource
import com.promenar.nexara.data.memory.LongTermMemoryWrite
import com.promenar.nexara.data.model.ExecutionStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class MemoryToolPresentationTest {
    @Test
    fun `新建结果解析出写入内容并可撤销`() {
        val change = memoryChange(invocation("memory_save", """{"id":"m1","action":"created","kind":"user","title":"称呼","content":"叫我小冷"}"""))!!
        assertThat(change.action).isEqualTo(MemoryChangeAction.CREATED)
        assertThat(change.written).isEqualTo(MemorySnapshot(LongTermMemoryKind.USER, "称呼", "叫我小冷"))
        assertThat(memoryUndoState(change, memory("m1", "称呼", "叫我小冷"))).isEqualTo(MemoryUndoState.AVAILABLE)
        assertThat(memoryUndoState(change, null)).isEqualTo(MemoryUndoState.UNDONE)
        assertThat(memoryUndoState(change, memory("m1", "称呼", "叫我阿冷"))).isEqualTo(MemoryUndoState.UNAVAILABLE)
    }

    @Test
    fun `结果只带标题时从调用参数补全内容`() {
        val change = memoryChange(invocation(
            "memory_save",
            """{"id":"m1","action":"created","title":"称呼"}""",
            args = """{"kind":"user","title":" 称呼 ","content":"叫我小冷"}""",
        ))!!
        assertThat(change.written).isEqualTo(MemorySnapshot(LongTermMemoryKind.USER, "称呼", "叫我小冷"))
    }

    @Test
    fun `覆盖结果带旧值且恢复旧值后显示已撤销`() {
        val change = memoryChange(invocation(
            "memory_save",
            """{"id":"m1","action":"updated","kind":"feedback","title":"语言","content":"简体中文","previous":{"kind":"feedback","title":"语言","content":"中文"}}""",
        ))!!
        assertThat(change.action).isEqualTo(MemoryChangeAction.UPDATED)
        assertThat(memoryUndoState(change, memory("m1", "语言", "简体中文", LongTermMemoryKind.FEEDBACK))).isEqualTo(MemoryUndoState.AVAILABLE)
        assertThat(memoryUndoState(change, memory("m1", "语言", "中文", LongTermMemoryKind.FEEDBACK))).isEqualTo(MemoryUndoState.UNDONE)
        assertThat(memoryUndoState(change, null)).isEqualTo(MemoryUndoState.UNAVAILABLE)
    }

    @Test
    fun `失败、未完成与其它工具不产生记忆改动`() {
        assertThat(memoryChange(invocation("memory_save", """{"error":"MEMORY_LIMIT"}""", type = "error"))).isNull()
        assertThat(memoryChange(ToolInvocationPresentation(listOf(callStep("memory_save", "{}"))))).isNull()
        assertThat(memoryChange(invocation("memory_read", """{"id":"m1","kind":"user","title":"a","content":"b"}"""))).isNull()
        assertThat(memoryChange(invocation("memory_save", "不是 JSON"))).isNull()
    }

    @Test
    fun `撤销新建删除记忆，记忆已被改动时不动`() = runBlocking {
        val repository = FakeRepository(memory("m1", "称呼", "叫我小冷"))
        val change = memoryChange(invocation("memory_save", """{"id":"m1","action":"created","kind":"user","title":"称呼","content":"叫我小冷"}"""))!!
        repository.memories.value = mapOf("m1" to memory("m1", "称呼", "叫我阿冷"))
        undoMemoryChange(change, repository)
        assertThat(repository.get("m1")!!.content).isEqualTo("叫我阿冷")

        repository.memories.value = mapOf("m1" to memory("m1", "称呼", "叫我小冷"))
        undoMemoryChange(change, repository)
        assertThat(repository.get("m1")).isNull()
    }

    @Test
    fun `撤销覆盖写回旧内容并保留编号与创建时间`() = runBlocking {
        val repository = FakeRepository(memory("m1", "语言", "简体中文", LongTermMemoryKind.FEEDBACK).copy(createdAt = 5))
        val change = memoryChange(invocation(
            "memory_save",
            """{"id":"m1","action":"updated","kind":"feedback","title":"语言","content":"简体中文","previous":{"kind":"project","title":"旧语言","content":"中文"}}""",
        ))!!
        undoMemoryChange(change, repository)
        val restored = repository.get("m1")!!
        assertThat(restored.kind).isEqualTo(LongTermMemoryKind.PROJECT)
        assertThat(restored.title).isEqualTo("旧语言")
        assertThat(restored.content).isEqualTo("中文")
        assertThat(restored.createdAt).isEqualTo(5)
    }

    @Test
    fun `撤销删除按结果写回来源与时间，缺字段时用撤销时刻`() = runBlocking {
        val repository = FakeRepository()
        val full = memoryChange(invocation(
            "memory_forget",
            """{"id":"m1","kind":"reference","title":"文档","content":"见 docs","source_agent_id":"a1","source_session_id":"s1","source_message_id":null,"created_at":10,"updated_at":20}""",
        ))!!
        assertThat(memoryUndoState(full, null)).isEqualTo(MemoryUndoState.AVAILABLE)
        undoMemoryChange(full, repository) { 99 }
        val restored = repository.get("m1")!!
        assertThat(restored.sourceAgentId).isEqualTo("a1")
        assertThat(restored.sourceSessionId).isEqualTo("s1")
        assertThat(restored.sourceMessageId).isNull()
        assertThat(restored.createdAt).isEqualTo(10)
        assertThat(restored.updatedAt).isEqualTo(20)
        assertThat(memoryUndoState(full, restored)).isEqualTo(MemoryUndoState.UNDONE)

        val bare = memoryChange(invocation("memory_forget", """{"id":"m2","kind":"user","title":"t","content":"c"}"""))!!
        undoMemoryChange(bare, repository) { 99 }
        assertThat(repository.get("m2")!!.createdAt).isEqualTo(99)
    }

    private fun invocation(name: String, result: String, args: String = "{}", type: String = "tool_result") =
        ToolInvocationPresentation(listOf(callStep(name, args), ExecutionStep("r", type, toolCallId = "c1", content = result)))

    private fun callStep(name: String, args: String) = ExecutionStep("s", "tool_call", toolName = name, toolCallId = "c1", toolArgs = args)

    private fun memory(id: String, title: String, content: String, kind: LongTermMemoryKind = LongTermMemoryKind.USER) =
        LongTermMemory(id, kind, title, content, null, null, null, 1, 1)

    private class FakeRepository(vararg initial: LongTermMemory) : LongTermMemoryRepository {
        val memories = MutableStateFlow(initial.associateBy { it.id })
        override fun observeAll(): Flow<List<LongTermMemory>> = throw UnsupportedOperationException()
        override suspend fun getAll() = memories.value.values.toList()
        override suspend fun get(id: String) = memories.value[id]
        override suspend fun save(kind: LongTermMemoryKind, title: String, content: String, id: String?, source: LongTermMemorySource?): LongTermMemoryWrite =
            throw UnsupportedOperationException()
        override suspend fun delete(id: String): LongTermMemory? = memories.value[id]?.also { memories.value = memories.value - id }
        override suspend fun restore(memory: LongTermMemory) {
            memories.value = memories.value + (memory.id to memory)
        }
    }
}

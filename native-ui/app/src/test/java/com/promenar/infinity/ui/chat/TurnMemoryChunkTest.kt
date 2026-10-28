package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TurnMemoryChunkTest {
    @Test
    fun `记忆片段按行首角色标记拆分发言`() {
        assertThat(memoryChunkParts("User: 帮我改脚本\nAssistant: 已拆成两个任务\n并在 CI 中并行。")).containsExactly(
            MemoryChunkPart(MemoryChunkRole.USER, "帮我改脚本"),
            MemoryChunkPart(MemoryChunkRole.ASSISTANT, "已拆成两个任务\n并在 CI 中并行。"),
        ).inOrder()
    }

    @Test
    fun `从半句开始的片段开头没有角色，空白发言被忽略`() {
        assertThat(memoryChunkParts("键值，避免互相覆盖。\nUser:   \nAssistant: 好的")).containsExactly(
            MemoryChunkPart(null, "键值，避免互相覆盖。"),
            MemoryChunkPart(MemoryChunkRole.ASSISTANT, "好的"),
        ).inOrder()
    }

    @Test
    fun `图谱谓词的下划线显示为空格`() {
        assertThat(readableRelation("officially_supports")).isEqualTo("officially supports")
        assertThat(readableRelation("开发")).isEqualTo("开发")
    }
}

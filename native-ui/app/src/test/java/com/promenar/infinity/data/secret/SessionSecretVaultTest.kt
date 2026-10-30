package com.promenar.infinity.data.secret

import com.promenar.infinity.domain.secret.SecretIoLimits
import com.promenar.infinity.domain.secret.SecretSlotState
import com.promenar.infinity.domain.secret.SecretSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionSecretVaultTest {
    private val vault = SessionSecretVault()

    @Test fun `写入和取值均复制且会话隔离`() {
        val input = "test-token".toByteArray()
        vault.put("s", "TOKEN", "测试令牌", SecretSource.UserInput, input)
        input.fill(0)
        val first = vault.revealBytes("s", "TOKEN")!!
        assertEquals("test-token", first.decodeToString())
        first.fill(0)
        assertEquals("test-token", vault.revealBytes("s", "TOKEN")!!.decodeToString())
        assertNull(vault.revealBytes("other", "TOKEN"))
    }

    @Test fun `环境只注入完整引用且不把前缀误认成槽位`() {
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "test-token".toByteArray())
        vault.put("s", "OTHER", "其它", SecretSource.UserInput, "another-token".toByteArray())
        assertEquals(mapOf("TOKEN" to "test-token"), vault.environment("s", "echo \$TOKEN \${TOKEN} \$TOKEN_SUFFIX \$PATH"))
        assertTrue(vault.environment("s", "TOKEN without dollar").isEmpty())
    }

    @Test fun `销毁阻止引用并同步既有卡片`() = runBlocking {
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "test-token".toByteArray())
        vault.show("s", "assistant", "call", "TOKEN", "查看令牌")
        vault.destroy("s", "TOKEN")
        assertNull(vault.revealBytes("s", "TOKEN"))
        assertEquals(SecretSlotState.DESTROYED, vault.observeSlots("s").first().single().state)
        assertEquals(SecretSlotState.DESTROYED, vault.observeCards("s").first().single().slot.state)
        assertEquals("SECRET_SLOT_UNAVAILABLE", assertThrows(IllegalStateException::class.java) {
            vault.environment("s", "echo \$TOKEN")
        }.message)
    }

    @Test fun `覆盖值更新卡片且重复show调用幂等`() = runBlocking {
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "test-token".toByteArray())
        vault.show("s", "assistant", "call", "TOKEN", "查看令牌")
        vault.show("s", "assistant", "call", "TOKEN", "查看令牌")
        vault.put("s", "TOKEN", "新令牌", SecretSource.UserInput, "new-token-value".toByteArray())
        assertEquals(1, vault.observeCards("s").first().size)
        assertEquals(15, vault.observeCards("s").first().single().slot.byteLength)
        assertEquals("new-token-value", vault.environment("s", "\$TOKEN")["TOKEN"])
    }

    @Test fun `清会话同时清空已有观察流和卡片`() = runBlocking {
        val slots = vault.observeSlots("s")
        val cards = vault.observeCards("s")
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "test-token".toByteArray())
        vault.show("s", "assistant", "call", "TOKEN", "查看令牌")
        vault.clearSession("s")
        assertNull(vault.revealBytes("s", "TOKEN"))
        assertTrue(slots.first().isEmpty())
        assertTrue(cards.first().isEmpty())
    }

    @Test fun `非法槽位空值和超限值只返回固定错误码`() {
        assertEquals("SECRET_SLOT_INVALID", assertThrows(IllegalArgumentException::class.java) {
            vault.put("s", "bad-name", "令牌", SecretSource.UserInput, "test-token".toByteArray())
        }.message)
        assertEquals("SECRET_EMPTY", assertThrows(IllegalArgumentException::class.java) {
            vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, byteArrayOf())
        }.message)
        assertEquals("SECRET_TOO_LARGE", assertThrows(IllegalArgumentException::class.java) {
            vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, ByteArray(SecretIoLimits.MAX_SECRET_BYTES + 1))
        }.message)
    }

    @Test fun `二进制值可捕获但不能注入操作系统环境`() {
        vault.put("s", "BINARY", "文件", SecretSource.File("file"), byteArrayOf(1, 0, 2, 3))
        assertEquals("SECRET_ENV_INVALID", assertThrows(IllegalStateException::class.java) {
            vault.environment("s", "\$BINARY")
        }.message)
    }

    @Test fun `缺失槽位不能创建输出卡且用途超限拒绝`() {
        assertEquals("SECRET_SLOT_UNAVAILABLE", assertThrows(IllegalStateException::class.java) {
            vault.show("s", "a", "c", "TOKEN", "查看")
        }.message)
        vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "test-token".toByteArray())
        assertEquals("SECRET_PURPOSE_INVALID", assertThrows(IllegalArgumentException::class.java) {
            vault.show("s", "a", "c", "TOKEN", "用".repeat(SecretIoLimits.MAX_PURPOSE_CHARS + 1))
        }.message)
    }

    @Test fun `恢复缺失槽位后命令引用明确失败且公开流只有墓碑`() = runBlocking {
        vault.rememberUnavailable("s", "TOKEN", "恢复令牌", SecretSource.UserInput)
        val slot = vault.observeSlots("s").first().single()
        assertEquals(SecretSlotState.DESTROYED, slot.state)
        assertEquals(0, slot.byteLength)
        assertNull(vault.revealBytes("s", "TOKEN"))
        assertEquals("SECRET_SLOT_UNAVAILABLE", assertThrows(IllegalStateException::class.java) {
            vault.environment("s", "echo \${TOKEN}")
        }.message)
    }

    @Test fun `恢复墓碑不覆盖可用值并校验公开槽位元数据`() = runBlocking {
        vault.put("s", "TOKEN", "当前令牌", SecretSource.UserInput, "test-token".toByteArray())
        vault.rememberUnavailable("s", "TOKEN", "恢复令牌", SecretSource.CommandStdout("命令标准输出"))
        assertEquals("test-token", vault.environment("s", "\$TOKEN")["TOKEN"])
        assertEquals("当前令牌", vault.observeSlots("s").first().single().label)
        assertEquals("SECRET_SLOT_INVALID", assertThrows(IllegalArgumentException::class.java) {
            vault.rememberUnavailable("s", "bad-name", "恢复令牌", SecretSource.UserInput)
        }.message)
        assertEquals("SECRET_LABEL_INVALID", assertThrows(IllegalArgumentException::class.java) {
            vault.rememberUnavailable("s", "OTHER", "", SecretSource.UserInput)
        }.message)
    }
}

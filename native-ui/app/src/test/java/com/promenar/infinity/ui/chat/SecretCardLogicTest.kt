package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.secret.SecretDestination
import com.promenar.infinity.domain.secret.SecretIoLimits
import com.promenar.infinity.domain.secret.SecretOutputCard
import com.promenar.infinity.domain.secret.SecretSlot
import com.promenar.infinity.domain.secret.SecretSlotState
import com.promenar.infinity.domain.secret.SecretSource
import com.promenar.infinity.domain.secret.SshSecretField
import org.junit.Test

/** 保密卡片的纯逻辑：FLAG_SECURE 计数、倒计时、必填、另存去向、剪贴板清空判断与卡片归组。 */
class SecretCardLogicTest {

    @Test fun `FLAG_SECURE 按计数保留，最后一个释放才清除`() {
        val counter = SecureWindowCounter<String>()
        var secure = false
        val set: (Boolean) -> Unit = { secure = it }
        counter.acquire("w", { secure }, set)
        counter.acquire("w", { secure }, set)
        assertThat(secure).isTrue()
        counter.release("w", set)
        assertThat(secure).isTrue()
        assertThat(counter.holders("w")).isEqualTo(1)
        counter.release("w", set)
        assertThat(secure).isFalse()
        assertThat(counter.holders("w")).isEqualTo(0)
        // 多余的释放不会误改窗口。
        secure = true
        counter.release("w", set)
        assertThat(secure).isTrue()
    }

    @Test fun `窗口原本就安全时释放后保持安全`() {
        val counter = SecureWindowCounter<String>()
        var secure = true
        val writes = mutableListOf<Boolean>()
        val set: (Boolean) -> Unit = { secure = it; writes += it }
        counter.acquire("w", { secure }, set)
        counter.release("w", set)
        assertThat(secure).isTrue()
        assertThat(writes).isEmpty()
    }

    @Test fun `不同窗口各自计数`() {
        val counter = SecureWindowCounter<String>()
        val flags = mutableMapOf("a" to false, "b" to false)
        counter.acquire("a", { flags.getValue("a") }) { flags["a"] = it }
        counter.acquire("b", { flags.getValue("b") }) { flags["b"] = it }
        counter.release("a") { flags["a"] = it }
        assertThat(flags).containsExactly("a", false, "b", true)
    }

    @Test fun `剩余时间向上取整到秒，到期为 null`() {
        assertThat(secretInputRemaining(expiresAtMillis = 600_000, nowMillis = 0)).isEqualTo(10 to 0)
        assertThat(secretInputRemaining(expiresAtMillis = 61_500, nowMillis = 0)).isEqualTo(1 to 2)
        assertThat(secretInputRemaining(expiresAtMillis = 1_000, nowMillis = 999)).isEqualTo(0 to 1)
        assertThat(secretInputRemaining(expiresAtMillis = 1_000, nowMillis = 1_000)).isNull()
    }

    @Test fun `值为空或缺用户名时不能提交`() {
        assertThat(secretInputReady(0, needsUsername = false, username = "")).isFalse()
        assertThat(secretInputReady(5, needsUsername = true, username = "  ")).isFalse()
        assertThat(secretInputReady(5, needsUsername = true, username = "Promenar")).isTrue()
        assertThat(secretInputReady(5, needsUsername = false, username = "")).isTrue()
    }

    @Test fun `去向决定卡片呈现`() {
        assertThat(secretInputStyle(SecretDestination.GitCredential("github.com"))).isEqualTo(SecretInputStyle.CREDENTIAL)
        assertThat(secretInputStyle(SecretDestination.SshHost("h", SshSecretField.PASSWORD))).isEqualTo(SecretInputStyle.CREDENTIAL)
        assertThat(secretInputStyle(SecretDestination.ProviderApiKey("p"))).isEqualTo(SecretInputStyle.CREDENTIAL)
        assertThat(secretInputStyle(SecretDestination.JobInput("3"))).isEqualTo(SecretInputStyle.JOB)
        assertThat(secretInputStyle(SecretDestination.SessionEnv("OPENAI_API_KEY"))).isEqualTo(SecretInputStyle.SESSION_ENV)
    }

    @Test fun `另存为只在字段齐全时组成去向`() {
        assertThat(secretSaveAsDestination(SecretSaveAsKind.GIT, " github.com ", null, SshSecretField.PASSWORD, null))
            .isEqualTo(SecretDestination.GitCredential("github.com"))
        assertThat(secretSaveAsDestination(SecretSaveAsKind.GIT, "  ", null, SshSecretField.PASSWORD, null)).isNull()
        assertThat(secretSaveAsDestination(SecretSaveAsKind.SSH, "", null, SshSecretField.PASSWORD, null)).isNull()
        assertThat(secretSaveAsDestination(SecretSaveAsKind.SSH, "", "host-1", SshSecretField.KEY_PASSPHRASE, null))
            .isEqualTo(SecretDestination.SshHost("host-1", SshSecretField.KEY_PASSPHRASE))
        assertThat(secretSaveAsDestination(SecretSaveAsKind.PROVIDER, "", null, SshSecretField.PASSWORD, "openai"))
            .isEqualTo(SecretDestination.ProviderApiKey("openai"))
    }

    @Test fun `剪贴板到期只清自己写入的那条，读不到时按保密优先清空`() {
        assertThat(shouldClearSensitiveClip(true, "保密信息", 100L, "保密信息", 100L)).isTrue()
        assertThat(shouldClearSensitiveClip(true, "保密信息", 200L, "保密信息", 100L)).isFalse()
        assertThat(shouldClearSensitiveClip(true, "别的内容", 100L, "保密信息", 100L)).isFalse()
        assertThat(shouldClearSensitiveClip(false, null, null, "保密信息", 100L)).isTrue()
    }

    @Test fun `回执与输出卡归到发起调用的回复组，找不到时归最后一组`() {
        val groups = listOf(setOf("u1", "a1"), setOf("u2", "a2", "a3"))
        val card = outputCard(assistantMessageId = "a1", toolCallId = "c1")
        val orphan = outputCard(assistantMessageId = "gone", toolCallId = "c2")
        val receipt = SecretInputReceipt("s", "a3", "c3", SecretInputReceipt.Status.SAVED, "Git 凭据 · github.com")
        val result = secretItemsByGroup(groups, listOf(card, orphan), listOf(receipt))
        assertThat(result.getValue(0)).isEqualTo(SecretGroupItems(emptyList(), listOf(card)))
        assertThat(result.getValue(1)).isEqualTo(SecretGroupItems(listOf(receipt), listOf(orphan)))
        assertThat(secretItemsByGroup(emptyList(), listOf(card), emptyList())).isEmpty()
    }

    @Test fun `保密工具归入保密信息类别`() {
        assertThat(toolSummaryAction(SecretIoLimits.REQUEST_TOOL_NAME)).isEqualTo(ToolSummaryAction.SECRET)
        assertThat(toolSummaryAction(SecretIoLimits.SHOW_TOOL_NAME)).isEqualTo(ToolSummaryAction.SECRET)
    }

    private fun outputCard(assistantMessageId: String, toolCallId: String) = SecretOutputCard(
        sessionId = "s",
        assistantMessageId = assistantMessageId,
        toolCallId = toolCallId,
        slot = SecretSlot("s", "GITEA_ADMIN_PASSWORD", "Gitea 管理员初始密码", SecretSource.CommandStdout("openssl rand"), 32, SecretSlotState.AVAILABLE),
        purpose = "Gitea 管理员 admin 的初始密码。",
    )
}

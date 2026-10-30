package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.git.remote.GitCredential
import com.promenar.infinity.data.git.remote.GitCredentialStore
import com.promenar.infinity.domain.secret.PendingSecretInput
import com.promenar.infinity.domain.secret.SecretDestination
import com.promenar.infinity.domain.secret.SecretExposure
import com.promenar.infinity.domain.secret.SecretInputCoordinator
import com.promenar.infinity.domain.secret.SecretInputOutcome
import com.promenar.infinity.domain.secret.SecretInputRequest
import com.promenar.infinity.domain.secret.SecretKind
import com.promenar.infinity.domain.secret.SecretOutputCard
import com.promenar.infinity.domain.secret.SecretOutputController
import com.promenar.infinity.domain.secret.SecretSlot
import com.promenar.infinity.domain.secret.SecretSlotState
import com.promenar.infinity.domain.secret.SecretSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** 会话页保密接线：提交、取消、回执、失败重试、输出卡转发与输入框令牌保存；值数组用后都被清零。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSecretIoTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private class FakeCoordinator : SecretInputCoordinator {
        val pending = MutableStateFlow<PendingSecretInput?>(null)
        var outcome: SecretInputOutcome? = SecretInputOutcome.Saved("git_credential")
        var gate: CompletableDeferred<Unit>? = null
        val submitted = mutableListOf<Pair<String, String?>>()
        val arrays = mutableListOf<CharArray>()
        val cancelled = mutableListOf<PendingSecretInput>()

        override fun observePending(sessionId: String): Flow<PendingSecretInput?> =
            pending.map { it?.takeIf { request -> request.sessionId == sessionId } }

        override suspend fun submit(expected: PendingSecretInput, value: CharArray, username: String?): SecretInputOutcome? {
            submitted += String(value) to username
            arrays += value
            gate?.await()
            return outcome
        }

        override suspend fun cancel(expected: PendingSecretInput): SecretInputOutcome? {
            cancelled += expected
            pending.value = null
            return SecretInputOutcome.Cancelled
        }

        var githubLoginOutcome: SecretInputOutcome? = null
        val githubLogins = mutableListOf<PendingSecretInput>()
        override suspend fun completeWithGitHubLogin(expected: PendingSecretInput): SecretInputOutcome? {
            githubLogins += expected
            return githubLoginOutcome
        }
    }

    private class FakeController : SecretOutputController {
        val cards = MutableStateFlow<Map<String, List<SecretOutputCard>>>(emptyMap())
        val destroyed = mutableListOf<String>()
        override fun observeCards(sessionId: String): Flow<List<SecretOutputCard>> = cards.map { it[sessionId].orEmpty() }
        override fun reveal(sessionId: String, name: String): CharArray? = if (name == "TOKEN") "s3cr3t".toCharArray() else null
        override suspend fun saveAs(sessionId: String, name: String, destination: SecretDestination, username: String?): SecretInputOutcome =
            SecretInputOutcome.Saved("git_credential", mapOf("host" to (destination as SecretDestination.GitCredential).host))
        override fun destroy(sessionId: String, name: String) { destroyed += "$sessionId/$name" }
    }

    private class FakeGitStore(private val fail: Boolean = false) : GitCredentialStore {
        override val credentials: StateFlow<List<GitCredential>> = MutableStateFlow(emptyList())
        val saved = mutableListOf<Triple<String, String, String>>()
        val arrays = mutableListOf<ByteArray>()
        override fun save(host: String, username: String, token: ByteArray) {
            if (fail) throw IllegalArgumentException("无效")
            saved += Triple(host, username, String(token, Charsets.US_ASCII))
            arrays += token
        }
        override fun delete(host: String) = Unit
        override fun token(host: String): ByteArray? = null
    }

    private val session = MutableStateFlow<String?>("s1")
    private val coordinator = FakeCoordinator()
    private val controller = FakeController()

    private fun TestScope.io(git: GitCredentialStore = FakeGitStore()) = ChatSecretIo(
        scope = backgroundScope,
        sessionId = session,
        coordinator = coordinator,
        controller = controller,
        gitCredentials = { git },
        saveTargetsLoader = { SecretSaveTargets(gitHosts = listOf("github.com" to "Promenar")) },
        ioDispatcher = dispatcher,
        failureText = { "操作失败" },
    )

    private fun pending(sessionId: String = "s1", toolCallId: String = "call-1", username: String? = null) = PendingSecretInput(
        sessionId = sessionId,
        assistantMessageId = "a1",
        toolCallId = toolCallId,
        request = SecretInputRequest("需要 GitHub 令牌", SecretKind.TOKEN, SecretDestination.GitCredential("github.com", username)),
        destinationLabel = "设置 › Git 凭据 · github.com",
        exposure = SecretExposure.APP_ONLY,
        needsUsername = username == null,
        offersGitHubBrowserLogin = true,
        expiresAtMillis = Long.MAX_VALUE,
    )

    @Test fun `提交成功后留下回执，值数组被清零`() = runTest(dispatcher) {
        val io = io()
        val request = pending()
        coordinator.pending.value = request
        val value = "tok-123".toCharArray()
        assertThat(io.submit(request, value, " Promenar ")).isTrue()
        assertThat(coordinator.submitted).containsExactly("tok-123" to "Promenar")
        assertThat(value.all { it == '\u0000' }).isTrue()
        assertThat(io.submission.value).isNull()
        assertThat(io.receipts.value).containsExactly(
            SecretInputReceipt("s1", "a1", "call-1", SecretInputReceipt.Status.SAVED, "设置 › Git 凭据 · github.com", "Promenar"),
        )
    }

    @Test fun `浏览器登录成功后由协调器确认才留下回执`() = runTest(dispatcher) {
        val io = io()
        val request = pending()
        coordinator.pending.value = request
        io.completeWithGitHubLogin()
        assertThat(coordinator.githubLogins).containsExactly(request)
        assertThat(io.receipts.value).isEmpty()
        coordinator.githubLoginOutcome = SecretInputOutcome.Saved("git_credential")
        io.completeWithGitHubLogin()
        assertThat(io.receipts.value.map { it.status }).containsExactly(SecretInputReceipt.Status.SAVED)
        coordinator.pending.value = request.copy(offersGitHubBrowserLogin = false, toolCallId = "call-2")
        io.completeWithGitHubLogin()
        assertThat(coordinator.githubLogins).hasSize(2)
    }

    @Test fun `提交中重复点击不再交给协调器`() = runTest(dispatcher) {
        val io = io()
        val request = pending()
        coordinator.pending.value = request
        coordinator.gate = CompletableDeferred()
        assertThat(io.submit(request, "a".toCharArray(), "u")).isTrue()
        assertThat(io.submission.value).isEqualTo(SecretInputSubmission("call-1", submitting = true))
        val second = "b".toCharArray()
        assertThat(io.submit(request, second, "u")).isFalse()
        assertThat(second.single()).isEqualTo('\u0000')
        coordinator.gate!!.complete(Unit)
        assertThat(coordinator.submitted).hasSize(1)
    }

    @Test fun `失败时卡片显示原因并允许重试`() = runTest(dispatcher) {
        val io = io()
        val request = pending()
        coordinator.pending.value = request
        coordinator.outcome = SecretInputOutcome.Failed("invalid_token", "令牌格式不对")
        io.submit(request, "x".toCharArray(), "u")
        assertThat(io.submission.value).isEqualTo(SecretInputSubmission("call-1", submitting = false, error = "令牌格式不对"))
        assertThat(io.receipts.value).isEmpty()
        coordinator.outcome = SecretInputOutcome.Saved("git_credential")
        assertThat(io.submit(request, "y".toCharArray(), "u")).isTrue()
        assertThat(io.receipts.value.map { it.status }).containsExactly(SecretInputReceipt.Status.SAVED)
    }

    @Test fun `请求已不在等待时不提交，并清零值`() = runTest(dispatcher) {
        val io = io()
        coordinator.pending.value = pending(toolCallId = "call-2")
        val stale = pending(toolCallId = "call-1")
        val value = "abc".toCharArray()
        assertThat(io.submit(stale, value, null)).isFalse()
        assertThat(value.all { it == '\u0000' }).isTrue()
        assertThat(coordinator.submitted).isEmpty()
    }

    @Test fun `协调器返回 null 时卡片消失且不留回执`() = runTest(dispatcher) {
        val io = io()
        val request = pending()
        coordinator.pending.value = request
        coordinator.outcome = null
        io.submit(request, "x".toCharArray(), "u")
        assertThat(io.submission.value).isNull()
        assertThat(io.receipts.value).isEmpty()
    }

    @Test fun `取消与停止本轮都把已取消交回，并只作用于当前会话`() = runTest(dispatcher) {
        val io = io()
        val request = pending()
        coordinator.pending.value = request
        io.cancelForSession("other")
        assertThat(coordinator.cancelled).isEmpty()
        io.cancelForSession("s1")
        assertThat(coordinator.cancelled).containsExactly(request)
        assertThat(io.receipts.value.single().status).isEqualTo(SecretInputReceipt.Status.CANCELLED)
        assertThat(io.pending.value).isNull()
    }

    @Test fun `回执与输出卡随当前会话切换`() = runTest(dispatcher) {
        val io = io()
        val request = pending()
        coordinator.pending.value = request
        io.submit(request, "x".toCharArray(), "u")
        val card = SecretOutputCard("s2", "a9", "call-9", SecretSlot("s2", "TOKEN", "令牌", SecretSource.UserInput, 6, SecretSlotState.AVAILABLE), "")
        controller.cards.value = mapOf("s2" to listOf(card))
        assertThat(io.outputCards.value).isEmpty()
        session.value = "s2"
        assertThat(io.receipts.value).isEmpty()
        assertThat(io.outputCards.value).containsExactly(card)
        assertThat(io.reveal(card)?.let(::String)).isEqualTo("s3cr3t")
        io.destroy(card)
        assertThat(controller.destroyed).containsExactly("s2/TOKEN")
        var outcome: SecretInputOutcome? = null
        io.saveAs(card, SecretDestination.GitCredential("github.com"), "  ") { outcome = it }
        assertThat(outcome).isEqualTo(SecretInputOutcome.Saved("git_credential", mapOf("host" to "github.com")))
        assertThat(io.saveTargets().gitHosts).containsExactly("github.com" to "Promenar")
    }

    @Test fun `输入框令牌写入 Git 凭据，令牌字符与字节都被清零`() = runTest(dispatcher) {
        val store = FakeGitStore()
        val io = io(store)
        val token = "ghp_abc".toCharArray()
        var result: Boolean? = null
        io.saveComposerGitToken("github.com", "Promenar", token) { result = it }
        assertThat(result).isTrue()
        assertThat(store.saved).containsExactly(Triple("github.com", "Promenar", "ghp_abc"))
        assertThat(token.all { it == '\u0000' }).isTrue()
        assertThat(store.arrays.single().all { it == 0.toByte() }).isTrue()
    }

    @Test fun `Git 凭据保存失败时回调 false`() = runTest(dispatcher) {
        val io = io(FakeGitStore(fail = true))
        var result: Boolean? = null
        io.saveComposerGitToken("github.com", "Promenar", "ghp_abc".toCharArray()) { result = it }
        assertThat(result).isFalse()
    }
}

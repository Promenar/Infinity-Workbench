package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.git.remote.GitCredentialStore
import com.promenar.infinity.domain.secret.PendingSecretInput
import com.promenar.infinity.domain.secret.SecretDestination
import com.promenar.infinity.domain.secret.SecretInputCoordinator
import com.promenar.infinity.domain.secret.SecretInputOutcome
import com.promenar.infinity.domain.secret.SecretOutputCard
import com.promenar.infinity.domain.secret.SecretOutputController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 提交后留在会话里的单行回执；不含值，只有去向与用户名。 */
data class SecretInputReceipt(
    val sessionId: String,
    val assistantMessageId: String,
    val toolCallId: String,
    val status: Status,
    val destinationLabel: String,
    val username: String? = null,
) {
    enum class Status { SAVED, CANCELLED, EXPIRED }
}

/** 正在提交或提交失败的输入卡；失败时卡片显示 [error] 并允许重试。 */
data class SecretInputSubmission(val toolCallId: String, val submitting: Boolean, val error: String? = null)

/** “保存为凭据…”可选的已有去向，只有标识与显示名。 */
data class SecretSaveTargets(
    val sshHosts: List<Target> = emptyList(),
    val providers: List<Target> = emptyList(),
    /** 已保存的 Git 凭据主机与用户名，用于预填。 */
    val gitHosts: List<Pair<String, String>> = emptyList(),
) {
    data class Target(val id: String, val label: String)
}

/**
 * 会话页的保密输入输出接线：观察当前会话的待输入请求与输出卡，转发提交、取消、显示、另存与销毁。
 * 值只经过函数参数与返回值，不进入任何 StateFlow；提交与取用后的数组在这里兜底清零。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatSecretIo(
    private val scope: CoroutineScope,
    sessionId: StateFlow<String?>,
    private val coordinator: SecretInputCoordinator,
    private val controller: SecretOutputController,
    private val gitCredentials: () -> GitCredentialStore,
    private val saveTargetsLoader: suspend () -> SecretSaveTargets,
    private val ioDispatcher: CoroutineDispatcher,
    private val failureText: () -> String,
) {
    private val currentSessionId = sessionId

    val pending: StateFlow<PendingSecretInput?> = sessionId
        .flatMapLatest { id -> if (id == null) flowOf(null) else coordinator.observePending(id) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val outputCards: StateFlow<List<SecretOutputCard>> = sessionId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else controller.observeCards(id) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val allReceipts = MutableStateFlow<List<SecretInputReceipt>>(emptyList())

    /** 当前会话的回执；只在内存里，离开应用后由工具行代替。 */
    val receipts: StateFlow<List<SecretInputReceipt>> = combine(sessionId, allReceipts) { id, list ->
        list.filter { it.sessionId == id }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _submission = MutableStateFlow<SecretInputSubmission?>(null)
    val submission: StateFlow<SecretInputSubmission?> = _submission.asStateFlow()

    /**
     * 提交用户输入。请求已不是当前会话的待输入项、或已在提交中时直接清零返回 false。
     * [value] 交给协调器后由其清零；这里在结束时再清一次，防止实现遗漏。
     */
    fun submit(expected: PendingSecretInput, value: CharArray, username: String?): Boolean {
        if (currentSessionId.value != expected.sessionId || pending.value != expected ||
            _submission.value?.let { it.toolCallId == expected.toolCallId && it.submitting } == true
        ) {
            value.fill('\u0000')
            return false
        }
        _submission.value = SecretInputSubmission(expected.toolCallId, submitting = true)
        scope.launch {
            try {
                val outcome = withContext(NonCancellable) { coordinator.submit(expected, value, username?.trim()?.ifEmpty { null }) }
                settle(expected, outcome, username)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _submission.value = SecretInputSubmission(expected.toolCallId, submitting = false, error = failureText())
            } finally {
                value.fill('\u0000')
            }
        }
        return true
    }

    fun cancel(expected: PendingSecretInput) {
        if (_submission.value?.let { it.toolCallId == expected.toolCallId && it.submitting } == true) return
        scope.launch {
            val outcome = try {
                withContext(NonCancellable) { coordinator.cancel(expected) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            settle(expected, outcome, null)
        }
    }

    /** 停止本轮时关闭本会话仍在等待的输入请求；请求已不在等待时协调器返回 null。 */
    suspend fun cancelForSession(sessionId: String) {
        val expected = pending.value?.takeIf { it.sessionId == sessionId } ?: return
        val outcome = try {
            coordinator.cancel(expected)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        settle(expected, outcome, null)
    }

    private fun settle(expected: PendingSecretInput, outcome: SecretInputOutcome?, username: String?) {
        val status = when (outcome) {
            is SecretInputOutcome.Saved -> SecretInputReceipt.Status.SAVED
            SecretInputOutcome.Cancelled -> SecretInputReceipt.Status.CANCELLED
            SecretInputOutcome.Expired -> SecretInputReceipt.Status.EXPIRED
            is SecretInputOutcome.Failed -> {
                _submission.value = SecretInputSubmission(expected.toolCallId, submitting = false, error = outcome.message)
                return
            }
            null -> null
        }
        _submission.value = null
        status ?: return
        val receipt = SecretInputReceipt(
            sessionId = expected.sessionId,
            assistantMessageId = expected.assistantMessageId,
            toolCallId = expected.toolCallId,
            status = status,
            destinationLabel = expected.destinationLabel,
            username = username?.trim()?.ifEmpty { null }
                ?: (expected.request.destination as? SecretDestination.GitCredential)?.username,
        )
        allReceipts.value = allReceipts.value.filterNot { it.toolCallId == expected.toolCallId } + receipt
    }

    /** 设备验证通过后取值；调用方用完立即清零。 */
    fun reveal(card: SecretOutputCard): CharArray? = controller.reveal(card.sessionId, card.slot.name)

    fun saveAs(card: SecretOutputCard, destination: SecretDestination, username: String?, onResult: (SecretInputOutcome) -> Unit) {
        scope.launch {
            val outcome = try {
                withContext(NonCancellable) { controller.saveAs(card.sessionId, card.slot.name, destination, username?.trim()?.ifEmpty { null }) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SecretInputOutcome.Failed("save_failed", failureText())
            }
            onResult(outcome)
        }
    }

    fun destroy(card: SecretOutputCard) = controller.destroy(card.sessionId, card.slot.name)

    suspend fun saveTargets(): SecretSaveTargets = try {
        withContext(ioDispatcher) { saveTargetsLoader() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SecretSaveTargets()
    }

    /**
     * 输入框提醒里“保存到 Git 凭据”：写入设置 › Git 凭据，与设置页保存同一存储。
     * [token] 在这里转成字节并清零；非 ASCII 字符映射为 0，由存储层按无效令牌拒绝。
     */
    fun saveComposerGitToken(host: String, username: String, token: CharArray, onResult: (Boolean) -> Unit) {
        val bytes = ByteArray(token.size) { index -> token[index].code.takeIf { it < 0x80 }?.toByte() ?: 0 }
        token.fill('\u0000')
        scope.launch {
            val saved = try {
                withContext(NonCancellable + ioDispatcher) { gitCredentials().save(host, username, bytes) }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            } finally {
                bytes.fill(0)
            }
            onResult(saved)
        }
    }
}

/** 跟在某一组回复之后的保密回执与输出卡。 */
internal data class SecretGroupItems(val receipts: List<SecretInputReceipt>, val cards: List<SecretOutputCard>)

/**
 * 把回执与输出卡分到发起调用的回复组：[groupMessageIds] 是各组包含的消息 ID。
 * 找不到所属回复（消息尚未加载或已删除）的项归到最后一组，保证用户仍能看到并处理。
 */
internal fun secretItemsByGroup(
    groupMessageIds: List<Set<String>>,
    cards: List<SecretOutputCard>,
    receipts: List<SecretInputReceipt>,
): Map<Int, SecretGroupItems> {
    if (groupMessageIds.isEmpty() || (cards.isEmpty() && receipts.isEmpty())) return emptyMap()
    fun groupOf(messageId: String): Int = groupMessageIds.indexOfFirst { messageId in it }.takeIf { it >= 0 } ?: groupMessageIds.lastIndex
    val receiptGroups = receipts.groupBy { groupOf(it.assistantMessageId) }
    val cardGroups = cards.groupBy { groupOf(it.assistantMessageId) }
    return (receiptGroups.keys + cardGroups.keys).associateWith {
        SecretGroupItems(receiptGroups[it].orEmpty(), cardGroups[it].orEmpty())
    }
}

package com.promenar.infinity.domain.secret

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 没有保密输入后端时的空实现：永远没有待输入请求。 */
object NoopSecretInputCoordinator : SecretInputCoordinator {
    override fun observePending(sessionId: String): Flow<PendingSecretInput?> = flowOf(null)

    override suspend fun submit(expected: PendingSecretInput, value: CharArray, username: String?): SecretInputOutcome? {
        value.fill('\u0000')
        return null
    }

    override suspend fun cancel(expected: PendingSecretInput): SecretInputOutcome? = null
}

/** 没有保密库后端时的空实现：没有输出卡，也取不到值。 */
object NoopSecretOutputController : SecretOutputController {
    override fun observeCards(sessionId: String): Flow<List<SecretOutputCard>> = flowOf(emptyList())

    override fun reveal(sessionId: String, name: String): CharArray? = null

    override suspend fun saveAs(sessionId: String, name: String, destination: SecretDestination, username: String?): SecretInputOutcome =
        SecretInputOutcome.Failed("unavailable", "保密库不可用")

    override fun destroy(sessionId: String, name: String) = Unit
}

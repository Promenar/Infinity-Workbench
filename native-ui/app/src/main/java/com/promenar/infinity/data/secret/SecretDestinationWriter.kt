package com.promenar.infinity.data.secret

import com.promenar.infinity.domain.secret.SecretDestination

/** 错误码固定，不携带底层异常与用户输入。 */
class SecretIoFailure(val code: String) : IllegalArgumentException(code)

data class SecretDestinationInfo(
    val label: String,
    val needsUsername: Boolean = false,
    val offersGitHubBrowserLogin: Boolean = false,
)

/** 元数据检查与实际写入均由应用已有凭据路径消费。 */
interface SecretDestinationWriter {
    suspend fun describe(sessionId: String, destination: SecretDestination): SecretDestinationInfo
    suspend fun write(sessionId: String, destination: SecretDestination, value: ByteArray, username: String?)

    /** 去向是 github.com 的 Git 凭据，且已有浏览器登录保存的凭据时为真。 */
    suspend fun satisfiedByGitHubLogin(destination: SecretDestination): Boolean = false
}

package com.promenar.infinity.domain.secret

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 保密信息的输入与输出契约（主控维护，后端与界面共同依赖）。
 *
 * 输入：Agent 调用 `request_secret`，用户在保密输入卡里填写，值由应用直接写入 [SecretDestination]，
 * Agent 只拿到 [SecretInputOutcome]。输出：命令以 `capture_secret` 把输出存进本会话保密库，
 * Agent 调用 `show_secret` 后用户在保密输出卡里验证查看、复制、另存或销毁。
 * 任何保密值都不进入消息表、工具账本、日志与诊断导出；会话保密库只在内存，不进入备份。
 * 写入持久去向（Git 凭据、SSH 主机、服务商密钥）后，值遵循该去向既有的存储与备份策略，
 * 与用户在设置里直接填写相同。
 */
object SecretIoLimits {
    const val REQUEST_TOOL_NAME = "request_secret"
    const val SHOW_TOOL_NAME = "show_secret"
    const val CAPTURE_PARAMETER = "capture_secret"
    const val MAX_PURPOSE_CHARS = 200
    const val MAX_LABEL_CHARS = 60
    const val MAX_SECRET_BYTES = 64 * 1024
    /** 会话保密库槽位名，同时是命令里的环境变量名。 */
    val SLOT_NAME = Regex("[A-Z][A-Z0-9_]{0,63}")
    /** 输入卡未提交的失效时间。 */
    const val INPUT_TIMEOUT_MILLIS = 10 * 60 * 1000L
}

/** 输入框的键盘与显示提示，不影响去向。 */
@Serializable
enum class SecretKind {
    @SerialName("password") PASSWORD,
    @SerialName("token") TOKEN,
    @SerialName("api_key") API_KEY,
    @SerialName("passphrase") PASSPHRASE,
    @SerialName("other") OTHER,
}

/** 保密值的去向；由应用直接消费，模型只描述去哪里，不经手值本身。 */
@Serializable
sealed interface SecretDestination {
    /** 设置 › Git 凭据；保存后内置 git、Linux git 与 gh 都经凭据代理使用。[username] 为空时由用户在卡片里填写。 */
    @Serializable
    @SerialName("git_credential")
    data class GitCredential(val host: String, val username: String? = null) : SecretDestination

    /** SSH 主机的登录密码或私钥口令。 */
    @Serializable
    @SerialName("ssh_host")
    data class SshHost(val hostId: String, val field: SshSecretField) : SecretDestination

    /** 模型服务商的 API Key。 */
    @Serializable
    @SerialName("provider_api_key")
    data class ProviderApiKey(val providerId: String) : SecretDestination

    /** 写入正在运行的后台任务终端并回车，只用一次、不保存。 */
    @Serializable
    @SerialName("job_input")
    data class JobInput(val jobId: String) : SecretDestination

    /** 本会话保密库的一个槽位，命令以同名环境变量读取；只在内存中，会话结束或应用退出即清除。 */
    @Serializable
    @SerialName("session_env")
    data class SessionEnv(val name: String) : SecretDestination
}

@Serializable
enum class SshSecretField {
    @SerialName("password") PASSWORD,
    @SerialName("key_passphrase") KEY_PASSPHRASE,
}

/** `request_secret` 工具的参数。 */
@Serializable
data class SecretInputRequest(
    val purpose: String,
    val kind: SecretKind = SecretKind.OTHER,
    val destination: SecretDestination,
)

/** 值落地后谁能接触到它；界面据此决定是否显示风险提示。 */
enum class SecretExposure {
    /** 应用内部消费，命令与 Agent 都读不到原文（Git/SSH/服务商凭据、写入任务终端）。 */
    APP_ONLY,
    /** 命令可以读取（本会话环境变量），只能靠输出遮蔽做逻辑隔离。 */
    COMMAND_READABLE,
}

/**
 * 会话中正在等待保密输入的请求，定位方式与 `ask_user` 的 PendingQuestion 一致。
 * [destinationLabel] 与 [exposure] 由应用根据去向生成，界面只展示它们，不展示模型拼写的去向文字。
 */
data class PendingSecretInput(
    val sessionId: String,
    val assistantMessageId: String,
    val toolCallId: String,
    val request: SecretInputRequest,
    val destinationLabel: String,
    val exposure: SecretExposure,
    /** 需要用户补填用户名（Git 凭据未给出用户名时）。 */
    val needsUsername: Boolean,
    /** 去向是 github.com 且应用配置了浏览器登录时，卡片提供“改用浏览器登录 GitHub”。 */
    val offersGitHubBrowserLogin: Boolean,
    val expiresAtMillis: Long,
)

/** 交回 Agent 的结果，只有状态与去向，没有值。 */
@Serializable
sealed interface SecretInputOutcome {
    @Serializable @SerialName("saved")
    data class Saved(val destination: String, val detail: Map<String, String> = emptyMap()) : SecretInputOutcome

    @Serializable @SerialName("cancelled")
    data object Cancelled : SecretInputOutcome

    @Serializable @SerialName("expired")
    data object Expired : SecretInputOutcome

    @Serializable @SerialName("failed")
    data class Failed(val code: String, val message: String) : SecretInputOutcome
}

/** 保密输入的界面入口；实现负责校验、落地、清零与续跑。 */
interface SecretInputCoordinator {
    fun observePending(sessionId: String): Flow<PendingSecretInput?>

    /**
     * 提交用户输入。实现落地后必须清零 [value]（[username] 不是保密值）；
     * 返回 null 表示该请求已不再等待（已取消、已过期或已被处理）。
     */
    suspend fun submit(expected: PendingSecretInput, value: CharArray, username: String?): SecretInputOutcome?

    suspend fun cancel(expected: PendingSecretInput): SecretInputOutcome?

    /**
     * 用户在卡片上改用浏览器登录 GitHub 并已保存 github.com 凭据后结束请求，结果与填写提交相同。
     * 没有浏览器登录保存的凭据、或请求已不再等待时返回 null。
     */
    suspend fun completeWithGitHubLogin(expected: PendingSecretInput): SecretInputOutcome? = null
}

/** 保密输出的来源，只用于展示。 */
@Serializable
sealed interface SecretSource {
    @Serializable @SerialName("command_stdout")
    data class CommandStdout(val commandSummary: String) : SecretSource

    @Serializable @SerialName("file")
    data class File(val path: String) : SecretSource

    /** 用户经 `request_secret` 提供给本会话的环境变量。 */
    @Serializable @SerialName("user_input")
    data object UserInput : SecretSource
}

enum class SecretSlotState { AVAILABLE, DESTROYED }

/** 本会话保密库一个槽位的公开描述，不含值。 */
data class SecretSlot(
    val sessionId: String,
    val name: String,
    val label: String,
    val source: SecretSource,
    val byteLength: Int,
    val state: SecretSlotState,
)

/** `show_secret` 交给用户的一张保密输出卡。 */
data class SecretOutputCard(
    val sessionId: String,
    val assistantMessageId: String,
    val toolCallId: String,
    val slot: SecretSlot,
    val purpose: String,
)

/** 保密输出卡上“保存为凭据…”可选的去向；不含任务终端与环境变量。 */
fun SecretDestination.isPersistentCredential(): Boolean = when (this) {
    is SecretDestination.GitCredential, is SecretDestination.SshHost, is SecretDestination.ProviderApiKey -> true
    is SecretDestination.JobInput, is SecretDestination.SessionEnv -> false
}

/** 保密输出卡的界面入口。 */
interface SecretOutputController {
    fun observeCards(sessionId: String): Flow<List<SecretOutputCard>>

    /** 用户已通过设备验证后取值用于显示或复制；调用方用完必须清零。槽位已销毁时返回 null。 */
    fun reveal(sessionId: String, name: String): CharArray?

    suspend fun saveAs(sessionId: String, name: String, destination: SecretDestination, username: String?): SecretInputOutcome

    fun destroy(sessionId: String, name: String)
}

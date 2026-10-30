package com.promenar.infinity.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.git.remote.GitCredential
import com.promenar.infinity.data.git.remote.GitCredentialHost
import com.promenar.infinity.data.git.remote.GitCredentialInputException
import com.promenar.infinity.data.git.remote.GitCredentialStore
import com.promenar.infinity.data.git.remote.GitHubDeviceFailure
import com.promenar.infinity.data.git.remote.GitHubDeviceFlowException
import com.promenar.infinity.data.git.remote.GitHubDeviceLogin
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Git 凭据操作状态；错误只带代码，文案由界面按资源解析（ViewModel 不读字符串资源）。 */
sealed interface GitCredentialOperation {
    data object Idle : GitCredentialOperation
    data object Saving : GitCredentialOperation
    data class Saved(val host: String) : GitCredentialOperation
    data class Deleted(val host: String) : GitCredentialOperation
    data class Error(val code: GitCredentialErrorCode) : GitCredentialOperation
}

enum class GitCredentialErrorCode { INVALID_HOST, INVALID_USERNAME, INVALID_TOKEN, LOAD_FAILED, SAVE_FAILED, DELETE_FAILED }

/** 浏览器登录 GitHub 的进度；令牌只在后端流转，不进入界面状态。 */
sealed interface GitHubLoginState {
    data object Idle : GitHubLoginState
    data object Requesting : GitHubLoginState
    data class AwaitingUser(val userCode: String, val verificationUri: String, val expiresAtMillis: Long) : GitHubLoginState
    data class Success(val login: String) : GitHubLoginState
    data class Failed(val failure: GitHubDeviceFailure) : GitHubLoginState
}

/** 界面状态只含主机与用户名，令牌不进入 StateFlow。[credentials] 为 null 表示仍在读取。 */
data class GitCredentialUiState(
    val credentials: List<GitCredential>? = null,
    val operation: GitCredentialOperation = GitCredentialOperation.Idle,
    /** 构建时配置了 OAuth App client_id 才提供浏览器登录。 */
    val githubLoginAvailable: Boolean = false,
    val githubLogin: GitHubLoginState = GitHubLoginState.Idle,
)

/** [store] 在 [ioDispatcher] 上首次取用：凭据存储构造时要解密 Keystore 中的主机列表，不能放在主线程。 */
class GitCredentialSettingsViewModel(
    private val store: () -> GitCredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val githubLogin: GitHubDeviceLogin? = null,
) : ViewModel() {
    private val credentials = MutableStateFlow<List<GitCredential>?>(null)
    private val operation = MutableStateFlow<GitCredentialOperation>(GitCredentialOperation.Idle)
    private val github = MutableStateFlow<GitHubLoginState>(GitHubLoginState.Idle)
    private var githubJob: Job? = null

    val state: StateFlow<GitCredentialUiState> = combine(credentials, operation, github) { list, current, login ->
        GitCredentialUiState(list, current, githubLoginAvailable = githubLogin?.configured == true, githubLogin = login)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GitCredentialUiState(githubLoginAvailable = githubLogin?.configured == true))

    init {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { store() }.credentials.collect { credentials.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                credentials.value = emptyList()
                operation.value = GitCredentialOperation.Error(GitCredentialErrorCode.LOAD_FAILED)
            }
        }
    }

    /** 保存或覆盖主机凭据；[token] 在函数内清零，调用方不再使用它。 */
    fun save(host: String, username: String, token: CharArray) {
        if (operation.value == GitCredentialOperation.Saving) {
            token.fill('\u0000')
            return
        }
        operation.value = GitCredentialOperation.Saving
        // 非 ASCII 字符映射为 0，由存储层按无效令牌拒绝。
        val bytes = ByteArray(token.size) { index -> token[index].code.takeIf { it < 0x80 }?.toByte() ?: 0 }
        token.fill('\u0000')
        viewModelScope.launch {
            operation.value = try {
                withContext(ioDispatcher) { store().save(host, username, bytes) }
                GitCredentialOperation.Saved(GitCredentialHost.normalize(host) ?: host.trim())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: GitCredentialInputException) {
                GitCredentialOperation.Error(
                    when (invalid.field) {
                        GitCredentialInputException.Field.HOST -> GitCredentialErrorCode.INVALID_HOST
                        GitCredentialInputException.Field.USERNAME -> GitCredentialErrorCode.INVALID_USERNAME
                        GitCredentialInputException.Field.TOKEN -> GitCredentialErrorCode.INVALID_TOKEN
                    },
                )
            } catch (_: Exception) {
                GitCredentialOperation.Error(GitCredentialErrorCode.SAVE_FAILED)
            } finally {
                bytes.fill(0)
            }
        }
    }

    /** 开始（或重试）浏览器登录；已在进行中时忽略。成功后凭据列表经存储自动刷新。 */
    fun startGitHubLogin() {
        val login = githubLogin?.takeIf { it.configured } ?: return
        if (githubJob?.isActive == true) return
        github.value = GitHubLoginState.Requesting
        githubJob = viewModelScope.launch {
            github.value = try {
                GitHubLoginState.Success(
                    login.login { code ->
                        github.value = GitHubLoginState.AwaitingUser(code.userCode, code.verificationUri, code.expiresAtMillis)
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: GitHubDeviceFlowException) {
                GitHubLoginState.Failed(failure.failure)
            } catch (_: Exception) {
                GitHubLoginState.Failed(GitHubDeviceFailure.NETWORK)
            }
        }
    }

    /** 关闭授权面板：仍在等待时停止轮询。 */
    fun closeGitHubLogin() {
        githubJob?.cancel()
        githubJob = null
        github.value = GitHubLoginState.Idle
    }

    fun delete(host: String) {
        viewModelScope.launch {
            operation.value = try {
                withContext(ioDispatcher) { store().delete(host) }
                GitCredentialOperation.Deleted(host)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                GitCredentialOperation.Error(GitCredentialErrorCode.DELETE_FAILED)
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GitCredentialSettingsViewModel(
                    { (application as InfinityApplication).gitCredentialStore },
                    githubLogin = (application as InfinityApplication).gitHubDeviceLogin,
                ) as T
        }
    }
}

package com.promenar.nexara.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.data.git.remote.GitCredential
import com.promenar.nexara.data.git.remote.GitCredentialHost
import com.promenar.nexara.data.git.remote.GitCredentialInputException
import com.promenar.nexara.data.git.remote.GitCredentialStore
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

/** 界面状态只含主机与用户名，令牌不进入 StateFlow。[credentials] 为 null 表示仍在读取。 */
data class GitCredentialUiState(
    val credentials: List<GitCredential>? = null,
    val operation: GitCredentialOperation = GitCredentialOperation.Idle,
)

/** [store] 在 [ioDispatcher] 上首次取用：凭据存储构造时要解密 Keystore 中的主机列表，不能放在主线程。 */
class GitCredentialSettingsViewModel(
    private val store: () -> GitCredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val credentials = MutableStateFlow<List<GitCredential>?>(null)
    private val operation = MutableStateFlow<GitCredentialOperation>(GitCredentialOperation.Idle)

    val state: StateFlow<GitCredentialUiState> = combine(credentials, operation) { list, current -> GitCredentialUiState(list, current) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GitCredentialUiState())

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
                GitCredentialSettingsViewModel({ (application as NexaraApplication).gitCredentialStore }) as T
        }
    }
}

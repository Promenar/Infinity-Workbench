package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.git.remote.GitCredential
import com.promenar.infinity.data.git.remote.GitCredentialStore
import com.promenar.infinity.data.git.remote.GitHubDeviceCode
import com.promenar.infinity.data.git.remote.GitHubDeviceFailure
import com.promenar.infinity.data.git.remote.GitHubDeviceFlowException
import com.promenar.infinity.data.git.remote.GitHubDeviceLogin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubLoginViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = object : GitCredentialStore {
        override val credentials: StateFlow<List<GitCredential>> = MutableStateFlow(emptyList())
        override fun save(host: String, username: String, token: ByteArray) = Unit
        override fun delete(host: String) = Unit
        override fun token(host: String): ByteArray? = null
    }

    /** 每次登录：先回调设备码，再等待测试给出结果（登录名或失败）。 */
    private class FakeLogin(override val configured: Boolean = true) : GitHubDeviceLogin {
        var calls = 0
        var cancelled = 0
        var result = CompletableDeferred<Result<String>>()
        override suspend fun login(onCode: (GitHubDeviceCode) -> Unit): String {
            calls++
            onCode(GitHubDeviceCode("device-secret", "WDJB-MJHT", "https://github.com/login/device", 900_000L, 5))
            try {
                return result.await().getOrThrow()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw e
            }
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(login: GitHubDeviceLogin?) = GitCredentialSettingsViewModel({ store }, dispatcher, login)

    @Test fun `未配置 client_id 时不提供入口，开始登录无效`() {
        val login = FakeLogin(configured = false)
        val model = viewModel(login)
        assertThat(model.state.value.githubLoginAvailable).isFalse()
        model.startGitHubLogin()
        assertThat(login.calls).isEqualTo(0)
        assertThat(model.state.value.githubLogin).isEqualTo(GitHubLoginState.Idle)
        assertThat(viewModel(null).state.value.githubLoginAvailable).isFalse()
    }

    @Test fun `显示设备码，授权后成功；状态不含设备密钥`() {
        val login = FakeLogin()
        val model = viewModel(login)
        assertThat(model.state.value.githubLoginAvailable).isTrue()
        model.startGitHubLogin()
        assertThat(model.state.value.githubLogin)
            .isEqualTo(GitHubLoginState.AwaitingUser("WDJB-MJHT", "https://github.com/login/device", 900_000L))
        assertThat(model.state.value.toString()).doesNotContain("device-secret")
        model.startGitHubLogin()
        assertThat(login.calls).isEqualTo(1)

        login.result.complete(Result.success("octocat"))
        assertThat(model.state.value.githubLogin).isEqualTo(GitHubLoginState.Success("octocat"))
    }

    @Test fun `失败按原因显示，重试重新取码`() {
        val login = FakeLogin()
        val model = viewModel(login)
        model.startGitHubLogin()
        login.result.complete(Result.failure(GitHubDeviceFlowException(GitHubDeviceFailure.EXPIRED)))
        assertThat(model.state.value.githubLogin).isEqualTo(GitHubLoginState.Failed(GitHubDeviceFailure.EXPIRED))

        login.result = CompletableDeferred()
        model.startGitHubLogin()
        assertThat(login.calls).isEqualTo(2)
        login.result.complete(Result.failure(GitHubDeviceFlowException(GitHubDeviceFailure.DENIED)))
        assertThat(model.state.value.githubLogin).isEqualTo(GitHubLoginState.Failed(GitHubDeviceFailure.DENIED))
    }

    @Test fun `关闭面板取消等待中的轮询`() {
        val login = FakeLogin()
        val model = viewModel(login)
        model.startGitHubLogin()
        model.closeGitHubLogin()
        assertThat(login.cancelled).isEqualTo(1)
        assertThat(model.state.value.githubLogin).isEqualTo(GitHubLoginState.Idle)
    }
}

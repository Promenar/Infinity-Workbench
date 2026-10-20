package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.git.remote.GitCredential
import com.promenar.nexara.data.git.remote.GitCredentialStore
import com.promenar.nexara.data.git.remote.SecretGitCredentialStore
import com.promenar.nexara.data.security.SecretId
import com.promenar.nexara.data.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitCredentialSettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val secrets = object : SecretStore {
        val values = linkedMapOf<String, ByteArray>()
        override fun put(id: SecretId, value: ByteArray) { values[id.value] = value.copyOf() }
        override fun get(id: SecretId): ByteArray? = values[id.value]?.copyOf()
        override fun contains(id: SecretId): Boolean = id.value in values
        override fun remove(id: SecretId) { values.remove(id.value) }
    }
    private val store = SecretGitCredentialStore(secrets)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(source: GitCredentialStore = store) = GitCredentialSettingsViewModel({ source }, dispatcher)

    @Test fun `保存后列表只含主机与用户名且清零输入数组`() {
        val model = viewModel()
        assertThat(model.state.value.credentials).isEmpty()
        val token = "nxt_SyntheticToken123".toCharArray()
        model.save("GitHub.com", "octocat", token)

        assertThat(token.all { it == '\u0000' }).isTrue()
        assertThat(model.state.value.credentials).containsExactly(GitCredential("github.com", "octocat"))
        assertThat(model.state.value.operation).isEqualTo(GitCredentialOperation.Saved("github.com"))
        assertThat(model.state.value.toString()).doesNotContain("nxt_SyntheticToken123")
        assertThat(store.token("github.com")!!.toString(Charsets.UTF_8)).isEqualTo("nxt_SyntheticToken123")
    }

    @Test fun `无效输入返回字段错误码`() {
        val model = viewModel()
        model.save("github.com/owner", "u", "t".toCharArray())
        assertThat(model.state.value.operation).isEqualTo(GitCredentialOperation.Error(GitCredentialErrorCode.INVALID_HOST))
        model.save("github.com", "a b", "t".toCharArray())
        assertThat(model.state.value.operation).isEqualTo(GitCredentialOperation.Error(GitCredentialErrorCode.INVALID_USERNAME))
        model.save("github.com", "u", "令牌".toCharArray())
        assertThat(model.state.value.operation).isEqualTo(GitCredentialOperation.Error(GitCredentialErrorCode.INVALID_TOKEN))
        assertThat(model.state.value.credentials).isEmpty()
    }

    @Test fun `删除`() {
        store.save("github.com", "octocat", "nxt_x".toByteArray())
        val model = viewModel()
        assertThat(model.state.value.credentials).hasSize(1)
        model.delete("github.com")
        assertThat(model.state.value.credentials).isEmpty()
        assertThat(model.state.value.operation).isEqualTo(GitCredentialOperation.Deleted("github.com"))
        assertThat(store.token("github.com")).isNull()
    }

    @Test fun `存储失败映射为保存失败`() {
        val failing = object : GitCredentialStore {
            override val credentials: StateFlow<List<GitCredential>> = store.credentials
            override fun save(host: String, username: String, token: ByteArray) = throw IllegalStateException("密钥 envelope 写入失败")
            override fun delete(host: String) = throw IllegalStateException("删除失败")
            override fun token(host: String): ByteArray? = null
        }
        val model = viewModel(failing)
        model.save("github.com", "u", "nxt_x".toCharArray())
        assertThat(model.state.value.operation).isEqualTo(GitCredentialOperation.Error(GitCredentialErrorCode.SAVE_FAILED))
        model.delete("github.com")
        assertThat(model.state.value.operation).isEqualTo(GitCredentialOperation.Error(GitCredentialErrorCode.DELETE_FAILED))
    }
}

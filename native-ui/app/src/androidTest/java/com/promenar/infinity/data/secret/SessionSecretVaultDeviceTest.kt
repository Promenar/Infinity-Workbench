package com.promenar.infinity.data.secret

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.secret.SecretSource
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在 ART 的 ICU 正则引擎上初始化会话保密库：ICU 要求未配对的 `}` 转义，桌面 JVM 不要求，
 * JVM 单元测试无法发现这类语法差异；保密库在应用启动时即被初始化，语法错误会让应用无法打开。
 */
@RunWith(AndroidJUnit4::class)
class SessionSecretVaultDeviceTest {
    @Test
    fun 变量引用的两种写法都只注入被引用的保密值() {
        val vault = SessionSecretVault()
        vault.put("s", "TEST_TOKEN", "令牌", SecretSource.UserInput, "synthetic-device-token".toByteArray())

        assertThat(vault.environment("s", "true")).isEmpty()
        assertThat(vault.environment("s", "echo \${TEST_TOKEN}")).containsEntry("TEST_TOKEN", "synthetic-device-token")
        assertThat(vault.environment("s", "echo \$TEST_TOKEN")).containsEntry("TEST_TOKEN", "synthetic-device-token")
    }
}

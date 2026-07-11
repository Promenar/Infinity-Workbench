package com.promenar.nexara.navigation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.CredentialUpdate
import com.promenar.nexara.data.model.toCredentialUpdate
import org.junit.jupiter.api.Test

class NavGraphCredentialUpdateTest {
    @Test
    fun `空白表单凭证映射为 Preserve`() {
        assertThat("".toCredentialUpdate()).isEqualTo(CredentialUpdate.Preserve)
        assertThat("   ".toCredentialUpdate()).isEqualTo(CredentialUpdate.Preserve)
    }

    @Test
    fun `非空表单凭证映射为 Replace`() {
        assertThat("fake-new-key".toCredentialUpdate())
            .isEqualTo(CredentialUpdate.Replace("fake-new-key"))
    }
}

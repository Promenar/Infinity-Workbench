package com.promenar.infinity.data.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.SessionHeaderIdentityStore
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SessionHeaderIdentityStoreTest {
    @Test
    fun `重建存储与并发请求复用持久安装盐`() = runTest {
        val directory = Files.createTempDirectory("infinity-session-salt").toFile()
        try {
            val values = (1..8).map { async { SessionHeaderIdentityStore(directory).identifier("conversation") } }.awaitAll()
            assertThat(values.toSet()).hasSize(1)
            assertThat(SessionHeaderIdentityStore(directory).identifier("conversation")).isEqualTo(values.first())
            assertThat(directory.listFiles()!!.map { it.name }).containsExactly("installation-salt")
        } finally {
            directory.deleteRecursively()
        }
    }
}

@file:Suppress("DEPRECATION")

package com.promenar.nexara.data.remote.webdav

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.UUID

class WebDavBackupClientRound2Test {
    private val config = WebDavConfig("https://dav.example.test/backups/", "user", "pass")

    @Test
    fun `body stall is bounded by get operation timeout and channel is cancelled`() = runBlocking {
        val stalled = ByteChannel(autoFlush = true)
        val client = client(
            MockEngine { respond(stalled, HttpStatusCode.OK) },
            WebDavTimeouts(500, 500, 30, 500, 500, 500, 100),
        )

        val error = runCatching {
            withTimeout(500) { client.download(config, backupName(1)) }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(WebDavException::class.java)
        assertThat(error!!.message).contains("下载超时")
        assertThat(stalled.isClosedForRead).isTrue()
    }

    @Test
    fun `outer shorter timeout is propagated instead of converted to webdav timeout`() = runTest {
        val client = client(
            MockEngine { awaitCancellation() },
            WebDavTimeouts(5_000, 5_000, 5_000, 5_000, 5_000, 5_000, 100),
        )

        val error = runCatching {
            withTimeout(25) { client.download(config, backupName(1)) }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(TimeoutCancellationException::class.java)
    }

    @Test
    fun `download never follows same origin path escape redirect with authorization`() = runTest {
        val requests = mutableListOf<Pair<String, String?>>()
        val engine = MockEngine { request ->
            requests += request.url.toString() to request.headers[HttpHeaders.Authorization]
            if (request.url.encodedPath == "/backups/${backupName(1)}") {
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://dav.example.test/private/secret"),
                )
            } else {
                respond(byteArrayOf(1), HttpStatusCode.OK)
            }
        }

        val error = runCatching { client(engine).download(config, backupName(1)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(WebDavException::class.java)
        assertThat(requests).hasSize(1)
        assertThat(requests.single().first).contains("/backups/")
    }

    @Test
    fun `download never follows cross origin or https downgrade redirect`() = runTest {
        for (location in listOf("https://other.example.test/steal", "http://dav.example.test/steal")) {
            val requests = mutableListOf<String>()
            val engine = MockEngine { request ->
                requests += request.url.toString()
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
            }

            val error = runCatching { client(engine).download(config, backupName(1)) }.exceptionOrNull()

            assertThat(error).isInstanceOf(WebDavException::class.java)
            assertThat(requests).hasSize(1)
        }
    }

    @Test
    fun `upload and prune keep zero is rejected before any request`() = runTest {
        var requests = 0
        val client = client(MockEngine { requests++; respond("", HttpStatusCode.OK) })

        val error = runCatching {
            client.uploadAndPrune(config, backupName(1), byteArrayOf(1), keep = 0)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(WebDavException::class.java)
        assertThat(requests).isEqualTo(0)
    }

    private fun client(
        engine: MockEngine,
        timeouts: WebDavTimeouts = WebDavTimeouts(500, 500, 500, 500, 500, 500, 100),
    ) = KtorWebDavBackupClient(
        engine,
        uuidFactory = { UUID(0, 11) },
        timeouts = timeouts,
    )

    private fun backupName(value: Long) = "nexara_backup_${value.toString().padStart(13, '0')}.nexara"
}

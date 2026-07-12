package com.promenar.nexara.data.remote.webdav

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WebDavBackupIdentityTest {
    private val config = WebDavConfig("https://dav.example.test/backups/", "u", "p")
    private val name = "nexara_backup_0000000000001.nexara"

    @Test
    fun `propfind explicitly requests getetag`() = runTest {
        var body = ""
        val client = KtorWebDavBackupClient(MockEngine { request ->
            body = (request.body as TextContent).text
            respond(multistatus(), HttpStatusCode.MultiStatus)
        })

        client.list(config)

        assertThat(body).contains("<d:getetag/>")
    }

    @Test
    fun `list keeps only strictly parsed strong etag as download identity`() = runTest {
        val xml = multistatus(
            response(name, "&quot;strong-1&amp;x&quot;"),
            response("nexara_backup_0000000000002.nexara", "W/&quot;weak&quot;"),
            response("nexara_backup_0000000000003.nexara", null),
            response("nexara_backup_0000000000004.nexara", "&quot;bad&#10;etag&quot;"),
        )
        val client = KtorWebDavBackupClient(MockEngine { respond(xml, HttpStatusCode.MultiStatus) })

        val listed = client.list(config)

        assertThat(listed.associate { it.fileName to it.strongEtag }).containsExactly(
            name, "\"strong-1&x\"",
            "nexara_backup_0000000000002.nexara", null,
            "nexara_backup_0000000000003.nexara", null,
            "nexara_backup_0000000000004.nexara", null,
        )
    }

    @Test
    fun `download sends if-match and accepts only matching strong response etag`() = runTest {
        var ifMatch: String? = null
        val selected = RemoteBackup(name, 3, 1, strongEtag = "\"identity-1\"")
        val client = KtorWebDavBackupClient(MockEngine { request ->
            ifMatch = request.headers[HttpHeaders.IfMatch]
            respond(
                ByteReadChannel(byteArrayOf(1, 2, 3)),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ETag, "\"identity-1\""),
            )
        })

        val bytes = client.download(config, selected)

        assertThat(ifMatch).isEqualTo("\"identity-1\"")
        assertThat(bytes).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `download rejects precondition failure and a replaced object returned as success`() = runTest {
        val selected = RemoteBackup(name, 3, 1, strongEtag = "\"identity-1\"")
        val precondition = KtorWebDavBackupClient(MockEngine {
            respond("", HttpStatusCode.PreconditionFailed)
        })
        val ignoredCondition = KtorWebDavBackupClient(MockEngine {
            respond(
                ByteReadChannel(byteArrayOf(9, 9, 9)),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ETag, "\"identity-2\""),
            )
        })

        val first = runCatching { precondition.download(config, selected) }.exceptionOrNull()
        val second = runCatching { ignoredCondition.download(config, selected) }.exceptionOrNull()

        assertThat(first).isInstanceOf(WebDavException::class.java)
        assertThat(first!!.message).contains("变化")
        assertThat(second).isInstanceOf(WebDavException::class.java)
        assertThat(second!!.message).contains("身份")
    }

    @Test
    fun `download without strong etag is rejected before GET`() = runTest {
        var requests = 0
        val client = KtorWebDavBackupClient(MockEngine { requests++; error("不应请求") })

        val failure = runCatching {
            client.download(config, RemoteBackup(name, 3, 1, strongEtag = null))
        }.exceptionOrNull()

        assertThat(requests).isEqualTo(0)
        assertThat(failure).isInstanceOf(WebDavException::class.java)
        assertThat(failure!!.message).contains("强 ETag")
    }

    private fun multistatus(vararg responses: String) =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">${responses.joinToString("")}</d:multistatus>"""

    private fun response(fileName: String, etag: String?): String = """
        <d:response><d:href>/backups/$fileName</d:href><d:propstat><d:prop>
          <d:resourcetype/><d:getcontentlength>3</d:getcontentlength>
          <d:getlastmodified>Wed, 02 Jul 2025 09:00:00 GMT</d:getlastmodified>
          ${etag?.let { "<d:getetag>$it</d:getetag>" }.orEmpty()}
        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
    """.trimIndent()
}

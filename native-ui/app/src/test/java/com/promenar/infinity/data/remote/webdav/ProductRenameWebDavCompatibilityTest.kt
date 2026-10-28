package com.promenar.infinity.data.remote.webdav

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProductRenameWebDavCompatibilityTest {
    @Test
    fun `混合新旧文件名按时间排序并排除伪造扩展名`() = runTest {
        val names = listOf("nexara_backup_1700000000002.nexara", "infinity_backup_1700000000003.iwb",
            "infinity_backup_1700000000001.iwb", "nexara_backup_1700000000004.iwb", "infinity_backup_123.iwb")
        val body = """<d:multistatus xmlns:d="DAV:">${names.joinToString("") { name ->
            """<d:response><d:href>/backups/$name</d:href><d:propstat><d:prop><d:getcontentlength>1</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
        }}</d:multistatus>"""
        val client = KtorWebDavBackupClient(MockEngine { respond(body, HttpStatusCode.MultiStatus) })
        assertThat(client.list(WebDavConfig("https://dav.example.test/backups/", "u", "p")).map { it.fileName })
            .containsExactly(names[1], names[0], names[2]).inOrder()
    }
}

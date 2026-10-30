package com.promenar.infinity.data.jobs

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.jobs.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test

class SftpTransferStoreTest {
    @Test fun `传输描述进度摘要和失败跨存储重开保持一致`() = runBlocking {
        val root = Files.createTempDirectory("sftp-store").toFile()
        try {
            val transfer = SftpTransferRecord("UPLOAD", "workspace", "/a.bin", "/remote/a.bin", 1234, 987, 456, "abc", "NETWORK")
            val record = BackgroundJobRecord("job-transfer", "s", "root", "SSH", "上传 a.bin → /remote/a.bin",
                startedAtMillis = 1, maxRuntimeMinutes = 120, onExit = "none", logWorkspacePath = null, runDirectory = "unused", remoteHost = "nas", transfer = transfer)
            BackgroundJobStore(root).put(record)
            val loaded = BackgroundJobStore(root).all().single()
            assertThat(loaded).isEqualTo(record)
            assertThat(loaded.job().transfer).isEqualTo(transfer.value())
            assertThat(loaded.job().transfer!!.failure).isEqualTo(SftpTransferFailure.NETWORK)
        } finally { root.deleteRecursively() }
    }
    @Test fun `缺少transfer的旧记录读成null`() {
        val old = """{"id":"legacy","sessionId":"s","workspaceRootUuid":"root","runner":"SSH","summary":"echo","startedAtMillis":1,"maxRuntimeMinutes":120,"onExit":"none","logWorkspacePath":null,"runDirectory":"unused"}"""
        assertThat(Json.decodeFromString<BackgroundJobRecord>(old).job().transfer).isNull()
    }
}

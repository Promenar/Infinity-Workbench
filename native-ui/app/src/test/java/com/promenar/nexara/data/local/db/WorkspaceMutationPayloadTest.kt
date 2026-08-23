package com.promenar.nexara.data.local.db

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayload
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadCodec
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadErrorCode
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadResult
import org.junit.Test

class WorkspaceMutationPayloadTest {
    @Test
    fun `当前版本 journal payload 可往返`() {
        val payload = WorkspaceMutationPayload(
            sourceRelativePath = "docs/a.md",
            targetRelativePath = ".recycle/docs/a.md",
            databaseTargetUuid = "file-1",
            expectedSha256 = "abc123",
        )

        val decoded = WorkspaceMutationPayloadCodec.decode(
            WorkspaceMutationPayloadCodec.CURRENT_VERSION,
            WorkspaceMutationPayloadCodec.encode(payload),
        )

        assertThat(decoded).isEqualTo(WorkspaceMutationPayloadResult.Valid(payload))
    }

    @Test
    fun `畸形或未知版本 journal payload 失败关闭`() {
        val malformed = WorkspaceMutationPayloadCodec.decode(1, "{\"sourceRelativePath\":")
        val unknown = WorkspaceMutationPayloadCodec.decode(99, "{}")

        assertThat((malformed as WorkspaceMutationPayloadResult.Invalid).error.code)
            .isEqualTo(WorkspaceMutationPayloadErrorCode.MALFORMED_PAYLOAD)
        assertThat((unknown as WorkspaceMutationPayloadResult.Invalid).error.code)
            .isEqualTo(WorkspaceMutationPayloadErrorCode.UNSUPPORTED_VERSION)
    }
}

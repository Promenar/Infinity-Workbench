package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import org.junit.jupiter.api.Test

class IndexStatusBadgeTest {

    @Test
    fun `文件索引状态使用双语资源而非硬编码标签`() {
        assertThat(FileIndexStatus.INDEXED.labelResource()).isEqualTo(R.string.rag_status_ready)
        assertThat(FileIndexStatus.INDEXING.labelResource()).isEqualTo(R.string.rag_status_indexing)
        assertThat(FileIndexStatus.STALE.labelResource()).isEqualTo(R.string.rag_status_pending)
        assertThat(FileIndexStatus.NOT_INDEXED.labelResource()).isEqualTo(R.string.rag_status_pending)
        assertThat(FileIndexStatus.FAILED.labelResource()).isEqualTo(R.string.rag_status_error)
    }

    @Test
    fun `知识图谱状态均映射到可理解的语义资源`() {
        assertThat(KgStatus.COMPLETED.descriptionResource()).isEqualTo(R.string.common_cd_success)
        assertThat(KgStatus.IN_PROGRESS.descriptionResource()).isEqualTo(R.string.rag_status_indexing)
        assertThat(KgStatus.FAILED.descriptionResource()).isEqualTo(R.string.common_cd_failed)
        assertThat(KgStatus.NOT_STARTED.descriptionResource()).isEqualTo(R.string.rag_status_pending)
    }
}

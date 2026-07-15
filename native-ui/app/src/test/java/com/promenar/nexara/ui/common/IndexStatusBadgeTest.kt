package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class IndexStatusBadgeTest {
    private val source by lazy {
        String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/common/IndexStatusBadge.kt"),
            ),
            Charsets.UTF_8,
        )
    }

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

    @Test
    fun `状态原语使用静态 Material 表达且不伪装为单选操作`() {
        assertThat(source).doesNotContain("rememberInfiniteTransition")
        assertThat(source).doesNotContain("RadioButtonUnchecked")
        assertThat(source).doesNotContain("RoundedCornerShape(50)")
        assertThat(source).contains("MaterialTheme.colorScheme")
        assertThat(source).contains("MaterialTheme.typography")
        assertThat(source).contains("stateDescription =")
    }

    @Test
    fun `就绪状态使用主色调且图谱进行中与待开始不只靠颜色区分`() {
        val indexedBlock = source
            .substringAfter("FileIndexStatus.INDEXED -> FileStatusVisuals(")
            .substringBefore("FileIndexStatus.INDEXING -> FileStatusVisuals(")
        val inProgressIcon = source
            .substringAfter("val icon = when (status) {")
            .substringAfter("KgStatus.IN_PROGRESS ->")
            .substringBefore("\n")
        val notStartedIcon = source
            .substringAfter("val icon = when (status) {")
            .substringAfter("KgStatus.NOT_STARTED ->")
            .substringBefore("\n")

        assertThat(indexedBlock).contains("containerColor = colors.primaryContainer")
        assertThat(inProgressIcon).contains("Icons.Rounded.Sync")
        assertThat(notStartedIcon).contains("Icons.Rounded.AccountTree")
        assertThat(inProgressIcon).isNotEqualTo(notStartedIcon)
    }
}

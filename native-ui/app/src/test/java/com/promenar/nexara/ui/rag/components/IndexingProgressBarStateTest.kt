package com.promenar.nexara.ui.rag.components

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class IndexingProgressBarStateTest {
    private val source by lazy {
        String(
            Files.readAllBytes(
                Path.of("app/src/main/java/com/promenar/nexara/ui/rag/components/IndexingProgressBar.kt"),
            ),
            Charsets.UTF_8,
        )
    }

    @Test
    fun `进度语义限制在合法范围`() {
        assertThat(resolveIndexingProgressAccessibility(progress = -1f, isError = false).progress).isEqualTo(0f)
        assertThat(resolveIndexingProgressAccessibility(progress = 2f, isError = false).progress).isEqualTo(1f)
    }

    @Test
    fun `错误使用紧急播报而普通状态使用礼貌播报`() {
        assertThat(resolveIndexingProgressAccessibility(progress = 0.5f, isError = true).assertive).isTrue()
        assertThat(resolveIndexingProgressAccessibility(progress = 0.5f, isError = false).assertive).isFalse()
    }

    @Test
    fun `进度状态不依赖自绘动画或固定微字号`() {
        assertThat(source).doesNotContain("animateFloatAsState")
        assertThat(source).doesNotContain("fontSize = 11.sp")
        assertThat(source).contains("LinearProgressIndicator(")
        assertThat(source).contains("MaterialTheme.colorScheme")
        assertThat(source).contains("MaterialTheme.typography")
        assertThat(source).contains("stateDescription = statusText")
    }

    @Test
    fun `可见状态文字百分比和内层进度不作为额外无障碍事实源`() {
        assertThat(source).contains("clearAndSetSemantics")
    }
}

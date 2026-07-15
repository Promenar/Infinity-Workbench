package com.promenar.nexara.ui.common

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UnifiedPromptEditorTest {
    @Test
    fun `空文本统计稳定且至少保留一个编辑行`() {
        assertThat(promptTextStatistics("")).isEqualTo(
            PromptTextStatistics(words = 0, lines = 1, characters = 0),
        )
    }

    @Test
    fun `中日韩字符逐字计数且拉丁数字连续段按词计数`() {
        assertThat(promptTextStatistics("hello world 你好世界 v2.0")).isEqualTo(
            PromptTextStatistics(words = 7, lines = 1, characters = 21),
        )
        assertThat(promptTextStatistics("第一行\nsecond-line").words).isEqualTo(4)
        assertThat(promptTextStatistics("こんにちは 한국어").words).isEqualTo(8)
    }

    @Test
    fun `仅内容发生变化时需要退出保护`() {
        assertThat(hasUnsavedPromptChanges("same", "same")).isFalse()
        assertThat(hasUnsavedPromptChanges("original", "changed")).isTrue()
    }

    @Test
    fun `保存契约等待结果且不再暴露伪sheet模式`() {
        val source = sourceFile().readText()

        assertThat(source).doesNotContain("SHEET")
        assertThat(source).doesNotContain("TODO: ModalBottomSheet")
        assertThat(source).contains("suspend (String) -> Result<Unit>")
        assertThat(source).doesNotContain("onSave(text)\n                    onDismiss()")
    }

    @Test
    fun `慢保存完成前保持进行中且成功只完成一次`() = runTest {
        val gate = PromptSaveGate()
        val persistence = CompletableDeferred<Result<Unit>>()
        var calls = 0

        assertThat(gate.tryStart()).isTrue()
        val saving = async {
            runPromptSave("changed") {
                calls++
                persistence.await()
            }
        }
        runCurrent()

        assertThat(gate.tryStart()).isFalse()
        assertThat(saving.isCompleted).isFalse()
        assertThat(calls).isEqualTo(1)
        persistence.complete(Result.success(Unit))
        assertThat(saving.await().isSuccess).isTrue()
        gate.finish()
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `仓库异常转换为失败结果并保留错误边界`() = runTest {
        val failure = runPromptSave("changed") {
            throw IllegalStateException("repository failed")
        }

        assertThat(failure.isFailure).isTrue()
        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `保存取消必须向上传播`() = runTest {
        var thrown: CancellationException? = null
        try {
            runPromptSave("changed") { throw CancellationException("cancelled") }
        } catch (cancelled: CancellationException) {
            thrown = cancelled
        }
        assertThat(thrown?.message).isEqualTo("cancelled")

        thrown = null
        try {
            runPromptSave("changed") {
                Result.failure(CancellationException("wrapped cancellation"))
            }
        } catch (cancelled: CancellationException) {
            thrown = cancelled
        }
        assertThat(thrown?.message).isEqualTo("wrapped cancellation")
    }

    @Test
    fun `保存门禁结束后允许重试`() {
        val gate = PromptSaveGate()

        assertThat(gate.tryStart()).isTrue()
        assertThat(gate.tryStart()).isFalse()
        gate.finish()
        assertThat(gate.tryStart()).isTrue()
    }

    @Test
    fun `行号画布不绑定第二个纵向滚动容器`() {
        val editorPane = sourceFile().readText()
            .substringAfter("fun EditorPane")
            .substringBefore("fun PreviewPane")
        val canvas = editorPane.substringAfter("Canvas(").substringBefore("BasicTextField(")

        assertThat(canvas).doesNotContain("verticalScroll(")
        assertThat(editorPane).contains("scrollState.value")
        assertThat(editorPane).contains("scrollState = scrollState")
        assertThat(editorPane).doesNotContain(".verticalScroll(scrollState)")
    }

    private fun sourceFile(): File {
        val root = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(
            root.resolve("app/src/main/java/com/promenar/nexara/ui/common/UnifiedPromptEditor.kt"),
            root.resolve("native-ui/app/src/main/java/com/promenar/nexara/ui/common/UnifiedPromptEditor.kt"),
        ).first { it.isFile }
    }
}

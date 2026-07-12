package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentReferenceExtractorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `UTF8文本按增量切块并保持重叠`() {
        val root = temporary.newFolder("root")
        root.resolve("note.txt").writeText("abcdefghijklmnop")

        val result = DocumentReferenceExtractor(chunkSize = 8, chunkOverlap = 2)
            .extract(entry(root.path, "note.txt", "text/plain"))

        assertThat(result.chunks).containsExactly("abcdefgh", "ghijklmn", "mnop").inOrder()
        assertThat(result.graphText).isEqualTo("abcdefghijklmnop")
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `非法UTF8文本失败关闭而不是替换字符继续索引`() {
        val root = temporary.newFolder("invalid")
        root.resolve("bad.txt").writeBytes(byteArrayOf(0x61, 0xC3.toByte(), 0x28))

        val failure = runCatching {
            DocumentReferenceExtractor(8, 2).extract(entry(root.path, "bad.txt", "text/plain"))
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
    }

    private fun entry(root: String, name: String, mime: String) = FileEntry(
        uuid = "file",
        workspaceRootUuid = "root",
        parentUuid = "root",
        name = name,
        hash = "hash",
        mimeType = mime,
        sizeBytes = java.io.File(root, name).length(),
        physicalRootPath = root,
        materializedPath = "/$name",
        createdAt = 1,
        updatedAt = 1,
    )
}

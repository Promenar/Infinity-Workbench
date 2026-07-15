package com.promenar.nexara.data.rag

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 发行门禁：真实文档二进制必须能通过生产解析路径提取稳定文本。 */
@RunWith(AndroidJUnit4::class)
class DocumentParserDeviceE2eTest {
    private lateinit var fixtureRoot: File

    @Before
    fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        fixtureRoot = File(targetContext.cacheDir, "document-parser-e2e-${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
    }

    @After
    fun tearDown() {
        fixtureRoot.deleteRecursively()
    }

    @Test
    fun realDocxBinaryUsesProductionPoiXmlBeansPath() {
        val file = copyFixture(DOCX_FIXTURE)

        val extracted = productionExtractor().extract(entry(file, DocumentReferenceExtractor.DOCX_MIME))

        assertThat(extracted.truncated).isFalse()
        assertThat(extracted.chunks).isNotEmpty()
        assertThat(extracted.graphText).contains("NEXARA_DOCX_PATH_OK")
        assertThat(extracted.graphText).contains("中文验证：发行解析链路正常。")
    }

    @Test
    fun realPdfBinaryUsesProductionPdfBoxPath() {
        val file = copyFixture(PDF_FIXTURE)

        val extracted = productionExtractor().extract(entry(file, "application/pdf"))

        assertThat(extracted.truncated).isFalse()
        assertThat(extracted.chunks).isNotEmpty()
        assertThat(extracted.graphText).contains("NEXARA_PDF_PATH_OK")
    }

    private fun productionExtractor() = DocumentReferenceExtractor(
        chunkSize = 4_096,
        chunkOverlap = 128,
    )

    private fun copyFixture(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destination = File(fixtureRoot, name.substringAfterLast('/'))
        instrumentation.context.assets.open(name).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        assertThat(destination.length()).isGreaterThan(0L)
        return destination
    }

    private fun entry(file: File, mimeType: String) = FileEntry(
        uuid = "fixture-${file.name}",
        workspaceRootUuid = "fixture-root",
        parentUuid = "fixture-root",
        name = file.name,
        hash = "fixture-hash",
        mimeType = mimeType,
        sizeBytes = file.length(),
        physicalRootPath = fixtureRoot.path,
        materializedPath = "/${file.name}",
        createdAt = 1,
        updatedAt = 1,
    )

    private companion object {
        const val DOCX_FIXTURE = "document-fixtures/release-parser-fixture.docx"
        const val PDF_FIXTURE = "document-fixtures/release-parser-fixture.pdf"
    }
}

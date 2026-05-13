package com.promenar.nexara.data.rag

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

class PdfExtractorTest {

    @Nested
    inner class Extract {

        @Test
        fun `returns failure when file cannot be opened`() {
            val context = mockk<Context>(relaxed = true)
            val uri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>(relaxed = true)

            every { context.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(uri) } returns null

            val result = PdfExtractor.extract(context, uri)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).isEqualTo("Cannot open PDF file")
        }

        @Test
        fun `returns failure when IOException is thrown`() {
            val context = mockk<Context>(relaxed = true)
            val uri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>(relaxed = true)

            every { context.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(uri) } throws IOException("Disk error")

            val result = PdfExtractor.extract(context, uri)

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        }

        @Test
        @DisplayName("Full PDF text extraction requires Apache PDFBox — degradation path must not crash")
        fun `degradation path does not crash on stub exception`() {
            val context = mockk<Context>(relaxed = true)
            val uri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>(relaxed = true)

            every { context.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(uri) } throws RuntimeException("Stub!")

            val result = PdfExtractor.extract(context, uri)

            assertThat(result.isFailure).isTrue()
        }
    }
}

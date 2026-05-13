package com.promenar.nexara.data.rag

import android.content.Context
import android.net.Uri
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

object PdfExtractor {

    data class PdfResult(
        val pageCount: Int,
        val text: String
    )

    fun extract(context: Context, uri: Uri): Result<PdfResult> {
        return try {
            val contentResolver = context.contentResolver
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(Exception("Cannot open PDF file"))

            val tempFile = File(context.cacheDir, "pdf_extract_${System.currentTimeMillis()}.pdf")
            try {
                tempFile.outputStream().use { it.write(bytes) }
                val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd.use { parcelFd ->
                    val renderer = PdfRenderer(parcelFd)
                    val pageCount = renderer.pageCount

                    // TODO: PDFBox integration for production use
                    // PdfRenderer only renders pages to Bitmap; it does not expose text layers.
                    // Currently returns page count only — actual text extraction requires:
                    //   implementation("com.tom-roush:pdfbox-android:2.0.27.0")
                    // Usage:
                    //   val document = PDDocument.load(inputStream)
                    //   val stripper = PDFTextStripper()
                    //   val text = stripper.getText(document)
                    //   document.close()

                    renderer.close()

                    Result.success(
                        PdfResult(
                            pageCount = pageCount,
                            text = "PDF document with $pageCount page(s). " +
                                "Full text extraction requires Apache PDFBox integration."
                        )
                    )
                }
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

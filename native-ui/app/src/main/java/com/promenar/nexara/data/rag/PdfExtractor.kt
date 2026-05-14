package com.promenar.nexara.data.rag

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

object PdfExtractor {

    data class PdfResult(val pageCount: Int, val text: String)

    fun extract(context: Context, uri: Uri): Result<PdfResult> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(Exception("Cannot open PDF file"))
            val document = PDDocument.load(inputStream)
            try {
                val pageCount = document.numberOfPages
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                val text = stripper.getText(document)
                if (text.isBlank()) {
                    Result.failure(Exception("PDF 可能为扫描件，无可提取文本层"))
                } else {
                    Result.success(PdfResult(pageCount = pageCount, text = text))
                }
            } finally { document.close() }
        } catch (e: Exception) { Result.failure(e) }
    }
}

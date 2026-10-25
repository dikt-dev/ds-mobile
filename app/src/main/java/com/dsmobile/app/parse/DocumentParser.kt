package com.dsmobile.app.parse

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

data class ParsedDocument(val name: String, val text: String)

object DocumentParser {
    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_CHARS = 30_000

    suspend fun parse(context: Context, uri: Uri): ParsedDocument = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: throw IllegalStateException("无法读取文件")
        if (bytes.size > MAX_BYTES) throw IllegalStateException("文件超过 20MB")

        val isPdf = name.endsWith(".pdf", true) ||
            context.contentResolver.getType(uri)?.contains("pdf", true) == true
        val ext = name.substringAfterLast('.', "").lowercase()
        val text = if (ext in setOf("docx", "xlsx", "pptx", "zip")) {
            OfficeParser.parse(name, bytes)
        } else if (isPdf) {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(bytes).use { doc ->
                PDFTextStripper().getText(doc)
            }
        } else {
            decodeText(bytes)
        }.trim()

        if (text.isEmpty()) {
            throw IllegalStateException(
                if (isPdf) "PDF 没有可提取文字（扫描件 OCR 后续支持）" else "文件没有可提取文字"
            )
        }
        ParsedDocument(name, text.take(MAX_CHARS))
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx) ?: "文件"
                }
            }
        return uri.lastPathSegment ?: "文件"
    }

    private fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        val bad = utf8.count { it == '\uFFFD' }
        return if (bad > utf8.length / 20) {
            runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
        } else {
            utf8
        }
    }
}

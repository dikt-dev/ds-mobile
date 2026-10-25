package com.dsmobile.app.parse

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 阶段 0 的本地解析 spike：
 * - 生成并解析 50 页 PDF（PDFBox-Android）
 * - 生成并解析 100 条目 ZIP（java.util.zip）
 * 只用于验证 K60 不 OOM、耗时可接受。
 */
object ParseSpike {

    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        try {
            PDFBoxResourceLoader.init(context.applicationContext)
            val dir = context.filesDir
            val pdf = File(dir, "spike-50p.pdf")
            val zip = File(dir, "spike-100.zip")

            val pdfCreate = System.currentTimeMillis()
            createPdf(pdf, 50)
            val pdfRead = System.currentTimeMillis()
            val chars = PDDocument.load(pdf).use { doc ->
                PDFTextStripper().getText(doc).length
            }
            val pdfEnd = System.currentTimeMillis()

            val zipCreate = System.currentTimeMillis()
            createZip(zip, 100, 64 * 1024)
            val zipRead = System.currentTimeMillis()
            var entries = 0
            var bytes = 0L
            ZipFile(zip).use { zf ->
                val e = zf.entries()
                while (e.hasMoreElements()) {
                    val entry = e.nextElement()
                    entries++
                    bytes += zf.getInputStream(entry).use { it.readBytes().size }
                }
            }
            val zipEnd = System.currentTimeMillis()

            "PDF: 50 页, $chars 字符, 生成 ${pdfRead - pdfCreate}ms, 解析 ${pdfEnd - pdfRead}ms\n" +
                "ZIP: $entries 条目, ${bytes / 1024}KB, 生成 ${zipRead - zipCreate}ms, 解析 ${zipEnd - zipRead}ms\n" +
                "文件: ${pdf.length() / 1024}KB / ${zip.length() / 1024}KB"
        } catch (e: Throwable) {
            "解析失败: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun createPdf(file: File, pages: Int) {
        PDDocument().use { doc ->
            val font = PDType1Font.HELVETICA
            repeat(pages) { i ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 12f)
                    cs.newLineAtOffset(50f, 720f)
                    cs.showText("DS-Mobile PDF spike page ${i + 1} / $pages")
                    cs.endText()
                }
            }
            doc.save(file)
        }
    }

    private fun createZip(file: File, entries: Int, size: Int) {
        val payload = ByteArray(size) { ('a'.code + it % 26).toByte() }
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            repeat(entries) { i ->
                zos.putNextEntry(ZipEntry("docs/sample-${i + 1}.txt"))
                zos.write(payload)
                zos.closeEntry()
            }
        }
    }
}

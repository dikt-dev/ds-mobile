package com.dsmobile.app.parse

import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.ByteArrayOutputStream
import java.io.File

/** RAR/7z 归档解析：解出可解析的文本/Office 文件，再交给 OfficeParser。 */
object ArchiveParser {
    private const val MAX_ENTRY = 8 * 1024 * 1024
    private val supported = setOf("txt", "md", "csv", "json", "docx", "xlsx", "pptx")

    fun parseRar(bytes: ByteArray): String {
        val tmp = File.createTempFile("ds-rar-", ".rar")
        return try {
            tmp.writeBytes(bytes)
            val archive = Archive(tmp)
            try {
                val sb = StringBuilder("【RAR 内容】\n")
                var count = 0
                for (header in archive.fileHeaders) {
                    if (header.isDirectory) continue
                    val name = header.fileName.replace('\\', '/')
                    if (name.substringAfterLast('.', "").lowercase() !in supported) continue
                    if (count++ >= 50) break
                    val out = ByteArrayOutputStream()
                    archive.extractFile(header, out)
                    appendEntry(sb, name, out.toByteArray())
                    if (sb.length > 30_000) return sb.substring(0, 30_000)
                }
                if (count == 0) throw IllegalStateException("RAR 里没有可解析的文本/Office 文件")
                sb.toString().trim()
            } finally {
                archive.close()
            }
        } finally {
            tmp.delete()
        }
    }

    fun parse7z(bytes: ByteArray): String {
        val tmp = File.createTempFile("ds-7z-", ".7z")
        return try {
            tmp.writeBytes(bytes)
            SevenZFile.builder().setFile(tmp).get().use { archive ->
                val sb = StringBuilder("【7z 内容】\n")
                var count = 0
                var entry = archive.nextEntry
                while (entry != null && count < 50) {
                    val name = entry.name.replace('\\', '/')
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (!entry.isDirectory && ext in supported) {
                        val out = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = archive.read(chunk)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_ENTRY) break
                            out.write(chunk, 0, n)
                        }
                        appendEntry(sb, name, out.toByteArray())
                        count++
                        if (sb.length > 30_000) return sb.substring(0, 30_000)
                    }
                    entry = archive.nextEntry
                }
                if (count == 0) throw IllegalStateException("7z 里没有可解析的文本/Office 文件")
                sb.toString().trim()
            }
        } finally {
            tmp.delete()
        }
    }

    private fun appendEntry(sb: StringBuilder, name: String, data: ByteArray) {
        sb.append("\n=== $name ===\n")
        val ext = name.substringAfterLast('.', "").lowercase()
        val text = when (ext) {
            "docx" -> OfficeParser.parse(name, data)
            "xlsx" -> OfficeParser.parse(name, data)
            "pptx" -> OfficeParser.parse(name, data)
            else -> String(data, Charsets.UTF_8)
        }
        sb.append(text.take(6000))
    }
}

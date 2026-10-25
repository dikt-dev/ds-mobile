package com.dsmobile.app.parse

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** 轻量 OOXML 文本提取：DOCX / XLSX / PPTX / ZIP，不依赖 Apache POI。 */
object OfficeParser {
    private const val MAX_ENTRY = 8 * 1024 * 1024
    private val sheetRe = Regex("""xl/worksheets/sheet(\d+)\.xml""")
    private val slideRe = Regex("""ppt/slides/slide(\d+)\.xml""")

    fun parse(name: String, bytes: ByteArray): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "docx" -> parseDocx(bytes)
            "xlsx" -> parseXlsx(bytes)
            "pptx" -> parsePptx(bytes)
            "zip" -> parseZip(bytes)
            "rar" -> ArchiveParser.parseRar(bytes)
            "7z" -> ArchiveParser.parse7z(bytes)
            else -> throw IllegalArgumentException("暂不支持 .$ext")
        }
    }

    private fun parseDocx(bytes: ByteArray): String {
        val xml = readEntries(bytes) { it == "word/document.xml" }.firstOrNull()?.second
            ?: throw IllegalStateException("DOCX 缺少 document.xml")
        val sb = StringBuilder()
        val parser = newParser(xml)
        var inText = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':') ?: ""
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (tag == "t") inText = true
                XmlPullParser.TEXT -> if (inText) sb.append(parser.text)
                XmlPullParser.END_TAG -> when (tag) {
                    "t" -> inText = false
                    "p", "tr" -> sb.append('\n')
                }
            }
            parser.next()
        }
        return sb.toString().trim()
    }

    private fun parseXlsx(bytes: ByteArray): String {
        val entries = readEntries(bytes) {
            it == "xl/sharedStrings.xml" || sheetRe.matches(it)
        }
        val shared = entries.firstOrNull { it.first == "xl/sharedStrings.xml" }
            ?.second?.let { parseSharedStrings(it) } ?: emptyList()
        val sheets = entries.filter { sheetRe.matches(it.first) }
            .sortedBy { sheetRe.matchEntire(it.first)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        if (sheets.isEmpty()) throw IllegalStateException("XLSX 没有工作表")
        val sb = StringBuilder()
        sheets.forEachIndexed { idx, (name, xml) ->
            if (idx >= 20) return@forEachIndexed
            sb.append("【Sheet ${idx + 1}】\n")
            parseSheet(xml, shared, sb)
            sb.append('\n')
            if (sb.length > 30_000) return sb.substring(0, 30_000)
        }
        return sb.toString().trim()
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val parser = newParser(xml)
        var inText = false
        val current = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current.clear()
                    "t" -> inText = true
                }
                XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> out.add(current.toString())
                }
            }
            parser.next()
        }
        return out
    }

    private fun parseSheet(xml: ByteArray, shared: List<String>, sb: StringBuilder) {
        val parser = newParser(xml)
        var inValue = false
        var inInline = false
        var cellType = ""
        var value = StringBuilder()
        var rows = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT && rows < 2000) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                        rows++
                    }
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t") ?: ""
                        value = StringBuilder()
                    }
                    "v" -> inValue = true
                    "is" -> inInline = true
                    "t" -> if (inInline) inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inValue = false
                    "t" -> inValue = false
                    "is" -> inInline = false
                    "c" -> {
                        val raw = value.toString()
                        val out = if (cellType == "s") {
                            shared.getOrNull(raw.toIntOrNull() ?: -1) ?: raw
                        } else raw
                        if (out.isNotBlank()) sb.append(out).append('\t')
                    }
                    "row" -> sb.append('\n')
                }
            }
            parser.next()
        }
    }

    private fun parsePptx(bytes: ByteArray): String {
        val slides = readEntries(bytes) { slideRe.matches(it) }
            .sortedBy { slideRe.matchEntire(it.first)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        if (slides.isEmpty()) throw IllegalStateException("PPTX 没有幻灯片")
        val sb = StringBuilder()
        slides.forEachIndexed { idx, (_, xml) ->
            sb.append("【Slide ${idx + 1}】\n")
            val parser = newParser(xml)
            var inText = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> if (parser.name?.substringAfter(':') == "t") inText = true
                    XmlPullParser.TEXT -> if (inText) sb.append(parser.text).append(' ')
                    XmlPullParser.END_TAG -> if (parser.name?.substringAfter(':') == "t") inText = false
                }
                parser.next()
            }
            sb.append("\n\n")
            if (sb.length > 30_000) return sb.substring(0, 30_000)
        }
        return sb.toString().trim()
    }

    private fun parseZip(bytes: ByteArray): String {
        val entries = readEntries(bytes) {
            val ext = it.substringAfterLast('.', "").lowercase()
            ext in setOf("txt", "md", "csv", "json", "docx", "xlsx", "pptx")
        }
        if (entries.isEmpty()) throw IllegalStateException("ZIP 里没有可解析的文本/Office 文件")
        val sb = StringBuilder("【压缩包内容】\n")
        entries.take(50).forEach { (name, data) ->
            sb.append("\n=== $name ===\n")
            val ext = name.substringAfterLast('.', "").lowercase()
            val text = when (ext) {
                "docx" -> parseDocx(data)
                "xlsx" -> parseXlsx(data)
                "pptx" -> parsePptx(data)
                else -> String(data, Charsets.UTF_8)
            }
            sb.append(text.take(5000))
            if (sb.length > 30_000) return sb.substring(0, 30_000)
        }
        return sb.toString().trim()
    }

    private fun newParser(bytes: ByteArray): XmlPullParser =
        Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), null) }

    private fun readEntries(
        bytes: ByteArray,
        include: (String) -> Boolean,
    ): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                if (!include(name)) continue
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = zis.read(chunk)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_ENTRY) break
                    buffer.write(chunk, 0, n)
                }
                out.add(name to buffer.toByteArray())
                if (out.size >= 100) break
            }
        }
        return out
    }
}

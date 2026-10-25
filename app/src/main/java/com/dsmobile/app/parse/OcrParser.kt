package com.dsmobile.app.parse

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 扫描版 PDF：逐页渲染成 Bitmap，再用 ML Kit 中文 OCR。 */
object OcrParser {
    private const val MAX_PAGES = 10
    private const val DPI = 150f

    suspend fun ocrPdf(context: Context, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
        try {
            PDDocument.load(bytes).use { doc ->
                val renderer = PDFRenderer(doc)
                val sb = StringBuilder()
                val pages = minOf(doc.numberOfPages, MAX_PAGES)
                for (i in 0 until pages) {
                    val bitmap: Bitmap = renderer.renderImageWithDPI(i, DPI)
                    try {
                        val image = InputImage.fromBitmap(bitmap, 0)
                        val result = Tasks.await(recognizer.process(image))
                        if (result.text.isNotBlank()) {
                            sb.append(result.text.trim()).append("\n\n")
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    if (sb.length > 30_000) break
                }
                sb.toString().trim()
            }
        } finally {
            recognizer.close()
        }
    }
}

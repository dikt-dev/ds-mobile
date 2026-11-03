package com.dsmobile.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint

/**
 * 把动图抽成九宫格静图给模型看。
 *
 * 只发首帧等于只发了张图片：她能看到"谁、拿着什么"，但看不到"怎么动的"。
 * 九帧按时间排开，苹果冒出来、被瞪回去、碗被翻过来这种过程就读得出来了。
 *
 * 用 [Movie] 而不是 [android.graphics.drawable.AnimatedImageDrawable]：
 * 后者只能播，拿不到任意时刻的帧。Movie 虽然标了 deprecated，但解 GIF 帧这一步
 * 一直是它最稳，其它方案要么要多带一个解码库，要么得自己写 GIF 解析。
 */
fun gifContactSheet(bytes: ByteArray, cols: Int = 3, rows: Int = 3): Bitmap? {
    val movie = runCatching { Movie.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
    val srcW = movie.width()
    val srcH = movie.height()
    if (srcW <= 0 || srcH <= 0) return null

    val count = cols * rows
    val duration = movie.duration().let { if (it > 0) it else 1000 }
    val frames = (0 until count).mapNotNull { i ->
        val bmp = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        movie.setTime((duration.toLong() * i / count).toInt())
        movie.draw(c, 0f, 0f)
        bmp
    }
    return contactSheet(frames, cols, rows, "同一张动图，按时间从左到右、从上到下")
}

/**
 * 把一组画面拼成一张宫格图（动图、视频共用）。
 *
 * 单发一张宫格的好处是"过程"能一眼看全；坏处是每格变小、细节糊。
 * 所以视频那边是「12 张原帧 + 1 张宫格」一起发：细节看单帧，先后看宫格。
 */
fun contactSheet(
    frames: List<Bitmap>,
    cols: Int,
    rows: Int,
    header: String,
    totalWidth: Int = 1000,
): Bitmap? {
    if (frames.isEmpty()) return null
    val srcW = frames[0].width
    val srcH = frames[0].height
    if (srcW <= 0 || srcH <= 0) return null

    // 整张图控制在 ~1000px 宽：再大模型也是缩放，白白多传几百 KB。
    val cellW = (totalWidth / cols).coerceAtLeast(160)
    val cellH = (cellW.toFloat() * srcH / srcW).toInt().coerceAtLeast(1)
    val headerH = if (header.isBlank()) 0 else 34

    val sheet = Bitmap.createBitmap(cellW * cols, cellH * rows + headerH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet)
    canvas.drawColor(Color.WHITE)

    val text = Paint().apply {
        color = Color.rgb(80, 80, 80)
        textSize = 22f
        isAntiAlias = true
    }
    if (headerH > 0) canvas.drawText(header, 12f, 24f, text)

    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    for (i in frames.indices) {
        if (i >= cols * rows) break
        val dst = android.graphics.Rect(
            (i % cols) * cellW,
            headerH + (i / cols) * cellH,
            (i % cols) * cellW + cellW,
            headerH + (i / cols) * cellH + cellH,
        )
        canvas.drawBitmap(frames[i], null, dst, paint)
    }
    // 分隔线：格子贴在一起时，模型容易把两格看成一格
    val linePaint = Paint().apply {
        color = Color.rgb(225, 225, 225)
        strokeWidth = 2f
    }
    for (c in 1 until cols) canvas.drawLine((c * cellW).toFloat(), headerH.toFloat(), (c * cellW).toFloat(), sheet.height.toFloat(), linePaint)
    for (r in 1 until rows) canvas.drawLine(0f, (headerH + r * cellH).toFloat(), sheet.width.toFloat(), (headerH + r * cellH).toFloat(), linePaint)

    return sheet
}

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

    // 整张图控制在 ~1000px 宽：再大模型也是缩放，白白多传几百 KB。
    val cellW = (1000 / cols).coerceAtLeast(160)
    val cellH = (cellW.toFloat() * srcH / srcW).toInt().coerceAtLeast(1)
    val header = 34

    val sheet = Bitmap.createBitmap(cellW * cols, cellH * rows + header, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet)
    canvas.drawColor(Color.WHITE)

    val text = Paint().apply {
        color = Color.rgb(80, 80, 80)
        textSize = 22f
        isAntiAlias = true
    }
    canvas.drawText("同一张动图，按时间从左到右、从上到下", 12f, 24f, text)

    val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    val scale = cellW.toFloat() / srcW
    for (i in 0 until count) {
        movie.setTime((duration.toLong() * i / count).toInt())
        canvas.save()
        canvas.translate((i % cols) * cellW.toFloat(), (header + (i / cols) * cellH).toFloat())
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f)
        canvas.restore()
    }
    // 分隔线：九格贴在一起时，模型容易把两格看成一格
    val linePaint = Paint().apply {
        color = Color.rgb(225, 225, 225)
        strokeWidth = 2f
    }
    for (c in 1 until cols) canvas.drawLine((c * cellW).toFloat(), header.toFloat(), (c * cellW).toFloat(), sheet.height.toFloat(), linePaint)
    for (r in 1 until rows) canvas.drawLine(0f, (header + r * cellH).toFloat(), sheet.width.toFloat(), (header + r * cellH).toFloat(), linePaint)

    return sheet
}

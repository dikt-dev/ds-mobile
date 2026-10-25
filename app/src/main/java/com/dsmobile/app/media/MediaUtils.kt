package com.dsmobile.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object MediaUtils {
    /** 读取相册图片，缩到最长边 1600，JPEG 82%，返回 data URL。 */
    suspend fun imageToDataUrl(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val original = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: throw IllegalStateException("无法读取图片")

        val maxSide = 1600
        val scale = minOf(1f, maxSide.toFloat() / maxOf(original.width, original.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            original
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (bitmap !== original) bitmap.recycle()
        original.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

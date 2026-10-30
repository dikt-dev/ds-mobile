package com.dsmobile.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayOutputStream

/**
 * App 内拍照：用系统 Camera1 API，不依赖系统相机 App。
 *
 * 为什么不用 CameraX：项目要能离线编译，不想为拍照引三个新依赖；
 * Camera1 虽然 deprecated，但 SurfaceView + takePicture 足够稳，坑也都踩过。
 * 默认后置相机（拍东西/拍屏幕），拍完直接把 JPEG 交给调用方上传。
 */
@Composable
fun CameraCaptureOverlay(onCaptured: (ByteArray) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val session = remember { CameraSession(context) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { session.close() }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                ready = session.open(holder)
                                if (!ready) error = "相机打不开，可能被别的 App 占用了"
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                session.close()
                                ready = false
                            }
                        })
                    }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "取消", tint = Color.White)
                }
                Text(
                    "拍一张",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            if (error.isNotEmpty()) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFB4B4),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (busy) "正在保存…" else "点一下拍，拍完直接发出",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xCCFFFFFF),
                )
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(if (ready && !busy) Color.White else Color(0x55FFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(
                        enabled = ready && !busy,
                        onClick = {
                            busy = true
                            session.shoot { bytes ->
                                busy = false
                                if (bytes == null || bytes.isEmpty()) {
                                    error = "拍照失败，再试一次"
                                } else {
                                    onCaptured(bytes)
                                }
                            }
                        },
                        modifier = Modifier.size(74.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF6C5CE7)),
                        )
                    }
                }
            }
        }
    }
}

/** Camera1 的薄封装：只管打开/预览/拍一张/释放。 */
private class CameraSession(private val context: Context) {
    private var camera: Camera? = null

    fun open(holder: SurfaceHolder): Boolean = runCatching {
        val id = backCameraId()
        val c = Camera.open(id)
        c.setPreviewDisplay(holder)
        val info = Camera.CameraInfo().also { Camera.getCameraInfo(id, it) }
        c.setDisplayOrientation(previewDegrees(info.orientation, displayRotation()))
        val params = c.parameters
        params.supportedPictureSizes?.maxByOrNull { it.width.toLong() * it.height }?.let {
            params.setPictureSize(it.width, it.height)
        }
        // 预览尺寸挑最小的一档：只求能看，省解码
        params.supportedPreviewSizes?.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height) }?.let {
            params.setPreviewSize(it.width, it.height)
        }
        if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
        }
        params.jpegQuality = 90
        c.parameters = params
        c.startPreview()
        camera = c
        true
    }.getOrElse { false }

    fun shoot(onResult: (ByteArray?) -> Unit) {
        val c = camera ?: return onResult(null)
        runCatching {
            c.takePicture(null, null) { data, _ ->
                onResult(data?.let { straighten(it, c) })
            }
        }.onFailure { onResult(null) }
    }

    fun close() {
        camera?.let { c ->
            runCatching { c.stopPreview() }
            runCatching { c.release() }
        }
        camera = null
    }

    private fun backCameraId(): Int {
        var id = 0
        for (i in 0 until Camera.getNumberOfCameras()) {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                id = i
                break
            }
        }
        return id
    }

    private fun displayRotation(): Int = runCatching {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)

    private fun previewDegrees(orientation: Int, rotation: Int): Int {
        val rotationDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (orientation - rotationDegrees + 360) % 360
    }

    /** 传感器拍出来是"躺着"的，按预览角度转正再上传。 */
    private fun straighten(jpeg: ByteArray, c: Camera): ByteArray = runCatching {
        val info = Camera.CameraInfo().also { Camera.getCameraInfo(backCameraId(), it) }
        val degrees = previewDegrees(info.orientation, displayRotation())
        if (degrees == 0) return jpeg
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        ByteArrayOutputStream().use { out ->
            rotated.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }.getOrDefault(jpeg)
}
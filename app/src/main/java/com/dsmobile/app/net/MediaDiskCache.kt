package com.dsmobile.app.net

import java.io.File
import java.security.MessageDigest

/** 简单的媒体磁盘缓存：跨 App 重启保留图片/GIF 字节。 */
class MediaDiskCache(
    private val dir: File,
    private val maxBytes: Long = 1024L * 1024 * 1024,
    private val maxFiles: Int = 1000,
) {
    init {
        runCatching { dir.mkdirs() }
    }

    fun get(key: String): ByteArray? {
        val file = file(key)
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun put(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > 24 * 1024 * 1024) return
        runCatching {
            val file = file(key)
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
            trim()
        }
    }

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(40)
        return File(dir, digest)
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        if (count <= maxFiles && total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (count <= maxFiles && total <= maxBytes) return
            val len = file.length()
            if (file.delete()) {
                total -= len
                count--
            }
        }
    }
}

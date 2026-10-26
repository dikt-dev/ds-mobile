package com.dsmobile.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_messages")
data class PendingMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 服务端幂等键：重试必须复用同一个值 */
    val clientMessageId: String = "",
    /** 本地待上传附件：图片/视频/文件；上传成功后由服务端消息替换 */
    val localUri: String? = null,
    val attachmentKind: String? = null,
    val attachmentName: String? = null,
)

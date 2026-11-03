package com.dsmobile.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: Long,
    val role: String,
    val content: String,
    val channel: String,
    val createdAt: String,
    val turnId: Long? = null,
    val proactiveKind: String? = null,
    val clientMessageId: String? = null,
    /** 回复的是哪条用户消息；补发的旧消息靠它挂回原位。 */
    val replyToId: Long? = null,
)

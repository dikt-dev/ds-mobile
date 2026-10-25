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
)

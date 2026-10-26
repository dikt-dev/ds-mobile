package com.dsmobile.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    // 本地 Room 就是聊天记录缓存；不再只取 300 条，避免往上翻时缺历史。
    @Query("SELECT * FROM messages ORDER BY id ASC")
    fun observe(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("SELECT COALESCE(MAX(id), 0) FROM messages")
    suspend fun maxId(): Long

    @Query("SELECT COALESCE(MIN(id), 0) FROM messages")
    suspend fun minId(): Long

    @Query("SELECT id FROM messages")
    suspend fun allIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPending(message: PendingMessageEntity): Long

    @Query("SELECT * FROM pending_messages ORDER BY id ASC")
    fun observePending(): Flow<List<PendingMessageEntity>>

    @Delete
    suspend fun deletePending(message: PendingMessageEntity)

    @Query("DELETE FROM pending_messages WHERE clientMessageId IN (:ids)")
    suspend fun deletePendingByClientIds(ids: List<String>)

    /**
     * 服务端消息落库与 pending 清理放在同一个 Room transaction：
     * 避免轮询先插入服务端 user 行时，本地 pending 还留着，短暂出现两条自己的消息。
     */
    @Transaction
    suspend fun applyServerMessages(messages: List<MessageEntity>) {
        insertAll(messages)
        val ids = messages.mapNotNull { it.clientMessageId }.distinct()
        if (ids.isNotEmpty()) deletePendingByClientIds(ids)
    }
}

package com.dsmobile.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPending(message: PendingMessageEntity): Long

    @Query("SELECT * FROM pending_messages ORDER BY id ASC")
    fun observePending(): Flow<List<PendingMessageEntity>>

    @Delete
    suspend fun deletePending(message: PendingMessageEntity)
}

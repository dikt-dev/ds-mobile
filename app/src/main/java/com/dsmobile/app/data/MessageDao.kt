package com.dsmobile.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM (SELECT * FROM messages ORDER BY id DESC LIMIT 300) ORDER BY id ASC")
    fun observe(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("SELECT COALESCE(MAX(id), 0) FROM messages")
    suspend fun maxId(): Long
}

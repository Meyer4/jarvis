package com.jarvis.ai.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Stores one user or assistant message in the on-device conversation history. */
@Entity(
    tableName = "conversation_messages",
    indices = [Index(value = ["createdAtMillis"])],
)
data class ConversationMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

/** Room accessors for persistent, device-local conversation memory. */
@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(message: ConversationMessage): Long

    @Insert
    suspend fun insertAll(messages: List<ConversationMessage>)

    @Query("SELECT * FROM conversation_messages ORDER BY createdAtMillis DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ConversationMessage>

    @Query("SELECT * FROM conversation_messages ORDER BY createdAtMillis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ConversationMessage>>

    @Query("DELETE FROM conversation_messages")
    suspend fun clear()
}

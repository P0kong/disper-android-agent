package com.example.aiuseagent.data

import androidx.room.withTransaction
import com.example.aiuseagent.data.local.AppDatabase
import com.example.aiuseagent.data.local.GenerationDao

/** Production supplies a real Room transaction; tests may supply a rollback-capable fake. */
interface GenerationPersistence {
    val generations: GenerationDao
    val attachments:com.example.aiuseagent.data.local.AttachmentDao? get()=null
    val attachmentObjects:com.example.aiuseagent.data.attachment.AttachmentObjectPersistence? get()=null
    suspend fun transaction(block: suspend () -> Unit)
}

class RoomGenerationPersistence(private val database: AppDatabase) : GenerationPersistence {
    override val attachmentObjects=com.example.aiuseagent.data.attachment.RoomAttachmentCatalog(database)
    override val generations: GenerationDao get() = database.generationDao()
    override val attachments:com.example.aiuseagent.data.local.AttachmentDao get()=database.attachmentDao()
    override suspend fun transaction(block: suspend () -> Unit) = database.withTransaction { block() }
}

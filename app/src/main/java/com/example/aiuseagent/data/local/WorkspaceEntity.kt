package com.example.aiuseagent.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "workspaces")
data class WorkspaceEntity(@PrimaryKey val id: String, val name: String, val treeUri: String,
    val createdAt: Long) {
    override fun toString(): String = "WorkspaceEntity(id=$id)"
}

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY createdAt ASC") suspend fun list(): List<WorkspaceEntity>
    @Query("SELECT * FROM workspaces WHERE id = :id") suspend fun findById(id: String): WorkspaceEntity?
    @Insert suspend fun insert(workspace: WorkspaceEntity)
    @Query("DELETE FROM workspaces WHERE id = :id") suspend fun delete(id: String)
}

package com.example.aiuseagent.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, MessageEntity::class, GenerationEntity::class,
        ToolRoundEntity::class, WorkspaceEntity::class,ConnectionProfileEntity::class,
        AttachmentEntity::class,OperationJournalEntity::class,AttachmentObjectEntity::class,AttachmentObjectReferenceEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun generationDao(): GenerationDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun connectionProfileDao(): ConnectionProfileDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun attachmentObjectDao(): AttachmentObjectDao
    abstract fun operationJournalDao(): OperationJournalDao

    companion object {
        /** 数据库文件名（公开给诊断页展示路径，避免两处写死字符串）。 */
        const val NAME = "ai-use-agent.db"

        /** v1 stays intact. No old message is assigned an inferred provider or workspace. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN workspaceId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT NOT NULL DEFAULT 'COMPLETED'")
                db.execSQL("ALTER TABLE messages ADD COLUMN providerType TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN generationId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToMessageId INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS workspaces (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, treeUri TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS generations (id TEXT NOT NULL PRIMARY KEY, sessionId INTEGER NOT NULL, userMessageId INTEGER NOT NULL, assistantMessageId INTEGER, providerType TEXT NOT NULL, workspaceId TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, errorKind TEXT, FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_generations_sessionId ON generations(sessionId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS tool_rounds (generationId TEXT NOT NULL, callId TEXT NOT NULL, name TEXT NOT NULL, status TEXT NOT NULL, bytes INTEGER NOT NULL, durationMs INTEGER NOT NULL, PRIMARY KEY(generationId, callId), FOREIGN KEY(generationId) REFERENCES generations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }

        /** Unknown historic modes stay LEGACY. Never infer BUILD from an old workspace ID. */
        val MIGRATION_2_3: Migration = object : Migration(2,3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("sessions","messages","generations")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN mode TEXT NOT NULL DEFAULT 'LEGACY'")
                    db.execSQL("ALTER TABLE $table ADD COLUMN contextEpoch INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN profileId TEXT")
                    db.execSQL("ALTER TABLE $table ADD COLUMN profileRevision INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("ALTER TABLE sessions ADD COLUMN permissionMode TEXT NOT NULL DEFAULT 'VIEW_ONLY'")
                db.execSQL("ALTER TABLE generations ADD COLUMN workspaceVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tool_rounds ADD COLUMN sequence INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS connection_profiles (id TEXT NOT NULL PRIMARY KEY, brand TEXT NOT NULL, protocol TEXT NOT NULL, baseUrl TEXT NOT NULL, model TEXT NOT NULL, revision INTEGER NOT NULL, capabilityRevision TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS attachments (id TEXT NOT NULL PRIMARY KEY, sessionId INTEGER NOT NULL, messageId INTEGER NOT NULL, contextEpoch INTEGER NOT NULL, profileId TEXT NOT NULL, profileRevision INTEGER NOT NULL, name TEXT NOT NULL, mimeType TEXT NOT NULL, sizeBytes INTEGER NOT NULL, localToken TEXT NOT NULL, sha256 TEXT NOT NULL, status TEXT NOT NULL, FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_sessionId ON attachments(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_messageId ON attachments(messageId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS operation_journal (operationId TEXT NOT NULL PRIMARY KEY, generationId TEXT, callId TEXT NOT NULL, sessionId INTEGER NOT NULL, contextEpoch INTEGER NOT NULL, workspaceId TEXT NOT NULL, workspaceVersion INTEGER NOT NULL, profileId TEXT NOT NULL, profileRevision INTEGER NOT NULL, digest TEXT NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, receipt TEXT, FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(generationId) REFERENCES generations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_operation_journal_sessionId ON operation_journal(sessionId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_operation_journal_generationId_callId ON operation_journal(generationId,callId)")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_3_4: Migration = object : Migration(3,4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attachments ADD COLUMN selectionJson TEXT")
            }
        }

        /** Preserve legacy rows verbatim; only explicitly saved versioned identities fill this column. */
        val MIGRATION_4_5: Migration = object : Migration(4,5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN profileJson TEXT")
            }
        }

        /** Preserve every legacy receipt; owned metadata is populated only by verified application work. */
        val MIGRATION_5_6: Migration = object : Migration(5,6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attachments ADD COLUMN snapshotToken TEXT")
                db.execSQL("ALTER TABLE attachments ADD COLUMN snapshotSha256 TEXT")
                db.execSQL("ALTER TABLE attachments ADD COLUMN snapshotSizeBytes INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS attachment_objects (token TEXT NOT NULL PRIMARY KEY, sha256 TEXT, sizeBytes INTEGER, state TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS attachment_object_refs (attachmentId TEXT NOT NULL, token TEXT NOT NULL, PRIMARY KEY(attachmentId,token), FOREIGN KEY(attachmentId) REFERENCES attachments(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(token) REFERENCES attachment_objects(token) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachment_object_refs_token ON attachment_object_refs(token)")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NAME,
                ).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6).build().also { instance = it }
            }
    }
}

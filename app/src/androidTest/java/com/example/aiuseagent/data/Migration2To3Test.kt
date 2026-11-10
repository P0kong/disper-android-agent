package com.example.aiuseagent.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration2To3Test {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper=MigrationTestHelper(instrumentation,AppDatabase::class.java,emptyList(),FrameworkSQLiteOpenHelperFactory())

    @Test fun v2HistoryWorkspaceAndUnknownToolOrderSurviveAndRoomReopens(): Unit = runBlocking {
        val name="synthetic-migration-v2-v3"
        instrumentation.targetContext.deleteDatabase(name)
        helper.createDatabase(name,2).use { db ->
            db.execSQL("INSERT INTO workspaces VALUES('root-a','fixture','content://fixture/tree/a',10)")
            db.execSQL("INSERT INTO sessions VALUES(11,'kept',1,2,'root-a')")
            db.execSQL("INSERT INTO messages(id,sessionId,role,content,createdAt,isDemo,status,providerType,generationId,replyToMessageId) VALUES(21,11,'user','original',3,0,'COMPLETED','DEEPSEEK',NULL,NULL)")
            db.execSQL("INSERT INTO generations VALUES('old',11,21,NULL,'DEEPSEEK','root-a','COMPLETED',3,4,NULL)")
            db.execSQL("INSERT INTO tool_rounds VALUES('old','call-z','read_text_file','SUCCESS',4,5)")
            db.execSQL("INSERT INTO tool_rounds VALUES('old','call-a','list_directory','SUCCESS',1,2)")
        }
        helper.runMigrationsAndValidate(name,3,true,AppDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT title,createdAt,updatedAt,workspaceId,mode,contextEpoch,profileId,permissionMode FROM sessions WHERE id=11").use {
                assertTrue(it.moveToFirst());assertEquals("kept",it.getString(0));assertEquals(1,it.getLong(1));assertEquals(2,it.getLong(2))
                assertEquals("root-a",it.getString(3));assertEquals("LEGACY",it.getString(4));assertEquals(0,it.getLong(5));assertTrue(it.isNull(6));assertEquals("VIEW_ONLY",it.getString(7))
            }
            db.query("SELECT content,mode FROM messages WHERE id=21").use { assertTrue(it.moveToFirst());assertEquals("original",it.getString(0));assertEquals("LEGACY",it.getString(1)) }
            db.query("SELECT COUNT(*) FROM tool_rounds WHERE sequence IS NULL").use { assertTrue(it.moveToFirst());assertEquals(2,it.getInt(0)) }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
        repeat(2) {
            val db=Room.databaseBuilder(instrumentation.targetContext,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_1_2,AppDatabase.MIGRATION_2_3,AppDatabase.MIGRATION_3_4,AppDatabase.MIGRATION_4_5,AppDatabase.MIGRATION_5_6).build()
            try { assertEquals("LEGACY",db.sessionDao().findById(11)!!.mode);assertEquals("original",db.messageDao().findById(21)!!.content) }
            finally { db.close() }
        }
        instrumentation.targetContext.deleteDatabase(name)
    }

    @Test fun v1ToV3ChainPreservesLegacyWithoutInferringMode() {
        val name="synthetic-migration-v1-v3"
        instrumentation.targetContext.deleteDatabase(name)
        helper.createDatabase(name,1).use { db ->
            db.execSQL("INSERT INTO sessions VALUES(7,'original',10,20)")
            db.execSQL("INSERT INTO messages VALUES(8,7,'assistant','demo',11,1)")
        }
        helper.runMigrationsAndValidate(name,3,true,AppDatabase.MIGRATION_1_2,AppDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT mode,workspaceId FROM sessions WHERE id=7").use { assertTrue(it.moveToFirst());assertEquals("LEGACY",it.getString(0));assertTrue(it.isNull(1)) }
            db.query("SELECT content,isDemo,status FROM messages WHERE id=8").use { assertTrue(it.moveToFirst());assertEquals("demo",it.getString(0));assertEquals(1,it.getInt(1));assertEquals("COMPLETED",it.getString(2)) }
        }
        instrumentation.targetContext.deleteDatabase(name)
    }

    @Test fun removingWorkspacePreservesHistoryAndPopulatedBuildCannotBeRebound(): Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(instrumentation.targetContext,AppDatabase::class.java).build()
        try {
            db.workspaceDao().insert(com.example.aiuseagent.data.local.WorkspaceEntity("A","fixture","content://fixture/tree/A",1))
            val session=db.sessionDao().insert(com.example.aiuseagent.data.local.SessionEntity(title="old root",createdAt=1,updatedAt=1,workspaceId="A",mode="BUILD"))
            db.messageDao().insert(com.example.aiuseagent.data.local.MessageEntity(sessionId=session,role="user",content="old content",createdAt=1))
            val registry=com.example.aiuseagent.data.workspace.RoomWorkspaceRegistry(db)
            try { registry.bind(session,"default");fail("Populated session root must be immutable") } catch(_:IllegalStateException) { }
            registry.removeReplacing("A","default")
            assertEquals("A",db.sessionDao().findById(session)!!.workspaceId)
            assertEquals("old content",db.messageDao().listInSession(session).single().content)
        } finally { db.close() }
    }
}

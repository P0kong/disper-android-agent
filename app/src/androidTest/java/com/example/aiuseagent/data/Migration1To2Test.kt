package com.example.aiuseagent.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the Android SQLite engine, never against a fake DAO or host SQLite substitute. */
@RunWith(AndroidJUnit4::class)
class Migration1To2Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun preservesLegacyRowsValidatesV2AndReopensRoom(): Unit = runBlocking {
        val name = "migration-1-2-preservation"
        instrumentation.targetContext.deleteDatabase(name)
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO sessions(id,title,createdAt,updatedAt) VALUES(11,'保留标题',101,202)")
            db.execSQL("INSERT INTO sessions(id,title,createdAt,updatedAt) VALUES(12,'另一个',303,404)")
            db.execSQL("INSERT INTO messages(id,sessionId,role,content,createdAt,isDemo) VALUES(21,11,'user','原始问题',111,0)")
            db.execSQL("INSERT INTO messages(id,sessionId,role,content,createdAt,isDemo) VALUES(22,11,'assistant','本地演示',112,1)")
            db.execSQL("INSERT INTO messages(id,sessionId,role,content,createdAt,isDemo) VALUES(23,12,'user','独立会话',313,0)")
        }
        helper.runMigrationsAndValidate(name, 2, true, AppDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT id,title,createdAt,updatedAt,workspaceId FROM sessions WHERE id=11").use {
                assertTrue(it.moveToFirst()); assertEquals(11L, it.getLong(0)); assertEquals("保留标题", it.getString(1))
                assertEquals(101L, it.getLong(2)); assertEquals(202L, it.getLong(3)); assertTrue(it.isNull(4))
            }
            db.query("SELECT id,sessionId,role,content,createdAt,isDemo,status,providerType,generationId,replyToMessageId FROM messages ORDER BY id").use {
                assertTrue(it.moveToFirst()); assertEquals(21L,it.getLong(0)); assertEquals(11L,it.getLong(1))
                assertEquals("user",it.getString(2)); assertEquals("原始问题",it.getString(3)); assertEquals(111L,it.getLong(4))
                assertEquals(0,it.getInt(5)); assertEquals("COMPLETED",it.getString(6))
                assertTrue(it.isNull(7)); assertTrue(it.isNull(8)); assertTrue(it.isNull(9))
                assertTrue(it.moveToNext()); assertEquals(22L,it.getLong(0)); assertEquals("本地演示",it.getString(3))
                assertEquals(1,it.getInt(5)); assertTrue(it.moveToNext()); assertEquals(23L,it.getLong(0))
                assertFalse(it.moveToNext())
            }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
        val db = Room.databaseBuilder(instrumentation.targetContext, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2,AppDatabase.MIGRATION_2_3,AppDatabase.MIGRATION_3_4,AppDatabase.MIGRATION_4_5,AppDatabase.MIGRATION_5_6).build()
        try {
                assertEquals(2, db.sessionDao().observeSessions().first().size)
                assertNull(db.sessionDao().findById(11)!!.workspaceId)
                val repository = SessionRepository(db.sessionDao(), db.messageDao(), RoomGenerationPersistence(db))
                val turn = repository.prepareGeneration(11, "新问题", "DEEPSEEK", "default", "instrumented-generation") { }
                assertNotEquals(11L,turn.sessionId)
                repository.persistGeneration("instrumented-generation", "部分输出", "STREAMING")
                repository.recoverInterruptedGenerations()
                assertEquals("INTERRUPTED", db.generationDao().findById("instrumented-generation")!!.status)
                assertEquals("INTERRUPTED", db.messageDao().listInSession(turn.sessionId).last().status)
                assertEquals("原始问题", db.messageDao().findById(21)!!.content)
                db.sessionDao().delete(11)
                assertTrue(db.messageDao().listInSession(11).isEmpty())
                assertNotNull(db.generationDao().findById("instrumented-generation"))
                db.sessionDao().delete(turn.sessionId)
                assertNull(db.generationDao().findById("instrumented-generation"))
                assertNotNull(db.messageDao().findById(23))
        } finally { db.close() }
        instrumentation.targetContext.deleteDatabase(name)
    }

    @Test fun missingMigrationFailsWithoutDeletingV1Data() {
        val name = "migration-1-2-no-destructive-fallback"
        instrumentation.targetContext.deleteDatabase(name)
        helper.createDatabase(name, 1).use {
            it.execSQL("INSERT INTO sessions(id,title,createdAt,updatedAt) VALUES(99,'不得删除',1,2)")
        }
        val withoutMigration = Room.databaseBuilder(instrumentation.targetContext, AppDatabase::class.java, name).build()
        try {
            try { withoutMigration.openHelper.writableDatabase; fail("Expected missing migration failure") }
            catch (_: IllegalStateException) { }
        } finally { withoutMigration.close() }
        helper.runMigrationsAndValidate(name, 2, true, AppDatabase.MIGRATION_1_2).use {
            it.query("SELECT title FROM sessions WHERE id=99").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("不得删除",cursor.getString(0))
            }
        }
        instrumentation.targetContext.deleteDatabase(name)
    }
}

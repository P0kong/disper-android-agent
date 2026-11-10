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
class Migration3To4Test {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper=MigrationTestHelper(instrumentation,AppDatabase::class.java,emptyList(),FrameworkSQLiteOpenHelperFactory())
    @Test fun originalAttachmentAndJournalSurviveWithUnconfirmedLegacySelection()=runBlocking {
        val name="synthetic-attachment-migration-v3-v4"
        instrumentation.targetContext.deleteDatabase(name)
        helper.createDatabase(name,3).use { db ->
            db.execSQL("INSERT INTO sessions(id,title,createdAt,updatedAt,mode) VALUES(11,'kept',1,2,'CHAT')")
            db.execSQL("INSERT INTO messages(id,sessionId,role,content,createdAt,isDemo) VALUES(21,11,'user','kept text',3,0)")
            db.execSQL("INSERT INTO attachments VALUES('attachment',11,21,0,'profile',0,'kept.txt','text/plain',3,'owned-token','source-hash','PREPARED')")
            db.execSQL("INSERT INTO operation_journal VALUES('operation',NULL,'call',11,0,'default',0,'profile',0,'digest','write','UNKNOWN',1,2,'receipt')")
        }
        helper.runMigrationsAndValidate(name,4,true,AppDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT name,sha256,selectionJson FROM attachments WHERE id='attachment'").use { cursor ->
                assertTrue(cursor.moveToFirst());assertEquals("kept.txt",cursor.getString(0));assertEquals("source-hash",cursor.getString(1));assertTrue(cursor.isNull(2))
            }
            db.query("SELECT state,receipt FROM operation_journal WHERE operationId='operation'").use {
                assertTrue(it.moveToFirst());assertEquals("UNKNOWN",it.getString(0));assertEquals("receipt",it.getString(1))
            }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
        val reopened=Room.databaseBuilder(instrumentation.targetContext,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_4_5,AppDatabase.MIGRATION_5_6).build()
        try {assertEquals("kept text",reopened.messageDao().findById(21)!!.content);assertNull(reopened.attachmentDao().inSession(11).single().selectionJson)}
        finally {reopened.close();instrumentation.targetContext.deleteDatabase(name)}
    }
    @Test fun supportedV1ChainPreservesOriginalMessage() {
        val name="synthetic-attachment-migration-v1-v4"
        instrumentation.targetContext.deleteDatabase(name)
        helper.createDatabase(name,1).use {db ->
            db.execSQL("INSERT INTO sessions VALUES(7,'kept',1,2)")
            db.execSQL("INSERT INTO messages VALUES(8,7,'user','original',3,0)")
        }
        helper.runMigrationsAndValidate(name,4,true,AppDatabase.MIGRATION_1_2,AppDatabase.MIGRATION_2_3,AppDatabase.MIGRATION_3_4).use {db ->
            db.query("SELECT content FROM messages WHERE id=8").use {assertTrue(it.moveToFirst());assertEquals("original",it.getString(0))}
        }
        instrumentation.targetContext.deleteDatabase(name)
    }
}

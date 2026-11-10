package com.example.aiuseagent.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.data.local.AppDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentCleanupDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper=MigrationTestHelper(instrumentation,AppDatabase::class.java,emptyList(),FrameworkSQLiteOpenHelperFactory())
    @Test fun nextSchemaPreservesLegacyReceiptAndStoresDurableObjectReferences()=runBlocking<Unit> {
        val context=instrumentation.targetContext;val name="synthetic-owned-migration-${UUID.randomUUID()}"
        helper.createDatabase(name,5).use {db ->
            db.execSQL("INSERT INTO sessions(id,title,createdAt,updatedAt,mode) VALUES(71,'synthetic kept',1,2,'CHAT')")
            db.execSQL("INSERT INTO messages(id,sessionId,role,content,createdAt,isDemo) VALUES(72,71,'user','synthetic kept',3,0)")
            db.execSQL("INSERT INTO attachments(id,sessionId,messageId,contextEpoch,profileId,profileRevision,name,mimeType,sizeBytes,localToken,sha256,status,selectionJson) VALUES('synthetic-legacy',71,72,0,'historic',7,'kept.txt','text/plain',3,'historic-token','historic-hash','PREPARED','unchanged-receipt')")
        }
        val room=Room.databaseBuilder(context,AppDatabase::class.java,name)
            .addMigrations(AppDatabase.MIGRATION_1_2,AppDatabase.MIGRATION_2_3,AppDatabase.MIGRATION_3_4,AppDatabase.MIGRATION_4_5,AppDatabase.MIGRATION_5_6).build()
        try {
            val db=room.openHelper.writableDatabase
            assertEquals(6,db.version)
            assertEquals("unchanged-receipt",room.attachmentDao().inSession(71).single().selectionJson)
            db.query("SELECT snapshotToken,snapshotSha256,snapshotSizeBytes FROM attachments").use {
                assertTrue(it.moveToFirst());assertTrue(it.isNull(0));assertTrue(it.isNull(1));assertTrue(it.isNull(2))
            }
            db.execSQL("INSERT INTO attachment_objects(token,sha256,sizeBytes,state) VALUES('synthetic-object','synthetic-hash',3,'READY')")
            db.execSQL("INSERT INTO attachment_object_refs(attachmentId,token) VALUES('synthetic-legacy','synthetic-object')")
            db.query("PRAGMA foreign_key_check").use {assertFalse(it.moveToFirst())}
            db.execSQL("DELETE FROM sessions WHERE id=71")
            db.query("SELECT COUNT(*) FROM attachment_object_refs").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            db.query("SELECT COUNT(*) FROM attachment_objects").use {assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))}
        } finally {room.close();context.deleteDatabase(name)}
    }
}

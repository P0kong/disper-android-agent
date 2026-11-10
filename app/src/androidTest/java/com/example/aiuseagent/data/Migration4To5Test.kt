package com.example.aiuseagent.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.data.local.AppDatabase
import com.example.aiuseagent.data.provider.HttpProviders
import com.example.aiuseagent.domain.provider.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration4To5Test {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper=MigrationTestHelper(instrumentation,AppDatabase::class.java,emptyList(),FrameworkSQLiteOpenHelperFactory())

    @Test fun legacyRowsRemainUnmodifiedAndNewIdentitySurvivesRoomReopen()=runBlocking {
        val context=instrumentation.targetContext
        val name="synthetic-profile-migration-v4-v5"
        context.deleteDatabase(name)
        helper.createDatabase(name,4).use { db ->
            db.execSQL("INSERT INTO connection_profiles VALUES('legacy:unknown','unknown-brand','unknown-wire','https://example.invalid/v1','kept-model',7,'legacy-unverified')")
            db.execSQL("INSERT INTO sessions(id,title,createdAt,updatedAt,mode) VALUES(71,'kept',1,2,'CHAT')")
            db.execSQL("INSERT INTO messages(id,sessionId,role,content,createdAt,isDemo) VALUES(72,71,'user','kept',3,0)")
            db.execSQL("INSERT INTO attachments VALUES('kept-attachment',71,72,0,'legacy:unknown',7,'kept.txt','text/plain',3,'owned-token','source-hash','PREPARED','unchanged-receipt')")
        }
        helper.runMigrationsAndValidate(name,5,true,AppDatabase.MIGRATION_4_5).use { db ->
            db.query("SELECT brand,revision,profileJson FROM connection_profiles").use {
                assertTrue(it.moveToFirst());assertEquals("unknown-brand",it.getString(0));assertEquals(7L,it.getLong(1));assertTrue(it.isNull(2))
            }
            db.query("PRAGMA foreign_key_check").use {assertFalse(it.moveToFirst())}
        }
        var room=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_5_6).build()
        try {
            val first=HttpProviders.repository()
            first.useConnectionProfile(ConnectionProfile("custom:visual",8,ProviderBrand.CLAUDE,
                Protocol.ANTHROPIC_MESSAGES,"https://api.anthropic.com/v1","claude-sonnet-5-5","2023-06-01",configurationSaved=true))
            val expected=first.prepare()
            ConnectionProfileRepository(room.connectionProfileDao()).save(expected)
            room.close()
            room=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_5_6).build()
            val row=requireNotNull(room.connectionProfileDao().find(expected.profileId))
            assertEquals(expected.config.connectionProfile,ConnectionProfileCodec.decode(requireNotNull(row.profileJson)))
            assertEquals("unchanged-receipt",room.attachmentDao().inSession(71).single().selectionJson)
            assertEquals("kept",room.messageDao().findById(72)!!.content)
            val restored=HttpProviders.repository()
            val profiles=ConnectionProfileRepository(room.connectionProfileDao())
            profiles.restoreInto(restored)
            assertTrue(profiles.requiresReconfiguration)
            assertEquals(ProviderType.MOCK,restored.activeType())
            assertFalse(restored.credentialStatus(ProviderType.ANTHROPIC).isPresent)
        } finally {room.close();context.deleteDatabase(name)}
    }
}

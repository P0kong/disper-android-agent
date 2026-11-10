package com.example.aiuseagent.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.data.attachment.*
import com.example.aiuseagent.data.attachment.parser.*
import com.example.aiuseagent.data.local.*
import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.platform.AndroidAttachmentSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only fresh UUID databases and files; the original picker URI is deleted after copying. */
internal class HistoryDeviceFixture:AutoCloseable {
    val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val id=UUID.randomUUID().toString()
    private val name="synthetic-history-$id"
    val root=File(context.filesDir.canonicalFile,"synthetic-history-$id").also {
        check(it.canonicalFile==it.absoluteFile && it.mkdir())
    }.toPath()
    var database=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
    var catalog=RoomAttachmentCatalog(database)
    var owned=OwnedAttachmentStore(root,AttachmentLeaseRegistry())
    val sessions get()=SessionRepository(database.sessionDao(),database.messageDao(),RoomGenerationPersistence(database))
    val scope=AttachmentScope(UUID.randomUUID().toString(),null,"CHAT",0,null,0,"historic",1,0)
    private val sourceFiles=mutableListOf<File>()
    val repository by lazy {AttachmentRepository(root,AndroidAttachmentSource(context.contentResolver),{true},ownedStore=owned,ownership=catalog)}
    suspend fun prepare(image:Boolean=false,legacy:Boolean=false):AttachmentCommit {
        val repository=if(legacy) AttachmentRepository(root,AndroidAttachmentSource(context.contentResolver),{true}) else this.repository
        val cameraRoot=File(context.cacheDir.canonicalFile,"attachment-camera").also {check(it.mkdirs() || it.isDirectory)}
        val original=File(cameraRoot,"${UUID.randomUUID()}.${if(image) "png" else "txt"}")
        check(original.canonicalFile.parentFile==cameraRoot.canonicalFile && original.createNewFile());sourceFiles+=original
        val bytes=if(image) {
            val bitmap=Bitmap.createBitmap(2,3,Bitmap.Config.ARGB_8888)
            try {bitmap.eraseColor(Color.CYAN);ByteArrayOutputStream().also {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()}
            finally {bitmap.recycle()}
        } else "synthetic confirmed text\n第二行".toByteArray()
        original.writeBytes(bytes)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.attachment.camera",original)
        val handle=repository.stage(scope,uri.toString(),UUID.randomUUID().toString())
        check(original.delete())
        assertTrue(runCatching {context.contentResolver.openInputStream(uri)?.use {it.read()}}.isFailure)
        val read:suspend (AttachmentHandle)->ByteArray={repository.read(scope,it)}
        val result=if(image) ImageAttachmentProcessor(read).parse(handle,AttachmentLimits()) else TextAttachmentParser(read).parse(handle,AttachmentLimits())
        repository.parsed(scope,handle,result)
        val frozen=repository.freeze(scope,listOf(AttachmentSelection(handle.id,handle.sha256,result.parserRevision,result.parts.map {it.id},true)),"historic-budget:1")
        val prepared=AttachmentRequestAssembler(repository,"historic-budget:1") {_,h->ImageAttachmentProcessor(read).normalize(h,AttachmentLimits())}.assemble(scope,frozen,"")
        return repository.prepareCommit(scope,frozen,prepared)
    }
    suspend fun send(commit:AttachmentCommit):GenerationTurn=try {
        sessions.prepareGeneration(null,"","MOCK",null,UUID.randomUUID().toString(),profileId="historic",profileRevision=1,attachments=commit) {}
    } finally {commit.releaseSending()}
    fun reopen() {
        database.close();database=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        catalog=RoomAttachmentCatalog(database);owned=OwnedAttachmentStore(root,AttachmentLeaseRegistry())
    }
    override fun close() {
        database.close();context.deleteDatabase(name)
        sourceFiles.forEach {if(it.exists()) check(it.delete())}
        check(root.toFile().canonicalFile==File(context.filesDir.canonicalFile,"synthetic-history-$id").canonicalFile)
        root.toFile().deleteRecursively()
    }
}

@RunWith(AndroidJUnit4::class)
class HistoryAttachmentDeviceTest {
    @Test fun exactLegacyReceiptGetsAtomicSnapshotAndReferencesWithoutChangingOldContent()=runBlocking<Unit> {
        HistoryDeviceFixture().use {f ->
            val commit=f.prepare(legacy=true);val handle=commit.records.single().handle;val turn=f.send(commit)
            val before=requireNotNull(f.catalog.find(handle.id));assertNull(before.snapshot);assertTrue(before.objects.isEmpty())
            f.reopen()
            val migrator=LegacyHistorySnapshotMigrator(f.owned,f.catalog,f.catalog::bindLegacy)
            val reader=HistoryAttachmentReader(f.catalog,f.owned,legacyUpgrade=migrator::upgrade)
            (reader.open(turn.sessionId,turn.userMessageId,handle.id) as HistoryPreviewResult.Readable).use {
                assertEquals("synthetic confirmed text\n第二行",it.snapshot.parts.single().text)
                assertEquals("historic-budget:1",it.snapshot.input.modelBudgetRevision)
            }
            val after=requireNotNull(f.catalog.find(handle.id))
            assertEquals(before.attachment,after.attachment.copy(snapshotToken=null,snapshotSha256=null,snapshotSizeBytes=null))
            assertEquals(before.message,after.message);assertEquals(2,after.objects.size)
            f.reopen()
            (HistoryAttachmentReader(f.catalog,f.owned).open(turn.sessionId,turn.userMessageId,handle.id) as HistoryPreviewResult.Readable).close()
            assertEquals(CleanupSummary(0,2,0),AttachmentGarbageCollector(f.catalog,f.owned).collectPending())
            f.sessions.deleteSession(turn.sessionId)
            assertEquals(CleanupSummary(2,0,0),AttachmentGarbageCollector(f.catalog,f.owned).collectPending())
            f.database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {assertFalse(it.moveToFirst())}
        }
    }
    @Test fun deletedLegacyMessageCannotBeRecreatedByAnInFlightUpgrade()=runBlocking<Unit> {
        HistoryDeviceFixture().use {f ->
            val commit=f.prepare(legacy=true);val handle=commit.records.single().handle;val turn=f.send(commit)
            var called=false
            val migrator=LegacyHistorySnapshotMigrator(f.owned,f.catalog,{old,prepared ->
                called=true;f.sessions.deleteSession(turn.sessionId)
                f.catalog.bindLegacy(old,prepared)
            })
            assertFalse(migrator.upgrade(requireNotNull(f.catalog.find(handle.id))));assertTrue(called)
            assertNull(f.catalog.find(handle.id));assertNull(f.database.messageDao().findById(turn.userMessageId))
            assertEquals(CleanupSummary(2,0,0),AttachmentGarbageCollector(f.catalog,f.owned).collectPending())
        }
    }
    @Test fun unavailableLegacySourceOwnershipRollsBackSnapshotColumnsAndReferences()=runBlocking<Unit> {
        HistoryDeviceFixture().use {f ->
            val commit=f.prepare(legacy=true);val handle=commit.records.single().handle;f.send(commit)
            val before=requireNotNull(f.catalog.find(handle.id))
            f.catalog.register(listOf(OwnedAttachmentObject(handle.localToken,handle.sha256,handle.sizeBytes)))
            assertEquals(1,f.database.attachmentObjectDao().state(handle.localToken,"REVIEW"))
            val migrator=LegacyHistorySnapshotMigrator(f.owned,f.catalog,f.catalog::bindLegacy)
            try {migrator.upgrade(before);fail("Non-ready ownership must reject binding")} catch(_:IllegalStateException) {}
            assertEquals(before,requireNotNull(f.catalog.find(handle.id)))
            assertTrue(f.database.attachmentObjectDao().forAttachment(handle.id).isEmpty())
            assertEquals(CleanupSummary(1,1,0),AttachmentGarbageCollector(f.catalog,f.owned).collectPending())
            assertTrue(f.root.resolve("${handle.localToken}.blob").toFile().exists())
        }
    }
    @Test fun confirmedTextSurvivesRoomReopenAndConnectionChangeAfterOriginalUriDisappears()=runBlocking<Unit> {
        HistoryDeviceFixture().use {f ->
            val commit=f.prepare();val handle=commit.records.single().handle;val turn=f.send(commit)
            val original=f.root.resolve("${handle.localToken}.blob").toFile().readBytes()
            f.reopen()
            assertEquals(1,f.database.sessionDao().updateContext(turn.sessionId,0,1,"different-current-profile",9))
            (HistoryAttachmentReader(f.catalog,f.owned).open(turn.sessionId,turn.userMessageId,handle.id) as HistoryPreviewResult.Readable).use {read ->
                assertEquals("synthetic confirmed text\n第二行",read.snapshot.parts.single().text)
                assertEquals("historic-budget:1",read.snapshot.input.modelBudgetRevision)
                assertEquals(handle.sha256,read.snapshot.input.handle.sha256)
            }
            assertArrayEquals(original,f.root.resolve("${handle.localToken}.blob").toFile().readBytes())
            f.database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {assertFalse(it.moveToFirst())}
        }
    }
    @Test fun normalizedImageSurvivesReopenAndReplacementBecomesCorrupt()=runBlocking<Unit> {
        HistoryDeviceFixture().use {f ->
            val commit=f.prepare(true);val handle=commit.records.single().handle;val turn=f.send(commit);f.reopen()
            var imageToken=""
            (HistoryAttachmentReader(f.catalog,f.owned).open(turn.sessionId,turn.userMessageId,handle.id) as HistoryPreviewResult.Readable).use {read ->
                val part=read.snapshot.parts.single();imageToken=part.image!!.token
                val bytes=read.imageBytes(part.id)!!;val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                try {assertEquals(2,bitmap.width);assertEquals(3,bitmap.height);assertEquals(Color.CYAN,bitmap.getPixel(1,2))}
                finally {bitmap.recycle()}
            }
            val image=f.root.resolve("$imageToken.blob").toFile();val changed=image.readBytes();changed[changed.lastIndex]=(changed.last().toInt() xor 1).toByte();image.writeBytes(changed)
            assertEquals(HistoryPreviewResult.Corrupt,HistoryAttachmentReader(f.catalog,f.owned).open(turn.sessionId,turn.userMessageId,handle.id))
        }
    }
}

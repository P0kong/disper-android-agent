package com.example.aiuseagent.data

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aiuseagent.data.attachment.*
import com.example.aiuseagent.data.local.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentCatalogDeviceTest {
    @Test fun sessionDeletionLeavesDurableCandidatesAndReadLeaseProtectsThemAcrossCleanup()=runBlocking<Unit> {
        HistoryDeviceFixture().use {f ->
            val commit=f.prepare(true);val turn=f.send(commit);val record=commit.records.single()
            val preview=HistoryAttachmentReader(f.catalog,f.owned).open(turn.sessionId,turn.userMessageId,record.handle.id) as HistoryPreviewResult.Readable
            f.sessions.deleteSession(turn.sessionId)
            assertEquals(CleanupSummary(0,record.objects.size,0),AttachmentGarbageCollector(f.catalog,f.owned).collectPending())
            preview.close();f.reopen()
            assertEquals(CleanupSummary(record.objects.size,0,0),AttachmentGarbageCollector(f.catalog,f.owned).collectPending())
            assertTrue(f.catalog.candidates().isEmpty());assertTrue(f.root.toFile().listFiles()!!.isEmpty())
            f.database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {assertFalse(it.moveToFirst())}
        }
    }
    @Test fun failedMessageTransactionRetainsDraftThenDiscardedOwnedCopiesCanBeRecovered()=runBlocking<Unit> {
        HistoryDeviceFixture().use {f ->
            val commit=f.prepare();val record=commit.records.single()
            try {
                f.sessions.prepareGeneration(null,"","MOCK",null,UUID.randomUUID().toString(),profileId="historic",profileRevision=1,attachments=commit) {error("synthetic rollback")}
                fail("transaction must fail")
            } catch(_:IllegalStateException) {} finally {commit.releaseSending()}
            assertTrue(f.database.sessionDao().observeSessions().first().isEmpty())
            assertEquals(1,f.repository.state.value.size)
            f.repository.remove(f.scope,record.handle.id);f.reopen()
            assertEquals(CleanupSummary(record.objects.size,0,0),AttachmentGarbageCollector(f.catalog,f.owned).collectPending())
        }
    }
    @Test fun legacySourceCandidateIsRecordedBeforeSessionCascadeAndRecoveredAfterReopen()=runBlocking<Unit> {
        HistoryDeviceFixture().use {f ->
            val bytes="legacy synthetic copy".toByteArray();val token=UUID.randomUUID().toString()
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
            f.root.resolve("$token.blob").toFile().writeBytes(bytes)
            f.root.resolve("$token.owner").toFile().writeText("attachment-v1\n$token\n")
            val session=f.database.sessionDao().insert(SessionEntity(title="synthetic legacy",createdAt=1,updatedAt=1))
            val message=f.database.messageDao().insert(MessageEntity(sessionId=session,role="user",content="",createdAt=1))
            f.database.attachmentDao().insert(AttachmentEntity(UUID.randomUUID().toString(),session,message,0,"historic",1,"synthetic.txt","text/plain",bytes.size.toLong(),token,hash,"PREPARED"))
            f.sessions.deleteSession(session);f.reopen()
            assertEquals(listOf(token),f.catalog.candidates())
            assertEquals(CleanupSummary(1,0,0),AttachmentGarbageCollector(f.catalog,f.owned).collectPending())
        }
    }
    @Test fun deletingClaimRejectsNewReferenceInTheSameMessageTransaction()=runBlocking<Unit> {
        HistoryDeviceFixture().use {f ->
            val original=OwnedAttachmentObject(UUID.randomUUID().toString(),"a".repeat(64),3)
            val snapshot=OwnedAttachmentObject(UUID.randomUUID().toString(),"b".repeat(64),3)
            f.catalog.register(listOf(original,snapshot))
            assertTrue(f.catalog.claim(original.token) is AttachmentCleanupClaim.Deleting)
            try {
                f.database.withTransaction {
                    val session=f.database.sessionDao().insert(SessionEntity(title="synthetic",createdAt=1,updatedAt=1))
                    val message=f.database.messageDao().insert(MessageEntity(sessionId=session,role="user",content="",createdAt=1,profileId="historic",profileRevision=1))
                    val row=AttachmentEntity(UUID.randomUUID().toString(),session,message,0,"historic",1,"synthetic.txt","text/plain",3,original.token,original.sha256,"PREPARED",snapshotToken=snapshot.token,snapshotSha256=snapshot.sha256,snapshotSizeBytes=snapshot.sizeBytes)
                    f.database.attachmentDao().insert(row);f.catalog.bind(row.id,listOf(original,snapshot))
                }
                fail("deleting object cannot acquire a new reference")
            } catch(_:IllegalStateException) {}
            assertTrue(f.database.sessionDao().observeSessions().first().isEmpty())
        }
    }
    @Test fun incompleteAllocatedCopyStaysForReviewAfterRestart()=runBlocking<Unit> {
        HistoryDeviceFixture().use {f ->
            val token=UUID.randomUUID().toString();f.catalog.allocate(token)
            f.root.resolve("$token.owner").toFile().writeText("attachment-v1\n$token\n")
            f.root.resolve("$token.part").toFile().writeText("synthetic partial data")
            f.reopen()
            assertEquals(CleanupSummary(0,0,1),AttachmentGarbageCollector(f.catalog,f.owned).collectPending())
            assertTrue(f.root.resolve("$token.part").toFile().exists())
        }
    }
}

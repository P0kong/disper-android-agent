package com.example.aiuseagent.data

import android.content.*
import android.os.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.data.execution.*
import com.example.aiuseagent.data.local.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Kills only a dedicated debug fixture process at the restore reservation boundary. */
@RunWith(AndroidJUnit4::class)
class RecoveryProcessDeathTest {
    @Test fun killedRestoreProcessLeavesInspectableJournalAndConsumedToken()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=UUID.randomUUID().toString()
        val folder=File(context.cacheDir.canonicalFile,"recovery-process-$fixture").apply {check(mkdir())}
        val root=File(folder,"task").apply {check(mkdir())}
        val recovery=File(folder,"recovery")
        val store=TaskFileStore(root.toPath(),recovery.toPath(),1)
        val first=store.write("item.txt","MISSING","before".toByteArray())
        val second=store.write("item.txt",first.afterHash,"after".toByteArray())
        val next=UUID.randomUUID().toString()
        val dbName="synthetic-recovery-process-$fixture"
        fun open()=Room.databaseBuilder(context,AppDatabase::class.java,dbName).build()
        var db=open()
        var bound=false
        val connected=CompletableDeferred<IBinder>()
        val died=CompletableDeferred<Unit>()
        val hello=CompletableDeferred<Int>()
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder){connected.complete(binder)}
            override fun onServiceDisconnected(name:ComponentName){died.complete(Unit)}
        }
        try {
            val session=db.sessionDao().insert(SessionEntity(title="synthetic",createdAt=1,updatedAt=1,workspaceId="default",mode="BUILD"))
            db.generationDao().insert(GenerationEntity("g",session,1,providerType="MOCK",workspaceId="default",createdAt=1,updatedAt=1,mode="BUILD"))
            val scope=SessionContextScope(session,"BUILD","default",1,1,"synthetic",1)
            val binding=ApprovalBinding("g","restore",scope,"synthetic-digest")
            assertTrue(RoomOperationJournal(db.operationJournalDao()).insert(JournalRecord("op",binding,"WRITE","RUNNING","recovery:$next",1,1)))
            db.close()
            val intent=Intent().setClassName(context,"com.example.aiuseagent.testing.RecoveryFaultService")
            bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE)
            assertTrue("RECOVERY_FAULT_SERVICE_MISSING",bound)
            val binder=withTimeout(10000){connected.await()}
            binder.linkToDeath({died.complete(Unit)},0)
            val replies=Messenger(Handler(Looper.getMainLooper()){message ->
                if(message.what==1)hello.complete(message.arg1)
                true
            })
            val remote=Messenger(binder)
            remote.send(Message.obtain(null,1).apply {replyTo=replies})
            val remotePid=withTimeout(10000){hello.await()}
            assertTrue(remotePid>0);assertNotEquals(android.os.Process.myPid(),remotePid)
            remote.send(Message.obtain(null,2).apply {
                data=Bundle().apply {putString("fixture",fixture);putString("token",second.token);putString("next",next)}
            })
            withTimeout(10000){died.await()}
            context.unbindService(connection);bound=false
            assertEquals(remotePid.toString(),File(folder,"reservation-reached").readText())
            val reopened=TaskFileStore(root.toPath(),recovery.toPath(),1)
            val old=reopened.inspectRecovery(second.token)
            assertTrue(old.consumed);assertEquals(next,old.followupToken);assertTrue(old.backupVerified)
            val pending=reopened.inspectRecovery(next)
            assertTrue(pending.backupVerified);assertEquals("item.txt",pending.target.path)
            assertEquals(second.afterHash,pending.currentHash)
            assertEquals("after",File(root,"item.txt").readText())
            try {reopened.restore(second.token,second.afterHash);fail("Consumed token replayed")}
            catch(expected:FileMutationFailure){assertEquals("RECOVERY_INVALID",expected.code)}
            db=open()
            val journal=RoomOperationJournal(db.operationJournalDao());journal.recover(2)
            assertEquals("UNKNOWN",journal.find("g","restore")!!.state)
            val inspector=OperationRecoveryInspector(journal::uncertain,{scope},{_,token->reopened.inspectRecovery(token)})
            val reviewed=inspector.review(session).single()
            assertEquals("OBSERVED",reviewed.code);assertEquals("recovery:$next",reviewed.reference)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {assertFalse(it.moveToFirst())}
        } finally {
            if(bound)context.unbindService(connection)
            db.close();check(context.deleteDatabase(dbName))
            check(folder.canonicalFile.parentFile==context.cacheDir.canonicalFile)
            check(folder.deleteRecursively())
        }
    }
}

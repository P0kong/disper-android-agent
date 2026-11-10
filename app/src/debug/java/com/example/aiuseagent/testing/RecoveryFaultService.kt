package com.example.aiuseagent.testing

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.*
import com.example.aiuseagent.data.execution.TaskFileStore
import java.io.File
import java.io.FileOutputStream

/** Debug-only crash fixture, NOT an isolated command executor. No arbitrary paths or commands. */
class RecoveryFaultService:Service() {
    private val uuid=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val endpoint by lazy { Messenger(Handler(Looper.getMainLooper()){message ->
        check(Application.getProcessName()=="$packageName:recovery_fault")
        check(message.sendingUid==android.os.Process.myUid())
        when(message.what) {
            1 -> message.replyTo.send(Message.obtain(null,1,android.os.Process.myPid(),0))
            2 -> {
                check(message.data.keySet()==setOf("fixture","token","next"))
                fun id(key:String)=requireNotNull(message.data.getString(key)).also {require(uuid.matches(it))}
                val fixture=id("fixture");val token=id("token");val next=id("next")
                val cache=cacheDir.canonicalFile
                val folder=File(cache,"recovery-process-$fixture")
                check(folder.canonicalFile==folder && folder.isDirectory)
                val task=File(folder,"task");val recovery=File(folder,"recovery")
                val store=TaskFileStore(task.toPath(),recovery.toPath(),1) {
                    val marker=File(folder,"reservation-reached")
                    check(marker.createNewFile())
                    FileOutputStream(marker).use {it.write(android.os.Process.myPid().toString().toByteArray());it.fd.sync()}
                    // Only this exact secondary process dies; never the main app/test/ADB.
                    android.os.Process.killProcess(android.os.Process.myPid())
                    error("Kill unexpectedly returned")
                }
                check(store.describeRecovery(token).path=="item.txt")
                store.restore(token,TaskFileStore.hash("after".toByteArray()),recoveryToken=next)
            }
            else -> error("Unsupported fixture message")
        }
        true
    }) }
    override fun onBind(intent:Intent):IBinder=endpoint.binder
}

package com.example.aiuseagent.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.aiuseagent.domain.attachment.*
import java.io.File
import java.util.UUID

/** Process-owned tickets and camera files; never deletes a picker source or a workspace file. */
class AttachmentPickerController(context:Context) {
    private val app=context.applicationContext
    private val tickets=mutableMapOf<AttachmentPickerKind,PickerTicket>()
    private val cameras=mutableMapOf<String,File>()
    fun cameraOutput():String {
        val root=File(app.cacheDir,"attachment-camera")
        check(root.mkdirs() || root.isDirectory)
        val file=File(root,"${UUID.randomUUID()}.jpg")
        check(file.createNewFile())
        val uri=FileProvider.getUriForFile(app,"${app.packageName}.attachment.camera",file).toString()
        cameras[uri]=file
        return uri
    }
    fun register(ticket:PickerTicket) { check(tickets[ticket.kind]==null);tickets[ticket.kind]=ticket }
    fun take(kind:AttachmentPickerKind):PickerTicket?=tickets.remove(kind)
    fun finishCamera(uri:String?) {
        if(uri==null) return
        val owned=cameras.remove(uri) ?: return
        app.revokeUriPermission(Uri.parse(uri),Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        check(owned.parentFile?.canonicalFile==File(app.cacheDir,"attachment-camera").canonicalFile)
        owned.delete()
    }
}

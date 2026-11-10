package com.example.aiuseagent.platform

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.example.aiuseagent.data.attachment.*
import com.example.aiuseagent.domain.attachment.AttachmentFailure
import java.io.InputStream

class AndroidAttachmentSource(private val resolver: ContentResolver) : AttachmentSource {
    override suspend fun describe(uri: String): AttachmentSourceInfo {
        val source=Uri.parse(uri)
        var name="附件"
        var size: Long?=null
        resolver.query(source,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex>=0 && !cursor.isNull(nameIndex)) name=cursor.getString(nameIndex)
                val sizeIndex=cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex>=0 && !cursor.isNull(sizeIndex)) size=cursor.getLong(sizeIndex)
            }
        }
        return AttachmentSourceInfo(name,resolver.getType(source) ?: "application/octet-stream",size)
    }
    override suspend fun open(uri: String): InputStream = resolver.openInputStream(Uri.parse(uri))
        ?: throw AttachmentFailure("SOURCE_UNAVAILABLE")
}

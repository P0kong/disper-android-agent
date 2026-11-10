package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.AttachmentPart
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Frozen receipt algorithm v1. Preserve byte lengths, order, source, and original image hash exactly. */
internal object AttachmentContentDigest {
    fun compute(parts:List<AttachmentPart>):String {
        val digest=MessageDigest.getInstance("SHA-256")
        fun field(value:String) {
            val bytes=value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
        }
        for(part in parts) {field(part.id);field(part.source);field(if(part.text!=null) "text" else "image");field(part.text ?: part.image!!.sha256)}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
}

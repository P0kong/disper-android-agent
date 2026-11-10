package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.*
import kotlinx.serialization.json.*

/** References a normalized owned image, never an external path or original picker URI. */
data class HistoryImageReference(val token:String,val sha256:String,val sizeBytes:Long,val width:Int,val height:Int)
data class HistorySnapshotPart(val id:String,val source:String,val text:String?=null,val image:HistoryImageReference?=null) {
    override fun toString()="HistorySnapshotPart(id=$id,content=<omitted>)"
}
class HistoryAttachmentSnapshot(val input:FrozenAttachmentInput,parts:List<HistorySnapshotPart>) {
    val parts:List<HistorySnapshotPart> = java.util.Collections.unmodifiableList(ArrayList(parts))
    override fun toString()="HistoryAttachmentSnapshot(content=<omitted>)"
}

/** Version 1 preserves the exact already-frozen selection; decoding does not run a current parser. */
object HistoryAttachmentSnapshotCodec {
    const val MAX_BYTES=4*1024*1024
    private val hash=Regex("[a-f0-9]{64}")
    private val rootKeys=setOf("version","handle","receipt","parts")
    private val handleKeys=setOf("id","token","sha256","mimeType","sizeBytes")
    private val imageKeys=setOf("token","sha256","sizeBytes","width","height")

    fun encode(snapshot:HistoryAttachmentSnapshot):String=checked {
        validate(snapshot)
        val handle=snapshot.input.handle
        buildJsonObject {
            put("version",1)
            put("handle",buildJsonObject {
                put("id",handle.id);put("token",handle.localToken);put("sha256",handle.sha256)
                put("mimeType",handle.mimeType);put("sizeBytes",handle.sizeBytes)
            })
            put("receipt",Json.parseToJsonElement(AttachmentSelectionReceipt.encode(snapshot.input)))
            put("parts",buildJsonArray {
                snapshot.parts.forEach {part -> add(buildJsonObject {
                    put("id",part.id);put("source",part.source)
                    if(part.text!=null) put("text",part.text)
                    else put("image",buildJsonObject {
                        val image=requireNotNull(part.image)
                        put("token",image.token);put("sha256",image.sha256);put("sizeBytes",image.sizeBytes)
                        put("width",image.width);put("height",image.height)
                    })
                })}
            })
        }.toString().also {require(it.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)}
    }

    fun decode(raw:String):HistoryAttachmentSnapshot=checked {
        require(raw.length in 1..MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)
        val root=Json.parseToJsonElement(raw).jsonObject
        require(root.keys==rootKeys && number(root,"version")==1L)
        val original=root.getValue("handle").jsonObject
        require(original.keys==handleKeys)
        val handle=AttachmentHandle(text(original,"id"),text(original,"token"),text(original,"sha256"),
            text(original,"mimeType"),number(original,"sizeBytes"))
        val input=AttachmentSelectionReceipt.decode(root.getValue("receipt").jsonObject.toString(),handle)
        val values=root.getValue("parts").jsonArray
        require(values.size in 1..2048)
        val parts=values.map {item ->
            val part=item.jsonObject
            if("text" in part) {
                require(part.keys==setOf("id","source","text"))
                HistorySnapshotPart(text(part,"id"),text(part,"source"),text=text(part,"text"))
            } else {
                require(part.keys==setOf("id","source","image"))
                val image=part.getValue("image").jsonObject
                require(image.keys==imageKeys)
                val width=number(image,"width");val height=number(image,"height")
                require(width in 1..8000 && height in 1..8000)
                HistorySnapshotPart(text(part,"id"),text(part,"source"),image=HistoryImageReference(
                    text(image,"token"),text(image,"sha256"),number(image,"sizeBytes"),width.toInt(),height.toInt()))
            }
        }
        HistoryAttachmentSnapshot(input,parts).also(::validate)
    }

    private fun validate(snapshot:HistoryAttachmentSnapshot) {
        val input=snapshot.input;val handle=input.handle;val limits=AttachmentLimits()
        requireAttachmentObjectToken(handle.id);requireAttachmentObjectToken(handle.localToken)
        require(hash.matches(handle.sha256) && handle.sizeBytes in 1..limits.maxFileBytes)
        require(handle.mimeType.length in 1..128 && handle.mimeType.none {it.isISOControl()})
        AttachmentSelectionReceipt.encode(input)
        require(snapshot.parts.size in 1..2048)
        require(snapshot.parts.map {it.id}==input.selectedPartIds)
        require(snapshot.parts.map {it.id}.distinct().size==snapshot.parts.size)
        val originals=mutableListOf<AttachmentPart>()
        val images=mutableMapOf<String,HistoryImageReference>()
        var characters=0L
        for(part in snapshot.parts) {
            require(part.id.length in 1..160 && part.source.length<=1024)
            require((part.text!=null)!=(part.image!=null))
            if(part.text!=null) {
                characters+=part.text.length
                require(characters<=limits.maxDocumentCharacters)
                originals+=AttachmentPart(part.id,part.source,text=part.text)
            } else {
                val image=requireNotNull(part.image)
                requireAttachmentObjectToken(image.token)
                require(image.token!=handle.localToken && hash.matches(image.sha256))
                // Current image IDs are image:<hash>; later page IDs may prefix the same bound hash.
                require(part.id.endsWith(":"+image.sha256))
                require(image.sizeBytes in 1..7L*1024*1024 && image.width in 1..8000 && image.height in 1..8000)
                require(image.width.toLong()*image.height<=limits.maxPixels)
                require(images[image.token]?.let {it==image}!=false)
                images[image.token]=image
                originals+=AttachmentPart(part.id,part.source,image=handle)
            }
        }
        require(images.values.sumOf {it.sizeBytes}<=20L*1024*1024)
        require(AttachmentContentDigest.compute(originals)==input.selectedContentSha256)
    }
    private fun text(obj:JsonObject,key:String):String=obj.getValue(key).jsonPrimitive.let {require(it.isString);it.content}
    private fun number(obj:JsonObject,key:String):Long=obj.getValue(key).jsonPrimitive.let {require(!it.isString);requireNotNull(it.longOrNull)}
    private inline fun <T> checked(action:()->T):T=try {action()} catch(_:Exception) {throw AttachmentFailure("HISTORY_SNAPSHOT_INVALID")}
}

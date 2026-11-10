package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.domain.provider.*
import com.example.aiuseagent.data.attachment.parser.NormalizedAttachmentImage

class HistorySnapshotMaterial(val snapshot:HistoryAttachmentSnapshot,images:Map<String,ByteArray>) {
    private val savedImages=images.mapValues {it.value.copyOf()}
    fun images():Map<String,ByteArray> = savedImages.mapValues {it.value.copyOf()}
}
class PreparedAttachmentInput(parts:List<ProviderContentPart>,val payloads:ProviderAttachmentPayloads,private val userText:String,
    history:List<HistorySnapshotMaterial> = emptyList()) {
    val parts:List<ProviderContentPart> = java.util.Collections.unmodifiableList(ArrayList(parts))
    val history:List<HistorySnapshotMaterial> = java.util.Collections.unmodifiableList(ArrayList(history))
    fun userMessage()=ProviderMessage(ChatRole.USER,userText,parts=parts)
    override fun toString()="PreparedAttachmentInput(<omitted>)"
}

class AttachmentRequestAssembler(private val repository:AttachmentRepository,private val budgetRevision:String,
    private val normalize:suspend (AttachmentScope,AttachmentHandle)->NormalizedAttachmentImage) {
    suspend fun assemble(scope:AttachmentScope,inputs:List<FrozenAttachmentInput>,userText:String):PreparedAttachmentInput {
        if(inputs.isEmpty() || inputs.size>10 || inputs.map {it.handle.id}.distinct().size!=inputs.size) throw AttachmentFailure("SELECTION_INVALID")
        val parts=mutableListOf<ProviderContentPart>()
        val bytes=linkedMapOf<ProviderContentPart.Image,ByteArray>()
        val history=mutableListOf<HistorySnapshotMaterial>()
        var characters=userText.length.toLong()
        for(input in inputs) {
            val selected=repository.selectedParts(scope,input,budgetRevision)
            val savedParts=mutableListOf<HistorySnapshotPart>()
            val savedImages=linkedMapOf<String,ByteArray>()
            for(part in selected) {
                if(part.text!=null) {
                    characters+=part.text.length+part.source.length+8
                    if(characters>AttachmentLimits().maxMessageCharacters) throw AttachmentFailure("CONTENT_LIMIT")
                    parts+=ProviderContentPart.Text(part.text,part.source)
                    savedParts+=HistorySnapshotPart(part.id,part.source,text=part.text)
                } else {
                    val image=normalize(scope,requireNotNull(part.image))
                    if(part.id!="image:${image.sha256}") throw AttachmentFailure("CONTENT_CHANGED")
                    if(image.bytes.size>7*1024*1024 || image.width>8000 || image.height>8000 ||
                        bytes.values.sumOf {it.size.toLong()}+image.bytes.size>20L*1024*1024) throw AttachmentFailure("MODEL_IMAGE_BUDGET")
                    val imagePart=ProviderContentPart.Image(java.util.UUID.randomUUID().toString(),"image/png",image.sha256)
                    parts+=ProviderContentPart.Text("附件图像",part.source)
                    parts+=imagePart;bytes[imagePart]=image.bytes
                    savedParts+=HistorySnapshotPart(part.id,part.source,image=HistoryImageReference(imagePart.localToken,
                        image.sha256,image.bytes.size.toLong(),image.width,image.height))
                    savedImages[imagePart.localToken]=image.bytes
                }
            }
            history+=HistorySnapshotMaterial(HistoryAttachmentSnapshot(input,savedParts),savedImages)
        }
        if(!repository.accepts(scope)) throw AttachmentFailure("SCOPE_CHANGED")
        return PreparedAttachmentInput(parts,ProviderAttachmentPayloads(bytes) {repository.acceptsInput(scope,inputs)},userText,history)
    }
}

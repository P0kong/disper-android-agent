package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.*
import kotlinx.serialization.json.*

object AttachmentSelectionReceipt {
    fun encode(input:FrozenAttachmentInput):String = buildJsonObject {
        put("version",1);put("sourceSha256",input.handle.sha256);put("parserRevision",input.parserRevision)
        put("partIds",buildJsonArray {input.selectedPartIds.forEach {add(it)}})
        put("contentSha256",input.selectedContentSha256);put("confirmed",input.selectionConfirmed)
        put("budgetRevision",input.modelBudgetRevision)
    }.toString().also { decode(it,input.handle) }

    fun decode(raw:String,handle:AttachmentHandle):FrozenAttachmentInput {
        try {
            require(raw.length in 1..524288)
            val root=Json.parseToJsonElement(raw).jsonObject
            require(root.keys==setOf("version","sourceSha256","parserRevision","partIds","contentSha256","confirmed","budgetRevision"))
            fun text(key:String)=root.getValue(key).jsonPrimitive.let { require(it.isString);it.content }
            require(root.getValue("version").jsonPrimitive.int==1 && text("sourceSha256")==handle.sha256)
            val revision=text("parserRevision");val budget=text("budgetRevision");val digest=text("contentSha256")
            require(revision.length in 1..128 && budget.length in 1..128 && digest.matches(Regex("[a-f0-9]{64}")))
            val ids=root.getValue("partIds").jsonArray.map {it.jsonPrimitive.let { value->require(value.isString);value.content}}
            require(ids.size in 1..2048 && ids.distinct().size==ids.size && ids.all {it.length in 1..160})
            val confirmation=root.getValue("confirmed").jsonPrimitive
            require(!confirmation.isString)
            return FrozenAttachmentInput(handle,revision,ids,digest,confirmation.boolean,budget)
        } catch(_:Exception) {throw AttachmentFailure("SELECTION_RECEIPT_INVALID")}
    }
}

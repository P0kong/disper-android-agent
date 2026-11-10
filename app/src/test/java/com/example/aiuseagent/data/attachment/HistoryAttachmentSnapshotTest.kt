package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.*
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryAttachmentSnapshotTest {
    @get:Rule val temporary=TemporaryFolder()
    private suspend fun fixture(image:Boolean=false):HistoryAttachmentSnapshot {
        val bytes="synthetic original bytes".toByteArray()
        val scope=AttachmentScope(UUID.randomUUID().toString(),null,"CHAT",0,null,0,"profile",1,0)
        val repository=AttachmentRepository(temporary.newFolder().toPath(),object:AttachmentSource {
            override suspend fun describe(uri:String)=AttachmentSourceInfo("synthetic.txt","text/plain",bytes.size.toLong())
            override suspend fun open(uri:String)=ByteArrayInputStream(bytes)
        },{true})
        val handle=repository.stage(scope,"content://synthetic/owned",UUID.randomUUID().toString())
        val imageHash="b".repeat(64)
        val chosen=if(image) AttachmentPart("image:$imageHash","selected image",image=handle)
            else AttachmentPart("line:1","line 1",text="VISIBLE selected text")
        repository.parsed(scope,handle,AttachmentParseResult(handle.sha256,"parser:historic-1",listOf(chosen,
            AttachmentPart("line:hidden","line 2",text="HIDDEN UNSELECTED TEXT"))))
        val input=repository.freeze(scope,listOf(AttachmentSelection(handle.id,handle.sha256,"parser:historic-1",listOf(chosen.id),true)),"budget:old-1").single()
        val part=if(image) HistorySnapshotPart(chosen.id,chosen.source,image=HistoryImageReference(UUID.randomUUID().toString(),imageHash,100,3,4))
            else HistorySnapshotPart(chosen.id,chosen.source,text=chosen.text)
        return HistoryAttachmentSnapshot(input,listOf(part))
    }
    @Test fun roundTripContainsOnlyTheActualFrozenSelectionAndKeepsHistoricIdentity()=runBlocking<Unit> {
        val snapshot=fixture();val encoded=HistoryAttachmentSnapshotCodec.encode(snapshot)
        assertTrue(encoded.contains("VISIBLE selected text"));assertFalse(encoded.contains("HIDDEN UNSELECTED TEXT"))
        val read=HistoryAttachmentSnapshotCodec.decode(encoded)
        assertEquals(snapshot.input.handle,read.input.handle)
        assertEquals("parser:historic-1",read.input.parserRevision)
        assertEquals("budget:old-1",read.input.modelBudgetRevision)
        assertEquals(snapshot.input.selectedPartIds,read.parts.map {it.id})
        assertEquals(snapshot.input.selectedContentSha256,read.input.selectedContentSha256)
        assertEquals("VISIBLE selected text",read.parts.single().text)
        assertEquals(encoded,HistoryAttachmentSnapshotCodec.encode(read))
    }
    @Test fun addingAnUnselectedPartCannotProduceAValidSnapshot()=runBlocking<Unit> {
        val snapshot=fixture()
        invalid {HistoryAttachmentSnapshotCodec.encode(HistoryAttachmentSnapshot(snapshot.input,
            snapshot.parts+HistorySnapshotPart("line:hidden","line 2",text="HIDDEN UNSELECTED TEXT")))}
    }
    @Test fun selectedTextAndSourceTamperingAreRejected()=runBlocking<Unit> {
        val raw=HistoryAttachmentSnapshotCodec.encode(fixture())
        invalid {HistoryAttachmentSnapshotCodec.decode(raw.replace("VISIBLE selected text","altered content"))}
        invalid {HistoryAttachmentSnapshotCodec.decode(raw.replace("line 1","different source"))}
    }
    @Test fun handleAndReceiptMustAgreeOnOriginalSourceHash()=runBlocking<Unit> {
        val root=Json.parseToJsonElement(HistoryAttachmentSnapshotCodec.encode(fixture())).jsonObject
        val changed=buildJsonObject {root.forEach { (key,value) -> put(key,if(key=="handle") buildJsonObject {
            value.jsonObject.forEach { (field,item) -> put(field,if(field=="sha256") JsonPrimitive("a".repeat(64)) else item)}
        } else value)}}
        invalid {HistoryAttachmentSnapshotCodec.decode(changed.toString())}
    }
    @Test fun normalizedImageIdentityAndOwnedTokenAreBoundToFrozenPart()=runBlocking<Unit> {
        val snapshot=fixture(true);val part=snapshot.parts.single();val image=part.image!!
        val roundTrip=HistoryAttachmentSnapshotCodec.decode(HistoryAttachmentSnapshotCodec.encode(snapshot))
        assertEquals(image,roundTrip.parts.single().image)
        for(changed in listOf(image.copy(token="../external"),image.copy(sha256="c".repeat(64)),
            image.copy(sizeBytes=7L*1024*1024+1),image.copy(width=0),image.copy(width=8000,height=8000))) {
            invalid {HistoryAttachmentSnapshotCodec.encode(HistoryAttachmentSnapshot(snapshot.input,listOf(part.copy(image=changed))))}
        }
    }
    @Test fun unknownVersionsAndFieldsNeverBecomePreviewContent()=runBlocking<Unit> {
        val root=Json.parseToJsonElement(HistoryAttachmentSnapshotCodec.encode(fixture())).jsonObject
        for(extra in listOf("version" to JsonPrimitive(2),"originalUri" to JsonPrimitive("content://external/private"))) {
            invalid {HistoryAttachmentSnapshotCodec.decode(buildJsonObject {root.forEach {put(it.key,it.value)};put(extra.first,extra.second)}.toString())}
        }
        invalid {HistoryAttachmentSnapshotCodec.decode("[]")}
    }
    @Test fun snapshotPartsCannotBeExpandedByMutatingCallerCollections()=runBlocking<Unit> {
        val original=fixture();val supplied=original.parts.toMutableList()
        val snapshot=HistoryAttachmentSnapshot(original.input,supplied)
        supplied.clear();assertEquals(1,snapshot.parts.size)
        assertThrows(UnsupportedOperationException::class.java) {(snapshot.parts as MutableList<HistorySnapshotPart>).clear()}
    }
    private fun invalid(action:()->Unit) {
        assertEquals("HISTORY_SNAPSHOT_INVALID",assertThrows(AttachmentFailure::class.java,action).code)
    }
}

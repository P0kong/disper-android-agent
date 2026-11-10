package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.data.attachment.parser.NormalizedAttachmentImage
import com.example.aiuseagent.data.provider.openai.OpenAiCompatibleProvider
import com.example.aiuseagent.data.provider.anthropic.AnthropicMessagesProvider
import com.example.aiuseagent.data.provider.http.OkHttpTransport
import com.example.aiuseagent.data.provider.http.RetryPolicy
import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.domain.provider.*
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentWireFormatTest {
    @get:Rule val temp=TemporaryFolder(java.io.File("build/test-task-files").absoluteFile.apply {mkdirs()})
    private val scope=AttachmentScope("draft",null,"CHAT",0,null,0,"profile",0,0)
    private val png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC")
    private val digest=MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
    private fun capability()=FeatureCapability(CapabilitySupport.SUPPORTED,VerificationState.PASSED,sources=listOf(
        CapabilitySource(CapabilitySourceKind.OFFICIAL_DOCUMENTATION,"synthetic-official-reference","2026-10-28"),
        CapabilitySource(CapabilitySourceKind.PROTOCOL_FIXTURE,"attachment-wire-fixture","2026-10-28")))
    private suspend fun input():PreparedAttachmentInput {
        val repo=AttachmentRepository(temp.newFolder().toPath(),object:AttachmentSource {
            override suspend fun describe(uri:String)=AttachmentSourceInfo("synthetic.png","image/png")
            override suspend fun open(uri:String)=ByteArrayInputStream(png)
        },{it==scope})
        val handle=repo.stage(scope,"content://synthetic/image",UUID.randomUUID().toString())
        repo.parsed(scope,handle,AttachmentParseResult(handle.sha256,"fixture:1",listOf(
            AttachmentPart("chosen","page 1",text="selected synthetic text"),
            AttachmentPart("unselected","page 2",text="MUST_NOT_UPLOAD"),
            AttachmentPart("image:$digest","image 1",image=handle))))
        val frozen=repo.freeze(scope,listOf(AttachmentSelection(handle.id,handle.sha256,"fixture:1",listOf("chosen","image:$digest"),true)),"budget:1")
        return AttachmentRequestAssembler(repo,"budget:1") { _,_->NormalizedAttachmentImage(png,1,1,digest) }.assemble(scope,frozen,"")
    }
    private fun response()=MockResponse.Builder().code(200).body("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").build()
    @Test fun openAiRequestUsesSelectedTextAndActualImageBytesWithoutLocalToken()=runBlocking {
        MockWebServer().use { server ->
            server.start();server.enqueue(response())
            val prepared=input()
            val request=ChatRequest(listOf(prepared.userMessage()),attachmentPayloads=prepared.payloads)
            val config=ProviderConfig(ProviderType.OPENAI_COMPATIBLE,server.url("/v1").toString(),"synthetic-vision",allowInsecureHttp=true,imageCapability=capability())
            val events=OpenAiCompatibleProvider(config.type,OkHttpTransport(),RetryPolicy(maxAttempts=1)).stream(request,config,ProviderCredential.of("synthetic-not-a-real-key")).toList()
            assertTrue(events.last() is ProviderEvent.Completed)
            val body=server.takeRequest().body!!.utf8()
            assertTrue(body.contains("selected synthetic text"));assertFalse(body.contains("MUST_NOT_UPLOAD"))
            val parts=Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            val url=parts.last().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
            assertArrayEquals(png,java.util.Base64.getDecoder().decode(url.substringAfter(",")))
            assertFalse(body.contains("localToken"));assertFalse(body.contains("content://"))
        }
    }
    @Test fun unknownVisualCapabilityAndForgedPayloadAreRejectedBeforeHttp()=runBlocking {
        MockWebServer().use { server ->
            server.start()
            val prepared=input()
            val config=ProviderConfig(ProviderType.OPENAI_COMPATIBLE,server.url("/v1").toString(),"unknown",allowInsecureHttp=true)
            val provider=OpenAiCompatibleProvider(config.type,OkHttpTransport(),RetryPolicy(maxAttempts=1))
            val request=ChatRequest(listOf(prepared.userMessage()),attachmentPayloads=prepared.payloads)
            assertTrue(provider.stream(request,config,ProviderCredential.of("synthetic-not-a-real-key")).toList().last() is ProviderEvent.Failed)
            val forged=ProviderContentPart.Image(UUID.randomUUID().toString(),"image/png",digest)
            val invalid=request.copy(messages=listOf(ProviderMessage(ChatRole.USER,"",parts=listOf(forged))))
            assertTrue(provider.stream(invalid,config.copy(imageCapability=capability()),ProviderCredential.of("synthetic-not-a-real-key")).toList().last() is ProviderEvent.Failed)
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun anthropicSendsOnlyFrozenPartsWithBase64ImageSource()=runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n").build())
            val prepared=input()
            val config=ProviderConfig(ProviderType.ANTHROPIC,server.url("/v1").toString(),"synthetic-vision",allowInsecureHttp=true,imageCapability=capability())
            val events=AnthropicMessagesProvider(OkHttpTransport(),RetryPolicy(maxAttempts=1)).stream(
                ChatRequest(listOf(prepared.userMessage()),attachmentPayloads=prepared.payloads),config,
                ProviderCredential.of("synthetic-not-a-real-key")).toList()
            assertTrue(events.last() is ProviderEvent.Completed)
            val body=server.takeRequest().body!!.utf8()
            val parts=Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            val source=parts.last().jsonObject["source"]!!.jsonObject
            assertEquals("base64",source["type"]!!.jsonPrimitive.content)
            assertEquals("image/png",source["media_type"]!!.jsonPrimitive.content)
            assertArrayEquals(png,java.util.Base64.getDecoder().decode(source["data"]!!.jsonPrimitive.content))
            assertTrue(body.contains("selected synthetic text"));assertFalse(body.contains("MUST_NOT_UPLOAD"))
            assertFalse(body.contains("localToken"));assertFalse(body.contains("content://"))
        }
    }
    @Test fun invalidatedAttachmentDuringBackoffIsNeverUploadedAgain()=runBlocking {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse.Builder().code(503).body("{}").build());server.enqueue(response())
            var current=true
            val image=ProviderContentPart.Image(UUID.randomUUID().toString(),"image/png",digest)
            val payloads=ProviderAttachmentPayloads(mapOf(image to png)) {current}
            val request=ChatRequest(listOf(ProviderMessage(ChatRole.USER,"",parts=listOf(image))),attachmentPayloads=payloads)
            val config=ProviderConfig(ProviderType.OPENAI_COMPATIBLE,server.url("/v1").toString(),"synthetic-vision",allowInsecureHttp=true,imageCapability=capability())
            val provider=OpenAiCompatibleProvider(config.type,OkHttpTransport(),RetryPolicy(maxAttempts=2),backoff={current=false})
            val events=provider.stream(request,config,ProviderCredential.of("synthetic-not-a-real-key")).toList()
            assertEquals(1,server.requestCount)
            assertTrue(events.last() is ProviderEvent.Failed)
        }
    }
}

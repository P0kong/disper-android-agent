package com.example.aiuseagent.ui

import com.example.aiuseagent.data.provider.http.*
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** Test-only actual HTTP socket. All captured content is synthetic and remains in memory. */
internal class AttachmentLoopbackServer:Closeable {
    private val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).apply {soTimeout=1000}
    @Volatile private var closed=false
    @Volatile private var active:Socket?=null
    @Volatile var failure:Throwable?=null
        private set
    val bodies=LinkedBlockingQueue<String>()
    val requests=AtomicInteger()
    val attempts=AtomicInteger()
    val transport=object:HttpTransport {
        override suspend fun open(request:ProviderHttpRequest,timeouts:HttpTimeouts):StreamingHttpResponse {
            attempts.incrementAndGet()
            check(request.url=="https://api.openai.com/v1/chat/completions")
            check(request.headers.single {it.name.equals("Authorization",true)}.value=="Bearer $KEY")
            return OkHttpTransport().open(request.copy(url="http://127.0.0.1:${server.localPort}/v1/chat/completions",allowInsecureHttp=true),timeouts)
        }
    }
    private val worker=Thread({
        while(!closed) {
            try {
                server.accept().use {socket ->
                    active=socket;socket.soTimeout=5000
                    val input=socket.getInputStream().buffered()
                    val header=StringBuilder()
                    while(!header.endsWith("\r\n\r\n")) {
                        check(header.length<16384);val b=input.read();check(b>=0);header.append(b.toChar())
                    }
                    check(header.startsWith("POST /v1/chat/completions HTTP/1.1\r\n"))
                    val length=header.lines().single {it.startsWith("Content-Length:",true)}.substringAfter(':').trim().toInt()
                    check(length in 1..(20*1024*1024))
                    val bytes=ByteArray(length);var read=0
                    while(read<length){val n=input.read(bytes,read,length-read);check(n>0);read+=n}
                    requests.incrementAndGet();bodies.put(bytes.toString(Charsets.UTF_8))
                    val response="data: {\"choices\":[{\"delta\":{\"content\":\"synthetic image reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    val body=response.toByteArray()
                    socket.getOutputStream().use {out ->
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body);out.flush()
                    }
                    active=null
                }
            } catch(_:SocketTimeoutException) {if(active!=null){failure=IllegalStateException("FIXTURE_REQUEST_TIMEOUT");break}}
            catch(error:Throwable) {if(!closed)failure=error;break}
        }
    },"attachment-loopback-fixture").apply {isDaemon=true;start()}
    override fun close() {closed=true;active?.close();server.close();worker.join(6000);check(!worker.isAlive)}
    companion object {const val KEY="SYNTHETIC_MAIN_ACTIVITY_IMAGE_KEY"}
}

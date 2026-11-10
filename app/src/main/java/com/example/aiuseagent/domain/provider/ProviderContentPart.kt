package com.example.aiuseagent.domain.provider

sealed interface ProviderContentPart {
    data class Text(val text:String,val source:String?=null):ProviderContentPart {
        override fun toString()="ProviderContentPart.Text(<omitted>)"
    }
    data class Image(val localToken:String,val mimeType:String,val sha256:String):ProviderContentPart {
        override fun toString()="ProviderContentPart.Image(<omitted>)"
    }
    data class Document(val localToken:String,val mimeType:String,val sha256:String):ProviderContentPart {
        override fun toString()="ProviderContentPart.Document(<omitted>)"
    }
}

/** Only trusted attachment assembly can populate this per-request in-memory byte registry. */
class ProviderAttachmentPayloads internal constructor(
    entries:Map<ProviderContentPart.Image,ByteArray> = emptyMap(), private val isCurrent:()->Boolean = {true},
) {
    private val images=entries.mapValues { it.value.copyOf() }
    init {
        require(images.size<=10 && images.values.sumOf { it.size.toLong() }<=20L*1024*1024)
        require(images.values.all { it.isNotEmpty() && it.size<=7*1024*1024 })
    }
    fun current():Boolean=isCurrent()
    fun resolve(image:ProviderContentPart.Image):ByteArray? {
        if(!isCurrent() || image.mimeType!="image/png" || !TOKEN.matches(image.localToken)) return null
        val bytes=images[image] ?: return null
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return bytes.copyOf().takeIf { hash==image.sha256 && isCurrent() }
    }
    override fun toString()="ProviderAttachmentPayloads(count=${images.size})"
    companion object {
        internal fun combine(payloads:List<ProviderAttachmentPayloads>,isCurrent:()->Boolean):ProviderAttachmentPayloads {
            val images=linkedMapOf<ProviderContentPart.Image,ByteArray>()
            payloads.forEach { payload ->
                require(payload.current())
                payload.images.forEach { (key,bytes) -> require(images.put(key,bytes)==null) }
            }
            return ProviderAttachmentPayloads(images) {isCurrent() && payloads.all {it.current()}}
        }
        private val TOKEN=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

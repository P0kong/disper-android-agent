package com.example.aiuseagent.data.attachment.parser

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.example.aiuseagent.data.attachment.AttachmentParser
import com.example.aiuseagent.domain.attachment.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.*

class NormalizedAttachmentImage(val bytes: ByteArray, val width: Int, val height: Int, val sha256: String) {
    override fun toString() = "NormalizedAttachmentImage(width=$width,height=$height)"
}

/** Platform decoder applies EXIF orientation, including mirrored orientations. No network access. */
class ImageAttachmentProcessor(private val read: suspend (AttachmentHandle) -> ByteArray) : AttachmentParser {
    suspend fun normalize(handle: AttachmentHandle, limits: AttachmentLimits, previewMaxDimension:Int?=null): NormalizedAttachmentImage = withContext(Dispatchers.Default) {
        val bytes = read(handle)
        if (bytes.size.toLong() > limits.maxFileBytes) throw AttachmentFailure("FILE_LIMIT")
        if (!isSupportedImage(bytes)) throw AttachmentFailure("IMAGE_FORMAT")
        currentCoroutineContext().ensureActive()
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                if (info.size.width.toLong() * info.size.height > limits.maxPixels) throw AttachmentFailure("PIXEL_LIMIT")
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                previewMaxDimension?.let { maximum ->
                    require(maximum in 1..1024)
                    val ratio=minOf(1.0,maximum.toDouble()/maxOf(info.size.width,info.size.height))
                    decoder.setTargetSize(maxOf(1,(info.size.width*ratio).toInt()),maxOf(1,(info.size.height*ratio).toInt()))
                }
            }
        } catch (error: AttachmentFailure) { throw error }
        catch (_: Exception) { throw AttachmentFailure("IMAGE_INVALID") }
        try {
            currentCoroutineContext().ensureActive()
            val output = object : ByteArrayOutputStream() {
                override fun write(value: Int) { if (count.toLong() >= limits.maxFileBytes) throw AttachmentFailure("FILE_LIMIT"); super.write(value) }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    if (length > limits.maxFileBytes-count) throw AttachmentFailure("FILE_LIMIT")
                    super.write(buffer,offset,length)
                }
            }
            if (!bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) throw AttachmentFailure("IMAGE_INVALID")
            currentCoroutineContext().ensureActive()
            val normalized=output.toByteArray()
            NormalizedAttachmentImage(normalized,bitmap.width,bitmap.height,
                MessageDigest.getInstance("SHA-256").digest(normalized).joinToString("") { "%02x".format(it) })
        } finally { bitmap.recycle() }
    }
    override suspend fun parse(handle: AttachmentHandle, limits: AttachmentLimits): AttachmentParseResult {
        val image=normalize(handle,limits)
        return AttachmentParseResult(handle.sha256,"android-image:1",listOf(
            AttachmentPart("image:${image.sha256}","图像 ${image.width} × ${image.height}",image=handle)))
    }
    companion object {
        fun isSupportedImage(bytes: ByteArray): Boolean =
            bytes.size>=8 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)) ||
                bytes.size>=3 && bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte() && bytes[2]==0xff.toByte()
    }
}

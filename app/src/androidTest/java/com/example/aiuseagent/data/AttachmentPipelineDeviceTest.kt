package com.example.aiuseagent.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aiuseagent.data.attachment.parser.ImageAttachmentProcessor
import com.example.aiuseagent.domain.attachment.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentPipelineDeviceTest {
    private fun image():ByteArray {
        val bitmap=Bitmap.createBitmap(32,33,Bitmap.Config.ARGB_8888)
        return try { bitmap.eraseColor(Color.BLUE);ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    private fun handle(bytes:ByteArray)=AttachmentHandle("synthetic","synthetic","a".repeat(64),"application/octet-stream",bytes.size.toLong())
    @Test fun actualPlatformDecoderNormalizesPngAndGeneratesBoundImagePart()=runBlocking {
        val bytes=image();val processor=ImageAttachmentProcessor { bytes }
        val image=processor.normalize(handle(bytes),AttachmentLimits())
        assertEquals(32,image.width);assertEquals(33,image.height)
        assertTrue(ImageAttachmentProcessor.isSupportedImage(image.bytes))
        val part=processor.parse(handle(bytes),AttachmentLimits()).parts.single()
        assertEquals("image:${image.sha256}",part.id);assertEquals(handle(bytes),part.image)
    }
    @Test fun pixelLimitIsCheckedInHeaderBeforeBitmapAllocation()=runBlocking {
        val bytes=image()
        try { ImageAttachmentProcessor { bytes }.normalize(handle(bytes),AttachmentLimits(maxPixels=1055));fail("Expected pixel limit") }
        catch(error:AttachmentFailure) { assertEquals("PIXEL_LIMIT",error.code) }
    }
    @Test fun corruptImageCannotBecomeReady()=runBlocking {
        val bytes=image().copyOf(12)
        try { ImageAttachmentProcessor { bytes }.parse(handle(bytes),AttachmentLimits());fail("Expected corrupt image") }
        catch(error:AttachmentFailure) { assertEquals("IMAGE_INVALID",error.code) }
    }
}

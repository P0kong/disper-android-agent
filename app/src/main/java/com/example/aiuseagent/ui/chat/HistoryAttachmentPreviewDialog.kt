package com.example.aiuseagent.ui.chat

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.aiuseagent.ui.i18n.tr

private data class SavedPage(val source:String,val text:String?,val imagePartId:String?)

/** Display-only: pagination never changes the saved selection and offers no send authorization. */
@Composable
fun HistoryAttachmentPreviewDialog(preview:HistoryAttachmentPreview,onDismiss:()->Unit) {
    val pages=remember(preview) {buildList {
        for(part in preview.snapshot.parts) {
            val text=part.text
            if(text==null) add(SavedPage(part.source,null,part.id))
            else {
                var start=0
                do {
                    var end=minOf(start+4000,text.length)
                    if(end<text.length && end>start && text[end-1].isHighSurrogate()) end--
                    add(SavedPage(part.source,text.substring(start,end),null));start=end
                } while(start<text.length)
            }
        }
    }}
    var index by remember(preview) {mutableIntStateOf(0)}
    val page=pages.getOrNull(index)
    val decoded by produceState<Pair<Bitmap?,Boolean>>(null to false,preview,index) {
        value=null to false
        var pending:Bitmap?=null
        try {
            val bitmap=page?.imagePartId?.let {id -> withContext(Dispatchers.Default) {
                val bytes=requireNotNull(preview.imageBytes(id))
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) {decoder,info,_->
                    require(info.size.width.toLong()*info.size.height<=20_000_000)
                    val scale=minOf(1.0,1024.0/maxOf(info.size.width,info.size.height))
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
                }.also {pending=it}
            }}
            value=bitmap to true;pending=null
        } catch(cancelled:kotlinx.coroutines.CancellationException) {throw cancelled}
        catch(_:Exception) {value=null to true}
        finally {pending?.recycle()}
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text(tr("已发送附件", "Sent attachment"))},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) {
            Text(preview.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
            Text(tr("这是已发送的内容，查看不会再次发送。", "This content was already sent; viewing it won't send it again."),style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            page?.let {
                Text(it.source,style=MaterialTheme.typography.labelMedium)
                it.text?.let {text->Text(text)}
                if(it.imagePartId!=null) {
                    val bitmap=decoded.first
                    if(bitmap!=null) Image(bitmap.asImageBitmap(),contentDescription=tr("已发送图片", "Sent image"),modifier=Modifier.fillMaxWidth().heightIn(min=160.dp,max=280.dp))
                    else if(decoded.second) Text(tr("图片不可用。", "Image unavailable.")) else CircularProgressIndicator()
                }
            }
            if(pages.size>1) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={index--},enabled=index>0) {Text(tr("上一段", "Previous"))}
                Text("${index+1} / ${pages.size}",modifier=Modifier.padding(top=12.dp))
                TextButton(onClick={index++},enabled=index<pages.lastIndex) {Text(tr("下一段", "Next"))}
            }
        }
    },confirmButton={TextButton(onClick=onDismiss) {Text(tr("关闭", "Close"))}})
}

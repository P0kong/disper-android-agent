package com.example.aiuseagent.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.platform.AttachmentPickerController
import kotlinx.coroutines.launch
import com.example.aiuseagent.ui.i18n.tr

@Composable
fun rememberAttachmentPicker(chat:ChatViewModel, controller:AttachmentPickerController):(AttachmentPickerKind)->Unit {
    val scope=rememberCoroutineScope()
    val files=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        controller.take(AttachmentPickerKind.FILE)?.let { chat.acceptAttachmentResult(it,uris.map(Uri::toString)) }
    }
    val photos=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        controller.take(AttachmentPickerKind.PHOTO)?.let { chat.acceptAttachmentResult(it,uris.map(Uri::toString)) }
    }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        controller.take(AttachmentPickerKind.CAMERA)?.let { ticket ->
            if(success && ticket.outputUri!=null) chat.acceptAttachmentResult(ticket,listOf(ticket.outputUri)) { controller.finishCamera(ticket.outputUri) }
            else controller.finishCamera(ticket.outputUri)
        }
    }
    return { kind -> scope.launch {
        var output:String?=null
        try {
            if(kind==AttachmentPickerKind.CAMERA) output=controller.cameraOutput()
            val ticket=chat.prepareAttachmentPick(kind,output)
            if(ticket==null) controller.finishCamera(output)
            else {
                controller.register(ticket)
                when(kind) {
                    AttachmentPickerKind.FILE -> files.launch(arrayOf("*/*"))
                    AttachmentPickerKind.PHOTO -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    AttachmentPickerKind.CAMERA -> camera.launch(Uri.parse(requireNotNull(output)))
                }
            }
        } catch(_:android.content.ActivityNotFoundException) {
            controller.take(kind);controller.finishCamera(output)
            chat.reportAttachmentIssue(if(kind==AttachmentPickerKind.CAMERA) tr("没有可用的系统相机。", "No system camera available.") else tr("没有可用的系统选择器。", "No system picker available."))
        } catch(error:kotlinx.coroutines.CancellationException) {
            controller.finishCamera(output);throw error
        } catch(_:Exception) {
            controller.take(kind);controller.finishCamera(output);chat.reportAttachmentIssue(tr("无法打开附件入口，请重试。", "Can't open attachments. Try again."))
        }
    }; Unit }
}

package com.example.aiuseagent.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ChatLaunchPreferences(val startNewChat: Boolean = true, val lastSessionId: Long? = null,
    val lastBuildSessionId: Long? = null)

/** null 表示偏好尚未读完；会话启动选择必须等待它就绪。 */
interface ChatPreferences {
    val chatLaunch: StateFlow<ChatLaunchPreferences?>
    fun rememberSession(id: Long?)
    fun rememberSession(id: Long?,mode: String) { if(mode=="CHAT") rememberSession(id) }
}

/** 无 Android 依赖的默认值，也供纯 JVM 行为验证使用。 */
class MemoryChatPreferences(initial: ChatLaunchPreferences = ChatLaunchPreferences()) : ChatPreferences {
    override val chatLaunch = MutableStateFlow<ChatLaunchPreferences?>(initial)
    override fun rememberSession(id: Long?) {
        chatLaunch.value = chatLaunch.value?.copy(lastSessionId = id)
    }
    override fun rememberSession(id: Long?,mode: String) {
        if(mode=="BUILD") chatLaunch.value=chatLaunch.value?.copy(lastBuildSessionId=id) else rememberSession(id)
    }
}

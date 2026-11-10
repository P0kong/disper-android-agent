package com.example.aiuseagent

import android.content.Context

internal object ApplicationContainerFactory {
    private var instance:AppContainer?=null
    @Synchronized fun get(context:Context):AppContainer =
        instance ?: AppContainer(context).also {instance=it}
}

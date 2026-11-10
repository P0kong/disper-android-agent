package com.example.aiuseagent

import android.content.Context

/** In-process instrumentation boundary only; no Intent, preference or exported component controls it. */
internal object ApplicationContainerFactory {
    private var instance:AppContainer?=null
    @Volatile var instrumentationContainer:AppContainer?=null
    @Synchronized fun get(context:Context):AppContainer = instrumentationContainer ?: (
        instance ?: AppContainer(context).also {instance=it})
}

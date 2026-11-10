package com.example.aiuseagent.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Two explicit instrumentation processes share only this test's UUID-owned non-secret state. */
@RunWith(AndroidJUnit4::class)
class MainActivityRestartFlowTest {
    @Test fun savedProfileAndEncryptedCredentialSurviveRealProcessRestart() {
        val args=InstrumentationRegistry.getArguments()
        val stage=requireNotNull(args.getString("restartStage"))
        val fixture=requireNotNull(args.getString("restartFixtureId"))
        MainActivityAttachmentFlowTest().imageFlow(true,stage,fixture,history=true)
    }
}

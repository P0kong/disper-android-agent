package com.example.aiuseagent.ui.update

import com.example.aiuseagent.data.provider.update.ReleaseSource
import com.example.aiuseagent.data.update.UpdateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var now = UpdateRepository.AUTO_INTERVAL_MS * 2
    private var last = 0L
    private var dismissed: String? = null

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun releaseJson(tag: String) =
        """[{"tag_name":"$tag","name":"$tag","body":"notes","html_url":"https://github.com/POkong/disper-android-agent/releases/tag/$tag","draft":false}]"""

    private fun viewModel(tag: String, failing: Boolean = false): UpdateViewModel {
        val source = ReleaseSource { if (failing) ReleaseSource.Result.Network else ReleaseSource.Result.Ok(releaseJson(tag)) }
        val repository = UpdateRepository(
            source = source,
            versionName = { "0.1.0-alpha.1" },
            lastAutoCheckAt = { last },
            markAutoChecked = { last = it },
            clock = { now },
        )
        return UpdateViewModel(repository, dismissedTag = { dismissed }, onDismissTag = { dismissed = it })
    }

    @Test fun autoReminderShownThenDismissedAndNotRepeatedForSameTag() = runTest(dispatcher) {
        val first = viewModel("v0.1.0-alpha.2")
        first.autoCheck()
        advanceUntilIdle()
        assertEquals("v0.1.0-alpha.2", first.autoReminder.value?.tag)

        first.dismissAutoReminder()
        assertNull(first.autoReminder.value)
        assertEquals("v0.1.0-alpha.2", dismissed)

        last = 0L
        now += UpdateRepository.AUTO_INTERVAL_MS
        val again = viewModel("v0.1.0-alpha.2")
        again.autoCheck()
        advanceUntilIdle()
        assertNull("same tag must not be shown again", again.autoReminder.value)
    }

    @Test fun newerTagRearmsReminderAndFailureStaysSilent() = runTest(dispatcher) {
        dismissed = "v0.1.0-alpha.2"
        last = 0L
        now += UpdateRepository.AUTO_INTERVAL_MS
        val newer = viewModel("v0.1.0-alpha.3")
        newer.autoCheck()
        advanceUntilIdle()
        assertNotNull(newer.autoReminder.value)

        last = 0L
        now += UpdateRepository.AUTO_INTERVAL_MS
        val failing = viewModel("v0.1.0-alpha.4", failing = true)
        failing.autoCheck()
        advanceUntilIdle()
        assertNull("failed auto check must stay silent", failing.autoReminder.value)
        assertEquals(UpdateUiState.Idle, failing.state.value)
    }
}

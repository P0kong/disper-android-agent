package com.example.aiuseagent.data.update

import com.example.aiuseagent.data.provider.update.ReleaseSource
import com.example.aiuseagent.data.provider.update.UpdateOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRepositoryTest {

    private class FakeSource(var result: ReleaseSource.Result) : ReleaseSource {
        var calls = 0
        override suspend fun fetch(): ReleaseSource.Result {
            calls++
            return result
        }
    }

    private var now = 1_000_000L
    private var last = 0L

    private fun repository(source: ReleaseSource, version: String = "0.1.0-alpha.1") = UpdateRepository(
        source = source,
        versionName = { version },
        lastAutoCheckAt = { last },
        markAutoChecked = { last = it },
        clock = { now },
    )

    private fun json(vararg tags: String): String = tags.joinToString(",", "[", "]") { tag ->
        """{"tag_name":"$tag","name":"$tag","body":"notes","html_url":"https://github.com/POkong/disper-android-agent/releases/tag/$tag","draft":false}"""
    }

    @Test fun autoCheckRespects24HourBoundary() {
        val repo = repository(FakeSource(ReleaseSource.Result.Ok(json())))
        last = 0L
        now = UpdateRepository.AUTO_INTERVAL_MS - 1
        assertFalse(repo.shouldAutoCheck(now))
        now = UpdateRepository.AUTO_INTERVAL_MS
        assertTrue(repo.shouldAutoCheck(now))
    }

    @Test fun autoAttemptIsRecordedEvenOnNetworkFailure() = runBlocking {
        val source = FakeSource(ReleaseSource.Result.Network)
        val repo = repository(source)
        val outcome = repo.check(manual = false)
        assertEquals(UpdateOutcome.Unavailable(UpdateOutcome.Reason.OFFLINE), outcome)
        assertEquals(now, last)
    }

    @Test fun newerReleaseIsReturned() = runBlocking {
        val source = FakeSource(ReleaseSource.Result.Ok(json("v0.1.0-alpha.2")))
        val outcome = repository(source).check(manual = true)
        assertTrue(outcome is UpdateOutcome.Available)
        assertEquals("v0.1.0-alpha.2", (outcome as UpdateOutcome.Available).release.tag)
    }

    @Test fun httpAndParseErrorsMapToStableReasons() = runBlocking {
        assertEquals(UpdateOutcome.Unavailable(UpdateOutcome.Reason.PRIVATE_OR_NOT_FOUND),
            repository(FakeSource(ReleaseSource.Result.HttpError(404))).check(manual = true))
        assertEquals(UpdateOutcome.Unavailable(UpdateOutcome.Reason.RATE_LIMITED),
            repository(FakeSource(ReleaseSource.Result.HttpError(403))).check(manual = true))
        assertEquals(UpdateOutcome.Unavailable(UpdateOutcome.Reason.SERVER_ERROR),
            repository(FakeSource(ReleaseSource.Result.HttpError(500))).check(manual = true))
        assertEquals(UpdateOutcome.Unavailable(UpdateOutcome.Reason.INVALID_RESPONSE),
            repository(FakeSource(ReleaseSource.Result.Ok("not-json"))).check(manual = true))
    }
}

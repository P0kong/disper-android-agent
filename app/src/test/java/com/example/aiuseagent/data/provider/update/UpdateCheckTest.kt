package com.example.aiuseagent.data.provider.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    private val current = SemVer.parse("0.1.0-alpha.1")!!

    @Test fun versionOrderingRespectsStagesAndNumbers() {
        val a1 = SemVer.parse("v0.1.0-alpha.1")!!
        val a2 = SemVer.parse("0.1.0-alpha.2")!!
        val b1 = SemVer.parse("0.1.0-beta.1")!!
        val rc1 = SemVer.parse("0.1.0-rc.1")!!
        val stable = SemVer.parse("0.1.0")!!
        val older = SemVer.parse("0.0.9")!!
        assertTrue(a2 > a1)
        assertTrue(b1 > a2)
        assertTrue(rc1 > b1)
        assertTrue(stable > rc1)
        assertTrue(older < a1)
        assertEquals(0, SemVer.parse("v0.1.0")!!.compareTo(stable))
        assertNull(SemVer.parse("v0.1.1-latest"))
        assertNull(SemVer.parse("nightly"))
    }

    @Test fun selectorPicksHighestNewerReleaseAndSkipsDraftsAndBadUrls() {
        val json = """
        [
          {"tag_name":"v0.1.0-alpha.2","name":"a2","body":"notes a2","html_url":"https://github.com/POkong/disper-android-agent/releases/tag/v0.1.0-alpha.2","draft":false},
          {"tag_name":"v0.1.0","name":"stable","body":"stable notes","html_url":"https://github.com/POkong/disper-android-agent/releases/tag/v0.1.0","draft":false},
          {"tag_name":"v0.2.0","name":"draft","body":"draft","html_url":"https://github.com/POkong/disper-android-agent/releases/tag/v0.2.0","draft":true},
          {"tag_name":"v0.0.9","name":"old","body":"old","html_url":"https://github.com/POkong/disper-android-agent/releases/tag/v0.0.9","draft":false},
          {"tag_name":"v0.3.0","name":"bad url","body":"x","html_url":"https://evil.example.com/v0.3.0","draft":false},
          {"tag_name":"latest","name":"bad tag","body":"x","html_url":"https://github.com/POkong/disper-android-agent/releases/tag/latest","draft":false}
        ]
        """.trimIndent()
        val outcome = ReleaseSelector.select(json, current)
        assertTrue(outcome is UpdateOutcome.Available)
        val release = (outcome as UpdateOutcome.Available).release
        assertEquals("v0.1.0", release.tag)
        assertEquals("stable notes", release.body)
    }

    @Test fun selectorReturnsUpToDateForEmptyOrOlderReleases() {
        assertEquals(UpdateOutcome.UpToDate, ReleaseSelector.select("[]", current))
        val older = """
        [{"tag_name":"v0.0.9","name":"old","body":"","html_url":"https://github.com/POkong/disper-android-agent/releases/tag/v0.0.9","draft":false}]
        """.trimIndent()
        assertEquals(UpdateOutcome.UpToDate, ReleaseSelector.select(older, current))
    }

    @Test fun bodyIsTruncatedAndTrustedUrlEnforced() {
        val longBody = "x".repeat(5000)
        val json = """
        [{"tag_name":"v0.1.0-alpha.2","name":"a2","body":"$longBody","html_url":"https://github.com/POkong/disper-android-agent/releases/tag/v0.1.0-alpha.2","draft":false}]
        """.trimIndent()
        val outcome = ReleaseSelector.select(json, current) as UpdateOutcome.Available
        assertEquals(ReleaseSelector.MAX_BODY_CHARS, outcome.release.body.length)
        assertTrue(ReleaseSelector.isTrustedUrl("https://github.com/POkong/disper-android-agent/releases/tag/v0.1.0"))
        assertTrue(!ReleaseSelector.isTrustedUrl("http://github.com/POkong/disper-android-agent/releases/tag/v0.1.0"))
        assertTrue(!ReleaseSelector.isTrustedUrl("https://github.com/other/repo/releases/tag/v0.1.0"))
    }
}

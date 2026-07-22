package com.concertonotes.smsforwarder

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateManagerInstrumentedTest {
	@Test
	fun parsesNewerReleaseWithTrustedApk() {
		val result = parseGitHubReleaseResponse(
			"""
			{
			  "tag_name": "v1.5.1",
			  "assets": [{
			    "name": "XinJie-Forwarder-1.5.1.apk",
			    "browser_download_url": "https://github.com/ConcertoNotes/smsforward-2/releases/download/v1.5.1/XinJie-Forwarder-1.5.1.apk"
			  }]
			}
			""".trimIndent(),
			"1.5.0"
		)

		assertTrue(result is UpdateCheckResult.Available)
		assertEquals("1.5.1", (result as UpdateCheckResult.Available).release.versionName)
	}

	@Test
	fun ignoresCurrentReleaseAndRejectsUntrustedApk() {
		val current = parseGitHubReleaseResponse(
			"""{"tag_name":"v1.5.0","assets":[]}""",
			"1.5.0"
		)
		val untrusted = parseGitHubReleaseResponse(
			"""
			{
			  "tag_name": "v1.5.1",
			  "assets": [{
			    "name": "app.apk",
			    "browser_download_url": "https://github.com/other/project/releases/download/v1.5.1/app.apk"
			  }]
			}
			""".trimIndent(),
			"1.5.0"
		)

		assertTrue(current is UpdateCheckResult.UpToDate)
		assertTrue(untrusted is UpdateCheckResult.Failed)
	}
}

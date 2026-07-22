package com.concertonotes.smsforwarder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
	@Test
	fun `parses release tags with optional prefix and suffix`() {
		assertEquals(listOf(1, 5, 0), parseVersionParts("v1.5.0"))
		assertEquals(listOf(2, 0, 1), parseVersionParts("2.0.1+12"))
		assertNull(parseVersionParts("release-1.5"))
	}

	@Test
	fun `compares semantic versions numerically`() {
		assertTrue(isNewerVersion("1.10.0", "1.9.9"))
		assertTrue(isNewerVersion("v2.0.0", "1.99.99"))
		assertFalse(isNewerVersion("1.5.0", "1.5.0"))
		assertFalse(isNewerVersion("1.4.9", "1.5.0"))
	}

	@Test
	fun `accepts only release assets from this repository`() {
		assertTrue(
			isTrustedReleaseAssetUrl(
				"https://github.com/ConcertoNotes/smsforward-2/releases/download/v1.5.1/app.apk"
			)
		)
		assertFalse(
			isTrustedReleaseAssetUrl(
				"https://github.com/other/project/releases/download/v1.5.1/app.apk"
			)
		)
		assertFalse(
			isTrustedReleaseAssetUrl(
				"http://github.com/ConcertoNotes/smsforward-2/releases/download/v1.5.1/app.apk"
			)
		)
	}

}

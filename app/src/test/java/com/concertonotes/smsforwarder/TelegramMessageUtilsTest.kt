package com.concertonotes.smsforwarder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramMessageUtilsTest {
	@Test
	fun escapeTelegramHtml_escapesReservedCharacters() {
		assertEquals(
			"&lt;tag attr=&quot;x&quot;&gt;A &amp; B&lt;/tag&gt;",
			escapeTelegramHtml("<tag attr=\"x\">A & B</tag>")
		)
	}

	@Test
	fun splitTelegramContent_reconstructsContentWithinLimit() {
		val content = "a".repeat(15) + "\n" + "b".repeat(15)
		val chunks = splitTelegramContent(content, maxChunkSize = 20)

		assertEquals(content, chunks.joinToString(separator = ""))
		assertTrue(chunks.all { it.length <= 20 })
	}

	@Test
	fun splitTelegramContent_doesNotSplitSurrogatePair() {
		val content = "1234\uD83D\uDE03abcd"
		val chunks = splitTelegramContent(content, maxChunkSize = 5)

		assertEquals(content, chunks.joinToString(separator = ""))
		assertFalse(chunks.any { it.isNotEmpty() && Character.isHighSurrogate(it.last()) })
	}

	@Test
	fun splitTelegramContent_returnsOneChunkForEmptyContent() {
		assertEquals(listOf(""), splitTelegramContent(""))
	}

	@Test
	fun splitTelegramContent_rejectsNonPositiveLimit() {
		assertThrows(IllegalArgumentException::class.java) {
			splitTelegramContent("content", maxChunkSize = 0)
		}
	}

	@Test
	fun calculateRetryBackoff_growsAndCapsAtFifteenMinutes() {
		assertEquals(10_000L, calculateRetryBackoff(0))
		assertEquals(20_000L, calculateRetryBackoff(1))
		assertEquals(640_000L, calculateRetryBackoff(6))
		assertEquals(MAX_RETRY_DELAY_MS, calculateRetryBackoff(7))
		assertEquals(MAX_RETRY_DELAY_MS, calculateRetryBackoff(Int.MAX_VALUE))
	}
}

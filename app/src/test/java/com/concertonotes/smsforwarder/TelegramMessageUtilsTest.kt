package com.concertonotes.smsforwarder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramMessageUtilsTest {
	@Test
	fun createFeishuSignature_matchesOfficialHmacFormat() {
		assertEquals(
			"mbm4Y4oluIPQ00qlBIhX8vAZ0EKv3nw0LuTb91jPL84=",
			createFeishuSignature(1_700_000_000L, "test-secret")
		)
	}

	@Test
	fun isValidFeishuWebhook_acceptsOnlyOfficialHttpsHookUrls() {
		assertTrue(isValidFeishuWebhook("https://open.feishu.cn/open-apis/bot/v2/hook/example-id"))
		assertFalse(isValidFeishuWebhook("http://open.feishu.cn/open-apis/bot/v2/hook/example-id"))
		assertFalse(isValidFeishuWebhook("https://example.com/open-apis/bot/v2/hook/example-id"))
		assertFalse(isValidFeishuWebhook("https://open.feishu.cn.evil.example/open-apis/bot/v2/hook/example-id"))
	}

	@Test
	fun isFeishuSuccessCode_supportsCurrentAndLegacyResponses() {
		assertTrue(isFeishuSuccessCode(code = 0, legacyStatusCode = null))
		assertTrue(isFeishuSuccessCode(code = null, legacyStatusCode = 0))
		assertFalse(isFeishuSuccessCode(code = 9499, legacyStatusCode = null))
		assertFalse(isFeishuSuccessCode(code = null, legacyStatusCode = null))
	}

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

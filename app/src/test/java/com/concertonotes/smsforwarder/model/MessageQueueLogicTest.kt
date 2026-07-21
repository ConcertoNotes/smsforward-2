package com.concertonotes.smsforwarder.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageQueueLogicTest {
	private val original = MessageItem(
		content = "verification code 123456",
		sender = "SMS from +10000000000",
		packageName = "SMS message",
		timestamp = 1_700_000_000_000L
	)

	@Test
	fun isSamePendingMessage_matchesExactLogicalDuplicate() {
		assertTrue(isSamePendingMessage(original, original.copy()))
	}

	@Test
	fun isSamePendingMessage_keepsRepeatedContentAtDifferentTime() {
		assertFalse(isSamePendingMessage(original, original.copy(timestamp = original.timestamp + 1_000L)))
	}

	@Test
	fun isSamePendingMessage_keepsDifferentMultipartContentAtSameTime() {
		assertFalse(isSamePendingMessage(original, original.copy(content = "second segment")))
	}

	@Test
	fun isSamePendingMessage_keepsDifferentSourceAtSameTime() {
		assertFalse(isSamePendingMessage(original, original.copy(packageName = "com.example.app")))
	}

	@Test
	fun selectNextReadyMessage_skipsDelayedRetryAtQueueHead() {
		val now = 1_700_000_100_000L
		val delayed = original.copy(nextAttemptAt = now + 60_000L)
		val ready = original.copy(timestamp = original.timestamp + 1_000L)

		assertEquals(ready, selectNextReadyMessage(listOf(delayed, ready), now))
	}

	@Test
	fun selectNextReadyMessage_prioritizesFreshMessagesOverRetries() {
		val now = 1_700_000_100_000L
		val retry = original.copy(retryCount = 4)
		val fresh = original.copy(timestamp = original.timestamp + 1_000L)

		assertEquals(fresh, selectNextReadyMessage(listOf(retry, fresh), now))
	}

	@Test
	fun selectNextReadyMessage_skipsMessagesAlreadyInFlight() {
		val now = 1_700_000_100_000L
		val first = original.copy()
		val second = original.copy(timestamp = original.timestamp + 1_000L)

		assertEquals(second, selectNextReadyMessage(listOf(first, second), now, listOf(first)))
	}

	@Test
	fun selectNextReadyMessage_excludesByIdentityNotValue() {
		val now = 1_700_000_100_000L
		val equalButDistinct = original.copy()

		assertEquals(equalButDistinct, selectNextReadyMessage(listOf(equalButDistinct), now, listOf(original)))
	}
}

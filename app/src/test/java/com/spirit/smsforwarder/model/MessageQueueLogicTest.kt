package com.spirit.smsforwarder.model

import org.junit.Assert.assertFalse
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
}

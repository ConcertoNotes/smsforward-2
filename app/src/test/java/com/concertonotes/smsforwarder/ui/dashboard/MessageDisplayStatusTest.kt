package com.concertonotes.smsforwarder.ui.dashboard

import com.concertonotes.smsforwarder.model.MessageItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageDisplayStatusTest {
	private val message = MessageItem(
		content = "test",
		sender = "sender",
		packageName = "package",
		timestamp = 1_700_000_000_000L
	)

	@Test
	fun pendingRetry_isNotDisplayedAsFailure() {
		assertEquals(
			MessageDisplayStatus.RETRYING,
			resolveMessageDisplayStatus(message.copy(isError = true, retryCount = 2), isPending = true)
		)
	}

	@Test
	fun oneDeliveredChannel_isDisplayedAsPartialSuccess() {
		assertEquals(
			MessageDisplayStatus.PARTIALLY_SENT,
			resolveMessageDisplayStatus(message.copy(feishuDelivered = true, retryCount = 2), isPending = true)
		)
	}

	@Test
	fun completedMessage_isDisplayedAsSuccess() {
		assertEquals(
			MessageDisplayStatus.SUCCESS,
			resolveMessageDisplayStatus(message.copy(isSent = true), isPending = false)
		)
	}
}

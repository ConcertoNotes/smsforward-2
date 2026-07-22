package com.concertonotes.smsforwarder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerHealthTest {
	@Test
	fun `does not alert while listener is connected`() {
		assertFalse(shouldAlertListenerDisconnected(true, true, 10))
	}

	@Test
	fun `waits for repeated rebind failures before alerting`() {
		assertFalse(shouldAlertListenerDisconnected(true, false, 1))
		assertTrue(shouldAlertListenerDisconnected(true, false, 2))
	}

	@Test
	fun `does not treat missing permission as a failed rebind`() {
		assertFalse(shouldAlertListenerDisconnected(false, false, 10))
	}
}

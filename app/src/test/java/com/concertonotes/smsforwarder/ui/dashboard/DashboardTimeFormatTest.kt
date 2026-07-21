package com.concertonotes.smsforwarder.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class DashboardTimeFormatTest {
	@Test
	fun formatMessageTimestamp_convertsEpochToSelectedTimeZone() {
		val timestamp = 1_700_000_000_000L

		assertEquals("2023-11-15 06:13:20", formatMessageTimestamp(timestamp, ZoneId.of("Asia/Taipei")))
		assertEquals("2023-11-14 22:13:20", formatMessageTimestamp(timestamp, ZoneId.of("UTC")))
	}
}

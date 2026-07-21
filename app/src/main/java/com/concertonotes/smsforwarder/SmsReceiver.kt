package com.concertonotes.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
//import android.util.Log
import com.concertonotes.smsforwarder.model.MessageItem
import com.concertonotes.smsforwarder.model.QueueSingleton

class SmsReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
			//If android kills the core service, waking up via this manifest receiver should restart it
			try {
				androidx.core.content.ContextCompat.startForegroundService(
					context,
					Intent(context, AllNotificationService::class.java)
				)
			} catch (e: Exception) {
				e.printStackTrace()
			}

			val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
			if (messages.isEmpty()) return

			val firstMessage = messages.first()
			val msg = MessageItem(
				content = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() },
				sender = "SMS from ${firstMessage.displayOriginatingAddress}",
				packageName = "SMS message",
				timestamp = messages.minOf { it.timestampMillis }
			)

			// Separate SMS broadcasts can legitimately have identical content and
			// second-level timestamps, so only deduplicate notification updates.
			if (QueueSingleton.enqueue(context, msg, deduplicate = false)) {
				QueueSingleton.wakeUp(context)
			}
		}
	}
}

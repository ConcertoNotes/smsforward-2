package com.spirit.smsforwarder

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.spirit.smsforwarder.model.MessageItem
import com.spirit.smsforwarder.model.QueueSingleton

class NotificationListener : NotificationListenerService() {
	private companion object {
		val EXCLUDED_PACKAGES = setOf(
			"com.google.android.apps.messaging",
			"com.android.messaging",
			"com.spirit.smsforwarder",
			"com.xiaomi.discover",
			"android",
			"com.android.systemui",
			"com.google.android.gms",
			"com.android.vending"
		)
	}

	override fun onListenerConnected() {
		super.onListenerConnected()
		QueueSingleton.initialize(this)
		QueueSingleton.isListenerConnected = true
	}

	override fun onListenerDisconnected() {
		super.onListenerDisconnected()
		QueueSingleton.isListenerConnected = false
	}

	override fun onNotificationPosted(sbn: StatusBarNotification) {
		val packageName = sbn.packageName
		if (packageName !in EXCLUDED_PACKAGES && !getSharedPreferences("smsforwarder_prefs", 0).getBoolean("${packageName}_ignore_enabled", false)) {
			val notification = sbn.notification
			val extras = notification.extras
			val appName = getAppName(packageName)
			val messageParts = linkedSetOf<String>()
			extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
				?.let { messageParts.add("Title: $it") }
			extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
				?.let { messageParts.add("Text: $it") }
			extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
				?.let { messageParts.add(it) }
			extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.takeIf { it.isNotBlank() }
				?.let { messageParts.add(it) }
			extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.takeIf { it.isNotBlank() }
				?.let { messageParts.add(it) }
			if (messageParts.isEmpty()) return

			val msg = MessageItem(
				content = messageParts.joinToString("\n"),
				sender = appName,
				packageName = packageName,
				timestamp = sbn.postTime
			)

			if (QueueSingleton.enqueue(this, msg)) {
				QueueSingleton.wakeUp(this)
			}

		}
	}

	private fun getAppName(packageName: String): String {
		return try {
			val packageManager = packageManager
			val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
			packageManager.getApplicationLabel(applicationInfo).toString()
		} catch (e: PackageManager.NameNotFoundException) {
			packageName
		}
	}
}

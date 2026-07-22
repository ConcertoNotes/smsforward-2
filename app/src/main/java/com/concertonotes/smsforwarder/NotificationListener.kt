package com.concertonotes.smsforwarder

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.concertonotes.smsforwarder.model.APP_PREFERENCES_NAME
import com.concertonotes.smsforwarder.model.MessageItem
import com.concertonotes.smsforwarder.model.QueueSingleton

class NotificationListener : NotificationListenerService() {
	private companion object {
		val EXCLUDED_PACKAGES = setOf(
			"com.google.android.apps.messaging",
			"com.android.messaging",
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
		Log.i("ConcertoForwarder", "Notification listener connected")
		notifyForwardingServiceOfConnectionChange()
	}

	override fun onListenerDisconnected() {
		super.onListenerDisconnected()
		QueueSingleton.isListenerConnected = false
		Log.w("ConcertoForwarder", "Notification listener disconnected; requesting rebind")
		try {
			requestRebind(ComponentName(this, NotificationListener::class.java))
		} catch (exception: Exception) {
			Log.e("ConcertoForwarder", "Unable to request notification listener rebind", exception)
		}
		notifyForwardingServiceOfConnectionChange()
	}

	private fun notifyForwardingServiceOfConnectionChange() {
		try {
			ContextCompat.startForegroundService(
				this,
				Intent(this, AllNotificationService::class.java)
					.setAction(ACTION_LISTENER_CONNECTION_CHANGED)
			)
		} catch (exception: Exception) {
			Log.e("ConcertoForwarder", "Unable to refresh listener connection state", exception)
		}
	}

	override fun onNotificationPosted(sbn: StatusBarNotification) {
		val packageName = sbn.packageName
		if (packageName != applicationContext.packageName &&
			packageName !in EXCLUDED_PACKAGES &&
			!getSharedPreferences(APP_PREFERENCES_NAME, 0).getBoolean("${packageName}_ignore_enabled", true)
		) {
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

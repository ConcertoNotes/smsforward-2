package com.concertonotes.smsforwarder

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.concertonotes.smsforwarder.model.APP_PREFERENCES_NAME
import com.concertonotes.smsforwarder.model.MessageItem
import com.concertonotes.smsforwarder.model.QueueSingleton

@SuppressLint("LogConditional")
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
		Log.i("ConcertoForwarder", "Notification callback received from $packageName")
		try {
			if (packageName == applicationContext.packageName || packageName in EXCLUDED_PACKAGES) {
				Log.i("ConcertoForwarder", "Notification ignored from $packageName; excluded package")
				return
			}
			if (getSharedPreferences(APP_PREFERENCES_NAME, 0)
					.getBoolean("${packageName}_ignore_enabled", true)
			) {
				Log.i("ConcertoForwarder", "Notification ignored from $packageName; forwarding disabled")
				return
			}

			// ColorOS may suspend the process again as soon as the listener Binder call
			// returns, so keep the CPU awake before doing any notification parsing.
			QueueSingleton.wakeUp(this)
			val messageParts = extractMessageParts(sbn.notification)
			if (messageParts.isEmpty()) {
				Log.w("ConcertoForwarder", "Notification ignored from $packageName; no readable text")
				return
			}

			val msg = MessageItem(
				content = messageParts.joinToString("\n"),
				sender = getAppName(packageName),
				packageName = packageName,
				timestamp = sbn.postTime
			)
			val added = QueueSingleton.enqueue(this, msg)
			Log.i("ConcertoForwarder", "Notification captured from $packageName; queued=$added")
			startForwardingServiceForPendingMessages()
		} catch (exception: Exception) {
			Log.e("ConcertoForwarder", "Unable to process notification from $packageName", exception)
		}
	}

	private fun extractMessageParts(notification: Notification): Set<String> {
		val messageParts = linkedSetOf<String>()
		val extras = notification.extras

		fun addText(value: CharSequence?, prefix: String? = null) {
			val text = value?.toString()?.trim().orEmpty()
			if (text.isNotEmpty()) {
				messageParts.add(if (prefix == null) text else "$prefix: $text")
			}
		}

		addText(extras.getCharSequence(Notification.EXTRA_TITLE), "Title")
		addText(extras.getCharSequence(Notification.EXTRA_TEXT), "Text")
		addText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
		addText(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
		addText(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
		extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { addText(it) }

		readMessageBundles(extras, Notification.EXTRA_MESSAGES, ::addText)
		readMessageBundles(extras, Notification.EXTRA_HISTORIC_MESSAGES, ::addText)

		addText(notification.tickerText)
		return messageParts
	}

	private fun readMessageBundles(
		extras: Bundle,
		key: String,
		addText: (CharSequence?, String?) -> Unit
	) {
		val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			extras.getParcelableArray(key, Bundle::class.java)
		} else {
			@Suppress("DEPRECATION")
			extras.getParcelableArray(key)?.mapNotNull { it as? Bundle }?.toTypedArray()
		}
		messages?.forEach { message ->
			addText(message.getCharSequence("text"), null)
		}
	}

	private fun startForwardingServiceForPendingMessages() {
		try {
			ContextCompat.startForegroundService(
				this,
				Intent(this, AllNotificationService::class.java)
					.setAction(ACTION_PROCESS_PENDING_MESSAGES)
			)
		} catch (exception: Exception) {
			Log.e("ConcertoForwarder", "Unable to wake forwarding service", exception)
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

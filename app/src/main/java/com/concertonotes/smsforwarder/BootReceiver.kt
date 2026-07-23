package com.concertonotes.smsforwarder

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

class BootReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
			intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
		) {
			try {
				NotificationListenerService.requestRebind(
					ComponentName(context, NotificationListener::class.java)
				)
				val serviceIntent = Intent(context, AllNotificationService::class.java)
				androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
			} catch (exception: Exception) {
				exception.printStackTrace()
			}
		}
	}
}

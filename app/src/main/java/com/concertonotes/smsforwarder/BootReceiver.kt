package com.concertonotes.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
			try {
				val serviceIntent = Intent(context, AllNotificationService::class.java)
				androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
			} catch (exception: Exception) {
				exception.printStackTrace()
			}
		}
	}
}

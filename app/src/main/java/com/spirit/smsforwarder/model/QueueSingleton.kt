package com.spirit.smsforwarder.model

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

internal fun isSamePendingMessage(first: MessageItem, second: MessageItem): Boolean {
	return first.timestamp == second.timestamp &&
		first.content == second.content &&
		first.sender == second.sender &&
		first.packageName == second.packageName
}

object QueueSingleton {
	private const val PREFS_NAME = "smsforwarder_prefs"
	private const val PENDING_MESSAGES_KEY = "pending_messages"
	private const val MAX_HISTORY_SIZE = 200
	private const val WAKE_LOCK_TIMEOUT_MS = 60_000L

	private val stateLock = Any()
	@Volatile
	private var initialized = false
	private lateinit var applicationContext: Context

	val messageQueue: ConcurrentLinkedQueue<MessageItem> = ConcurrentLinkedQueue()
	val messageHistory: ConcurrentLinkedQueue<MessageItem> = ConcurrentLinkedQueue()

	@Volatile
	var isListenerConnected = false

	private var wakeLock: PowerManager.WakeLock? = null

	fun initialize(context: Context) {
		if (initialized) return
		synchronized(stateLock) {
			if (initialized) return

			applicationContext = context.applicationContext
			val preferences = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			restoreQueue(preferences.getString(PENDING_MESSAGES_KEY, null), messageQueue)

			val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
			wakeLock = powerManager.newWakeLock(
				PowerManager.PARTIAL_WAKE_LOCK,
				"SMSForwarder::MessageProcessing"
			).apply { setReferenceCounted(false) }
			initialized = true
		}
	}

	fun containsMessage(item: MessageItem): Boolean {
		return messageQueue.any { isSamePendingMessage(it, item) }
	}

	fun enqueue(context: Context, item: MessageItem): Boolean {
		initialize(context)
		synchronized(stateLock) {
			if (containsMessage(item)) return false
			messageQueue.add(item)
			persistLocked()
			return true
		}
	}

	fun updatePending(item: MessageItem) {
		synchronized(stateLock) {
			if (messageQueue.contains(item)) persistLocked()
		}
	}

	fun completePending(item: MessageItem) {
		synchronized(stateLock) {
			if (messageQueue.peek() == item) messageQueue.poll() else messageQueue.remove(item)
			messageHistory.add(item)
			trimHistory()
			persistLocked()
		}
	}

	fun discardPendingOlderThan(cutoffTimestamp: Long) {
		synchronized(stateLock) {
			val changed = messageQueue.removeIf { it.timestamp < cutoffTimestamp }
			if (changed) persistLocked()
		}
	}

	fun wakeUp(context: Context? = null) {
		try {
			if (!initialized && context != null) initialize(context)
			wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
		} catch (exception: Exception) {
			exception.printStackTrace()
		}
	}

	fun releaseWakeLock() {
		try {
			if (wakeLock?.isHeld == true) wakeLock?.release()
		} catch (exception: Exception) {
			exception.printStackTrace()
		}
	}

	@SuppressLint("ApplySharedPref")
	private fun persistLocked() {
		if (!initialized) return
		applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			.edit()
			.putString(PENDING_MESSAGES_KEY, queueToJson(messageQueue).toString())
			.commit()
	}

	private fun queueToJson(queue: ConcurrentLinkedQueue<MessageItem>): JSONArray {
		return JSONArray().apply {
			queue.forEach { item ->
				put(JSONObject().apply {
					put("content", item.content)
					put("sender", item.sender)
					put("packageName", item.packageName)
					put("timestamp", item.timestamp)
					put("isSent", item.isSent)
					put("isError", item.isError)
					put("retryCount", item.retryCount)
					put("nextAttemptAt", item.nextAttemptAt)
					put("nextChunkIndex", item.nextChunkIndex)
				})
			}
		}
	}

	private fun restoreQueue(serialized: String?, target: ConcurrentLinkedQueue<MessageItem>) {
		if (serialized.isNullOrBlank()) return
		try {
			val array = JSONArray(serialized)
			for (index in 0 until array.length()) {
				val item = array.getJSONObject(index)
				target.add(
					MessageItem(
						content = item.getString("content"),
						sender = item.getString("sender"),
						packageName = item.getString("packageName"),
						timestamp = item.getLong("timestamp"),
						isSent = item.optBoolean("isSent"),
						isError = item.optBoolean("isError"),
						retryCount = item.optInt("retryCount"),
						nextAttemptAt = item.optLong("nextAttemptAt"),
						nextChunkIndex = item.optInt("nextChunkIndex")
					)
				)
			}
		} catch (exception: Exception) {
			exception.printStackTrace()
			target.clear()
		}
	}

	private fun trimHistory() {
		while (messageHistory.size > MAX_HISTORY_SIZE) messageHistory.poll()
	}
}

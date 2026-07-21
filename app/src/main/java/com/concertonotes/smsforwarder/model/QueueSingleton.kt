package com.concertonotes.smsforwarder.model

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

const val APP_PREFERENCES_NAME = "concerto_smsforwarder_prefs"

internal fun isSamePendingMessage(first: MessageItem, second: MessageItem): Boolean {
	return first.timestamp == second.timestamp &&
		first.content == second.content &&
		first.sender == second.sender &&
		first.packageName == second.packageName
}

internal fun selectNextReadyMessage(
	messages: Iterable<MessageItem>,
	now: Long,
	excludedMessages: Collection<MessageItem> = emptyList()
): MessageItem? {
	return messages.asSequence()
		.filter { candidate -> excludedMessages.none { it === candidate } }
		.filter { it.nextAttemptAt <= now }
		.minWithOrNull(compareBy<MessageItem> { it.retryCount }.thenBy { it.timestamp })
}

object QueueSingleton {
	private const val PENDING_MESSAGES_KEY = "pending_messages"
	private const val MESSAGE_HISTORY_KEY = "message_history"
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
	@Volatile
	private var queueChangedListener: (() -> Unit)? = null

	fun setQueueChangedListener(listener: (() -> Unit)?) {
		queueChangedListener = listener
	}

	fun initialize(context: Context) {
		if (initialized) return
		synchronized(stateLock) {
			if (initialized) return

			applicationContext = context.applicationContext
			val preferences = applicationContext.getSharedPreferences(APP_PREFERENCES_NAME, Context.MODE_PRIVATE)
			restoreQueue(preferences.getString(PENDING_MESSAGES_KEY, null), messageQueue)
			restoreQueue(preferences.getString(MESSAGE_HISTORY_KEY, null), messageHistory)
			trimHistory()

			wakeLock = try {
				applicationContext.getSystemService(PowerManager::class.java)?.newWakeLock(
					PowerManager.PARTIAL_WAKE_LOCK,
					"ConcertoSMSForwarder::MessageProcessing"
				)?.apply { setReferenceCounted(false) }
			} catch (exception: Exception) {
				exception.printStackTrace()
				null
			}
			initialized = true
		}
	}

	fun containsMessage(item: MessageItem): Boolean {
		return messageQueue.any { isSamePendingMessage(it, item) }
	}

	fun enqueue(context: Context, item: MessageItem, deduplicate: Boolean = true): Boolean {
		initialize(context)
		val added = synchronized(stateLock) {
			if (deduplicate && containsMessage(item)) return@synchronized false
			messageQueue.add(item)
			persistLocked()
			true
		}
		if (added) queueChangedListener?.invoke()
		return added
	}

	fun retryPendingNow() {
		synchronized(stateLock) {
			messageQueue.forEach { it.nextAttemptAt = 0 }
			persistLocked()
		}
		queueChangedListener?.invoke()
	}

	fun updatePending(item: MessageItem) {
		synchronized(stateLock) {
			if (messageQueue.contains(item)) persistLocked()
		}
	}

	fun completePending(item: MessageItem) {
		synchronized(stateLock) {
			if (messageQueue.none { it === item }) return
			messageQueue.removeIf { it === item }
			messageHistory.add(item)
			trimHistory()
			persistLocked()
		}
	}

	fun deleteMessage(item: MessageItem): Boolean {
		val deleted = synchronized(stateLock) {
			val removedPending = messageQueue.removeIf { it === item }
			val removedFromHistory = messageHistory.removeIf { it === item }
			if (removedPending || removedFromHistory) persistLocked()
			removedPending || removedFromHistory
		}
		if (deleted) queueChangedListener?.invoke()
		return deleted
	}

	fun clearMessages() {
		synchronized(stateLock) {
			messageQueue.clear()
			messageHistory.clear()
			persistLocked()
		}
		queueChangedListener?.invoke()
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
		applicationContext.getSharedPreferences(APP_PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.putString(PENDING_MESSAGES_KEY, queueToJson(messageQueue).toString())
			.putString(MESSAGE_HISTORY_KEY, queueToJson(messageHistory).toString())
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
					put("telegramDelivered", item.telegramDelivered)
					put("feishuDelivered", item.feishuDelivered)
					put("nextFeishuChunkIndex", item.nextFeishuChunkIndex)
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
						nextChunkIndex = item.optInt("nextChunkIndex"),
						telegramDelivered = item.optBoolean("telegramDelivered"),
						feishuDelivered = item.optBoolean("feishuDelivered"),
						nextFeishuChunkIndex = item.optInt("nextFeishuChunkIndex")
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

package com.concertonotes.smsforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import com.concertonotes.smsforwarder.model.APP_PREFERENCES_NAME
import com.concertonotes.smsforwarder.model.MessageItem
import com.concertonotes.smsforwarder.model.QueueSingleton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.min

internal const val TELEGRAM_CONTENT_CHUNK_SIZE = 3_500
internal const val MAX_RETRY_DELAY_MS = 15 * 60_000L

internal fun escapeTelegramHtml(value: String): String = buildString(value.length) {
	value.forEach { character ->
		when (character) {
			'&' -> append("&amp;")
			'<' -> append("&lt;")
			'>' -> append("&gt;")
			'\"' -> append("&quot;")
			else -> append(character)
		}
	}
}

internal fun splitTelegramContent(content: String, maxChunkSize: Int = TELEGRAM_CONTENT_CHUNK_SIZE): List<String> {
	require(maxChunkSize > 0) { "maxChunkSize must be positive" }
	if (content.isEmpty()) return listOf("")

	val chunks = mutableListOf<String>()
	var start = 0
	while (start < content.length) {
		var end = min(start + maxChunkSize, content.length)
		if (end < content.length && Character.isHighSurrogate(content[end - 1]) && Character.isLowSurrogate(content[end])) {
			end -= 1
		}
		val newline = content.lastIndexOf('\n', end - 1)
		if (newline > start + maxChunkSize / 2) end = newline + 1
		chunks.add(content.substring(start, end))
		start = end
	}
	return chunks
}

internal fun calculateRetryBackoff(retryCount: Int): Long {
	val multiplier = 1L shl retryCount.coerceIn(0, 7)
	return min(10_000L * multiplier, MAX_RETRY_DELAY_MS)
}

internal fun parseRetryAfterMillis(responseBody: String): Long {
	return try {
		val seconds = JSONObject(responseBody)
			.optJSONObject("parameters")
			?.optLong("retry_after", 60L)
			?: 60L
		seconds.coerceAtLeast(1L) * 1_000L
	} catch (_: Exception) {
		60_000L
	}
}

class AllNotificationService : Service() {
	private companion object {
		const val SERVICE_NOTIFICATION_ID = 1
		const val RATE_LIMIT_NOTIFICATION_ID = 2
		const val SERVICE_CHANNEL_ID = "ConcertoSMSForwarderServiceChannel"
		const val RATE_LIMIT_CHANNEL_ID = "ConcertoSMSForwarderRateLimitChannel"
		const val HTTP_TOO_MANY_REQUESTS = 429
		const val MAX_MESSAGE_AGE_MS = 14L * 24 * 60 * 60 * 1000
		const val IDLE_POLL_INTERVAL_MS = 5_000L
		const val BUSY_POLL_INTERVAL_MS = 500L
		const val CONFIG_RETRY_DELAY_MS = 60_000L
	}

	private val handler = Handler(Looper.getMainLooper())
	private val executorService = Executors.newSingleThreadExecutor()
	@Volatile
	private var isProcessingMessage = false
	@Volatile
	private var destroyed = false
	private var lastHealthCheckTime = System.currentTimeMillis()

	private sealed class SendResult {
		object Success : SendResult()
		data class Retry(val delayMs: Long, val responseCode: Int) : SendResult()
		data class PermanentFailure(val responseCode: Int) : SendResult()
	}

	private data class HttpResponse(val code: Int, val body: String)

	private val processRunnable = object : Runnable {
		override fun run() {
			if (destroyed) return
			checkNotificationServiceHealth()
			QueueSingleton.discardPendingOlderThan(System.currentTimeMillis() - MAX_MESSAGE_AGE_MS)

			val message = QueueSingleton.messageQueue.peek()
			if (message == null) {
				QueueSingleton.releaseWakeLock()
				scheduleNext(IDLE_POLL_INTERVAL_MS)
				return
			}

			val waitTime = message.nextAttemptAt - System.currentTimeMillis()
			if (waitTime > 0) {
				scheduleNext(min(waitTime, IDLE_POLL_INTERVAL_MS))
				return
			}

			if (isProcessingMessage) {
				scheduleNext(BUSY_POLL_INTERVAL_MS)
				return
			}

			isProcessingMessage = true
			QueueSingleton.wakeUp(this@AllNotificationService)
			executorService.execute {
				val result = sendMessage(message)
				handler.post {
					handleSendResult(message, result)
					isProcessingMessage = false
					if (!destroyed) scheduleNext(0)
				}
			}
		}
	}

	override fun onCreate() {
		super.onCreate()
		createNotificationChannels()
		startForeground(SERVICE_NOTIFICATION_ID, createNotification())
		QueueSingleton.initialize(this)
		handler.post(processRunnable)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		startForeground(SERVICE_NOTIFICATION_ID, createNotification())
		return START_STICKY
	}

	override fun onDestroy() {
		destroyed = true
		handler.removeCallbacks(processRunnable)
		executorService.shutdownNow()
		QueueSingleton.releaseWakeLock()
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	private fun handleSendResult(message: MessageItem, result: SendResult) {
		when (result) {
			SendResult.Success -> {
				message.isSent = true
				message.isError = false
				message.retryCount = 0
				message.nextAttemptAt = 0
				message.nextChunkIndex = 0
				QueueSingleton.completePending(message)
			}
			is SendResult.Retry -> {
				message.isError = true
				message.retryCount += 1
				message.nextAttemptAt = System.currentTimeMillis() + result.delayMs
				QueueSingleton.updatePending(message)
				if (result.responseCode == HTTP_TOO_MANY_REQUESTS) {
					showRateLimitNotification(result.delayMs)
				}
			}
			is SendResult.PermanentFailure -> {
				message.isSent = false
				message.isError = true
				message.nextAttemptAt = 0
				QueueSingleton.completePending(message)
			}
		}
		broadcastMessage(message)
	}

	private fun sendMessage(message: MessageItem): SendResult {
		val preferences = getSharedPreferences(APP_PREFERENCES_NAME, MODE_PRIVATE)
		val telegramToken = preferences.getString("telegram_token", null)
		val telegramUserId = preferences.getString("telegram_user_id", null)
		if (telegramToken.isNullOrBlank() || telegramUserId.isNullOrBlank() || ':' !in telegramToken) {
			return SendResult.Retry(CONFIG_RETRY_DELAY_MS, -1)
		}

		val chunks = splitTelegramContent(message.content)
		var chunkIndex = message.nextChunkIndex.coerceIn(0, chunks.lastIndex)
		while (chunkIndex < chunks.size) {
			val response = postTelegramMessage(
				token = telegramToken,
				chatId = telegramUserId,
				text = formatTelegramMessage(message, chunks[chunkIndex], chunkIndex, chunks.size)
			)

			when {
				response.code in 200..299 -> {
					chunkIndex += 1
					message.nextChunkIndex = chunkIndex
					message.retryCount = 0
					QueueSingleton.updatePending(message)
				}
				response.code == HTTP_TOO_MANY_REQUESTS -> {
					return SendResult.Retry(parseRetryAfterMillis(response.body), response.code)
				}
				response.code == HttpURLConnection.HTTP_UNAUTHORIZED ||
					response.code == HttpURLConnection.HTTP_FORBIDDEN ||
					response.code == HttpURLConnection.HTTP_NOT_FOUND -> {
					return SendResult.Retry(CONFIG_RETRY_DELAY_MS, response.code)
				}
				response.code == HttpURLConnection.HTTP_BAD_REQUEST &&
					(response.body.contains("chat not found", ignoreCase = true) ||
						response.body.contains("chat_id", ignoreCase = true)) -> {
					return SendResult.Retry(CONFIG_RETRY_DELAY_MS, response.code)
				}
				response.code == HttpURLConnection.HTTP_CLIENT_TIMEOUT || response.code >= 500 || response.code == -1 -> {
					return SendResult.Retry(calculateRetryBackoff(message.retryCount), response.code)
				}
				else -> return SendResult.PermanentFailure(response.code)
			}
		}
		return SendResult.Success
	}

	private fun postTelegramMessage(token: String, chatId: String, text: String): HttpResponse {
		var connection: HttpURLConnection? = null
		return try {
			connection = URL("https://api.telegram.org/bot$token/sendMessage").openConnection() as HttpURLConnection
			connection.requestMethod = "POST"
			connection.doOutput = true
			connection.connectTimeout = 15_000
			connection.readTimeout = 15_000
			connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")

			val params = listOf(
				"chat_id" to chatId,
				"parse_mode" to "HTML",
				"text" to text
			).joinToString("&") { (key, value) ->
				"$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
			}
			connection.outputStream.use { it.write(params.toByteArray(StandardCharsets.UTF_8)) }

			val responseCode = connection.responseCode
			val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
			HttpResponse(responseCode, responseStream?.bufferedReader()?.use { it.readText() }.orEmpty())
		} catch (exception: Exception) {
			exception.printStackTrace()
			HttpResponse(-1, "")
		} finally {
			connection?.disconnect()
		}
	}

	private fun formatTelegramMessage(
		message: MessageItem,
		contentChunk: String,
		chunkIndex: Int,
		chunkCount: Int
	): String {
		val timestamp = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm:ss")
			.withZone(ZoneId.systemDefault())
			.format(Instant.ofEpochMilli(message.timestamp))
		val part = if (chunkCount > 1) " (${chunkIndex + 1}/$chunkCount)" else ""
		return "<b>${escapeTelegramHtml(message.sender)} $timestamp$part</b>\n" +
			"<blockquote>${escapeTelegramHtml(contentChunk)}</blockquote>"
	}

	private fun scheduleNext(delayMs: Long) {
		handler.removeCallbacks(processRunnable)
		handler.postDelayed(processRunnable, delayMs)
	}

	private fun broadcastMessage(message: MessageItem) {
		val intent = Intent("$packageName.NEW_MESSAGE")
			.setPackage(packageName)
			.putExtra("messageItem", message)
		sendBroadcast(intent)
	}

	private fun createNotificationChannels() {
		val manager = getSystemService(NotificationManager::class.java) ?: return
		manager.createNotificationChannel(
			NotificationChannel(SERVICE_CHANNEL_ID, "Concerto SMS Forwarder Service", NotificationManager.IMPORTANCE_LOW).apply {
				description = "Concerto SMS Forwarder background service status"
				setShowBadge(false)
				lockscreenVisibility = Notification.VISIBILITY_PUBLIC
			}
		)
		manager.createNotificationChannel(
			NotificationChannel(RATE_LIMIT_CHANNEL_ID, "Rate Limit Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
				description = "Telegram API rate limit alerts"
				lockscreenVisibility = Notification.VISIBILITY_PUBLIC
			}
		)
	}

	private fun createNotification(): Notification {
		val intent = Intent(this, MainActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
		}
		val pendingIntent = PendingIntent.getActivity(
			this,
			0,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
			.setContentTitle("Listening for notifications")
			.setContentText("Concerto SMS Forwarder is running")
			.setSmallIcon(R.drawable.small_icon)
			.setContentIntent(pendingIntent)
			.setOngoing(true)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.build()
	}

	private fun showRateLimitNotification(delayMs: Long) {
		val minutes = ((delayMs + 59_999L) / 60_000L).coerceAtLeast(1L)
		val notification = NotificationCompat.Builder(this, RATE_LIMIT_CHANNEL_ID)
			.setContentTitle("Telegram rate limit reached")
			.setContentText("Sending paused for approximately $minutes minute(s).")
			.setSmallIcon(R.drawable.small_icon)
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.setCategory(NotificationCompat.CATEGORY_ERROR)
			.build()
		getSystemService(NotificationManager::class.java)?.notify(RATE_LIMIT_NOTIFICATION_ID, notification)
	}

	private fun checkNotificationServiceHealth() {
		val now = System.currentTimeMillis()
		if (now - lastHealthCheckTime < 60_000L) return
		lastHealthCheckTime = now

		val componentName = ComponentName(this, NotificationListener::class.java)
		val enabledListeners = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
		val enabled = enabledListeners
			?.split(':')
			?.any { it == componentName.flattenToString() }
			?: false
		if (enabled && !QueueSingleton.isListenerConnected) {
			NotificationListenerService.requestRebind(componentName)
		}
	}
}

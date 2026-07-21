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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.concertonotes.smsforwarder.model.APP_PREFERENCES_NAME
import com.concertonotes.smsforwarder.model.MessageItem
import com.concertonotes.smsforwarder.model.QueueSingleton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

internal const val TELEGRAM_CONTENT_CHUNK_SIZE = 3_500
internal const val FEISHU_CONTENT_CHUNK_SIZE = 3_500
internal const val MAX_RETRY_DELAY_MS = 15 * 60_000L

internal fun createFeishuSignature(timestampSeconds: Long, secret: String): String {
	val signingKey = "$timestampSeconds\n$secret".toByteArray(StandardCharsets.UTF_8)
	val mac = Mac.getInstance("HmacSHA256")
	mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
	return Base64.getEncoder().encodeToString(mac.doFinal(ByteArray(0)))
}

internal fun isValidFeishuWebhook(webhook: String): Boolean {
	return try {
		val uri = URI(webhook.trim())
		uri.scheme.equals("https", ignoreCase = true) &&
			uri.host.equals("open.feishu.cn", ignoreCase = true) &&
			uri.path.startsWith("/open-apis/bot/v2/hook/") &&
			uri.path.length > "/open-apis/bot/v2/hook/".length
	} catch (_: Exception) {
		false
	}
}

internal fun isFeishuResponseSuccessful(responseBody: String): Boolean {
	return try {
		val response = JSONObject(responseBody)
		isFeishuSuccessCode(
			code = if (response.has("code")) response.optInt("code", Int.MIN_VALUE) else null,
			legacyStatusCode = if (response.has("StatusCode")) response.optInt("StatusCode", Int.MIN_VALUE) else null
		)
	} catch (_: Exception) {
		false
	}
}

internal fun isFeishuSuccessCode(code: Int?, legacyStatusCode: Int?): Boolean {
	return when {
		code != null -> code == 0
		legacyStatusCode != null -> legacyStatusCode == 0
		else -> false
	}
}

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
	@Volatile
	private var startupFailed = false
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
		try {
			createNotificationChannels()
			startForeground(SERVICE_NOTIFICATION_ID, createNotification())
			QueueSingleton.initialize(this)
			handler.post(processRunnable)
		} catch (exception: Exception) {
			startupFailed = true
			Log.e("ConcertoForwarder", "Unable to initialize foreground service", exception)
			stopSelf()
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (startupFailed) return START_NOT_STICKY
		return try {
			startForeground(SERVICE_NOTIFICATION_ID, createNotification())
			START_STICKY
		} catch (exception: Exception) {
			startupFailed = true
			Log.e("ConcertoForwarder", "Unable to keep foreground service running", exception)
			stopSelf(startId)
			START_NOT_STICKY
		}
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
				message.nextFeishuChunkIndex = 0
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
		val telegramToken = preferences.getString("telegram_token", null).orEmpty()
		val telegramUserId = preferences.getString("telegram_user_id", null).orEmpty()
		val feishuWebhook = preferences.getString("feishu_webhook", null).orEmpty()
		val feishuSecret = preferences.getString("feishu_secret", null).orEmpty()
		val telegramConfigured = telegramToken.isNotBlank() && telegramUserId.isNotBlank() && ':' in telegramToken
		val feishuConfigured = feishuWebhook.isNotBlank() && isValidFeishuWebhook(feishuWebhook)

		if (!telegramConfigured && !feishuConfigured) {
			return SendResult.Retry(CONFIG_RETRY_DELAY_MS, -1)
		}

		var failure: SendResult? = null
		if (telegramConfigured && !message.telegramDelivered) {
			val telegramResult = sendTelegramMessage(message, telegramToken, telegramUserId)
			if (telegramResult == SendResult.Success) {
				message.telegramDelivered = true
				message.nextChunkIndex = 0
				QueueSingleton.updatePending(message)
			} else {
				failure = mergeSendFailures(failure, telegramResult)
			}
		}

		if (feishuConfigured && !message.feishuDelivered) {
			val feishuResult = sendFeishuMessage(message, feishuWebhook, feishuSecret)
			if (feishuResult == SendResult.Success) {
				message.feishuDelivered = true
				message.nextFeishuChunkIndex = 0
				QueueSingleton.updatePending(message)
			} else {
				failure = mergeSendFailures(failure, feishuResult)
			}
		}

		return failure ?: SendResult.Success
	}

	private fun mergeSendFailures(current: SendResult?, next: SendResult): SendResult {
		if (current == null) return next
		if (current is SendResult.Retry && next is SendResult.Retry) {
			return if (current.delayMs >= next.delayMs) current else next
		}
		return when {
			current is SendResult.Retry -> current
			next is SendResult.Retry -> next
			else -> current
		}
	}

	private fun sendTelegramMessage(message: MessageItem, telegramToken: String, telegramUserId: String): SendResult {
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

	private fun sendFeishuMessage(message: MessageItem, webhook: String, secret: String): SendResult {
		val chunks = splitTelegramContent(message.content, FEISHU_CONTENT_CHUNK_SIZE)
		var chunkIndex = message.nextFeishuChunkIndex.coerceIn(0, chunks.lastIndex)
		while (chunkIndex < chunks.size) {
			val response = postFeishuMessage(
				webhook = webhook,
				secret = secret,
				text = formatFeishuMessage(message, chunks[chunkIndex], chunkIndex, chunks.size)
			)

			when {
				response.code in 200..299 && isFeishuResponseSuccessful(response.body) -> {
					chunkIndex += 1
					message.nextFeishuChunkIndex = chunkIndex
					message.retryCount = 0
					QueueSingleton.updatePending(message)
				}
				response.code == HTTP_TOO_MANY_REQUESTS -> {
					return SendResult.Retry(calculateRetryBackoff(message.retryCount), response.code)
				}
				response.code == -1 || response.code >= 500 -> {
					return SendResult.Retry(calculateRetryBackoff(message.retryCount), response.code)
				}
				else -> return SendResult.Retry(CONFIG_RETRY_DELAY_MS, response.code)
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

	private fun postFeishuMessage(webhook: String, secret: String, text: String): HttpResponse {
		var connection: HttpURLConnection? = null
		return try {
			connection = URL(webhook).openConnection() as HttpURLConnection
			connection.requestMethod = "POST"
			connection.doOutput = true
			connection.connectTimeout = 15_000
			connection.readTimeout = 15_000
			connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

			val payload = JSONObject()
				.put("msg_type", "text")
				.put("content", JSONObject().put("text", text))
			if (secret.isNotBlank()) {
				val timestamp = System.currentTimeMillis() / 1_000L
				payload.put("timestamp", timestamp.toString())
				payload.put("sign", createFeishuSignature(timestamp, secret))
			}
			connection.outputStream.use {
				it.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
			}

			val responseCode = connection.responseCode
			val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
			HttpResponse(responseCode, responseStream?.bufferedReader()?.use { it.readText() }.orEmpty())
		} catch (exception: Exception) {
			Log.e("ConcertoForwarder", "Unable to send message to Feishu", exception)
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

	private fun formatFeishuMessage(
		message: MessageItem,
		contentChunk: String,
		chunkIndex: Int,
		chunkCount: Int
	): String {
		val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(ZoneId.systemDefault())
			.format(Instant.ofEpochMilli(message.timestamp))
		val part = if (chunkCount > 1) " (${chunkIndex + 1}/$chunkCount)" else ""
		return "${message.sender} $timestamp$part\n$contentChunk"
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
			NotificationChannel(SERVICE_CHANNEL_ID, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
				description = getString(R.string.service_channel_description)
				setShowBadge(false)
				lockscreenVisibility = Notification.VISIBILITY_PUBLIC
			}
		)
		manager.createNotificationChannel(
			NotificationChannel(RATE_LIMIT_CHANNEL_ID, getString(R.string.rate_limit_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
				description = getString(R.string.rate_limit_channel_description)
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
			.setContentTitle(getString(R.string.service_notification_title))
			.setContentText(getString(R.string.service_notification_text))
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
			.setContentTitle(getString(R.string.rate_limit_title))
			.setContentText(getString(R.string.rate_limit_text, minutes))
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

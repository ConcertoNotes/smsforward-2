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
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat
import com.concertonotes.smsforwarder.model.APP_PREFERENCES_NAME
import com.concertonotes.smsforwarder.model.MessageItem
import com.concertonotes.smsforwarder.model.QueueSingleton
import com.concertonotes.smsforwarder.model.selectNextReadyMessage
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
import java.util.concurrent.Future
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

internal const val TELEGRAM_CONTENT_CHUNK_SIZE = 3_500
internal const val FEISHU_CONTENT_CHUNK_SIZE = 3_500
internal const val MAX_RETRY_DELAY_MS = 15 * 60_000L
internal const val LISTENER_HEALTH_CHECK_INTERVAL_MS = 60_000L
internal const val LISTENER_REBIND_ATTEMPTS_BEFORE_ALERT = 2
internal const val ACTION_LISTENER_CONNECTION_CHANGED =
	"com.concertonotes.smsforwarder.LISTENER_CONNECTION_CHANGED"

internal fun shouldAlertListenerDisconnected(
	listenerEnabled: Boolean,
	listenerConnected: Boolean,
	rebindAttempts: Int
): Boolean = listenerEnabled && !listenerConnected &&
	rebindAttempts >= LISTENER_REBIND_ATTEMPTS_BEFORE_ALERT

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
		const val LISTENER_DISCONNECTED_NOTIFICATION_ID = 3
		const val SERVICE_CHANNEL_ID = "ConcertoSMSForwarderServiceChannel"
		const val RATE_LIMIT_CHANNEL_ID = "ConcertoSMSForwarderRateLimitChannel"
		const val LISTENER_HEALTH_CHANNEL_ID = "ConcertoSMSForwarderListenerHealthChannel"
		const val UPDATE_CHANNEL_ID = "ConcertoSMSForwarderUpdateChannel"
		const val HTTP_TOO_MANY_REQUESTS = 429
		const val MAX_MESSAGE_AGE_MS = 14L * 24 * 60 * 60 * 1000
		const val IDLE_POLL_INTERVAL_MS = 1_000L
		const val CONFIG_RETRY_DELAY_MS = 60_000L
		const val MAX_CONCURRENT_MESSAGES = 3
	}

	private val handler = Handler(Looper.getMainLooper())
	private val executorService = Executors.newFixedThreadPool(MAX_CONCURRENT_MESSAGES)
	private val channelExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_MESSAGES * 2)
	private val inFlightMessages = mutableListOf<MessageItem>()
	@Volatile
	private var destroyed = false
	@Volatile
	private var startupFailed = false
	private var lastHealthCheckTime = 0L
	private var listenerRebindAttempts = 0
	private var foregroundShowsListenerDisconnected = false
	private var lastUpdateCheckTime = 0L
	private var updateCheckInFlight = false
	private val queueChangedListener: () -> Unit = {
		handler.post {
			if (!destroyed) scheduleNext(0)
		}
	}

	private sealed class SendResult {
		object Success : SendResult()
		data class Retry(val delayMs: Long, val responseCode: Int) : SendResult()
	}

	private data class HttpResponse(val code: Int, val body: String)

	private val processRunnable = object : Runnable {
		override fun run() {
			if (destroyed) return
			checkNotificationServiceHealth()
			checkForAppUpdate()
			QueueSingleton.discardPendingOlderThan(System.currentTimeMillis() - MAX_MESSAGE_AGE_MS)

			val now = System.currentTimeMillis()
			while (inFlightMessages.size < MAX_CONCURRENT_MESSAGES) {
				val message = selectNextReadyMessage(QueueSingleton.messageQueue, now, inFlightMessages) ?: break
				inFlightMessages.add(message)
				QueueSingleton.wakeUp(this@AllNotificationService)
				try {
					executorService.execute { sendMessageInBackground(message) }
				} catch (exception: Exception) {
					inFlightMessages.removeAll { it === message }
					Log.e("ConcertoForwarder", "Unable to schedule message", exception)
					break
				}
			}

			if (inFlightMessages.isNotEmpty()) return

			QueueSingleton.releaseWakeLock()
			val nextAttemptAt = QueueSingleton.messageQueue
				.map { it.nextAttemptAt }
				.filter { it > now }
				.minOrNull()
			val delay = nextAttemptAt?.let { min(it - now, IDLE_POLL_INTERVAL_MS) } ?: IDLE_POLL_INTERVAL_MS
			scheduleNext(delay)
		}
	}

	private fun sendMessageInBackground(message: MessageItem) {
		val result = try {
			sendMessage(message)
		} catch (exception: Exception) {
			Log.e("ConcertoForwarder", "Unexpected failure while sending message", exception)
			SendResult.Retry(calculateRetryBackoff(message.retryCount), -1)
		}

		handler.post {
			inFlightMessages.removeAll { it === message }
			if (destroyed) return@post
			try {
				handleSendResult(message, result)
			} catch (exception: Exception) {
				Log.e("ConcertoForwarder", "Unable to finalize message result", exception)
			} finally {
				scheduleNext(0)
			}
		}
	}

	override fun onCreate() {
		super.onCreate()
		try {
			QueueSingleton.setQueueChangedListener(queueChangedListener)
			createNotificationChannels()
			startForeground(
				SERVICE_NOTIFICATION_ID,
				createNotification(listenerConnected = QueueSingleton.isListenerConnected)
			)
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
			val listenerConnected = QueueSingleton.isListenerConnected
			foregroundShowsListenerDisconnected = !listenerConnected
			startForeground(
				SERVICE_NOTIFICATION_ID,
				createNotification(listenerConnected = listenerConnected)
			)
			if (listenerConnected) {
				getSystemService(NotificationManager::class.java)
					?.cancel(LISTENER_DISCONNECTED_NOTIFICATION_ID)
			}
			if (intent?.action == ACTION_LISTENER_CONNECTION_CHANGED) {
				lastHealthCheckTime = 0L
				scheduleNext(0)
			}
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
		QueueSingleton.setQueueChangedListener(null)
		handler.removeCallbacks(processRunnable)
		executorService.shutdownNow()
		channelExecutor.shutdownNow()
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
				// A retry is still pending work, not a final forwarding failure.
				message.isError = false
				message.retryCount += 1
				message.nextAttemptAt = System.currentTimeMillis() + result.delayMs
				QueueSingleton.updatePending(message)
				if (result.responseCode == HTTP_TOO_MANY_REQUESTS) {
					showRateLimitNotification(result.delayMs)
				}
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

		val telegramFuture: Future<SendResult>? = if (telegramConfigured && !message.telegramDelivered) {
			channelExecutor.submit<SendResult> { sendTelegramMessage(message, telegramToken, telegramUserId) }
		} else null
		val feishuFuture: Future<SendResult>? = if (feishuConfigured && !message.feishuDelivered) {
			channelExecutor.submit<SendResult> { sendFeishuMessage(message, feishuWebhook, feishuSecret) }
		} else null

		var failure: SendResult? = null
		if (telegramFuture != null) {
			val telegramResult = awaitSendResult(telegramFuture)
			if (telegramResult == SendResult.Success) {
				message.telegramDelivered = true
				message.nextChunkIndex = 0
				QueueSingleton.updatePending(message)
			} else {
				failure = mergeSendFailures(failure, telegramResult)
			}
		}

		if (feishuFuture != null) {
			val feishuResult = awaitSendResult(feishuFuture)
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

	private fun awaitSendResult(future: Future<SendResult>): SendResult {
		return try {
			future.get()
		} catch (exception: Exception) {
			Log.e("ConcertoForwarder", "Message channel worker failed", exception)
			SendResult.Retry(calculateRetryBackoff(0), -1)
		}
	}

	private fun mergeSendFailures(current: SendResult?, next: SendResult): SendResult {
		if (current == null) return next
		if (current is SendResult.Retry && next is SendResult.Retry) {
			return if (current.delayMs >= next.delayMs) current else next
		}
		return current
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
				else -> {
					Log.w("ConcertoForwarder", "Telegram send failed with HTTP ${response.code}; will retry")
					return SendResult.Retry(CONFIG_RETRY_DELAY_MS, response.code)
				}
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
				else -> {
					Log.w("ConcertoForwarder", "Feishu send failed with HTTP ${response.code}; will retry")
					return SendResult.Retry(CONFIG_RETRY_DELAY_MS, response.code)
				}
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
			connection.connectTimeout = 6_000
			connection.readTimeout = 6_000
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
			connection.connectTimeout = 6_000
			connection.readTimeout = 6_000
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
		manager.createNotificationChannel(
			NotificationChannel(
				LISTENER_HEALTH_CHANNEL_ID,
				getString(R.string.listener_health_channel_name),
				NotificationManager.IMPORTANCE_HIGH
			).apply {
				description = getString(R.string.listener_health_channel_description)
				lockscreenVisibility = Notification.VISIBILITY_PUBLIC
			}
		)
		manager.createNotificationChannel(
			NotificationChannel(
				UPDATE_CHANNEL_ID,
				getString(R.string.update_channel_name),
				NotificationManager.IMPORTANCE_DEFAULT
			).apply {
				description = getString(R.string.update_channel_description)
				lockscreenVisibility = Notification.VISIBILITY_PUBLIC
			}
		)
	}

	private fun createNotification(listenerConnected: Boolean): Notification {
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
			.setContentTitle(
				getString(
					if (listenerConnected) R.string.service_notification_title
					else R.string.listener_disconnected_title
				)
			)
			.setContentText(
				getString(
					if (listenerConnected) R.string.service_notification_text
					else R.string.listener_disconnected_text
				)
			)
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
		if (now - lastHealthCheckTime < LISTENER_HEALTH_CHECK_INTERVAL_MS) return
		lastHealthCheckTime = now

		val componentName = ComponentName(this, NotificationListener::class.java)
		val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
		val enabled = enabledListeners
			?.split(':')
			?.any { it == componentName.flattenToString() }
			?: false
		if (!enabled) {
			listenerRebindAttempts = 0
			showListenerDisconnectedState(alert = true)
			return
		}

		if (QueueSingleton.isListenerConnected) {
			listenerRebindAttempts = 0
			clearListenerDisconnectedState()
			return
		}

		listenerRebindAttempts += 1
		Log.w(
			"ConcertoForwarder",
			"Notification listener is not connected; rebind attempt $listenerRebindAttempts"
		)
		try {
			NotificationListenerService.requestRebind(componentName)
		} catch (exception: Exception) {
			Log.e("ConcertoForwarder", "Unable to request notification listener rebind", exception)
		}
		showListenerDisconnectedState(
			alert = shouldAlertListenerDisconnected(enabled, false, listenerRebindAttempts)
		)
	}

	private fun checkForAppUpdate() {
		val now = System.currentTimeMillis()
		if (updateCheckInFlight || now - lastUpdateCheckTime < UPDATE_CHECK_INTERVAL_MS) return
		lastUpdateCheckTime = now
		updateCheckInFlight = true
		AppUpdateManager.checkForUpdate(this) { result ->
			updateCheckInFlight = false
			if (destroyed) return@checkForUpdate
			when (result) {
				is UpdateCheckResult.Available -> showUpdateAvailableNotification(result.release)
				is UpdateCheckResult.Failed -> {
					// Retry transient GitHub/network failures in one hour instead of six.
					lastUpdateCheckTime = System.currentTimeMillis() - UPDATE_CHECK_INTERVAL_MS + 60 * 60_000L
				}
				UpdateCheckResult.UpToDate -> {
					getSystemService(NotificationManager::class.java)?.cancel(UPDATE_NOTIFICATION_ID)
				}
			}
		}
	}

	private fun showUpdateAvailableNotification(release: AppRelease) {
		val intent = Intent(this, MainActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
		}
		val pendingIntent = PendingIntent.getActivity(
			this,
			UPDATE_NOTIFICATION_ID,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		val notification = NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
			.setContentTitle(getString(R.string.update_available_title, release.versionName))
			.setContentText(getString(R.string.update_notification_text))
			.setSmallIcon(R.drawable.small_icon)
			.setContentIntent(pendingIntent)
			.setAutoCancel(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.build()
		getSystemService(NotificationManager::class.java)
			?.notify(UPDATE_NOTIFICATION_ID, notification)
	}

	private fun showListenerDisconnectedState(alert: Boolean) {
		val manager = getSystemService(NotificationManager::class.java) ?: return
		if (!foregroundShowsListenerDisconnected) {
			foregroundShowsListenerDisconnected = true
			manager.notify(SERVICE_NOTIFICATION_ID, createNotification(listenerConnected = false))
		}
		if (!alert) return

		val settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
		val pendingIntent = PendingIntent.getActivity(
			this,
			LISTENER_DISCONNECTED_NOTIFICATION_ID,
			settingsIntent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		val notification = NotificationCompat.Builder(this, LISTENER_HEALTH_CHANNEL_ID)
			.setContentTitle(getString(R.string.listener_disconnected_title))
			.setContentText(getString(R.string.listener_disconnected_action))
			.setSmallIcon(R.drawable.small_icon)
			.setContentIntent(pendingIntent)
			.setAutoCancel(true)
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.setCategory(NotificationCompat.CATEGORY_ERROR)
			.build()
		manager.notify(LISTENER_DISCONNECTED_NOTIFICATION_ID, notification)
	}

	private fun clearListenerDisconnectedState() {
		if (!foregroundShowsListenerDisconnected) return
		foregroundShowsListenerDisconnected = false
		getSystemService(NotificationManager::class.java)?.apply {
			notify(SERVICE_NOTIFICATION_ID, createNotification(listenerConnected = true))
			cancel(LISTENER_DISCONNECTED_NOTIFICATION_ID)
		}
	}
}

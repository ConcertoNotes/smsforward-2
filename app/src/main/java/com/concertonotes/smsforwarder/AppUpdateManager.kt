package com.concertonotes.smsforwarder

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.concertonotes.smsforwarder.model.APP_PREFERENCES_NAME
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

internal const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1_000
internal const val UPDATE_NOTIFICATION_ID = 4
private const val UPDATE_CACHE_DURATION_MS = 10L * 60 * 1_000
private const val LATEST_RELEASE_URL =
	"https://api.github.com/repos/ConcertoNotes/smsforward-2/releases/latest"
private const val UPDATE_DOWNLOAD_ID_KEY = "update_download_id"
private const val UPDATE_DOWNLOAD_FILE_KEY = "update_download_file"
private const val UPDATE_DOWNLOAD_VERSION_KEY = "update_download_version"

internal data class AppRelease(
	val versionName: String,
	val apkName: String,
	val apkUrl: String
)

internal sealed class UpdateCheckResult {
	data class Available(val release: AppRelease) : UpdateCheckResult()
	object UpToDate : UpdateCheckResult()
	data class Failed(val reason: String) : UpdateCheckResult()
}

internal data class PendingUpdateDownload(
	val id: Long,
	val fileName: String,
	val versionName: String
)

internal enum class UpdateDownloadState {
	NONE,
	RUNNING,
	SUCCESSFUL,
	FAILED
}

internal sealed class ApkValidationResult {
	data class Valid(val file: File) : ApkValidationResult()
	data class Invalid(val messageRes: Int) : ApkValidationResult()
}

internal fun parseVersionParts(value: String?): List<Int>? {
	if (value.isNullOrBlank()) return null
	val match = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matchEntire(value.trim())
		?: return null
	return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
}

internal fun isNewerVersion(candidate: String?, current: String?): Boolean {
	val candidateParts = parseVersionParts(candidate) ?: return false
	val currentParts = parseVersionParts(current) ?: return false
	return candidateParts.zip(currentParts).firstOrNull { (next, installed) -> next != installed }
		?.let { (next, installed) -> next > installed }
		?: false
}

internal fun isTrustedReleaseAssetUrl(value: String): Boolean {
	return try {
		val uri = URI(value)
		uri.scheme.equals("https", ignoreCase = true) &&
			uri.host.equals("github.com", ignoreCase = true) &&
			uri.path.startsWith("/ConcertoNotes/smsforward-2/releases/download/")
	} catch (_: Exception) {
		false
	}
}

internal fun parseGitHubReleaseResponse(body: String, currentVersion: String): UpdateCheckResult {
	return try {
		val response = JSONObject(body)
		val versionName = response.optString("tag_name").trim().removePrefix("v").removePrefix("V")
		if (!isNewerVersion(versionName, currentVersion)) return UpdateCheckResult.UpToDate

		val assets = response.optJSONArray("assets") ?: return UpdateCheckResult.Failed("Release has no assets")
		var release: AppRelease? = null
		for (index in 0 until assets.length()) {
			val asset = assets.optJSONObject(index) ?: continue
			val name = asset.optString("name")
			val url = asset.optString("browser_download_url")
			if (name.endsWith(".apk", ignoreCase = true) && isTrustedReleaseAssetUrl(url)) {
				release = AppRelease(versionName, name, url)
				break
			}
		}
		release?.let { UpdateCheckResult.Available(it) }
			?: UpdateCheckResult.Failed("Release has no trusted APK asset")
	} catch (exception: Exception) {
		UpdateCheckResult.Failed("Invalid GitHub release response")
	}
}

internal object AppUpdateManager {
	private val executor = Executors.newSingleThreadExecutor()
	private val mainHandler = Handler(Looper.getMainLooper())
	private val stateLock = Any()
	private val callbacks = mutableListOf<(UpdateCheckResult) -> Unit>()
	private var checkInFlight = false
	private var cachedAt = 0L
	private var cachedResult: UpdateCheckResult? = null

	fun checkForUpdate(context: Context, callback: (UpdateCheckResult) -> Unit) {
		val applicationContext = context.applicationContext
		val now = System.currentTimeMillis()
		synchronized(stateLock) {
			val cached = cachedResult
			if (cached != null && now - cachedAt < UPDATE_CACHE_DURATION_MS) {
				mainHandler.post { callback(cached) }
				return
			}
			callbacks.add(callback)
			if (checkInFlight) return
			checkInFlight = true
		}

		executor.execute {
			val result = fetchLatestRelease(currentVersionName(applicationContext))
			val pendingCallbacks = synchronized(stateLock) {
				checkInFlight = false
				if (result !is UpdateCheckResult.Failed) {
					cachedAt = System.currentTimeMillis()
					cachedResult = result
				}
				callbacks.toList().also { callbacks.clear() }
			}
			mainHandler.post {
				pendingCallbacks.forEach { it(result) }
			}
		}
	}

	fun enqueueDownload(context: Context, release: AppRelease): Long {
		require(isTrustedReleaseAssetUrl(release.apkUrl)) { "Untrusted release asset URL" }
		val safeVersion = release.versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")
		val fileName = "XinJie-Forwarder-$safeVersion.apk"
		context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
			?.resolve(fileName)
			?.takeIf { it.isFile }
			?.delete()

		val request = DownloadManager.Request(Uri.parse(release.apkUrl))
			.setTitle(context.getString(R.string.update_download_title, release.versionName))
			.setDescription(context.getString(R.string.update_download_description))
			.setMimeType("application/vnd.android.package-archive")
			.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
			.setAllowedOverMetered(true)
			.setAllowedOverRoaming(false)
			.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
		val manager = context.getSystemService(DownloadManager::class.java)
			?: error("DownloadManager unavailable")
		val downloadId = manager.enqueue(request)
		context.getSharedPreferences(APP_PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.putLong(UPDATE_DOWNLOAD_ID_KEY, downloadId)
			.putString(UPDATE_DOWNLOAD_FILE_KEY, fileName)
			.putString(UPDATE_DOWNLOAD_VERSION_KEY, release.versionName)
			.apply()
		return downloadId
	}

	fun pendingDownload(context: Context): PendingUpdateDownload? {
		val preferences = context.getSharedPreferences(APP_PREFERENCES_NAME, Context.MODE_PRIVATE)
		val id = preferences.getLong(UPDATE_DOWNLOAD_ID_KEY, -1L)
		val fileName = preferences.getString(UPDATE_DOWNLOAD_FILE_KEY, null)
		val versionName = preferences.getString(UPDATE_DOWNLOAD_VERSION_KEY, null)
		if (id < 0 || fileName.isNullOrBlank() || versionName.isNullOrBlank()) return null
		return PendingUpdateDownload(id, fileName, versionName)
	}

	fun downloadState(context: Context, pending: PendingUpdateDownload): UpdateDownloadState {
		val manager = context.getSystemService(DownloadManager::class.java)
			?: return UpdateDownloadState.FAILED
		return try {
			manager.query(DownloadManager.Query().setFilterById(pending.id))?.use { cursor ->
				if (!cursor.moveToFirst()) return@use UpdateDownloadState.NONE
				when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
					DownloadManager.STATUS_SUCCESSFUL -> UpdateDownloadState.SUCCESSFUL
					DownloadManager.STATUS_FAILED -> UpdateDownloadState.FAILED
					else -> UpdateDownloadState.RUNNING
				}
			} ?: UpdateDownloadState.NONE
		} catch (exception: Exception) {
			Log.e("ConcertoForwarder", "Unable to read update download status", exception)
			UpdateDownloadState.FAILED
		}
	}

	fun validateDownloadedApk(context: Context, pending: PendingUpdateDownload): ApkValidationResult {
		val file = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
			?.resolve(pending.fileName)
			?: return ApkValidationResult.Invalid(R.string.update_file_missing)
		if (!file.isFile) return ApkValidationResult.Invalid(R.string.update_file_missing)

		val archiveInfo = packageArchiveInfo(context.packageManager, file.absolutePath)
			?: return ApkValidationResult.Invalid(R.string.update_invalid_apk)
		if (archiveInfo.packageName != context.packageName) {
			return ApkValidationResult.Invalid(R.string.update_wrong_package)
		}
		if (packageVersionCode(archiveInfo) <= currentVersionCode(context)) {
			return ApkValidationResult.Invalid(R.string.update_not_newer)
		}
		if (parseVersionParts(archiveInfo.versionName) != parseVersionParts(pending.versionName)) {
			return ApkValidationResult.Invalid(R.string.update_version_mismatch)
		}

		val installedInfo = installedPackageInfo(context)
			?: return ApkValidationResult.Invalid(R.string.update_signature_mismatch)
		val installedSigners = signerDigests(installedInfo)
		val archiveSigners = signerDigests(archiveInfo)
		if (installedSigners.isEmpty() || installedSigners != archiveSigners) {
			return ApkValidationResult.Invalid(R.string.update_signature_mismatch)
		}
		return ApkValidationResult.Valid(file)
	}

	fun clearPendingDownload(context: Context) {
		context.getSharedPreferences(APP_PREFERENCES_NAME, Context.MODE_PRIVATE)
			.edit()
			.remove(UPDATE_DOWNLOAD_ID_KEY)
			.remove(UPDATE_DOWNLOAD_FILE_KEY)
			.remove(UPDATE_DOWNLOAD_VERSION_KEY)
			.apply()
	}

	fun discardPendingDownload(context: Context, pending: PendingUpdateDownload) {
		try {
			context.getSystemService(DownloadManager::class.java)?.remove(pending.id)
		} catch (exception: Exception) {
			Log.w("ConcertoForwarder", "Unable to remove update download", exception)
		}
		context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
			?.resolve(pending.fileName)
			?.takeIf { it.isFile }
			?.delete()
		clearPendingDownload(context)
	}

	private fun fetchLatestRelease(currentVersion: String): UpdateCheckResult {
		var connection: HttpURLConnection? = null
		return try {
			connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
			connection.requestMethod = "GET"
			connection.connectTimeout = 8_000
			connection.readTimeout = 8_000
			connection.setRequestProperty("Accept", "application/vnd.github+json")
			connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
			connection.setRequestProperty("User-Agent", "XinJie-Forwarder-Android")

			when (val responseCode = connection.responseCode) {
				HttpURLConnection.HTTP_NOT_FOUND -> UpdateCheckResult.UpToDate
				HttpURLConnection.HTTP_OK -> parseGitHubReleaseResponse(
					connection.inputStream.bufferedReader().use { it.readText() },
					currentVersion
				)
				else -> UpdateCheckResult.Failed("GitHub HTTP $responseCode")
			}
		} catch (exception: Exception) {
			Log.w("ConcertoForwarder", "Unable to check GitHub release", exception)
			UpdateCheckResult.Failed(exception.javaClass.simpleName)
		} finally {
			connection?.disconnect()
		}
	}

	private fun currentVersionName(context: Context): String {
		return installedPackageInfo(context)?.versionName.orEmpty()
	}

	private fun currentVersionCode(context: Context): Long {
		return installedPackageInfo(context)?.let(::packageVersionCode) ?: Long.MAX_VALUE
	}

	private fun installedPackageInfo(context: Context): PackageInfo? {
		return try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				context.packageManager.getPackageInfo(
					context.packageName,
					PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
				)
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				@Suppress("DEPRECATION")
				context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
			} else {
				@Suppress("DEPRECATION")
				context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
			}
		} catch (_: Exception) {
			null
		}
	}

	private fun packageArchiveInfo(packageManager: PackageManager, path: String): PackageInfo? {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			packageManager.getPackageArchiveInfo(
				path,
				PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
			)
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			@Suppress("DEPRECATION")
			packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
		} else {
			@Suppress("DEPRECATION")
			packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
		}
	}

	private fun packageVersionCode(packageInfo: PackageInfo): Long {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			packageInfo.longVersionCode
		} else {
			@Suppress("DEPRECATION")
			packageInfo.versionCode.toLong()
		}
	}

	private fun signerDigests(packageInfo: PackageInfo): Set<String> {
		@Suppress("DEPRECATION")
		val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			packageInfo.signingInfo?.apkContentsSigners.orEmpty()
		} else {
			packageInfo.signatures.orEmpty()
		}
		return signatures.mapTo(mutableSetOf()) { signature ->
			MessageDigest.getInstance("SHA-256")
				.digest(signature.toByteArray())
				.joinToString("") { byte -> "%02x".format(byte) }
		}
	}
}

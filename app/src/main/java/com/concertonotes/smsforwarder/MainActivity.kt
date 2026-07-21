package com.concertonotes.smsforwarder

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.concertonotes.smsforwarder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

	private lateinit var binding: ActivityMainBinding
	private val PERMISSIONS_REQUEST_CODE = 1
	private var batteryOptimizationPromptShown = false
	private var notificationAccessPromptShown = false

	private val permissions = mutableListOf<String>().apply {
		add(android.Manifest.permission.RECEIVE_SMS)
		// Add permissions conditionally based on API levels
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			add(android.Manifest.permission.POST_NOTIFICATIONS)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		val navView: BottomNavigationView = binding.navView

		val navHostFragment = supportFragmentManager
			.findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
		val navController = navHostFragment.navController
		val appBarConfiguration = AppBarConfiguration(
			setOf(
				R.id.navigation_dashboard, R.id.navigation_configuration
			)
		)
		setupActionBarWithNavController(navController, appBarConfiguration)
		navView.setupWithNavController(navController)

		requestPermissions()
		startForwardingServiceSafely()
	}

	override fun onResume() {
		super.onResume()
		if (!batteryOptimizationPromptShown && requestBatteryOptimizationIfRequired()) {
			return
		}
		if (!isNotificationServiceEnabled() && !notificationAccessPromptShown) {
			notificationAccessPromptShown = true
			showNotificationAccessDialog()
		}
	}

	private fun showNotificationAccessDialog() {
		AlertDialog.Builder(this)
			.setTitle(R.string.notification_access_title)
			.setMessage(R.string.notification_access_message)
			.setPositiveButton(R.string.open_settings) { _, _ ->
				openSettingsSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
			}
			.setNegativeButton(R.string.later, null)
			.show()
	}

	private fun startForwardingServiceSafely() {
		try {
			ContextCompat.startForegroundService(this, Intent(this, AllNotificationService::class.java))
		} catch (exception: Exception) {
			Toast.makeText(this, R.string.service_start_failed, Toast.LENGTH_LONG).show()
		}
	}

	private fun openSettingsSafely(intent: Intent) {
		try {
			startActivity(intent)
		} catch (exception: Exception) {
			try {
				startActivity(Intent(Settings.ACTION_SETTINGS))
			} catch (_: Exception) {
				Toast.makeText(this, R.string.settings_open_failed, Toast.LENGTH_LONG).show()
			}
		}
	}

	private fun requestPermissions() {
		val permissionsToRequest = mutableListOf<String>()
		val permissionsToShowRationale = mutableListOf<String>()

		for (permission in permissions) {
			if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
				permissionsToRequest.add(permission)
				if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
					permissionsToShowRationale.add(permission)
				}
			}
		}

		if (permissionsToRequest.isNotEmpty()) {
			if (permissionsToShowRationale.isNotEmpty()) {
				// Show rationale dialog if needed
				showRationaleDialog(permissionsToRequest.toTypedArray())
			} else {
				// Request permissions directly
				ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSIONS_REQUEST_CODE)
			}
		}
	}

	private fun showRationaleDialog(permissionsToRequest: Array<String>) {
		val missingList = permissionsToRequest.joinToString("\n") { "- " + permissionDisplayName(it) }
		AlertDialog.Builder(this)
			.setTitle(R.string.permissions_required_title)
			.setMessage(getString(R.string.permissions_required_message, missingList))
			.setPositiveButton(R.string.confirm) { _, _ ->
				// Request permissions after showing rationale
				ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSIONS_REQUEST_CODE)
			}
			.setNegativeButton(R.string.cancel) { dialog, _ ->
				dialog.dismiss()
				Toast.makeText(this, R.string.permissions_required_toast, Toast.LENGTH_LONG).show()
			}
			.create()
			.show()
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if (requestCode == PERMISSIONS_REQUEST_CODE) {
			val permissionsToRequest = mutableListOf<String>()
			for ((index, permission) in permissions.withIndex()) {
				if (grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED) {
					permissionsToRequest.add(permission)
				}
			}
			if (permissionsToRequest.isNotEmpty()) {
				val missingList = permissionsToRequest.joinToString("\n") { "- " + permissionDisplayName(it) }
				AlertDialog.Builder(this)
					.setTitle(R.string.permissions_not_granted_title)
					.setMessage(getString(R.string.permissions_not_granted_message, missingList))
					.setPositiveButton(R.string.open_settings) { _, _ ->
						val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
							data = Uri.fromParts("package", packageName, null)
						}
						openSettingsSafely(intent)
					}
					.setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
					.create()
					.show()
			}
		}
	}

	private fun isNotificationServiceEnabled(): Boolean {
		val enabledListeners = try {
			Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
		} catch (_: Exception) {
			null
		}
		if (enabledListeners.isNullOrEmpty()) {
			return false
		}

		val colonSplitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabledListeners) }
		val componentName = ComponentName(this, NotificationListener::class.java)
		return colonSplitter.any { it == componentName.flattenToString() }
	}

	private fun requestBatteryOptimizationIfRequired(): Boolean {
		val powerManager = getSystemService(PowerManager::class.java) ?: return false
		batteryOptimizationPromptShown = true
		if (powerManager.isIgnoringBatteryOptimizations(packageName)) return false

		val directRequest = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
			data = Uri.parse("package:$packageName")
		}
		return try {
			startActivity(directRequest)
			true
		} catch (_: Exception) {
			try {
				startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
				true
			} catch (_: Exception) {
				Toast.makeText(this, R.string.battery_optimization_open_failed, Toast.LENGTH_LONG).show()
				false
			}
		}
	}

	private fun permissionDisplayName(permission: String): String {
		return when (permission) {
			android.Manifest.permission.RECEIVE_SMS -> getString(R.string.permission_receive_sms)
			android.Manifest.permission.POST_NOTIFICATIONS -> getString(R.string.permission_post_notifications)
			else -> permission.substringAfterLast('.')
		}
	}
}

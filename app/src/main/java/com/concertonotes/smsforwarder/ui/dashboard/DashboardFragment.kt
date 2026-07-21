package com.concertonotes.smsforwarder.ui.dashboard

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
//import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.concertonotes.smsforwarder.R
import com.concertonotes.smsforwarder.databinding.FragmentDashboardBinding
import com.concertonotes.smsforwarder.model.MessageItem
import com.concertonotes.smsforwarder.model.QueueSingleton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

internal fun formatMessageTimestamp(timestamp: Long, zoneId: ZoneId): String {
	return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
		.withZone(zoneId)
		.format(Instant.ofEpochMilli(timestamp))
}

internal enum class MessageDisplayStatus {
	PENDING,
	RETRYING,
	PARTIALLY_SENT,
	SUCCESS,
	FAILED
}

internal fun resolveMessageDisplayStatus(message: MessageItem, isPending: Boolean): MessageDisplayStatus {
	return when {
		message.isSent -> MessageDisplayStatus.SUCCESS
		isPending && (message.telegramDelivered || message.feishuDelivered) -> MessageDisplayStatus.PARTIALLY_SENT
		isPending && message.retryCount > 0 -> MessageDisplayStatus.RETRYING
		isPending -> MessageDisplayStatus.PENDING
		message.isError -> MessageDisplayStatus.FAILED
		else -> MessageDisplayStatus.PENDING
	}
}

class DashboardFragment : Fragment() {

	private var _binding: FragmentDashboardBinding? = null
	private val binding get() = _binding!!
	private lateinit var messageContainer: LinearLayout
	private lateinit var dashboardViewModel: DashboardViewModel

	private val messageReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			displayMessages()
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		dashboardViewModel = ViewModelProvider(this).get(DashboardViewModel::class.java)
		_binding = FragmentDashboardBinding.inflate(inflater, container, false)
		val root: View = binding.root
		messageContainer = binding.messageContainer
		QueueSingleton.initialize(requireContext())

		return root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		val menuHost: MenuHost = requireActivity()
		menuHost.addMenuProvider(object : MenuProvider {
			override fun onCreateMenu(menu: android.view.Menu, menuInflater: android.view.MenuInflater) {
				menuInflater.inflate(R.menu.dashboard_actions, menu)
			}

			override fun onPrepareMenu(menu: android.view.Menu) {
				menu.findItem(R.id.action_clear_records)?.isVisible =
					QueueSingleton.messageQueue.isNotEmpty() || QueueSingleton.messageHistory.isNotEmpty()
			}

			override fun onMenuItemSelected(menuItem: android.view.MenuItem): Boolean {
				if (menuItem.itemId != R.id.action_clear_records) return false
				confirmClearRecords()
				return true
			}
		}, viewLifecycleOwner, Lifecycle.State.RESUMED)
	}

	@SuppressLint("UnspecifiedRegisterReceiverFlag")
	override fun onResume() {
		super.onResume()
		val intentFilter = IntentFilter("${requireContext().packageName}.NEW_MESSAGE")
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			context?.registerReceiver(messageReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
		} else {
			context?.registerReceiver(messageReceiver, intentFilter)
		}
		displayMessages()
	}

	override fun onPause() {
		super.onPause()
		context?.unregisterReceiver(messageReceiver)
	}

	override fun onDestroyView() {
		super.onDestroyView()
		_binding = null
	}

	private fun displayMessages() {
		messageContainer.removeAllViews()
		val messages = (QueueSingleton.messageQueue.toList() + QueueSingleton.messageHistory.toList())
			.sortedByDescending { it.timestamp }
		binding.emptyMessage.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
		requireActivity().invalidateOptionsMenu()
		val zoneId = TimeZone.getDefault().toZoneId()

		for (message in messages) {
				val messageView = layoutInflater.inflate(R.layout.item_message, messageContainer, false)
				messageView.findViewById<TextView>(R.id.messageContent).text = message.content
				messageView.findViewById<TextView>(R.id.messageSender).text = message.sender
				messageView.findViewById<TextView>(R.id.messageTimestamp).text = getString(
					R.string.message_time_with_zone,
					formatMessageTimestamp(message.timestamp, zoneId),
					zoneId.id
				)

				val isPending = QueueSingleton.messageQueue.any { it === message }
				val (statusText, backgroundDrawable) = when (resolveMessageDisplayStatus(message, isPending)) {
					MessageDisplayStatus.SUCCESS -> R.string.message_status_success to R.drawable.rounded_background_sent
					MessageDisplayStatus.PARTIALLY_SENT -> R.string.message_status_partially_sent to R.drawable.rounded_background_sent
					MessageDisplayStatus.RETRYING -> R.string.message_status_retrying to R.drawable.rounded_background
					MessageDisplayStatus.FAILED -> R.string.message_status_failed to R.drawable.rounded_background_error
					MessageDisplayStatus.PENDING -> R.string.message_status_pending to R.drawable.rounded_background
				}
				messageView.findViewById<TextView>(R.id.messageStatus).setText(statusText)
				messageView.background = ContextCompat.getDrawable(requireContext(), backgroundDrawable)

				messageView.setOnClickListener {
					Toast.makeText(
						requireContext(),
						getString(R.string.message_origin, message.packageName),
						Toast.LENGTH_LONG
					).show()
				}
				messageView.findViewById<ImageButton>(R.id.deleteMessageButton).setOnClickListener {
					confirmDeleteMessage(message)
				}

				messageContainer.addView(messageView)
		}
	}

	private fun confirmDeleteMessage(message: MessageItem) {
		val isPending = QueueSingleton.messageQueue.any { it === message }
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.delete_message_title)
			.setMessage(if (isPending) R.string.delete_pending_message_warning else R.string.delete_completed_message_warning)
			.setNegativeButton(R.string.cancel, null)
			.setPositiveButton(R.string.delete_message) { _, _ ->
				QueueSingleton.deleteMessage(message)
				displayMessages()
			}
			.show()
	}

	private fun confirmClearRecords() {
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.clear_records_title)
			.setMessage(R.string.clear_records_warning)
			.setNegativeButton(R.string.cancel, null)
			.setPositiveButton(R.string.clear_forwarding_records) { _, _ ->
				QueueSingleton.clearMessages()
				displayMessages()
			}
			.show()
	}

}

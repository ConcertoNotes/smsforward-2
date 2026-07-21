package com.concertonotes.smsforwarder.model

import java.io.Serializable

data class MessageItem(
	val content: String,
	val sender: String,
	val packageName: String,
	val timestamp: Long,
	var isSent: Boolean = false,
	var isError: Boolean = false,
	var retryCount: Int = 0,
	var nextAttemptAt: Long = 0,
	var nextChunkIndex: Int = 0,
	var telegramDelivered: Boolean = false,
	var feishuDelivered: Boolean = false,
	var nextFeishuChunkIndex: Int = 0
) : Serializable

package com.concertonotes.smsforwarder.ui.notifications

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.content.SharedPreferences
import com.concertonotes.smsforwarder.model.APP_PREFERENCES_NAME
import com.concertonotes.smsforwarder.model.QueueSingleton

class ConfigurationViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPreferences: SharedPreferences =
        getApplication<Application>().getSharedPreferences(APP_PREFERENCES_NAME, 0)

    private val _telegramToken = MutableLiveData<String>().apply {
        value = sharedPreferences.getString("telegram_token", "")
    }
    val telegramToken: LiveData<String> = _telegramToken

    private val _userId = MutableLiveData<String>().apply {
        value = sharedPreferences.getString("telegram_user_id", "")
    }
    val userId: LiveData<String> = _userId

    private val _feishuWebhook = MutableLiveData<String>().apply {
        value = sharedPreferences.getString("feishu_webhook", "")
    }
    val feishuWebhook: LiveData<String> = _feishuWebhook

    private val _feishuSecret = MutableLiveData<String>().apply {
        value = sharedPreferences.getString("feishu_secret", "")
    }
    val feishuSecret: LiveData<String> = _feishuSecret

    fun saveTelegramToken(token: String) {
        _telegramToken.value = token
        sharedPreferences.edit().putString("telegram_token", token).apply()
        wakePendingMessages()
    }

    fun saveUserId(id: String) {
        _userId.value = id
        sharedPreferences.edit().putString("telegram_user_id", id).apply()
        wakePendingMessages()
    }

    fun saveFeishuWebhook(webhook: String) {
        _feishuWebhook.value = webhook
        sharedPreferences.edit().putString("feishu_webhook", webhook.trim()).apply()
        wakePendingMessages()
    }

    fun saveFeishuSecret(secret: String) {
        _feishuSecret.value = secret
        sharedPreferences.edit().putString("feishu_secret", secret.trim()).apply()
        wakePendingMessages()
    }

    private fun wakePendingMessages() {
        QueueSingleton.retryPendingNow()
        QueueSingleton.wakeUp(getApplication())
    }

    fun getAppEnabled(packageName: String): Boolean {
        return sharedPreferences.getBoolean("${packageName}_ignore_enabled", false)
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        sharedPreferences.edit().putBoolean("${packageName}_ignore_enabled", enabled).apply()
    }
}

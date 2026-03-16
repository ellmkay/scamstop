package com.scamkill.app.viewmodel

import android.app.Application
import android.app.role.RoleManager
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scamkill.app.ScamKillApp
import com.scamkill.app.service.CallForwardingHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val backendUrl: String = "",
    val smsScreeningEnabled: Boolean = true,
    val callScreeningEnabled: Boolean = true,
    val smsThreshold: Int = 70,
    val whitelist: List<String> = emptyList(),
    val isDefaultSmsApp: Boolean = false,
    val isCallScreener: Boolean = false,
    val forwardingNumber: String = "",
    val forwardingActive: Boolean = false,
    val forwardingBusy: Boolean = false,
    val forwardingStatus: String = "",
    val testBusy: Boolean = false,
    val testResult: String = "",
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ScamKillApp
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init {
        loadSettings()
    }

    fun loadSettings() {
        val prefs = app.preferences

        val isDefaultSms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = app.getSystemService(RoleManager::class.java)
            rm.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(app) == app.packageName
        }

        val isCallScreener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = app.getSystemService(RoleManager::class.java)
            rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        } else {
            false
        }

        _state.value = SettingsUiState(
            backendUrl = prefs.backendUrl,
            smsScreeningEnabled = prefs.smsScreeningEnabled,
            callScreeningEnabled = prefs.callScreeningEnabled,
            smsThreshold = prefs.smsThreshold,
            whitelist = prefs.whitelist.toList().sorted(),
            isDefaultSmsApp = isDefaultSms,
            isCallScreener = isCallScreener,
            forwardingNumber = prefs.forwardingNumber,
            forwardingActive = prefs.forwardingActive,
        )
    }

    fun setBackendUrl(url: String) {
        app.preferences.backendUrl = url
        _state.value = _state.value.copy(backendUrl = url)
    }

    fun setSmsScreeningEnabled(enabled: Boolean) {
        app.preferences.smsScreeningEnabled = enabled
        _state.value = _state.value.copy(smsScreeningEnabled = enabled)
    }

    fun setCallScreeningEnabled(enabled: Boolean) {
        app.preferences.callScreeningEnabled = enabled
        _state.value = _state.value.copy(callScreeningEnabled = enabled)
    }

    fun setSmsThreshold(threshold: Int) {
        app.preferences.smsThreshold = threshold
        _state.value = _state.value.copy(smsThreshold = threshold)
    }

    fun addToWhitelist(number: String) {
        if (number.isBlank()) return
        app.preferences.addToWhitelist(number)
        _state.value = _state.value.copy(whitelist = app.preferences.whitelist.toList().sorted())
    }

    fun removeFromWhitelist(number: String) {
        app.preferences.removeFromWhitelist(number)
        _state.value = _state.value.copy(whitelist = app.preferences.whitelist.toList().sorted())
    }

    fun setForwardingNumber(number: String) {
        app.preferences.forwardingNumber = number
        _state.value = _state.value.copy(forwardingNumber = number)
    }

    /**
     * Activate conditional call forwarding via USSD.
     * Returns true if programmatic USSD succeeded, false if the UI should
     * fall back to launching ACTION_CALL.
     */
    fun activateForwarding(onNeedsFallback: () -> Unit) {
        val number = _state.value.forwardingNumber.trim()
        if (number.isBlank()) {
            _state.value = _state.value.copy(forwardingStatus = "Enter a forwarding number first")
            return
        }
        _state.value = _state.value.copy(forwardingBusy = true, forwardingStatus = "Activating...")

        viewModelScope.launch(Dispatchers.IO) {
            val result = CallForwardingHelper.activate(app, number)
            when (result) {
                is CallForwardingHelper.UssdResult.Success -> {
                    app.preferences.forwardingActive = true
                    _state.value = _state.value.copy(
                        forwardingActive = true,
                        forwardingBusy = false,
                        forwardingStatus = result.response,
                    )
                }
                is CallForwardingHelper.UssdResult.Failed -> {
                    Log.w(TAG, "USSD activate failed: ${result.error}, trying fallback")
                    _state.value = _state.value.copy(forwardingBusy = false, forwardingStatus = "")
                    launch(Dispatchers.Main) { onNeedsFallback() }
                }
                is CallForwardingHelper.UssdResult.Unsupported -> {
                    _state.value = _state.value.copy(forwardingBusy = false, forwardingStatus = "")
                    launch(Dispatchers.Main) { onNeedsFallback() }
                }
            }
        }
    }

    fun deactivateForwarding(onNeedsFallback: () -> Unit) {
        _state.value = _state.value.copy(forwardingBusy = true, forwardingStatus = "Deactivating...")

        viewModelScope.launch(Dispatchers.IO) {
            val result = CallForwardingHelper.deactivate(app)
            when (result) {
                is CallForwardingHelper.UssdResult.Success -> {
                    app.preferences.forwardingActive = false
                    _state.value = _state.value.copy(
                        forwardingActive = false,
                        forwardingBusy = false,
                        forwardingStatus = result.response,
                    )
                }
                is CallForwardingHelper.UssdResult.Failed -> {
                    Log.w(TAG, "USSD deactivate failed: ${result.error}, trying fallback")
                    _state.value = _state.value.copy(forwardingBusy = false, forwardingStatus = "")
                    launch(Dispatchers.Main) { onNeedsFallback() }
                }
                is CallForwardingHelper.UssdResult.Unsupported -> {
                    _state.value = _state.value.copy(forwardingBusy = false, forwardingStatus = "")
                    launch(Dispatchers.Main) { onNeedsFallback() }
                }
            }
        }
    }

    fun checkForwardingStatus() {
        _state.value = _state.value.copy(forwardingBusy = true, forwardingStatus = "Checking...")

        viewModelScope.launch(Dispatchers.IO) {
            val result = CallForwardingHelper.checkStatus(app)
            when (result) {
                is CallForwardingHelper.UssdResult.Success -> {
                    _state.value = _state.value.copy(
                        forwardingBusy = false,
                        forwardingStatus = result.response,
                    )
                }
                is CallForwardingHelper.UssdResult.Failed -> {
                    _state.value = _state.value.copy(
                        forwardingBusy = false,
                        forwardingStatus = "Could not check: ${result.error}",
                    )
                }
                is CallForwardingHelper.UssdResult.Unsupported -> {
                    _state.value = _state.value.copy(
                        forwardingBusy = false,
                        forwardingStatus = "Not supported on this device",
                    )
                }
            }
        }
    }

    // ── Test scam detection ──────────────────────────────────────────────

    fun runScamTest(imageUrl: String) {
        _state.value = _state.value.copy(testBusy = true, testResult = "Downloading image...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Download the image and base64 encode it
                val url = java.net.URL(imageUrl)
                val conn = url.openConnection()
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val bytes = conn.getInputStream().use { it.readBytes() }
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

                _state.value = _state.value.copy(testResult = "Analyzing image (${bytes.size / 1024}KB)...")

                // Send to backend with innocent text + scam image
                val media = listOf(mapOf("data" to base64, "contentType" to "image/png"))
                val result = app.api.checkSms("+46700000000", "Check this out :)", media)

                // Write the message to the SMS inbox so it appears in conversations
                val timestamp = System.currentTimeMillis()
                try {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.Telephony.Sms.ADDRESS, "+46700000000")
                        put(android.provider.Telephony.Sms.BODY, "Check this out :) [Test MMS with scam image]")
                        put(android.provider.Telephony.Sms.DATE, timestamp)
                        put(android.provider.Telephony.Sms.DATE_SENT, timestamp)
                        put(android.provider.Telephony.Sms.READ, 0)
                        put(android.provider.Telephony.Sms.SEEN, 0)
                        put(android.provider.Telephony.Sms.TYPE, android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX)
                    }
                    app.contentResolver.insert(android.provider.Telephony.Sms.CONTENT_URI, values)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write test message to inbox", e)
                }

                // Save the image to internal storage so the UI can display it
                var savedImageUri: String? = null
                try {
                    val imageFile = java.io.File(app.filesDir, "test_scam_${timestamp}.png")
                    imageFile.writeBytes(bytes)
                    savedImageUri = android.net.Uri.fromFile(imageFile).toString()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not save test image", e)
                }

                // Log the scam result
                val entry = com.scamkill.app.data.SmsLogEntry(
                    from = "+46700000000",
                    body = "Check this out :) [Test MMS with scam image]",
                    timestamp = timestamp,
                    score = result.score,
                    verdict = result.verdict,
                    reason = result.reason,
                    keywords = result.keywords,
                    blocked = result.score >= app.preferences.smsThreshold,
                    imageUri = savedImageUri,
                )
                app.preferences.addSmsLogEntry(entry)

                val icon = if (result.score >= 70) "🔴" else if (result.score >= 40) "🟡" else "🟢"
                _state.value = _state.value.copy(
                    testBusy = false,
                    testResult = "$icon Score: ${result.score} | ${result.verdict}\n${result.reason}" +
                        if (result.keywords.isNotEmpty()) "\nKeywords: ${result.keywords.joinToString(", ")}" else "",
                )
            } catch (e: Exception) {
                Log.e(TAG, "Scam test failed", e)
                _state.value = _state.value.copy(
                    testBusy = false,
                    testResult = "❌ Error: ${e.message}",
                )
            }
        }
    }

    /** Mark forwarding as active after a successful ACTION_CALL fallback */
    fun markForwardingActive() {
        app.preferences.forwardingActive = true
        _state.value = _state.value.copy(forwardingActive = true, forwardingStatus = "Activated via dialer")
    }

    fun markForwardingInactive() {
        app.preferences.forwardingActive = false
        _state.value = _state.value.copy(forwardingActive = false, forwardingStatus = "Deactivated via dialer")
    }

    companion object {
        private const val TAG = "ScamStop.SettingsVM"
    }
}

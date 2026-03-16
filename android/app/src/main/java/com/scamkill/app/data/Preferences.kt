package com.scamkill.app.data

import android.content.Context
import android.content.SharedPreferences
import com.scamkill.app.network.SmsCheckResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class Preferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("scamkill_prefs", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    var backendUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value).apply()

    var smsScreeningEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SMS_ENABLED, value).apply()

    var callScreeningEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALL_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CALL_ENABLED, value).apply()

    var smsThreshold: Int
        get() = prefs.getInt(KEY_SMS_THRESHOLD, 70)
        set(value) = prefs.edit().putInt(KEY_SMS_THRESHOLD, value).apply()

    var callsScreenedToday: Int
        get() = prefs.getInt(KEY_CALLS_SCREENED, 0)
        set(value) = prefs.edit().putInt(KEY_CALLS_SCREENED, value).apply()

    var smsAnalyzedToday: Int
        get() = prefs.getInt(KEY_SMS_ANALYZED, 0)
        set(value) = prefs.edit().putInt(KEY_SMS_ANALYZED, value).apply()

    var smsBlockedToday: Int
        get() = prefs.getInt(KEY_SMS_BLOCKED, 0)
        set(value) = prefs.edit().putInt(KEY_SMS_BLOCKED, value).apply()

    var forwardingNumber: String
        get() = prefs.getString(KEY_FORWARDING_NUMBER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FORWARDING_NUMBER, value).apply()

    var forwardingActive: Boolean
        get() = prefs.getBoolean(KEY_FORWARDING_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_FORWARDING_ACTIVE, value).apply()

    /** User-managed whitelist of phone numbers that bypass call screening */
    var whitelist: Set<String>
        get() = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_WHITELIST, value).apply()

    fun addToWhitelist(number: String) {
        whitelist = whitelist + number.trim()
    }

    fun removeFromWhitelist(number: String) {
        whitelist = whitelist - number.trim()
    }

    fun isWhitelisted(number: String): Boolean {
        return whitelist.any { it == number || number.endsWith(it) || it.endsWith(number) }
    }

    /** Append an SMS analysis result to the log (kept in SharedPreferences, max 100) */
    fun addSmsLogEntry(entry: SmsLogEntry) {
        val log = getSmsLog().toMutableList()
        log.add(0, entry)
        while (log.size > 100) log.removeAt(log.lastIndex)
        prefs.edit().putString(KEY_SMS_LOG, json.encodeToString(SmsLogList.serializer(), SmsLogList(log))).apply()
    }

    fun getSmsLog(): List<SmsLogEntry> {
        val raw = prefs.getString(KEY_SMS_LOG, null) ?: return emptyList()
        return try {
            json.decodeFromString(SmsLogList.serializer(), raw).entries
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun resetDailyCounters() {
        callsScreenedToday = 0
        smsAnalyzedToday = 0
        smsBlockedToday = 0
    }

    companion object {
        const val DEFAULT_BACKEND_URL = "https://ai.1o.nu/nova/scamkill"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_SMS_ENABLED = "sms_enabled"
        private const val KEY_CALL_ENABLED = "call_enabled"
        private const val KEY_SMS_THRESHOLD = "sms_threshold"
        private const val KEY_CALLS_SCREENED = "calls_screened"
        private const val KEY_SMS_ANALYZED = "sms_analyzed"
        private const val KEY_SMS_BLOCKED = "sms_blocked"
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_SMS_LOG = "sms_log"
        private const val KEY_FORWARDING_NUMBER = "forwarding_number"
        private const val KEY_FORWARDING_ACTIVE = "forwarding_active"
    }
}

@Serializable
data class SmsLogEntry(
    val from: String,
    val body: String,
    val timestamp: Long,
    val score: Int,
    val verdict: String,
    val reason: String,
    val keywords: List<String> = emptyList(),
    val blocked: Boolean,
    val imageUri: String? = null,
)

@Serializable
data class SmsLogList(val entries: List<SmsLogEntry>)

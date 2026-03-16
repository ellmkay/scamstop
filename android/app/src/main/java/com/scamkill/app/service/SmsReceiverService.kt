package com.scamkill.app.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.scamkill.app.MainActivity
import com.scamkill.app.ScamKillApp
import com.scamkill.app.data.SmsLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fallback SMS receiver for when ScamKill is NOT the default SMS app.
 * Analyzes incoming SMS and posts a notification if scam is detected.
 */
class SmsReceiverService : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val app = context.applicationContext as ScamKillApp
        if (!app.preferences.smsScreeningEnabled) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].displayOriginatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody ?: "" }
        if (body.isBlank()) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = app.preferences
                prefs.smsAnalyzedToday++

                val result = app.api.checkSms(sender, body)
                val isBlocked = result.score >= prefs.smsThreshold

                val entry = SmsLogEntry(
                    from = sender,
                    body = body,
                    timestamp = System.currentTimeMillis(),
                    score = result.score,
                    verdict = result.verdict,
                    reason = result.reason,
                    keywords = result.keywords,
                    blocked = isBlocked,
                )
                prefs.addSmsLogEntry(entry)

                if (isBlocked) {
                    prefs.smsBlockedToday++
                    postBlockedNotification(context, sender, body, result.score, result.reason)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMS analysis error", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postBlockedNotification(
        context: Context,
        sender: String,
        body: String,
        score: Int,
        reason: String,
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("navigate_to", "messages")
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ScamKillApp.CHANNEL_SMS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SCAM BLOCKED (score: $score)")
            .setContentText("From $sender")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("SCAM BLOCKED (score: $score)")
                    .bigText("From: $sender\n\n${body.take(200)}\n\nReason: $reason")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID_BASE + (sender.hashCode() and 0xFFFF), notification)
    }

    companion object {
        private const val TAG = "ScamKill"
        private const val NOTIFICATION_ID_BASE = 3000
    }
}

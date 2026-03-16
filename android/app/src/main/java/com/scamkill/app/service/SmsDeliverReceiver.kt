package com.scamkill.app.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.scamkill.app.MainActivity
import com.scamkill.app.ScamKillApp
import com.scamkill.app.data.SmsLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives SMS_DELIVER when ScamStop is the default SMS app.
 * All messages are written to the inbox. Scam messages are flagged in the
 * local log so the UI can render them with a red annotation.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val app = context.applicationContext as ScamKillApp
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].displayOriginatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val timestamp = messages[0].timestampMillis

        if (body.isBlank()) return

        if (!app.preferences.smsScreeningEnabled) {
            writeToInbox(context, sender, body, timestamp)
            return
        }

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
                    timestamp = timestamp,
                    score = result.score,
                    verdict = result.verdict,
                    reason = result.reason,
                    keywords = result.keywords,
                    blocked = isBlocked,
                )
                prefs.addSmsLogEntry(entry)

                // Always write to inbox so the message is visible in conversations
                writeToInbox(context, sender, body, timestamp)

                if (isBlocked) {
                    prefs.smsBlockedToday++
                    postBlockedNotification(context, sender, body, result.score, result.reason)
                }
            } catch (e: Exception) {
                writeToInbox(context, sender, body, timestamp)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun writeToInbox(context: Context, sender: String, body: String, timestamp: Long) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.DATE_SENT, timestamp)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
        } catch (e: Exception) {
            // If we can't write (permissions issue), silently fail
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
        private const val NOTIFICATION_ID_BASE = 3000
    }
}

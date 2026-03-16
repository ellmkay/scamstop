package com.scamkill.app.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Receives WAP_PUSH_DELIVER_ACTION for incoming MMS when ScamKill is the default SMS app.
 * Parses the MMS PDU, extracts text and images, and sends to the backend for scam analysis.
 */
class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return

        val app = context.applicationContext as ScamKillApp

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Give the system a moment to write the MMS to the content provider
                kotlinx.coroutines.delay(3000)

                // Find the most recent MMS that was just received
                val mmsId = findLatestMmsId(context) ?: run {
                    Log.w(TAG, "Could not find newly received MMS in content provider")
                    pendingResult.finish()
                    return@launch
                }

                val sender = app.smsRepository.getMmsAddress(mmsId)
                val body = app.smsRepository.getMmsText(mmsId)
                val imageData = app.smsRepository.getMmsImageBase64(mmsId)

                Log.i(TAG, "MMS received id=$mmsId from=$sender bodyLen=${body.length} hasImage=${imageData != null}")

                if (sender == null) {
                    pendingResult.finish()
                    return@launch
                }

                if (!app.preferences.smsScreeningEnabled) {
                    pendingResult.finish()
                    return@launch
                }

                app.preferences.smsAnalyzedToday++

                val mediaList = if (imageData != null) {
                    listOf(mapOf("data" to imageData.first, "contentType" to imageData.second))
                } else {
                    emptyList()
                }

                val result = app.api.checkSms(sender, body, mediaList)
                val isBlocked = result.score >= app.preferences.smsThreshold

                val imageUri = app.smsRepository.getMmsImageUri(mmsId)
                val entry = SmsLogEntry(
                    from = sender,
                    body = body.ifBlank { "(MMS image)" },
                    timestamp = System.currentTimeMillis(),
                    score = result.score,
                    verdict = result.verdict,
                    reason = result.reason,
                    keywords = result.keywords,
                    blocked = isBlocked,
                    imageUri = imageUri,
                )
                app.preferences.addSmsLogEntry(entry)

                if (isBlocked) {
                    app.preferences.smsBlockedToday++
                    postBlockedNotification(context, sender, body, result.score, result.reason)
                }
            } catch (e: Exception) {
                Log.e(TAG, "MMS processing error", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun findLatestMmsId(context: Context): Long? {
        try {
            val uri = Uri.parse("content://mms")
            context.contentResolver.query(
                uri,
                arrayOf("_id"),
                null, null,
                "date DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find latest MMS", e)
        }
        return null
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

        val displayBody = body.ifBlank { "(MMS image)" }
        val notification = NotificationCompat.Builder(context, ScamKillApp.CHANNEL_SMS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("MMS SCAM BLOCKED (score: $score)")
            .setContentText("From $sender")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("MMS SCAM BLOCKED (score: $score)")
                    .bigText("From: $sender\n\n${displayBody.take(200)}\n\nReason: $reason")
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
        private const val TAG = "ScamKill.MmsReceiver"
        private const val NOTIFICATION_ID_BASE = 4000
    }
}

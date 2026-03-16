package com.scamkill.app.service

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.widget.Toast

/**
 * Minimal SMS compose activity required for default SMS app eligibility.
 * Handles SENDTO intents — extracts the recipient and optional body,
 * sends via SmsManager, and writes to the outbox.
 */
class ComposeSmsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val recipient = intent?.data?.schemeSpecificPart
            ?: intent?.getStringExtra("address")
            ?: ""
        val body = intent?.getStringExtra("sms_body")
            ?: intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: ""

        if (recipient.isBlank()) {
            Toast.makeText(this, "No recipient specified", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (body.isBlank()) {
            Toast.makeText(this, "No message body", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        sendSms(recipient, body)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun sendSms(recipient: String, body: String) {
        try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(body)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                sentIntents.add(
                    PendingIntent.getBroadcast(
                        this, i, Intent("SMS_SENT"),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            smsManager.sendMultipartTextMessage(recipient, null, parts, sentIntents, null)

            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, recipient)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.READ, 1)
            }
            contentResolver.insert(Telephony.Sms.CONTENT_URI, values)

            Toast.makeText(this, "Message sent", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to send: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

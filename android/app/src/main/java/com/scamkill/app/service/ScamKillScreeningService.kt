package com.scamkill.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import androidx.core.app.NotificationCompat
import com.scamkill.app.MainActivity
import com.scamkill.app.ScamKillApp
import com.scamkill.app.data.ContactsHelper

class ScamKillScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            callDetails.callDirection != Call.Details.DIRECTION_INCOMING
        ) {
            return
        }

        val app = application as ScamKillApp
        val prefs = app.preferences

        if (!prefs.callScreeningEnabled) {
            respondToCall(callDetails, allowCall())
            return
        }

        val handle = callDetails.handle
        val number = handle?.schemeSpecificPart ?: ""

        // Allow if number is in contacts
        if (number.isNotBlank() && ContactsHelper.isNumberInContacts(this, number)) {
            respondToCall(callDetails, allowCall())
            return
        }

        // Allow if number is in user whitelist
        if (number.isNotBlank() && prefs.isWhitelisted(number)) {
            respondToCall(callDetails, allowCall())
            return
        }

        // Always allow the Twilio forwarding number to ring through
        val fwdNumber = prefs.forwardingNumber
        if (fwdNumber.isNotBlank() && number.isNotBlank() &&
            (number.endsWith(fwdNumber) || fwdNumber.endsWith(number) || number == fwdNumber)
        ) {
            respondToCall(callDetails, allowCall())
            return
        }

        // Check STIR/SHAKEN verification (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val verificationStatus = callDetails.callerNumberVerificationStatus
            if (verificationStatus == Connection.VERIFICATION_STATUS_PASSED) {
                // Network-verified number but not in contacts -- still reject for ScamStop screening
                // (the call will be forwarded to Twilio which connects it anyway)
            }
        }

        // Unknown caller: reject so carrier forwards to Twilio
        prefs.callsScreenedToday++
        respondToCall(callDetails, rejectCall())
        postNotification(number)
    }

    private fun allowCall(): CallResponse {
        return CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .build()
    }

    private fun rejectCall(): CallResponse {
        return CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipNotification(false)
            .build()
    }

    private fun postNotification(number: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayNumber = number.ifBlank { "Private number" }

        val notification = NotificationCompat.Builder(this, ScamKillApp.CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Call screened")
            .setContentText("Unknown caller $displayNumber forwarded to ScamStop")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID_BASE + (number.hashCode() and 0xFFFF), notification)
    }

    companion object {
        private const val NOTIFICATION_ID_BASE = 2000
    }
}

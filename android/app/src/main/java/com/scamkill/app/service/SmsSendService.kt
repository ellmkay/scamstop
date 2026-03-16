package com.scamkill.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Required headless service for responding to SMS sends when acting as default SMS app.
 * Android may deliver send requests here when the app is the default SMS handler.
 */
class SmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // We don't initiate background sends from this service.
        // SMS sending is handled by ComposeSmsActivity directly.
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

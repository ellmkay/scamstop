package com.scamkill.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.scamkill.app.data.Preferences
import com.scamkill.app.data.SmsRepository
import com.scamkill.app.network.ScamKillApi

class ScamKillApp : Application() {

    val preferences by lazy { Preferences(this) }
    val api by lazy { ScamKillApi(preferences) }
    val smsRepository by lazy { SmsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS,
                "Call Screening",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications about screened calls"
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SMS,
                "SMS Screening",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications about blocked SMS messages"
            }
        )
    }

    companion object {
        const val CHANNEL_CALLS = "scamkill_calls"
        const val CHANNEL_SMS = "scamkill_sms"
    }
}

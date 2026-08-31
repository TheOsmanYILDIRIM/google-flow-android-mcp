package com.googleflow.mcp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class FlowApplication : Application() {

    companion object {
        const val CHANNEL_ID = "flow_mcp_channel"
        lateinit var instance: FlowApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}

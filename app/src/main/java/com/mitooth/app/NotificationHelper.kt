
package com.mitooth.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationHelper {

    private const val CHANNEL_ID = "mitooth_messages"
    private const val CHANNEL_NAME = "Mitooth Mesajları"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Yeni Mitooth mesajları"
            }

            val manager = context.getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    fun showMessage(context: Context, message: String) {
        createChannel(context)

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Yeni Mitooth mesajı")
            .setContentText(message)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()

        val manager = context.getSystemService(
            NotificationManager::class.java
        )

        manager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }
}

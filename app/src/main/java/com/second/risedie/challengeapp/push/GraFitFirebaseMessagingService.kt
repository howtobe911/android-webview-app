package com.second.risedie.challengeapp.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.second.risedie.challengeapp.R
import com.second.risedie.challengeapp.ui.ChallengeWebViewActivity

class GraFitFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = PushTokenRegistrar.register(applicationContext, token)

    override fun onMessageReceived(message: RemoteMessage) {
        if (ChallengeWebViewActivity.isInForeground) return
        val title = message.notification?.title ?: message.data["title"] ?: getString(R.string.app_name)
        val body = message.notification?.body ?: message.data["body"] ?: return
        showNotification(title, body, message.data["route"], message.data["inbox_item_id"])
    }

    private fun showNotification(title: String, body: String, route: String?, inboxItemId: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "GraFit", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(this, ChallengeWebViewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ChallengeWebViewActivity.EXTRA_PUSH_ROUTE, route)
            putExtra(ChallengeWebViewActivity.EXTRA_INBOX_ITEM_ID, inboxItemId)
        }
        val requestCode = inboxItemId?.hashCode() ?: System.currentTimeMillis().toInt()
        val pendingIntent = PendingIntent.getActivity(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(pendingIntent).build()
        manager.notify(requestCode, notification)
    }

    companion object { const val CHANNEL_ID = "grafit_general" }
}

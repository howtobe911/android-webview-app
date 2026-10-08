package com.second.risedie.challengeapp.push

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import com.second.risedie.challengeapp.security.SecureCredentialStore
import com.second.risedie.challengeapp.security.TrustedWebOrigin
import java.util.concurrent.TimeUnit

object PushTokenRegistrar {
    private const val PREFS = "grafit_push"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_API_BASE = "api_base"
    private const val LOG_TAG = "GrafitPush"
    private const val REGISTER_WORK = "grafit_push_token_registration"

    fun configure(context: Context, accessToken: String, apiBase: String) {
        val normalizedApiBase = TrustedWebOrigin.canonicalOrigin(apiBase) ?: return
        if (accessToken.isBlank()) return

        SecureCredentialStore(context).writeAccessToken(accessToken)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TOKEN)
            .putString(KEY_API_BASE, normalizedApiBase)
            .apply()

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> register(context, token) }
            .addOnFailureListener { error -> Log.w(LOG_TAG, "Unable to obtain FCM token", error) }
    }

    fun register(context: Context, fcmToken: String) {
        if (fcmToken.isBlank()) return

        val request = OneTimeWorkRequestBuilder<PushTokenRegistrationWorker>()
            .setInputData(Data.Builder().putString(PushTokenRegistrationWorker.KEY_FCM_TOKEN, fcmToken).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(REGISTER_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        SecureCredentialStore(context).clearAccessToken()
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(REGISTER_WORK)
    }

    internal fun credentials(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val accessToken = SecureCredentialStore(context).readAccessToken().orEmpty()
        val apiBase = TrustedWebOrigin.canonicalOrigin(prefs.getString(KEY_API_BASE, null))
        return if (accessToken.isNotBlank() && apiBase != null) accessToken to apiBase else null
    }
}

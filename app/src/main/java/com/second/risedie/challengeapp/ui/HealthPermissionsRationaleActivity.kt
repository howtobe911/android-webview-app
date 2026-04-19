package com.second.risedie.challengeapp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MaterialAlertDialogBuilder(this)
            .setTitle("Доступ к данным активности")
            .setMessage(
                "Приложение запрашивает доступ только к шагам и дистанции через Health Connect, " +
                    "чтобы синхронизировать активность пользователя внутри Challenge app."
            )
            .setCancelable(false)
            .setPositiveButton("Понятно") { _, _ -> finish() }
            .show()
    }
}

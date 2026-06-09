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
                "Challenge app запрашивает через Health Connect только данные активности: шаги и дистанцию. " +
                    "Эти данные нужны для челленджей, рейтингов, проверки результатов и базовой защиты от накрутки.\n\n" +
                    "Приложение не использует эти данные для медицинской диагностики и не заменяет врача. " +
                    "Доступ можно в любой момент отключить в настройках Health Connect на устройстве.\n\n" +
                    "Документы: /legal/privacy и /legal/terms."
            )
            .setCancelable(false)
            .setPositiveButton("Понятно") { _, _ -> finish() }
            .show()
    }
}

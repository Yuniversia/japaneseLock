package com.example.japaneselock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Это СТАТИЧЕСКИЙ ресивер.
 * Он отвечает ТОЛЬКО за BOOT_COMPLETED и SCHEDULED_LAUNCH (таймер).
 * Слежением за экраном (SCREEN_ON/OFF) занимается ScreenListenerService.
 */
class LockScreenReceiver : BroadcastReceiver() {

    private val DEBUG_TAG = "DEBUG_LOCK"

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(DEBUG_TAG, "=======================================")
        Log.d(DEBUG_TAG, "(Static) Receiver: ПОЛУЧЕНО СОБЫТИЕ: ${intent.action}")

        val prefs = context.getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", false)

        if (!enabled) {
            Log.d(DEBUG_TAG, "(Static) Receiver: Сервис отключен, игнорирую.")
            return
        }

        when (intent.action) {
            // Событие от таймера (AlarmManager)
            "SCHEDULED_LAUNCH" -> {
                Log.d(DEBUG_TAG, "(Static) Receiver: Сработал таймер SCHEDULED_LAUNCH.")

                // --- V3.0: ИСПРАВЛЕНИЕ ---

                // 1. В любом случае, ставим флаг, что "пора".
                // ScreenListenerService увидит этот флаг при следующем SCREEN_ON.
                prefs.edit().putBoolean("should_launch", true).apply()
                Log.d(DEBUG_TAG, "(Static) Receiver: Установлен флаг 'should_launch = true'.")

                // 2. Проверяем, стоит ли галочка "Автозапуск"
                val autoLaunchEnabled = prefs.getBoolean("auto_launch_enabled", false)
                Log.d(DEBUG_TAG, "(Static) Receiver: Проверка 'auto_launch_enabled': $autoLaunchEnabled")

                // 3. Запускаем немедленно, ТОЛЬКО ЕСЛИ галочка стоит.
                if (autoLaunchEnabled) {
                    Log.d(DEBUG_TAG, "(Static) Receiver: AutoLaunch включен, запускаю немедленно...")
                    // V3.0: Используем ОБЩИЙ лаунчер, который содержит все проверки (звонки, будильники)
                    LockScreenLauncher.launch(context, "StaticReceiver")
                } else {
                    Log.d(DEBUG_TAG, "(Static) Receiver: AutoLaunch выключен. Экран НЕ запускаю (жду SCREEN_ON).")
                }
                // --- КОНЕЦ ИСПРАВЛЕНИЯ V3.0 ---
            }

            // Событие перезагрузки
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(DEBUG_TAG, "(Static) Receiver: Сработал BOOT_COMPLETED.")

                // 1. Перезапускаем таймер
                Log.d(DEBUG_TAG, "(Static) Receiver: Перезапускаю таймер (scheduleNextLaunch)...")
                MainActivity.scheduleNextLaunch(context)

                // 2. И ПЕРЕЗАПУСКАЕМ СЕРВИС СЛЕЖЕНИЯ ЗА ЭКРАНОМ
                Log.d(DEBUG_TAG, "(Static) Receiver: Перезапускаю ScreenListenerService...")
                val serviceIntent = Intent(context, ScreenListenerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }

    // --- V3.0: Эта функция больше не нужна, она была дубликатом и вызывала баги.
    // --- Теперь мы используем LockScreenLauncher.launch() ---
    /*
    private fun launchLockScreen(context: Context) { ... }
    */
}
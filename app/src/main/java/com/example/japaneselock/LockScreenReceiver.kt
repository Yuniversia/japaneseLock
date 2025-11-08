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

                // --- ИСПРАВЛЕНИЕ: ВОЗВРАЩАЕМ ПРОВЕРКУ "AutoLaunch" ---

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
                    launchLockScreen(context)
                } else {
                    Log.d(DEBUG_TAG, "(Static) Receiver: AutoLaunch выключен. Экран НЕ запускаю (жду SCREEN_ON).")
                }
                // --- КОНЕЦ ИСПРАВЛЕНИЯ ---
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

    // Вспомогательная функция (дубликат из сервиса для таймера)
    private fun launchLockScreen(context: Context) {
        Log.d(DEBUG_TAG, "--- (Static) launchLockScreen: Функция вызвана ---")
        val prefs = context.getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)

        // Проверка: идет ли звонок?
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE)
                    as TelephonyManager
            val callState = telephonyManager.callState
            if (callState != TelephonyManager.CALL_STATE_IDLE) {
                Log.w(DEBUG_TAG, "--- (Static) launchLockScreen: ПРОВАЛ. Идет телефонный звонок.")
                // Если звонок, нужно перепланировать, иначе таймер "сгорит"
                MainActivity.scheduleNextLaunch(context)
                return
            }
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "--- (Static) launchLockScreen: Ошибка проверки звонка: ${e.message}")
            // Тоже перепланируем
            MainActivity.scheduleNextLaunch(context)
            return
        }

        Log.d(DEBUG_TAG, "--- (Static) launchLockScreen: Вызываю context.startActivity(LockScreenActivity)...")
        try {
            val lockIntent = Intent(context, LockScreenActivity::class.java)
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(lockIntent)

            // Если мы успешно запустились, сбрасываем флаги
            prefs.edit()
                .remove("next_launch_time")
                .putBoolean("should_launch", false) // (флаг "should_launch" теперь используется только сервисом)
                .putBoolean("screen_was_off", false)
                .apply()
            // И планируем следующий
            MainActivity.scheduleNextLaunch(context)

            Log.d(DEBUG_TAG, "--- (Static) launchLockScreen: Вызов startActivity() ЗАВЕРШЕН.")
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "--- (Static) launchLockScreen: КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
        }
    }
}
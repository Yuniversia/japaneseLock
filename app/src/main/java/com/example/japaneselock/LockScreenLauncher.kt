package com.example.japaneselock

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

object LockScreenLauncher {

    private const val DEBUG_TAG = "DEBUG_LOCK"

    fun launch(context: Context, source: String) {
        Log.d(DEBUG_TAG, "--- ($source) launchLockScreen: Функция вызвана ---")
        val prefs = context.getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)

        // Проверка: идет ли звонок?
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE)
                    as TelephonyManager
            val callState = telephonyManager.callState
            if (callState != TelephonyManager.CALL_STATE_IDLE) {
                Log.w(DEBUG_TAG, "--- ($source) launchLockScreen: ПРОВАЛ. Идет телефонный звонок.")
                return
            }
        } catch (e: SecurityException) {
            Log.e(DEBUG_TAG, "--- ($source) launchLockScreen: Ошибка проверки звонка (нет 'READ_PHONE_STATE'?) ${e.message}")
            return // Не запускаем, чтобы не мешать звонку
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "--- ($source) launchLockScreen: Неизвестная ошибка TelephonyManager: ${e.message}")
            return // Не рискуем
        }

        Log.d(DEBUG_TAG, "--- ($source) launchLockScreen: Вызываю context.startActivity(LockScreenActivity)...")
        try {
            val lockIntent = Intent(context, LockScreenActivity::class.java)
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(lockIntent)

            // Если мы успешно запустились, сбрасываем флаги
            prefs.edit()
                .remove("next_launch_time")
                .putBoolean("should_launch", false)
                .putBoolean("screen_was_off", false)
                .apply()
            // И планируем следующий
            MainActivity.scheduleNextLaunch(context)

            Log.d(DEBUG_TAG, "--- ($source) launchLockScreen: Вызов startActivity() ЗАВЕРШЕН.")
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "--- ($source) launchLockScreen: КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
        }
    }
}
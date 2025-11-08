package com.example.japaneselock

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telephony.TelephonyManager
import android.util.Log

object LockScreenLauncher {

    private const val DEBUG_TAG = "DEBUG_LOCK"

    fun launch(context: Context, source: String) {
        Log.d(DEBUG_TAG, "--- ($source) launchLockScreen: Функция вызвана ---")
        val prefs = context.getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)

        // 1. Проверка: идет ли звонок?
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

        // 2. V3.0: Проверка на будущий будильник (остается)
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val nextAlarm = alarmManager.nextAlarmClock
            if (nextAlarm != null) {
                val timeToAlarm = nextAlarm.triggerTime - System.currentTimeMillis()
                // Не запускаемся, если будильник сработает в ближайшие 2 минуты
                if (timeToAlarm > 0 && timeToAlarm < 120000) {
                    Log.w(DEBUG_TAG, "--- ($source) launchLockScreen: ПРОВАЛ. Скоро сработает будильник.")
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "--- ($source) launchLockScreen: Ошибка проверки AlarmManager: ${e.message}")
            // Не рискуем
        }

        // 3. V3.1: Улучшенная проверка на музыку/аудио/звонок/будильник
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val audioMode = audioManager.mode

            // Не запускаемся, если:
            // 1. Играет музыка (audioManager.isMusicActive)
            // 2. Идет рингтон/будильник (audioMode == AudioManager.MODE_RINGTONE)
            // 3. Идет звонок (audioMode == AudioManager.MODE_IN_CALL)
            // 4. V3.1: Идет разговор по VOIP/и т.д. (audioMode == AudioManager.MODE_IN_COMMUNICATION)
            if (audioManager.isMusicActive ||
                audioMode == AudioManager.MODE_RINGTONE ||
                audioMode == AudioManager.MODE_IN_CALL ||
                audioMode == AudioManager.MODE_IN_COMMUNICATION) {

                Log.w(DEBUG_TAG, "--- ($source) launchLockScreen: ПРОВАЛ. Идет звонок, музыка или рингтон (Mode: $audioMode, MusicActive: ${audioManager.isMusicActive}).")
                return
            }
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "--- ($source) launchLockScreen: Ошибка проверки AudioManager: ${e.message}")
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
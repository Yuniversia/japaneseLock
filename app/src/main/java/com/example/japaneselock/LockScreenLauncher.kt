package com.example.japaneselock

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telephony.TelephonyManager
import android.util.Log
import java.util.Calendar
import java.util.Locale

object LockScreenLauncher {

    private const val DEBUG_TAG = "DEBUG_LOCK"

    fun launch(context: Context, source: String) {
        Log.d(DEBUG_TAG, "--- ($source) launchLockScreen: Функция вызвана ---")
        val prefs = context.getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)

        // (Req 5.0) - 0. Проверка на "Перерыв" (ИСПРАВЛЕНО V5.1)
        // Проверяем ТЕКУЩЕЕ время
        if (MainActivity.getBreakEndTimeInMillis(context, System.currentTimeMillis()) != null) {
            Log.w(DEBUG_TAG, "--- ($source) launchLockScreen: ПРОВАЛ. Активно время 'Перерыва'.")
            return
        }

        // 1. Проверка: идет ли звонок?
        if (prefs.getBoolean("exclude_calls", true)) {
            try {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE)
                        as TelephonyManager
                val callState = telephonyManager.callState
                if (callState != TelephonyManager.CALL_STATE_IDLE) {
                    Log.w(DEBUG_TAG, "--- ($source) launchLockScreen: ПРОВАЛ. Идет телефонный звонок (Исключение активно).")
                    return
                }
            } catch (e: SecurityException) {
                Log.e(DEBUG_TAG, "--- ($source) launchLockScreen: Ошибка проверки звонка (нет 'READ_PHONE_STATE'?) ${e.message}")
                return // Не запускаем, чтобы не мешать звонку
            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "--- ($source) launchLockScreen: Неизвестная ошибка TelephonyManager: ${e.message}")
                return // Не рискуем
            }
        }

        // (Req 5.0) - Проверяем будильник, только если включена опция
        if (prefs.getBoolean("exclude_alarms", true)) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val nextAlarm = alarmManager.nextAlarmClock
                if (nextAlarm != null) {
                    val timeToAlarm = nextAlarm.triggerTime - System.currentTimeMillis()
                    // Не запускаемся, если будильник сработает в ближайшие 2 минуты
                    if (timeToAlarm > 0 && timeToAlarm < 120000) {
                        Log.w(DEBUG_TAG, "--- ($source) launchLockScreen: ПРОВАЛ. Скоро сработает будильник (Исключение активно).")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "--- ($source) launchLockScreen: Ошибка проверки AlarmManager: ${e.message}")
                // Не рискуем
            }
        }

        // (Req 5.0) - Проверяем музыку/аудио, только если включена опция
        if (prefs.getBoolean("exclude_music", true)) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val audioMode = audioManager.mode
                val isMusicActive = audioManager.isMusicActive

                // (Req 5.0) - Проверяем музыку
                if (isMusicActive) {
                    Log.w(DEBUG_TAG, "--- ($source) launchLockScreen: ПРОВАЛ. Играет музыка (Исключение 'Музыка' активно).")
                    return
                }

                // (Req 5.0) - Проверяем звонки/рингтоны (даже если exclude_calls выключен,
                // AudioManager все равно должен иметь приоритет)
                if (audioMode == AudioManager.MODE_RINGTONE ||
                    audioMode == AudioManager.MODE_IN_CALL ||
                    audioMode == AudioManager.MODE_IN_COMMUNICATION) {

                    Log.w(DEBUG_TAG, "--- ($source) launchLockScreen: ПРОВАЛ. Идет звонок или рингтон (Режим AudioManager: $audioMode).")
                    return
                }
            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "--- ($source) launchLockScreen: Ошибка проверки AudioManager: ${e.message}")
                return // Не рискуем
            }
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

    // (Req 5.0) - Проверяет, активно ли сейчас время "Перерыва"
    private fun isBreakTime(context: Context): Boolean {
        val prefs = context.getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("break_enabled", false)) {
            return false // Перерыв выключен
        }

        try {
            val startStr = prefs.getString("break_start", "22:00") ?: "22:00"
            val endStr = prefs.getString("break_end", "06:00") ?: "06:00"

            val startParts = startStr.split(":").map { it.toInt() }
            val endParts = endStr.split(":").map { it.toInt() }

            val calStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, startParts[0])
                set(Calendar.MINUTE, startParts[1])
                set(Calendar.SECOND, 0)
            }
            val startTime = calStart.timeInMillis

            val calEnd = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, endParts[0])
                set(Calendar.MINUTE, endParts[1])
                set(Calendar.SECOND, 0)
            }
            var endTime = calEnd.timeInMillis

            val now = Calendar.getInstance().timeInMillis

            // Если "До" (06:00) раньше чем "C" (22:00), значит это "через ночь"
            if (endTime < startTime) {
                // Если сейчас (03:00) < "До" (06:00)
                if (now < endTime) {
                    // Мы в "сегодняшнем" дне до 06:00.
                    // Нам нужно "вчерашнее" 22:00.
                    calStart.add(Calendar.DAY_OF_YEAR, -1)
                    return now > calStart.timeInMillis // (03:00 > вчера 22:00)
                }
                // Если сейчас (23:00) > "C" (22:00)
                else {
                    // Мы во "вчерашнем" дне после 22:00.
                    // Нам нужно "завтрашнее" 06:00.
                    calEnd.add(Calendar.DAY_OF_YEAR, +1)
                    return now < calEnd.timeInMillis // (23:00 < завтра 06:00)
                }
            }

            // Обычный случай (например, с 09:00 до 17:00)
            return now in startTime..endTime

        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "--- (isBreakTime) Ошибка парсинга времени перерыва: ${e.message}")
            return false // В случае ошибки не блокируем
        }
    }
}
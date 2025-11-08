package com.example.japaneselock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

class ScreenListenerService : Service() {

    private val DEBUG_TAG = "DEBUG_LOCK"
    private val CHANNEL_ID = "ScreenListenerServiceChannel"

    // Это наш "слушатель", который будет создан и уничтожен вместе с сервисом
    // Он содержит ВСЮ ОРИГИНАЛЬНУЮ ЛОГИКУ из вашего LockScreenReceiver.kt
    private val screenReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            val prefs = context.getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("enabled", false)) return // Выключено, выходим

            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(DEBUG_TAG, "=======================================")
                    Log.d(DEBUG_TAG, "ScreenListenerService: ПОЛУЧЕНО СОБЫТИЕ: ACTION_SCREEN_OFF")
                    prefs.edit().putBoolean("screen_was_off", true).apply()
                }

                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.d(DEBUG_TAG, "=======================================")
                    Log.d(DEBUG_TAG, "ScreenListenerService: ПОЛУЧЕНО СОБЫТИЕ: ${intent.action}")

                    val nextLaunchTime = prefs.getLong("next_launch_time", 0)
                    val currentTime = System.currentTimeMillis()
                    val shouldLaunch = prefs.getBoolean("should_launch", false)
                    val screenWasOff = prefs.getBoolean("screen_was_off", false)
                    val autoLaunchEnabled = prefs.getBoolean("auto_launch_enabled", false)

                    Log.d(DEBUG_TAG, "ScreenListenerService: nextLaunchTime: $nextLaunchTime, currentTime: $currentTime")
                    Log.d(DEBUG_TAG, "ScreenListenerService: shouldLaunch: $shouldLaunch, screenWasOff: $screenWasOff")
                    Log.d(DEBUG_TAG, "ScreenListenerService: autoLaunchEnabled: $autoLaunchEnabled")

                    // Проверяем, наступило ли время (по флагу от таймера ИЛИ по времени)
                    val timeHasCome = (shouldLaunch || currentTime >= nextLaunchTime) && nextLaunchTime > 0
                    Log.d(DEBUG_TAG, "ScreenListenerService: timeHasCome: $timeHasCome")

                    if (timeHasCome) {
                        val launchAction = {
                            Log.d(DEBUG_TAG, "ScreenListenerService: Вызов LockScreenLauncher.launch()...")
                            // ИСПОЛЬЗУЕМ ОБЩИЙ ЗАПУСК
                            LockScreenLauncher.launch(context, "Service")
                            // Сбрасываем флаги и время
                            prefs.edit()
                                .remove("next_launch_time")
                                .putBoolean("should_launch", false)
                                .putBoolean("screen_was_off", false)
                                .apply()
                            // Сразу планируем следующий запуск
                            MainActivity.scheduleNextLaunch(context)
                        }

                        // Режим БЕЗ галочки (только при цикле "погас/включился")
                        if (!autoLaunchEnabled) {
                            if (screenWasOff) {
                                Log.d(DEBUG_TAG, "ScreenListenerService: Launching (cycle mode)")
                                launchAction()
                            } else {
                                Log.d(DEBUG_TAG, "ScreenListenerService: Skipped (cycle mode) - screen was not off")
                            }
                        }
                        // Режим С галочкой (в любой момент при включении экрана)
                        else {
                            Log.d(DEBUG_TAG, "ScreenListenerService: Launching (auto mode on screen on)")
                            launchAction()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(DEBUG_TAG, "ScreenListenerService: onCreate - Сервис создается.")

        // Создаем канал уведомлений (для Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Japanese Lock Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }

        // Регистрируем наш "слушатель" динамически
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT) // Важно для полной логики
        }
        registerReceiver(screenReceiver, filter)
        Log.d(DEBUG_TAG, "ScreenListenerService: Динамический screenReceiver ЗАРЕГИСТРИРОВАН.")
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(DEBUG_TAG, "ScreenListenerService: onStartCommand - Сервис запускается.")

        if (intent == null) {
            Log.w(DEBUG_TAG, "ScreenListenerService: Intent is null, using default behavior")
            return START_STICKY
        }

        // Уведомление, которое ведет обратно на LockScreen (чтобы не было лазейкой)
        val notificationIntent = Intent(this, LockScreenActivity::class.java)
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Japanese Lock Активен")
            .setContentText("Нажмите, чтобы вернуться к заданию")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification) // ID = 1
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(DEBUG_TAG, "ScreenListenerService: onDestroy - Сервис уничтожается.")
        // Обязательно "отписываемся" от событий
        unregisterReceiver(screenReceiver)
        Log.d(DEBUG_TAG, "ScreenListenerService: Динамический screenReceiver ОТКЛЮЧЕН.")
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    // --- Функция запуска (с проверкой на звонок) ---
    private fun launchLockScreen(context: Context) {
        Log.d(DEBUG_TAG, "--- (Service) launchLockScreen: Функция вызвана ---")

        // Проверка: идет ли звонок?
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE)
                    as TelephonyManager
            val callState = telephonyManager.callState
            if (callState != TelephonyManager.CALL_STATE_IDLE) {
                Log.w(DEBUG_TAG, "--- (Service) launchLockScreen: ПРОВАЛ. Идет телефонный звонок.")
                return
            }
        } catch (e: SecurityException) {
            Log.e(DEBUG_TAG, "--- (Service) launchLockScreen: Ошибка проверки звонка (нет 'READ_PHONE_STATE'?) ${e.message}")
            // В этом случае лучше не запускать, чтобы не мешать звонку
            return
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "--- (Service) launchLockScreen: Неизвестная ошибка TelephonyManager: ${e.message}")
            return // Не рискуем
        }

        Log.d(DEBUG_TAG, "--- (Service) launchLockScreen: Вызываю context.startActivity(LockScreenActivity)...")
        try {
            val lockIntent = Intent(context, LockScreenActivity::class.java)
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(lockIntent)
            Log.d(DEBUG_TAG, "--- (Service) launchLockScreen: Вызов startActivity() ЗАВЕРШЕН.")
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "--- (Service) launchLockScreen: КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
        }
    }
}
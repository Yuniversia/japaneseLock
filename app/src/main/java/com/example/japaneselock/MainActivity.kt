package com.example.japaneselock

import android.Manifest
import android.widget.SeekBar
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.japaneselock.database.AppDatabase
import com.example.japaneselock.database.Deck
import com.example.japaneselock.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.app.admin.DevicePolicyManager
import android.content.ComponentName

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var binding: ActivityMainBinding // Используем ViewBinding
    private lateinit var db: AppDatabase
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    // Константы для логов и разрешений
    companion object {
        const val DEBUG_TAG = "DEBUG_LOCK"
        private const val ALARM_ACTION = "SCHEDULED_LAUNCH"
        private const val ALARM_REQUEST_CODE = 123
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 101
        private const val PHONE_STATE_PERMISSION_REQUEST_CODE = 102
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 104
        private const val DEVICE_ADMIN_REQUEST_CODE = 103 // НОВАЯ

        /**
         * ОБЩАЯ ФУНКЦИЯ ПЛАНИРОВАНИЯ (НОВАЯ ЛОГИКА - МИНУТЫ)
         */
        fun scheduleNextLaunch(context: Context) {
            val prefs = context.getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("enabled", false)) {
                Log.d(DEBUG_TAG, "scheduleNextLaunch: Пропущено (сервис отключен)")
                return
            }

            // Проверяем, не запланирован ли уже запуск
            val existingLaunchTime = prefs.getLong("next_launch_time", 0)
            if (existingLaunchTime > System.currentTimeMillis()) {
                val timeString = java.text.SimpleDateFormat("HH:mm").format(java.util.Date(existingLaunchTime))
                Log.d(DEBUG_TAG, "scheduleNextLaunch: Пропущено (запуск уже запланирован на $timeString)")
                // Обновляем UI, если мы в MainActivity
                if (context is MainActivity) {
                    context.runOnUiThread { context.updateNextLaunchTime() }
                }
                return
            }

            Log.d(DEBUG_TAG, "scheduleNextLaunch: Планирую новый запуск...")

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, LockScreenReceiver::class.java).apply { action = ALARM_ACTION }
            val pendingIntent = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // --- НОВАЯ ЛОГИКА: ИНТЕРВАЛ В МИНУТАХ ---
            val intervalMinutes = prefs.getInt("launch_interval_minutes", 30)
            if (intervalMinutes <= 0) {
                Log.e(DEBUG_TAG, "scheduleNextLaunch: intervalMinutes = 0. Отмена.")
                return
            }

            val delayMillis = intervalMinutes * 60 * 1000L
            val triggerTime = System.currentTimeMillis() + delayMillis
            prefs.edit().putLong("next_launch_time", triggerTime).apply()
            // --- КОНЕЦ НОВОЙ ЛОГИКИ ---


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                    Log.d(DEBUG_TAG, "scheduleNextLaunch: Будильник (API 31+) установлен на $triggerTime")
                } else {
                    Log.e(DEBUG_TAG, "scheduleNextLaunch: НЕ МОГУ установить будильник, нет разрешения SCHEDULE_EXACT_ALARM")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                Log.d(DEBUG_TAG, "scheduleNextLaunch: Будильник (API < 31) установлен на $triggerTime")
            }

            val dateFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val timeString = dateFormat.format(java.util.Date(triggerTime))
            Log.d(DEBUG_TAG, "Следующий запуск: $timeString")

            // Обновляем UI, если мы в MainActivity
            if (context is MainActivity) {
                context.runOnUiThread { context.updateNextLaunchTime() }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(DEBUG_TAG, "MainActivity: onCreate")

        prefs = getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)
        db = AppDatabase.getDatabase(this)

        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        setupUI()
        loadAndDisplayDecks() // Загружаем колоды

        // Запрашиваем все разрешения
        checkAndRequestOverlayPermission()
        checkAndRequestPhoneStatePermission()
        checkAndRequestExactAlarmPermission()
        checkAndRequestNotificationPermission()
        checkAndRequestDeviceAdminPermission()
    }

    private fun setupUI() {
        Log.d(DEBUG_TAG, "MainActivity: setupUI (v2.0)")

        val isEnabled = prefs.getBoolean("enabled", false)
        updateStatusUI(isEnabled)
        updateNextLaunchTime()

        // Загружаем сохраненные настройки
        binding.intervalInput.setText(prefs.getInt("launch_interval_minutes", 30).toString())
        binding.autoLaunchCheckbox.isChecked = prefs.getBoolean("auto_launch_enabled", false)
        binding.countSeekBar.progress = prefs.getInt("count", 5) - 1
        binding.countText.text = "Вопросов за раз: ${prefs.getInt("count", 5)}"


        // --- ОБРАБОТЧИКИ ---
        binding.countSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.countText.text = "Вопросов за раз: ${progress + 1}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- КНОПКА "ВКЛЮЧИТЬ" (ИСПРАВЛЕНА) ---
        binding.enableButton.setOnClickListener {
            Log.d(DEBUG_TAG, "MainActivity: ENABLE button clicked")

            // --- НОВАЯ ПРОВЕРКА РАЗРЕШЕНИЯ НА УВЕДОМЛЕНИЯ ---
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(DEBUG_TAG, "MainActivity: ПРОВАЛ ЗАПУСКА. Нет разрешения POST_NOTIFICATIONS.")
                    Toast.makeText(this, "Сначала дайте разрешение на Уведомления!", Toast.LENGTH_LONG).show()
                    // Запрашиваем разрешение еще раз
                    checkAndRequestNotificationPermission()
                    return@setOnClickListener // <-- НЕ запускаем сервис
                }
            }
            // --- КОНЕЦ ПРОВЕРКИ ---

            saveSettings()
            prefs.edit().putBoolean("enabled", true).apply()

            prefs.edit().remove("next_launch_time").remove("should_launch").apply()
            scheduleNextLaunch(this)

            startScreenService(true) // Теперь этот вызов безопасен
            updateStatusUI(true)
            Toast.makeText(this, "✅ Запущено! Интервал: ${binding.intervalInput.text} мин.", Toast.LENGTH_LONG).show()
        }
        // --- КОНЕЦ КНОПКИ "ВКЛЮЧИТЬ" ---

        binding.disableButton.setOnClickListener {
            Log.d(DEBUG_TAG, "MainActivity: DISABLE button clicked")
            prefs.edit().putBoolean("enabled", false).apply()

            cancelScheduledLaunches()
            startScreenService(false)

            updateStatusUI(false)
            Toast.makeText(this, "Блокировка отключена", Toast.LENGTH_SHORT).show()
        }

        binding.updateButton.setOnClickListener {
            Log.d(DEBUG_TAG, "MainActivity: UPDATE button clicked")
            saveSettings()

            if (prefs.getBoolean("enabled", false)) {

                // --- НОВАЯ ПРОВЕРКА РАЗРЕШЕНИЯ (также для "Обновить") ---
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        Log.e(DEBUG_TAG, "MainActivity: ПРОВАЛ ОБНОВЛЕНИЯ. Нет разрешения POST_NOTIFICATIONS.")
                        Toast.makeText(this, "Сначала дайте разрешение на Уведомления!", Toast.LENGTH_LONG).show()
                        checkAndRequestNotificationPermission()
                        return@setOnClickListener
                    }
                }
                // --- КОНЕЦ ПРОВЕРКИ ---

                Log.d(DEBUG_TAG, "MainActivity: Сервис включен, перезапускаю таймер и сервис...")
                // Полный перезапуск
                startScreenService(false) // Сначала стоп
                cancelScheduledLaunches()

                scheduleNextLaunch(this) // Потом старт
                startScreenService(true)

                Toast.makeText(this, "🔄 Обновлено! Интервал: ${binding.intervalInput.text} мин.", Toast.LENGTH_LONG).show()
            } else {
                Log.d(DEBUG_TAG, "MainActivity: Сервис выключен, просто сохраняю.")
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
        }

        binding.manageDecksButton.setOnClickListener {
            Log.d(DEBUG_TAG, "MainActivity: MANAGE DECKS button clicked")
            // (Проверяем, что DeckManagerActivity существует)
            try {
                val intent = Intent(this, DeckManagerActivity::class.java)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "Ошибка открытия DeckManagerActivity: ${e.message}")
                Toast.makeText(this, "Не удалось открыть менеджер колод.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveSettings() {
        Log.d(DEBUG_TAG, "MainActivity: saveSettings")

        // 1. Сохраняем базовые настройки
        val interval = binding.intervalInput.text.toString().toIntOrNull() ?: 30
        val count = binding.countSeekBar.progress + 1
        val autoLaunch = binding.autoLaunchCheckbox.isChecked

        prefs.edit().apply {
            putInt("launch_interval_minutes", interval)
            putInt("count", count)
            putBoolean("auto_launch_enabled", autoLaunch)
            apply()
        }

        // 2. Сохраняем выбраные колоды
        val selectedDeckIds = mutableSetOf<String>()
        for (i in 0 until binding.deckListContainer.childCount) {
            val view = binding.deckListContainer.getChildAt(i)
            if (view is CheckBox) {
                if (view.isChecked) {
                    val deckId = view.tag as Long
                    selectedDeckIds.add(deckId.toString())
                }
            }
        }

        prefs.edit().putStringSet("selected_deck_ids", selectedDeckIds).apply()
        Log.d(DEBUG_TAG, "Настройки сохранены (Interval: $interval, Count: $count, Auto: $autoLaunch)")
        Log.d(DEBUG_TAG, "Выбранные колоды: $selectedDeckIds")
    }

    // --- НОВАЯ ЛОГИКА ДЛЯ КОЛОД ---
    private fun loadAndDisplayDecks() {
        val selectedIds = prefs.getStringSet("selected_deck_ids", setOf("1", "2"))?.map { it.toLong() } ?: listOf(1L, 2L)

        lifecycleScope.launch(Dispatchers.IO) {
            val decks = db.cardDao().getAllDecks()
            withContext(Dispatchers.Main) {
                binding.deckListContainer.removeAllViews() // Очищаем старый список
                if (decks.isEmpty()) {
                    binding.deckListContainer.addView(createDisabledTextView("Нет колод. Нажмите 'Управление', чтобы добавить."))
                } else {
                    binding.deckListContainer.addView(createDisabledTextView("Выберите колоды для блокировки:"))
                    decks.forEach { deck ->
                        val checkBox = CheckBox(this@MainActivity).apply {
                            text = deck.name
                            tag = deck.id
                            isChecked = selectedIds.contains(deck.id)
                            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                        }
                        binding.deckListContainer.addView(checkBox)
                    }
                }
            }
        }
    }

    private fun createDisabledTextView(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
        }
    }

    // --- ОРИГИНАЛЬНЫЕ UI-ФУНКЦИИ ---

    fun updateNextLaunchTime() {
        val nextLaunchTime = prefs.getLong("next_launch_time", 0)

        if (prefs.getBoolean("enabled", false) && nextLaunchTime > 0) {
            val dateFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val timeString = dateFormat.format(java.util.Date(nextLaunchTime))
            binding.nextLaunchText.text = "⏰ Следующий запуск: $timeString"
            binding.nextLaunchText.visibility = View.VISIBLE
        } else {
            binding.nextLaunchText.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(DEBUG_TAG, "MainActivity: onResume")
        updateNextLaunchTime() // Обновляем время при возвращении
        loadAndDisplayDecks() // Обновляем список колод
        // Повторная проверка разрешений
        checkAndRequestOverlayPermission()
        checkAndRequestExactAlarmPermission()
        checkAndRequestNotificationPermission()
    }

    private fun updateStatusUI(isEnabled: Boolean) {
        if (isEnabled) {
            binding.statusText.text = "Статус: ✅ Активно"
            binding.statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.enableButton.visibility = View.GONE
            binding.disableButton.visibility = View.VISIBLE
        } else {
            binding.statusText.text = "Статус: ⏸️ Отключено"
            binding.statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.enableButton.visibility = View.VISIBLE
            binding.disableButton.visibility = View.GONE
        }
    }

    private fun cancelScheduledLaunches() {
        Log.d(DEBUG_TAG, "MainActivity: cancelScheduledLaunches")
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, LockScreenReceiver::class.java).apply { action = ALARM_ACTION }
        val pendingIntent = PendingIntent.getBroadcast(
            this, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        prefs.edit().remove("next_launch_time").remove("should_launch").apply()
        updateNextLaunchTime()
    }

    // --- ФУНКЦИЯ ЗАПУСКА/ОСТАНОВКИ СЕРВИСА ---
    private fun startScreenService(enable: Boolean) {
        val serviceIntent = Intent(this, ScreenListenerService::class.java)
        try {
            if (enable) {
                Log.d(DEBUG_TAG, "MainActivity: Запускаю ScreenListenerService...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Log.d(DEBUG_TAG, "MainActivity: Останавливаю ScreenListenerService...")
                stopService(serviceIntent)
            }
        } catch (e: Exception) {
            // Эта ошибка возникает, если мы пытаемся запустить сервис, а разрешение POST_NOTIFICATIONS было отозвано
            Log.e(DEBUG_TAG, "КРИТИЧЕСКАЯ ОШИБКА startScreenService: ${e.message}")
            Toast.makeText(this, "Ошибка запуска сервиса. Проверьте разрешения.", Toast.LENGTH_LONG).show()
            // Сбрасываем UI
            prefs.edit().putBoolean("enabled", false).apply()
            updateStatusUI(false)
        }
    }

    // --- БЛОК ЗАПРОСА РАЗРЕШЕНИЙ (С ЛОГАМИ) ---
    private fun checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.d(DEBUG_TAG, "MainActivity: Запрашиваю SYSTEM_ALERT_WINDOW (Наложение)")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        }
    }

    private fun checkAndRequestDeviceAdminPermission() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            Log.d(DEBUG_TAG, "MainActivity: Запрашиваю Device Admin")
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Это разрешение необходимо для принудительной блокировки экрана после 3-х неудачных попыток.")
            }
            startActivityForResult(intent, DEVICE_ADMIN_REQUEST_CODE)
        }
    }

    private fun checkAndRequestPhoneStatePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
                Log.d(DEBUG_TAG, "MainActivity: Запрашиваю READ_PHONE_STATE (Состояние телефона)")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_PHONE_STATE),
                    PHONE_STATE_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    private fun checkAndRequestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.d(DEBUG_TAG, "MainActivity: Запрашиваю SCHEDULE_EXACT_ALARM (Точные будильники)")
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                startActivity(intent)
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.d(DEBUG_TAG, "MainActivity: Запрашиваю POST_NOTIFICATIONS (Уведомления)")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Log.d(DEBUG_TAG, "MainActivity: Разрешение SYSTEM_ALERT_WINDOW ПОЛУЧЕНО")
                } else {
                    Log.e(DEBUG_TAG, "MainActivity: В разрешении SYSTEM_ALERT_WINDOW ОТКАЗАНО")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PHONE_STATE_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(DEBUG_TAG, "MainActivity: Разрешение READ_PHONE_STATE ПОЛУЧЕНО")
                } else {
                    Log.e(DEBUG_TAG, "MainActivity: В разрешении READ_PHONE_STATE ОТКАЗАНО")
                }
            }
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(DEBUG_TAG, "MainActivity: Разрешение POST_NOTIFICATIONS ПОЛУЧЕНО")
                } else {
                    Log.e(DEBUG_TAG, "MainActivity: В разрешении POST_NOTIFICATIONS ОТКАЗАНО")
                }
            }
        }
    }
}
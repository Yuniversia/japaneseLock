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
import android.app.TimePickerDialog
import java.util.Calendar
import java.util.Locale

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

        // (ИСПРАВЛЕНИЕ V5.1) - Логика "Перерыва", перенесена из LockScreenLauncher
        /**
         * Проверяет, попадает ли указанное время (timeToCheck) в активный "Перерыв".
         * @return Long (время окончания перерыва), если он активен, или null, если нет.
         */
        fun getBreakEndTimeInMillis(context: Context, timeToCheck: Long): Long? {
            val prefs = context.getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("break_enabled", false)) {
                return null // Перерыв выключен
            }

            try {
                val startStr = prefs.getString("break_start", "22:00") ?: "22:00"
                val endStr = prefs.getString("break_end", "06:00") ?: "06:00"

                val startParts = startStr.split(":").map { it.toInt() }
                val endParts = endStr.split(":").map { it.toInt() }

                val calNow = Calendar.getInstance().apply { timeInMillis = timeToCheck }

                val calStart = Calendar.getInstance().apply {
                    timeInMillis = timeToCheck
                    set(Calendar.HOUR_OF_DAY, startParts[0])
                    set(Calendar.MINUTE, startParts[1])
                    set(Calendar.SECOND, 0)
                }
                val startTime = calStart.timeInMillis

                val calEnd = Calendar.getInstance().apply {
                    timeInMillis = timeToCheck
                    set(Calendar.HOUR_OF_DAY, endParts[0])
                    set(Calendar.MINUTE, endParts[1])
                    set(Calendar.SECOND, 0)
                }
                var endTime = calEnd.timeInMillis

                // Если "До" (06:00) раньше чем "C" (22:00), значит это "через ночь"
                if (endTime <= startTime) {

                    // Пример: 22:00 - 06:00
                    // calStart = СЕГОДНЯ в 22:00
                    // calEnd = СЕГОДНЯ в 06:00

                    if (calNow.timeInMillis >= startTime) {
                        // Мы СЕГОДНЯ после 22:00 (например, 23:00)
                        // Перерыв закончится ЗАВТРА в 06:00
                        calEnd.add(Calendar.DAY_OF_YEAR, 1)
                        return calEnd.timeInMillis // Завтра 06:00
                    } else if (calNow.timeInMillis < endTime) {
                        // Мы СЕГОДНЯ до 06:00 (например, 03:00)
                        // Перерыв начался ВЧЕРА в 22:00
                        return endTime // Сегодня 06:00
                    }

                } else {
                    // Обычный случай (например, с 09:00 до 17:00)
                    if (timeToCheck in startTime..endTime) {
                        return endTime
                    }
                }

                return null // Не в перерыве

            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "--- (getBreakEndTimeInMillis) Ошибка парсинга времени перерыва: ${e.message}")
                return null // В случае ошибки
            }
        }

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
            var triggerTime = System.currentTimeMillis() + delayMillis

            // (ИСПРАВЛЕНИЕ V5.1) - Проверяем, не попадает ли запуск на перерыв
            val breakEndTime = getBreakEndTimeInMillis(context, triggerTime)
            if (breakEndTime != null) {
                Log.d(DEBUG_TAG, "scheduleNextLaunch: Запуск ($triggerTime) попадает в перерыв (до $breakEndTime).")
                // Планируем следующий запуск через 1 минуту ПОСЛЕ окончания перерыва
                triggerTime = breakEndTime + 60000
                Log.d(DEBUG_TAG, "scheduleNextLaunch: Новый запуск запланирован на $triggerTime")
            }
            // --- КОНЕЦ ИСПРАВЛЕНИЯ V5.1 ---

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

        // Запрашиваем все разрешения
        checkAndRequestOverlayPermission()
        checkAndRequestPhoneStatePermission()
        checkAndRequestExactAlarmPermission()
        checkAndRequestNotificationPermission()
        checkAndRequestDeviceAdminPermission()
    }

    // --- V3.0: ФУНКЦИЯ ОБНОВЛЕНА ---
    private fun setupUI() {
        Log.d(DEBUG_TAG, "MainActivity: setupUI (v3.0)")

        val isEnabled = prefs.getBoolean("enabled", false)
        updateStatusUI(isEnabled)
        updateNextLaunchTime()

        // Загружаем сохраненные настройки
        binding.intervalInput.setText(prefs.getInt("launch_interval_minutes", 30).toString())
        binding.autoLaunchCheckbox.isChecked = prefs.getBoolean("auto_launch_enabled", false)
        // V3.0: Загружаем в новый EditText
        binding.countInput.setText(prefs.getInt("count", 5).toString())


        // --- ОБРАБОТЧИКИ ---

        // V3.0: SeekBar удален

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

            // V3.0: saveSettings() теперь асинхронный, запускаем и ждем
            lifecycleScope.launch {
                saveSettings() // Сначала сохраняем (с проверкой лимита)

                // Этот код выполнится ПОСЛЕ завершения saveSettings
                prefs.edit().putBoolean("enabled", true).apply()
                prefs.edit().remove("next_launch_time").remove("should_launch").apply()
                scheduleNextLaunch(this@MainActivity)

                startScreenService(true) // Теперь этот вызов безопасен
                updateStatusUI(true)

                // V3.0: Читаем обновленное значение (могло быть исправлено)
                val finalInterval = prefs.getInt("launch_interval_minutes", 30)
                val finalCount = prefs.getInt("count", 5)
                Toast.makeText(this@MainActivity, "✅ Запущено! Интервал: $finalInterval мин. Вопросов: $finalCount", Toast.LENGTH_LONG).show()
            }
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

            // V3.0: saveSettings() теперь асинхронный
            lifecycleScope.launch {
                saveSettings() // Сохраняем и проверяем лимиты

                // V3.0: Читаем обновленные значения
                val finalInterval = prefs.getInt("launch_interval_minutes", 30)
                val finalCount = prefs.getInt("count", 5)

                if (prefs.getBoolean("enabled", false)) {
                    // --- НОВАЯ ПРОВЕРКА РАЗРЕШЕНИЯ (также для "Обновить") ---
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            Log.e(DEBUG_TAG, "MainActivity: ПРОВАЛ ОБНОВЛЕНИЯ. Нет разрешения POST_NOTIFICATIONS.")
                            Toast.makeText(this@MainActivity, "Сначала дайте разрешение на Уведомления!", Toast.LENGTH_LONG).show()
                            checkAndRequestNotificationPermission()
                            return@launch // V3.0: выходим из корутины
                        }
                    }
                    // --- КОНЕЦ ПРОВЕРКИ ---

                    Log.d(DEBUG_TAG, "MainActivity: Сервис включен, перезапускаю таймер и сервис...")
                    // Полный перезапуск
                    startScreenService(false) // Сначала стоп
                    cancelScheduledLaunches()

                    scheduleNextLaunch(this@MainActivity) // Потом старт
                    startScreenService(true)

                    Toast.makeText(this@MainActivity, "🔄 Обновлено! Интервал: $finalInterval мин. Вопросов: $finalCount", Toast.LENGTH_LONG).show()
                } else {
                    Log.d(DEBUG_TAG, "MainActivity: Сервис выключен, просто сохраняю.")
                    Toast.makeText(this@MainActivity, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                }
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

        // (Req 5.0) - Загрузка настроек Исключений
        binding.checkExcludeCalls.isChecked = prefs.getBoolean("exclude_calls", true)
        binding.checkExcludeAlarms.isChecked = prefs.getBoolean("exclude_alarms", true)
        binding.checkExcludeMusic.isChecked = prefs.getBoolean("exclude_music", true)

        // (Req 5.0) - Логика Перерыва
        val isBreakEnabled = prefs.getBoolean("break_enabled", false)
        binding.checkBreakTime.isChecked = isBreakEnabled
        binding.layoutBreakTime.visibility = if (isBreakEnabled) View.VISIBLE else View.GONE
        binding.editBreakStart.setText(prefs.getString("break_start", "22:00"))
        binding.editBreakEnd.setText(prefs.getString("break_end", "06:00"))

        binding.checkBreakTime.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutBreakTime.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Обработчики кликов для выбора времени
        binding.editBreakStart.setOnClickListener { showTimePicker(binding.editBreakStart) }
        binding.editBreakEnd.setOnClickListener { showTimePicker(binding.editBreakEnd) }
        binding.editBreakStart.isFocusable = false
        binding.editBreakEnd.isFocusable = false
    }

    private fun showTimePicker(editText: TextView) {
        val cal = Calendar.getInstance()
        val parts = editText.text.toString().split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: cal.get(Calendar.HOUR_OF_DAY)
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: cal.get(Calendar.MINUTE)

        TimePickerDialog(this, { _, h, m ->
            val formattedTime = String.format(java.util.Locale.getDefault(), "%02d:%02d", h, m)
            editText.text = formattedTime
        }, hour, minute, true).show()
    }

    // --- V3.0: ФУНКЦИЯ ПЕРЕПИСАНА (стала suspend) ---
    private suspend fun saveSettings() {
        Log.d(DEBUG_TAG, "MainActivity: saveSettings (suspend)")

        // 1. Сохраняем базовые настройки
        val interval = binding.intervalInput.text.toString().toIntOrNull() ?: 30
        var count = binding.countInput.text.toString().toIntOrNull() ?: 5
        val autoLaunch = binding.autoLaunchCheckbox.isChecked


        val selectedDeckIds = prefs.getStringSet("selected_deck_ids", setOf("1")) ?: setOf("1")


        // 3. V3.0: Проверка лимита вопросов
        val selectedIdsAsLong = selectedDeckIds.mapNotNull { it.toLongOrNull() }
        if (selectedIdsAsLong.isNotEmpty()) {
            // Запускаем асинхронный запрос к БД
            val (baseCount, invertedCount) = withContext(Dispatchers.IO) {
                val base = db.cardDao().getCardCountForDecks(selectedIdsAsLong)
                val inverted = db.cardDao().getInvertibleCardCountForDecks(selectedIdsAsLong)
                Pair(base, inverted) // Возвращаем пару значений
            }

            val maxQuestions = baseCount + invertedCount
            if (count > maxQuestions && maxQuestions > 0) {
                Log.w(DEBUG_TAG, "MainActivity: Лимит вопросов превышен. Запрошено: $count, Доступно: $maxQuestions. Устанавливаю максимум.")
                count = maxQuestions
                // Обновляем UI, так как мы в корутине
                withContext(Dispatchers.Main) {
                    binding.countInput.setText(count.toString())
                    Toast.makeText(this@MainActivity, "Лимит вопросов исправлен на $count (максимум для выбраных колод)", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (count > 0) {
            Log.w(DEBUG_TAG, "MainActivity: Колоды не выбраны, устанавливаю лимит 0")
            count = 0 // Нельзя задавать вопросы, если нет колод
            withContext(Dispatchers.Main) {
                binding.countInput.setText("0")
            }
        }

        val excludeCalls = binding.checkExcludeCalls.isChecked
        val excludeAlarms = binding.checkExcludeAlarms.isChecked
        val excludeMusic = binding.checkExcludeMusic.isChecked
        val breakEnabled = binding.checkBreakTime.isChecked
        val breakStart = binding.editBreakStart.text.toString()
        val breakEnd = binding.editBreakEnd.text.toString()

        // 4. Сохраняем финальные значения
        prefs.edit().apply {
            putInt("launch_interval_minutes", interval)
            putInt("count", count) // Сохраняем исправленное значение
            putBoolean("auto_launch_enabled", autoLaunch)

            // (Req 5.0)
            putBoolean("exclude_calls", excludeCalls)
            putBoolean("exclude_alarms", excludeAlarms)
            putBoolean("exclude_music", excludeMusic)
            putBoolean("break_enabled", breakEnabled)
            putString("break_start", breakStart)
            putString("break_end", breakEnd)

            apply()
        }

        Log.d(DEBUG_TAG, "Настройки сохранены (Interval: $interval, Count: $count, Auto: $autoLaunch)")
        Log.d(DEBUG_TAG, "Исключения (Calls: $excludeCalls, Alarms: $excludeAlarms, Music: $excludeMusic)")
        Log.d(DEBUG_TAG, "Перерыв (Enabled: $breakEnabled, $breakStart - $breakEnd)")
        Log.d(DEBUG_TAG, "Выбранные колоды: $selectedDeckIds")
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
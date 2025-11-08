package com.example.japaneselock

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.japaneselock.database.AppDatabase
import com.example.japaneselock.database.CardWithDeck
import com.example.japaneselock.databinding.ActivityLockScreenBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.app.admin.DevicePolicyManager
import android.content.ComponentName

class LockScreenActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var binding: ActivityLockScreenBinding
    private lateinit var db: AppDatabase

    private var currentQuestion = 0
    private var totalQuestions = 0
    private var currentCard: CardWithDeck? = null
    private var failedAttempts = 0
    private var isTimerActive = false
    private var usedCardIds = mutableSetOf<Long>()

    private val DEBUG_TAG = "DEBUG_LOCK"
    private lateinit var powerManager: PowerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(DEBUG_TAG, "***************************************************")
        Log.d(DEBUG_TAG, "LockScreenActivity: onCreate - ЭКРАН УСПЕШНО ЗАПУЩЕН")
        Log.d(DEBUG_TAG, "***************************************************")

        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        db = AppDatabase.getDatabase(this)
        prefs = getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)
        totalQuestions = prefs.getInt("count", 5)

        // --- Настройки окна (Анти-побег) ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) // Для скрытия шторки
        hideSystemUI()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        // ---

        setupLockScreen()
        generateQuestion()
    }

    private fun setupLockScreen() {
        binding.answerInput.requestFocus()

        binding.submitButton.setOnClickListener {
            if (!isTimerActive) {
                checkAnswer(binding.answerInput.text.toString().lowercase().trim())
            }
        }

        binding.answerInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (!isTimerActive) {
                    checkAnswer(binding.answerInput.text.toString().lowercase().trim())
                }
                true
            } else {
                false
            }
        }
    }

    private fun generateQuestion() {
        Log.d(DEBUG_TAG, "LockScreenActivity: generateQuestion (Вопрос ${currentQuestion + 1})")
        val selectedIds = prefs.getStringSet("selected_deck_ids", null)

        if (selectedIds.isNullOrEmpty()) {
            Log.e(DEBUG_TAG, "LockScreenActivity: Нет выбранных колод! Закрываюсь.")
            Toast.makeText(this, "Нет выбранных колод!", Toast.LENGTH_SHORT).show()
            finish() // Закрываем, если нечего показывать
            return
        }

        val selectedDeckIds = selectedIds.map { it.toLong() }

        lifecycleScope.launch(Dispatchers.IO) {
            // УДАЛЯЕМ while(attempt < 5)
            var card = db.cardDao().getRandomCardFromDecks(selectedDeckIds, usedCardIds)

            // Если не нашли (т.е. все уникальные карты кончились),
            // сбрасываем список и пробуем снова
            if (card == null && usedCardIds.isNotEmpty()) {
                Log.w(DEBUG_TAG, "LockScreenActivity: Все уникальные карты показаны. Сброс списка.")
                usedCardIds.clear()
                card = db.cardDao().getRandomCardFromDecks(selectedDeckIds, usedCardIds)
            }

            withContext(Dispatchers.Main) {
                if (card == null) {
                    // Это сработает, только если выбранные колоды АБСОЛЮТНО ПУСТЫ
                    Log.e(DEBUG_TAG, "LockScreenActivity: Не удалось найти карту (колоды пусты)")
                    unlockScreen() // Нечего показывать, разблокируем
                } else {
                    currentCard = card
                    usedCardIds.add(card.cardId)

                    binding.characterText.text = card.question
                    binding.progressText.text = "Вопрос ${currentQuestion + 1} из $totalQuestions"
                    binding.deckNameText.text = card.deckName // ПОКАЗЫВАЕМ НАЗВАНИЕ КОЛОДЫ
                    binding.answerInput.setText("")
                    binding.answerInput.requestFocus()
                }
            }
        }
    }

    private fun checkAnswer(answer: String) {
        val card = currentCard ?: return

        if (answer == "pass") {
            Log.d(DEBUG_TAG, "LockScreenActivity: Введен 'pass'")
            unlockScreen()
            return
        }

        if (answer == card.answer.lowercase().trim()) {
            currentQuestion++
            failedAttempts = 0

            if (currentQuestion >= totalQuestions) {
                Log.d(DEBUG_TAG, "LockScreenActivity: Все вопросы отвечены")
                unlockScreen()
            } else {
                Toast.makeText(this, "Правильно!", Toast.LENGTH_SHORT).show()
                generateQuestion()
            }
        } else {
            failedAttempts++
            Log.d(DEBUG_TAG, "LockScreenActivity: Неправильный ответ. Попытка $failedAttempts")

            if (failedAttempts >= 3) {
                lockDevice()
            } else {
                Toast.makeText(this, "Неправильно! Попыток осталось: ${3 - failedAttempts}", Toast.LENGTH_SHORT).show()
                startTimer()
            }
        }
    }

    private fun startTimer() {
        isTimerActive = true
        binding.answerInput.isEnabled = false
        binding.submitButton.isEnabled = false

        var secondsLeft = 10
        val timer = object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                secondsLeft = (millisUntilFinished / 1000).toInt()
                binding.timerText.text = "Ожидание: $secondsLeft сек"
            }

            override fun onFinish() {
                binding.timerText.text = ""
                binding.answerInput.isEnabled = true
                binding.submitButton.isEnabled = true
                binding.answerInput.requestFocus()
                isTimerActive = false
            }
        }
        timer.start()
    }

    private fun lockDevice() {
        Log.d(DEBUG_TAG, "LockScreenActivity: lockDevice (провал 3 попыток)")
        Toast.makeText(this, "Превышено количество попыток. До следующего раза!", Toast.LENGTH_LONG).show()

        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                Log.d(DEBUG_TAG, "LockScreenActivity: Вызываю dpm.lockNow()")
                dpm.lockNow()
            } else {
                Log.w(DEBUG_TAG, "LockScreenActivity: Не могу заблокировать, нет прав Device Admin.")
            }
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "LockScreenActivity: Ошибка при вызове lockNow(): ${e.message}")
        }

        // Планируем следующий запуск
        MainActivity.scheduleNextLaunch(this)
        finish()
    }

    private fun unlockScreen() {
        Log.d(DEBUG_TAG, "LockScreenActivity: unlockScreen (успех)")
        Toast.makeText(this, "Отлично! Разблокировано!", Toast.LENGTH_SHORT).show()
        // Планируем следующий запуск
        MainActivity.scheduleNextLaunch(this)
        finish()
    }

    override fun onPause() {
        super.onPause()
        Log.d(DEBUG_TAG, "LockScreenActivity: onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(DEBUG_TAG, "LockScreenActivity: onDestroy (Экран закрывается)")
    }

    // --- БЛОК АНТИ-ПОБЕГА (С ИСПРАВЛЕНИЕМ БАГА) ---

    override fun onBackPressed() {
        Log.d(DEBUG_TAG, "LockScreenActivity: Кнопка 'Назад' нажата и ЗАБЛОКИРОВАНА.")
    }

    override fun onStop() {
        super.onStop()
        Log.d(DEBUG_TAG, "LockScreenActivity: onStop")
        // Агрессивный перезапуск при сворачивании
        if (!isFinishing && powerManager.isInteractive) {
            Log.w(DEBUG_TAG, "LockScreenActivity: ПОПЫТКА ПОБЕГА! (onStop). Перезапускаюсь...")
            relaunch()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val isScreenOn = powerManager.isInteractive

        if (hasFocus) {
            Log.d(DEBUG_TAG, "LockScreenActivity: onWindowFocusChanged(true)")
            hideSystemUI()
        } else {
            Log.d(DEBUG_TAG, "LockScreenActivity: onWindowFocusChanged(false), isScreenOn: $isScreenOn")
            if (!isFinishing && isScreenOn) {
                Log.w(DEBUG_TAG, "LockScreenActivity: ПОПЫТКА ПОБЕГА! (Focus Lost + Screen On). Перезапускаюсь...")
                relaunch()
            } else if (!isFinishing && !isScreenOn) {
                Log.d(DEBUG_TAG, "LockScreenActivity: Фокус потерян, но экран выключается. Даем ему уснуть.")
            }
        }
    }

    private fun relaunch() {
        val intent = Intent(this, LockScreenActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
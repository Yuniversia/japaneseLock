package com.example.japaneselock

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.PowerManager
import android.util.Log
import android.view.View
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

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

    // --- V3.0: Новые поля состояния ---
    private var isCurrentCardStudy = false
    private var isCurrentCardInverted = false
    private var isCurrentCardReadingCheck = false
    // --- Конец V3.0 ---

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
                // V3.0: checkAnswer() теперь обрабатывает и "Изучил"
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

    // --- V3.1: ФУНКЦИЯ ОБНОВЛЕНА (ЛОГИКА UI И ИСПРАВЛЕНИЕ БАГА) ---
    private fun generateQuestion() {
        Log.d(DEBUG_TAG, "LockScreenActivity: generateQuestion (Вопрос ${currentQuestion + 1})")
        val selectedIds = prefs.getStringSet("selected_deck_ids", null)

        if (selectedIds.isNullOrEmpty() || totalQuestions == 0) {
            Log.e(DEBUG_TAG, "LockScreenActivity: Нет выбранных колод или totalQuestions = 0. Закрываюсь.")
            Toast.makeText(this, "Нет выбранных колод!", Toast.LENGTH_SHORT).show()
            unlockScreen()
            return
        }

        val selectedDeckIds = selectedIds.map { it.toLong() }

        lifecycleScope.launch(Dispatchers.IO) {
            var card = db.cardDao().getRandomCardFromDecks(selectedDeckIds, usedCardIds)

            if (card == null && usedCardIds.isNotEmpty()) {
                Log.w(DEBUG_TAG, "LockScreenActivity: Все уникальные карты показаны. Сброс списка.")
                usedCardIds.clear()
                card = db.cardDao().getRandomCardFromDecks(selectedDeckIds, usedCardIds)
            }

            withContext(Dispatchers.Main) {
                if (card == null) {
                    Log.e(DEBUG_TAG, "LockScreenActivity: Не удалось найти карту (колоды пусты)")
                    unlockScreen() // Нечего показывать, разблокируем
                } else {
                    currentCard = card
                    usedCardIds.add(card.cardId)

                    // V3.0: Логика определения типа карточки
                    isCurrentCardInverted = card.isInvertible && Random.nextBoolean()
                    isCurrentCardReadingCheck = card.isReadingCheck

                    // V3.1: Шанс 25% на "Изучение"
                    isCurrentCardStudy = (card.reading != null && card.reading.isNotBlank()) && Random.nextInt(100) < 25

                    binding.answerInput.setText("")
                    binding.answerInput.requestFocus()

                    if (isCurrentCardStudy) {
                        // --- V3.1: Режим "Изучение нового" (Новый UI) ---

                        // Показываем/скрываем контейнеры
                        binding.studyContainer.visibility = View.VISIBLE
                        binding.quizContainer.visibility = View.GONE
                        binding.answerInput.visibility = View.GONE

                        // Заполняем тексты (новыми View)
                        binding.studyCharacterText.text = card.question
                        binding.studyReadingText.text = card.reading ?: "---" // Показываем чтение
                        binding.studyAnswerText.text = card.answer // Показываем ответ

                        // Настраиваем кнопку
                        binding.submitButton.text = "Изучил"

                    } else {
                        // --- V3.1: Режим "Викторина" (Старый UI) ---

                        // Показываем/скрываем контейнеры
                        binding.studyContainer.visibility = View.GONE
                        binding.quizContainer.visibility = View.VISIBLE
                        binding.answerInput.visibility = View.VISIBLE

                        binding.submitButton.text = "Проверить"
                        binding.deckNameText.text = card.deckName

                        val progressString = "Вопрос ${currentQuestion + 1} из $totalQuestions"

                        if (isCurrentCardInverted) {
                            // Инвертировано (Feature 2.1)
                            binding.characterText.text = card.answer
                            // V3.1: ИСПРАВЛЕН БАГ С ПОДСКАЗКОЙ (убран ${card.question})
                            binding.progressText.text = "$progressString\n(Введите перевод/символ)"
                        } else {
                            // Нормальный режим
                            binding.characterText.text = card.question
                            if (isCurrentCardReadingCheck && card.reading != null) {
                                binding.progressText.text = "$progressString\n(Введите чтение)"
                            } else {
                                binding.progressText.text = "$progressString\n(Введите перевод)"
                            }
                        }
                    }
                }
            }
        }
    }

    // --- V3.0: ФУНКЦИЯ ОБНОВЛЕНА (ЛОГИКА ПРОВЕРКИ) ---
    private fun checkAnswer(answer: String) {
        val card = currentCard ?: return

        // 1. Если это была карточка "Изучил"
        if (isCurrentCardStudy) {
            isCurrentCardStudy = false // Сбрасываем
            currentQuestion++

            if (currentQuestion >= totalQuestions) {
                Log.d(DEBUG_TAG, "LockScreenActivity: Все вопросы (включая изучение) пройдены")
                unlockScreen()
            } else {
                generateQuestion() // Генерируем следующий (может быть снова "Изучил")
            }
            return
        }

        // 2. Проверка пароля "pass" (Feature 4)
        if (answer == "pass") {
            Log.d(DEBUG_TAG, "LockScreenActivity: Введен 'pass'")
            if (checkPassLimit()) {
                unlockScreen()
            } else {
                // Лимит превышен, считаем как ошибку
                Toast.makeText(this, "Пароль 'pass' использован 3/3 раза сегодня.", Toast.LENGTH_SHORT).show()
                handleFailedAttempt()
            }
            return
        }

        // 3. Логика викторины
        val expectedAnswer: String = if (isCurrentCardInverted) {
            card.question // Q: Answer, A: Question
        } else {
            if (isCurrentCardReadingCheck && card.reading != null) {
                card.reading // Q: Question, A: Reading
            } else {
                card.answer // Q: Question, A: Answer
            }
        }

        if (answer == expectedAnswer.lowercase().trim()) {
            // Правильный ответ
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
            // Неправильный ответ
            handleFailedAttempt()
        }
    }

    private fun handleFailedAttempt() {
        failedAttempts++
        Log.d(DEBUG_TAG, "LockScreenActivity: Неправильный ответ. Попытка $failedAttempts")

        if (failedAttempts >= 3) {
            lockDevice()
        } else {
            Toast.makeText(this, "Неправильно! Попыток осталось: ${3 - failedAttempts}", Toast.LENGTH_SHORT).show()
            startTimer()
        }
    }

    // --- V3.0: Новая функция лимита "pass" (Feature 4) ---
    private fun checkPassLimit(): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastUsedDay = prefs.getString("pass_last_used_day", "")
        var passCount = prefs.getInt("pass_count", 0)

        if (lastUsedDay != today) {
            // Новый день, сбрасываем счетчик
            passCount = 0
            prefs.edit().putString("pass_last_used_day", today).apply()
        }

        if (passCount < 3) {
            // Лимит не превышен
            passCount++
            prefs.edit().putInt("pass_count", passCount).apply()
            Log.d(DEBUG_TAG, "LockScreenActivity: 'pass' использован $passCount/3 раза сегодня.")
            return true
        } else {
            // Лимит превышен
            Log.w(DEBUG_TAG, "LockScreenActivity: 'pass' ПРОВЛЕН. Лимит (3) исчерпан.")
            return false
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
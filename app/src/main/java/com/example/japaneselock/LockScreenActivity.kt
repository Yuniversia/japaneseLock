package com.example.japaneselock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.PowerManager
import android.util.Log
import android.util.TypedValue
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
import com.example.japaneselock.database.Card
import com.example.japaneselock.database.CardType
import com.example.japaneselock.database.CardWithDeck
import com.example.japaneselock.databinding.ActivityLockScreenBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class LockScreenActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var binding: ActivityLockScreenBinding
    private lateinit var db: AppDatabase
    private lateinit var powerManager: PowerManager
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private var currentQuestionIndex = 0
    private var totalQuestions = 0
    private var currentCard: CardWithDeck? = null
    private var failedAttempts = 0
    private var isTimerActive = false
    private var usedCardIds = mutableSetOf<Long>()

    // (Req 1, 3) - Переменные для логики SRS
    private val SRS_LEVEL_REQUIRED_FOR_QUIZ = 3
    private val MAX_SRS_LEVEL = 10
    private val MIN_SRS_LEVEL = 0
    private var currentQuizType = QuizType.QUESTION_TO_ANSWER // Тип текущего вопроса
    private var isFinalLockOnStudy = false

    // (Req 6) - ID карточек для постепенного режима
    private var allowedCardIds = listOf<Long>()

    private val DEBUG_TAG = "DEBUG_LOCK"
    private var outOfCards = false

    // Определяем типы вопросов
    private enum class QuizType {
        STUDY_CARD, // Показ карточки (Req 1)
        QUESTION_TO_ANSWER, // Вопрос -> Ответ
        ANSWER_TO_QUESTION, // Ответ -> Вопрос
        QUESTION_TO_SOUND // Вопрос -> Звучание
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(DEBUG_TAG, "***************************************************")
        Log.d(DEBUG_TAG, "LockScreenActivity: onCreate - ЭКРАН УСПЕШНО ЗАПУЩЕН (v4.0)")
        Log.d(DEBUG_TAG, "***************************************************")

        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        db = AppDatabase.getDatabase(this)
        prefs = getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)
        totalQuestions = prefs.getInt("count", 5)

        setupWindow()
        setupListeners()

        // Запускаем процесс
        lifecycleScope.launch {
            // (Req 6) - Сначала определяем, какие карточки нам доступны
            allowedCardIds = loadAllowedCardIds()
            generateQuestion()
        }
    }

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        hideSystemUI()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
    }

    private fun setupListeners() {
        binding.answerInput.requestFocus()

        binding.submitButton.setOnClickListener {
            if (!isTimerActive) {
                checkAnswer(binding.answerInput.text.toString().trim())
            }
        }

        binding.answerInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (!isTimerActive) {
                    checkAnswer(binding.answerInput.text.toString().trim())
                }
                true
            } else false
        }

        // (Req 1) - Кнопка "Понятно" на карточке изучения
        binding.studyGotItButton.setOnClickListener {
            // (Баг 5.2) - Проверяем, был ли это показ после 3-й ошибки
            if (isFinalLockOnStudy) {
                lockDevice() // Сначала показали, теперь блокируем
                return@setOnClickListener
            }

            val card = currentCard ?: return@setOnClickListener

            val newSrsLevel = min(card.srsLevel + 1, SRS_LEVEL_REQUIRED_FOR_QUIZ)
            updateSrsLevel(card, QuizType.STUDY_CARD, true, newSrsLevel)

            // (Баг 5.1) - ФИКС: Увеличиваем счетчик
            currentQuestionIndex++

            if (currentQuestionIndex >= totalQuestions) {
                Log.d(DEBUG_TAG, "LockScreenActivity: Все вопросы пройдены")
                unlockScreen()
            } else {
                // Показываем следующий вопрос
                generateQuestion()
            }
        }
    }

    // (Req 6) - Загрузка ID карточек для постепенного режима
    private suspend fun loadAllowedCardIds(): List<Long> {
        val selectedIds = prefs.getStringSet("selected_deck_ids", null)?.map { it.toLong() } ?: emptyList()
        if (selectedIds.isEmpty()) return emptyList()

        // Берем настройки из ПЕРВОЙ колоды (предполагаем, что они одинаковые,
        // или что пользователь использует одну колоду для постепенного режима)
        val deck = db.cardDao().getDeckById(selectedIds.first())

        // Если "Полное изучение" включено (по умолч.) ИЛИ колода не найдена,
        // мы возвращаем "все" (пустой список ID, который DAO должен игнорировать)
        // ИСПРАВЛЕНИЕ: Мы не можем вернуть "все", т.к. запрос требует IN.
        // Мы должны вернуть ВСЕ ID из ВСЕХ колод.
        // ...
        // УПРОЩЕНИЕ: Если "Полное изучение" - используем запрос-заглушку.
        if (deck == null || deck.fullStudy) {
            return emptyList() // "emptyList" будет сигналом использовать getCardForQuiz-заглушку
        }

        // Логика "постепенного изучения" (Req 6)
        val progressiveLevel = prefs.getInt("progressive_level_${deck.id}", 0) // Уровень (0 = первые 5, 1 = вторые 5 и т.д.)
        val offset = progressiveLevel * deck.batchSize

        // Загружаем ID для всех "разблокированных" уровней
        val unlockedIds = mutableListOf<Long>()
        for (i in 0..progressiveLevel) {
            unlockedIds.addAll(
                db.cardDao().getProgressiveCardIds(deck.id, deck.batchSize, i * deck.batchSize)
            )
        }
        return unlockedIds
    }


    private fun generateQuestion() {
        Log.d(DEBUG_TAG, "LockScreenActivity: generateQuestion (Вопрос ${currentQuestionIndex + 1})")

        if (outOfCards) {
            Log.d(DEBUG_TAG, "LockScreenActivity: Карты закончились, сессия завершена.")
            unlockScreen("На этот сеанс больше нет уникальных карточек")
            return
        }

        val selectedIds = prefs.getStringSet("selected_deck_ids", null)
        if (selectedIds.isNullOrEmpty()) {
            Log.e(DEBUG_TAG, "LockScreenActivity: Нет выбранных колод! Закрываюсь.")
            unlockScreen()
            return
        }
        val selectedDeckIds = selectedIds.map { it.toLong() }

        lifecycleScope.launch(Dispatchers.IO) {
            // (Req 1, 3, 6) - Используем новый запрос
            var card: CardWithDeck?

            if (allowedCardIds.isEmpty()) {
                // Режим "Полного изучения" (Req 6)
                card = db.cardDao().getCardForQuiz(selectedDeckIds, usedCardIds)
            } else {
                // Режим "Постепенного изучения" (Req 6)
                card = db.cardDao().getCardForQuiz(selectedDeckIds, usedCardIds, allowedCardIds)
            }

            // Если не нашли (т.е. все уникальные карты кончились), сбрасываем список
            if (card == null && usedCardIds.isNotEmpty()) {
                Log.w(DEBUG_TAG, "LockScreenActivity: Все уникальные карты показаны. Сброс списка.")
                usedCardIds.clear()
                // Повторяем запрос
                card = if (allowedCardIds.isEmpty()) {
                    db.cardDao().getCardForQuiz(selectedDeckIds, usedCardIds)
                } else {
                    db.cardDao().getCardForQuiz(selectedDeckIds, usedCardIds, allowedCardIds)
                }
            }

            withContext(Dispatchers.Main) {
                if (card == null) {
                    // ... (логика, если card == null)
                    return@withContext
                }

                currentCard = card
                usedCardIds.add(card.cardId)

                binding.progressText.text = "Вопрос ${currentQuestionIndex + 1} из $totalQuestions"
                binding.deckNameText.text = card.deckName
                binding.answerInput.setText("")

                currentQuizType = decideQuizType(card)

                updateDebugInfo(card, currentQuizType)

                when (currentQuizType) {
                    QuizType.STUDY_CARD -> showStudyView(card)
                    else -> showQuizView(card, currentQuizType)
                }
            }
        }

    }

    private fun updateDebugInfo(card: CardWithDeck, quizType: QuizType) {
        // Собираем строку для отладки
        val srsInfo = """
            
            --- [SRS DEBUG INFO] ---
            ID: ${card.cardId}, Type: ${card.cardType}
            Q->A (srsMain): ${card.srsLevel}
            A->Q (srsInv): ${card.srsLevelInverted} (Invertible: ${card.invertAnswer})
            Q->S (srsSnd): ${card.srsLevelSound} (CheckSound: ${card.checkSound})
            Current Quiz: $quizType
            --------------------------
        """.trimIndent()

        // Выводим в Logcat (уровень Debug)
        Log.d(DEBUG_TAG, srsInfo)
    }

    // (Req 1) - Логика: показать карточку для изучения
    private fun showStudyView(card: CardWithDeck, isFinalLock: Boolean = false) {
        isFinalLockOnStudy = isFinalLock

        binding.quizContainer.visibility = View.GONE
        binding.studyContainer.visibility = View.VISIBLE

        binding.studyQuestion.text = card.question
        binding.studyAnswer.text = card.answer

        if (card.sound != null) {
            binding.studySound.text = "(${card.sound})"
            binding.studySound.visibility = View.VISIBLE
        } else {
            binding.studySound.visibility = View.GONE
        }

        // (Req 2) - Размер шрифта
        if (card.cardType == CardType.WORD || (card.cardType == CardType.READING && card.question.length > 5)) {
            binding.studyQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 60F)
        } else {
            binding.studyQuestion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 80F)
        }

        if (isFinalLock) {
            binding.studyGotItButton.text = "Понятно (Блокировка)"
        } else {
            binding.studyGotItButton.text = "Понятно"
        }
    }

    // (Req 1, 3) - Логика: показать вопрос для проверки
    private fun showQuizView(card: CardWithDeck, quizType: QuizType) {
        binding.studyContainer.visibility = View.GONE
        binding.quizContainer.visibility = View.VISIBLE
        binding.answerInput.requestFocus()

        var question = ""
        var title = ""
        var hint = ""

        when (quizType) {
            QuizType.QUESTION_TO_ANSWER -> {
                question = card.question
                title = "Напишите перевод/ответ:"
                hint = "Введите ответ"
            }
            QuizType.ANSWER_TO_QUESTION -> {
                question = card.answer
                title = "Напишите слово/символ:"
                hint = "Введите вопрос"
            }
            QuizType.QUESTION_TO_SOUND -> {
                question = card.question
                title = "Напишите звучание:"
                hint = "Введите звучание"
            }
            QuizType.STUDY_CARD -> {} // Уже обработано
        }

        binding.characterText.text = question
        binding.questionTitle.text = title
        binding.answerInput.hint = hint

        val isLong = (card.cardType == CardType.WORD || (card.cardType == CardType.READING && question.length > 5))
        if (isLong) {
            binding.characterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 80F)
        } else {
            binding.characterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 120F)
        }

    }

    // (Req 1, 3) - Логика выбора: учить или спрашивать
    // (Баг 6) - Логика выбора: учить или спрашивать
    private fun decideQuizType(card: CardWithDeck): QuizType {
        // (Баг 6) - "Чтение" никогда не бывает карточкой изучения
        if (card.cardType == CardType.READING) {
            // Просто выбираем, инвертировать или нет
            return if (card.invertAnswer && Random.nextBoolean()) {
                QuizType.ANSWER_TO_QUESTION
            } else {
                QuizType.QUESTION_TO_ANSWER
            }
        }

        // --- Старая логика (для Слогов, Кандзи, Слов) ---
        val options = mutableListOf<QuizType>()

        // 1. Проверяем SRS Level для (Вопрос -> Ответ)
        if (card.srsLevel < SRS_LEVEL_REQUIRED_FOR_QUIZ) {
            options.add(QuizType.STUDY_CARD) // Приоритет!
        } else {
            options.add(QuizType.QUESTION_TO_ANSWER)
        }

        // 2. Проверяем (Ответ -> Вопрос)
        if (card.invertAnswer) {
            if (card.srsLevelInverted < SRS_LEVEL_REQUIRED_FOR_QUIZ) {
                options.add(QuizType.STUDY_CARD) // Приоритет!
            } else {
                options.add(QuizType.ANSWER_TO_QUESTION)
            }
        }

        // 3. Проверяем (Вопрос -> Звучание)
        if (card.checkSound) {
            if (card.srsLevelSound < SRS_LEVEL_REQUIRED_FOR_QUIZ) {
                options.add(QuizType.STUDY_CARD) // Приоритет!
            } else {
                options.add(QuizType.QUESTION_TO_SOUND)
            }
        }

        if (options.contains(QuizType.STUDY_CARD)) {
            return QuizType.STUDY_CARD
        }

        return options.random()
    }

    // (Req 3) - Логика проверки ответа
    private fun checkAnswer(answer: String) {
        val card = currentCard ?: return

        if (answer.equals("pass", ignoreCase = true)) {
            Log.d(DEBUG_TAG, "LockScreenActivity: Введен 'pass'")
            unlockScreen()
            return
        }

        val correctAnswer = when (currentQuizType) {
            QuizType.QUESTION_TO_ANSWER -> card.answer
            QuizType.ANSWER_TO_QUESTION -> card.question
            QuizType.QUESTION_TO_SOUND -> card.sound ?: ""
            QuizType.STUDY_CARD -> "" // Не должно случиться
        }

        if (answer.equals(correctAnswer, ignoreCase = true)) {
            // ПРАВИЛЬНО
            failedAttempts = 0
            currentQuestionIndex++

            updateSrsLevel(card, currentQuizType, true)

            if (currentQuestionIndex >= totalQuestions) {
                Log.d(DEBUG_TAG, "LockScreenActivity: Все вопросы отвечены")
                unlockScreen()
            } else {
                Toast.makeText(this, "Правильно!", Toast.LENGTH_SHORT).show()
                generateQuestion()
            }
        } else {
            // НЕПРАВИЛЬНО (Баг 4 и 5)
            failedAttempts++
            Log.d(DEBUG_TAG, "LockScreenActivity: Неправильный ответ. Попытка $failedAttempts")

            // Определяем текущий уровень
            val (currentLevel, quizType) = when (currentQuizType) {
                QuizType.QUESTION_TO_ANSWER -> Pair(card.srsLevel, "Main")
                QuizType.ANSWER_TO_QUESTION -> Pair(card.srsLevelInverted, "Inverted")
                QuizType.QUESTION_TO_SOUND -> Pair(card.srsLevelSound, "Sound")
                else -> Pair(0, "Study")
            }

            // Рассчитываем изменение (Req 3)
            val srsChange = if (failedAttempts >= 2) -5 else -2 // Уменьшаем сильнее
            val newSrsLevel = max(MIN_SRS_LEVEL, currentLevel + srsChange)

            // Запускаем обновление в БД (в фоне)
            updateSrsLevel(card, currentQuizType, false)
            checkProgressiveUnlock(card.deckId) // (Req 6)

            // (Баг 5) - Логика 3-х ошибок
            if (failedAttempts >= 3) {
                Toast.makeText(this, "Превышено количество попыток. Запомните!", Toast.LENGTH_LONG).show()
                // Сначала показываем карточку
                showStudyView(card, true) // true = заблокировать после нажатия "Понятно"
            } else {
                // (Баг 4) - Логика "показать, если уровень низкий"
                Toast.makeText(this, "Неправильно! Попыток осталось: ${3 - failedAttempts}", Toast.LENGTH_SHORT).show()

                if (newSrsLevel < SRS_LEVEL_REQUIRED_FOR_QUIZ) {
                    Log.d(DEBUG_TAG, "LockScreenActivity: SRS level ($quizType) упал до $newSrsLevel. Показываю карточку.")
                    showStudyView(card) // Показываем карточку для изучения
                } else {
                    Log.d(DEBUG_TAG, "LockScreenActivity: SRS level ($quizType) $newSrsLevel. Карточка не показана.")
                    startTimer() // Просто таймер
                }
            }
        }
    }

    // (Req 6) - Проверка на "разблокировку" новой пачки
    private fun checkProgressiveUnlock(deckId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val deck = db.cardDao().getDeckById(deckId)
            if (deck == null || deck.fullStudy || allowedCardIds.isEmpty()) {
                return@launch // Выходим, мы не в постепенном режиме
            }

            // Проверяем, сколько карточек из "разрешенных" уже "изучены"
            val stats = db.cardDao().getDeckStats(deckId, SRS_LEVEL_REQUIRED_FOR_QUIZ)
            val totalAllowed = allowedCardIds.size
            val learnedAllowed = stats.learnedCards // (DAO считает только по изученным)

            val percentLearned = if (totalAllowed > 0) (learnedAllowed.toDouble() / totalAllowed.toDouble()) else 0.0

            // "Если пользователь отвечает уверенно на более половины"
            if (percentLearned > 0.5) {
                // Добавляем 5 (или batchSize) карточек
                val currentLevel = prefs.getInt("progressive_level_${deck.id}", 0)
                val nextLevel = currentLevel + 1
                prefs.edit().putInt("progressive_level_${deck.id}", nextLevel).apply()

                // Обновляем список разрешенных ID
                allowedCardIds = loadAllowedCardIds()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LockScreenActivity, "Отлично! Добавлено ${deck.batchSize} новых карточек!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // (Req 3) - Обновление индекса SRS
    private fun updateSrsLevel(card: CardWithDeck, type: QuizType, correct: Boolean, forceLevel: Int? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            var srs = 0
            var srsInv = card.srsLevelInverted
            var srsSnd = card.srsLevelSound
            var srsMain = card.srsLevel

            var totalCorrect = card.totalCorrect
            var totalIncorrect = card.totalIncorrect

            val change = if (correct) 1 else (if (failedAttempts >= 2) -5 else -2)

            val updateSrs = { level: Int ->
                if (forceLevel != null) forceLevel else {
                    max(MIN_SRS_LEVEL, min(MAX_SRS_LEVEL, level + change))
                }
            }

            when (type) {
                QuizType.STUDY_CARD -> {
                    srsInv = if (card.invertAnswer) updateSrs(srsInv) else srsInv
                    srsSnd = if (card.checkSound) updateSrs(srsSnd) else srsSnd
                    srsMain = updateSrs(srsMain)
                }
                QuizType.QUESTION_TO_ANSWER -> {
                    srsMain = updateSrs(srsMain)
                    if (correct) totalCorrect++ else totalIncorrect++
                }
                QuizType.ANSWER_TO_QUESTION -> {
                    srsInv = updateSrs(srsInv)
                    if (correct) totalCorrect++ else totalIncorrect++
                }
                QuizType.QUESTION_TO_SOUND -> {
                    srsSnd = updateSrs(srsSnd)
                    if (correct) totalCorrect++ else totalIncorrect++
                }
            }

            // --- ИСПРАВЛЕНИЕ (Оптимизация) ---
            // Вместо загрузки ВСЕХ карточек колоды, получаем одну по ID
            val updatedCard = db.cardDao().getCardById(card.cardId) ?: return@launch
            // ---

            db.cardDao().updateCard(updatedCard.copy(
                srsLevel = srsMain,
                srsLevelInverted = srsInv,
                srsLevelSound = srsSnd,
                totalCorrect = totalCorrect,
                totalIncorrect = totalIncorrect
            ))

            // (Req 4) - Обновляем общую статистику колоды
            val deck = db.cardDao().getDeckById(card.deckId) ?: return@launch
            db.cardDao().updateDeck(deck.copy(
                totalQuestionsAnswered = deck.totalQuestionsAnswered + 1,
                correctAnswers = if (correct) deck.correctAnswers + 1 else deck.correctAnswers
            ))
        }
    }

    // --- Остальная логика (без изменений) ---

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

        // --- (Баг 1) ИЗМЕНЕНИЕ ---
        // Устанавливаем флаг, чтобы при перезапуске сессия не началась заново
        outOfCards = true
        // --- КОНЕЦ ИЗМЕНЕНИЯ ---

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

    private fun unlockScreen(customMessage: String? = null) {
        Log.d(DEBUG_TAG, "LockScreenActivity: unlockScreen (успех)")

        val message = customMessage ?: "Отлично! Разблокировано!"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        // Планируем следующий запуск
        MainActivity.scheduleNextLaunch(this)
        finish()
    }

    override fun onBackPressed() {
        Log.d(DEBUG_TAG, "LockScreenActivity: Кнопка 'Назад' нажата и ЗАБЛОКИРОВАНА.")
    }

    override fun onStop() {
        super.onStop()
        Log.d(DEBUG_TAG, "LockScreenActivity: onStop")
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
                Log.d(DEBUG_TAG, "LockScreenActivity: Фокус потерян, но экран выключается.")
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

    override fun onPause() {
        super.onPause()
        Log.d(DEBUG_TAG, "LockScreenActivity: onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(DEBUG_TAG, "LockScreenActivity: onDestroy (Экран закрывается)")
    }
}
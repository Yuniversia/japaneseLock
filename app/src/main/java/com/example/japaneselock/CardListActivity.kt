package com.example.japaneselock

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.japaneselock.database.AppDatabase
import com.example.japaneselock.database.Card
import com.example.japaneselock.database.CardType
import com.example.japaneselock.databinding.ActivityCardListBinding
import com.example.japaneselock.databinding.DialogAddEditCardBinding // <-- ИЗМЕНЕН ИМПОРТ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CardListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCardListBinding
    private lateinit var db: AppDatabase
    private lateinit var cardAdapter: CardAdapter

    private var deckId: Long = -1L
    private var deckName: String = ""
    private val DEBUG_TAG = "DEBUG_LOCK"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = AppDatabase.getDatabase(this)

        deckId = intent.getLongExtra("DECK_ID", -1L)
        deckName = intent.getStringExtra("DECK_NAME") ?: "Колода"

        if (deckId == -1L) {
            Log.e(DEBUG_TAG, "CardListActivity: DECK_ID не был передан!")
            finish()
            return
        }

        Log.d(DEBUG_TAG, "CardListActivity: Открыта колода '$deckName' (ID: $deckId)")

        setupToolbar()
        setupRecyclerView()

        binding.fabAddCard.setOnClickListener {
            // (Req 2) - Показываем выбор типа карточки
            showCardTypeSelectionDialog()
        }

        loadCards()
    }

    private fun setupToolbar() {
        binding.toolbar.title = deckName
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        cardAdapter = CardAdapter(
            emptyList(),
            onEditClick = { card ->
                // (Req 2) - Логика редактирования
                showAddOrEditCardDialog(card)
            },
            onDeleteClick = { card ->
                showDeleteCardDialog(card)
            }
        )
        binding.cardRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.cardRecyclerView.adapter = cardAdapter
    }

    private fun loadCards() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cards = db.cardDao().getCardsForDeck(deckId)
                withContext(Dispatchers.Main) {
                    try {
                        cardAdapter.updateData(cards)
                        binding.emptyView.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
                        binding.cardRecyclerView.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE
                        Log.d(DEBUG_TAG, "CardListActivity: Загружено ${cards.size} карточек")
                    } catch (e: Exception) {
                        Log.e(DEBUG_TAG, "CardListActivity: Ошибка обновления UI", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "CardListActivity: Ошибка загрузки из БД", e)
            }
        }
    }

    private fun showDeleteCardDialog(card: Card) {
        AlertDialog.Builder(this)
            .setTitle("Удалить карточку?")
            .setMessage("Вопрос: ${card.question}\nОтвет: ${card.answer}")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.cardDao().deleteCard(card)
                    Log.d(DEBUG_TAG, "CardListActivity: Карточка ${card.id} удалена")
                    loadCards()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // --- (Req 2) НОВАЯ ЛОГИКА ДОБАВЛЕНИЯ КАРТОЧЕК ---

    private fun showCardTypeSelectionDialog() {
        val cardTypes = arrayOf("Слог", "Кандзи", "Слово", "Чтение")
        AlertDialog.Builder(this)
            .setTitle("Что вы хотите добавить?")
            .setItems(cardTypes) { _, which ->
                val cardType = when (which) {
                    0 -> CardType.SYLLABLE
                    1 -> CardType.KANJI
                    2 -> CardType.WORD
                    3 -> CardType.READING
                    else -> CardType.SYLLABLE
                }
                showAddOrEditCardDialog(null, cardType)
            }
            .show()
    }

    private fun showAddOrEditCardDialog(card: Card?, cardType: String = CardType.SYLLABLE) {
        val dialogBinding = DialogAddEditCardBinding.inflate(LayoutInflater.from(this))
        val isEditing = (card != null)
        val type = if (isEditing) card!!.cardType else cardType
        val title = if (isEditing) "Редактировать" else "Добавить"
        val SRS_LEVEL_REQUIRED_FOR_QUIZ = 3

        // Настраиваем UI в зависимости от типа
        when (type) {
            CardType.SYLLABLE -> {
                dialogBinding.layoutQuestion.hint = "Слог (напр. あ)"
                dialogBinding.layoutAnswer.hint = "Ответ (напр. a)"
            }
            CardType.KANJI -> {
                dialogBinding.layoutQuestion.hint = "Кандзи (напр. 日)"
                dialogBinding.layoutAnswer.hint = "Ответ (напр. Солнце)"
                dialogBinding.layoutSound.visibility = View.VISIBLE
                dialogBinding.checkboxContainer.visibility = View.VISIBLE
            }
            CardType.WORD -> {
                dialogBinding.layoutQuestion.hint = "Слово (напр. 日本)"
                dialogBinding.layoutAnswer.hint = "Ответ (напр. Япония)"
                dialogBinding.layoutSound.visibility = View.VISIBLE
                dialogBinding.layoutSound.hint = "Звучание (напр. にほん)"
                dialogBinding.checkboxContainer.visibility = View.VISIBLE
            }
            CardType.READING -> {
                dialogBinding.layoutQuestion.hint = "Текст (напр. 食べる)"
                dialogBinding.layoutAnswer.hint = "Чтение (напр. たべる)"
                dialogBinding.checkInvertReading.visibility = View.VISIBLE
            }
        }

        // Заполняем данными, если это редактирование
        if (isEditing) {
            dialogBinding.editQuestion.setText(card!!.question)
            dialogBinding.editAnswer.setText(card.answer)
            dialogBinding.editSound.setText(card.sound ?: "")
            dialogBinding.checkInvert.isChecked = card.invertAnswer
            dialogBinding.checkSound.isChecked = card.checkSound
            dialogBinding.checkInvertReading.isChecked = card.invertAnswer
        }

        AlertDialog.Builder(this)
            .setTitle("$title: ${type.lowercase().capitalize()}")
            .setView(dialogBinding.root)
            .setPositiveButton("Сохранить") { _, _ ->
                val question = dialogBinding.editQuestion.text.toString()
                val answer = dialogBinding.editAnswer.text.toString()
                val sound = dialogBinding.editSound.text.toString()

                if (question.isBlank() || answer.isBlank()) {
                    Toast.makeText(this, "Вопрос и Ответ не могут быть пустыми", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Проверка для Кандзи/Слов
                if ((type == CardType.KANJI || type == CardType.WORD) && sound.isBlank()) {
                    Toast.makeText(this, "Звучание обязательно для Кандзи и Слов", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    val startSrsLevel = if (!isEditing && type == CardType.READING) {
                        SRS_LEVEL_REQUIRED_FOR_QUIZ
                    } else if (isEditing) {
                        card!!.srsLevel
                    } else {
                        0 // 0 для новых Слогов, Кандзи, Слов
                    }

                    val startSrsInverted = if (!isEditing && type == CardType.READING) {
                        SRS_LEVEL_REQUIRED_FOR_QUIZ
                    } else if (isEditing) {
                        card!!.srsLevelInverted
                    } else {
                        0
                    }

                    val cardToSave = if (isEditing) {
                        card!!.copy(
                            question = question,
                            answer = answer,
                            cardType = type,
                            sound = if (sound.isBlank()) null else sound,
                            invertAnswer = if (type == CardType.READING) dialogBinding.checkInvertReading.isChecked else dialogBinding.checkInvert.isChecked,
                            checkSound = if (type == CardType.KANJI || type == CardType.WORD) dialogBinding.checkSound.isChecked else false,
                            srsLevel = startSrsLevel, // <-- Используем новые переменные
                            srsLevelInverted = startSrsInverted, // <-- Используем новые переменные
                            srsLevelSound = if (isEditing) card!!.srsLevelSound else 0
                        )
                    } else {
                        Card(
                            deckId = deckId,
                            question = question,
                            answer = answer,
                            cardType = type,
                            sound = if (sound.isBlank()) null else sound,
                            invertAnswer = if (type == CardType.READING) dialogBinding.checkInvertReading.isChecked else dialogBinding.checkInvert.isChecked,
                            checkSound = if (type == CardType.KANJI || type == CardType.WORD) dialogBinding.checkSound.isChecked else false,
                            srsLevel = 0,
                            srsLevelInverted = 0,
                            srsLevelSound = 0
                        )
                    }

                    if (isEditing) {
                        db.cardDao().updateCard(cardToSave)
                    } else {
                        db.cardDao().insertCard(cardToSave)
                    }
                    loadCards() // Обновляем список
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}

//lifecycleScope.launch(Dispatchers.IO) {
//
//    // (Баг 6) - Устанавливаем стартовый уровень
//    val startSrsLevel = if (!isEditing && type == CardType.READING) {
//        SRS_LEVEL_REQUIRED_FOR_QUIZ
//    } else if (isEditing) {
//        card!!.srsLevel
//    } else {
//        0 // 0 для новых Слогов, Кандзи, Слов
//    }
//
//    val startSrsInverted = if (!isEditing && type == CardType.READING) {
//        SRS_LEVEL_REQUIRED_FOR_QUIZ
//    } else if (isEditing) {
//        card!!.srsLevelInverted
//    } else {
//        0
//    }
//    // ---
//
//    val cardToSave = (if (isEditing) card!! else Card(deckId = deckId)).copy(
//        question = question,
//        answer = answer,
//        cardType = type,
//        sound = if (sound.isBlank()) null else sound,
//        invertAnswer = if (type == CardType.READING) dialogBinding.checkInvertReading.isChecked else dialogBinding.checkInvert.isChecked,
//        checkSound = if (type == CardType.KANJI || type == CardType.WORD) dialogBinding.checkSound.isChecked else false,
//
//        srsLevel = startSrsLevel, // <-- Используем новые переменные
//        srsLevelInverted = startSrsInverted, // <-- Используем новые переменные
//        srsLevelSound = if (isEditing) card!!.srsLevelSound else 0
//    )
//
//    if (isEditing) {
//        db.cardDao().updateCard(cardToSave)
//    } else {
//        db.cardDao().insertCard(cardToSave)
//    }
//    loadCards() // Обновляем список
//}
//}
//.setNegativeButton("Отмена", null)
//.show()
//}
//}
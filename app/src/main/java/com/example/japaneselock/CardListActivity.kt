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
import com.example.japaneselock.databinding.ActivityCardListBinding
import com.example.japaneselock.databinding.DialogEditCardBinding
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

        // Получаем ID и Название колоды из MainActivity
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
            showAddOrEditCardDialog(null)
        }

        loadCards()
    }

    private fun setupToolbar() {
        binding.toolbar.title = deckName
        binding.toolbar.setNavigationOnClickListener {
            finish() // Кнопка "Назад"
        }
    }

    private fun setupRecyclerView() {
        cardAdapter = CardAdapter(
            emptyList(),
            // Лямбда-функция для "Редактировать"
            onEditClick = { card ->
                showAddOrEditCardDialog(card)
            },
            // Лямбда-функция для "Удалить"
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
                Log.d(DEBUG_TAG, "CardListActivity: Загрузка карточек...")
                val cards = db.cardDao().getCardsForDeck(deckId)

                withContext(Dispatchers.Main) {
                    try {
                        cardAdapter.updateData(cards)

                        if (cards.isEmpty()) {
                            binding.emptyView.visibility = View.VISIBLE
                            binding.cardRecyclerView.visibility = View.GONE
                        } else {
                            binding.emptyView.visibility = View.GONE
                            binding.cardRecyclerView.visibility = View.VISIBLE
                        }
                        Log.d(DEBUG_TAG, "CardListActivity: Загружено ${cards.size} карточек")
                    } catch (e: Exception) {
                        Log.e(DEBUG_TAG, "CardListActivity: Ошибка обновления UI", e)
                        Toast.makeText(this@CardListActivity, "Ошибка отображения карточек", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "CardListActivity: Ошибка загрузки из БД", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CardListActivity, "Ошибка загрузки карточек", Toast.LENGTH_SHORT).show()
                }
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
                    loadCards() // Обновляем список
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // --- V3.0: ФУНКЦИЯ ОБНОВЛЕНА ---
    private fun showAddOrEditCardDialog(card: Card?) {
        // Используем ViewBinding для макета диалога
        val dialogBinding = DialogEditCardBinding.inflate(LayoutInflater.from(this))
        val isEditing = (card != null)
        val title = if (isEditing) "Редактировать карточку" else "Добавить карточку"

        if (isEditing) {
            dialogBinding.editQuestion.setText(card?.question)
            dialogBinding.editAnswer.setText(card?.answer)
            // V3.0: Загружаем новые поля
            dialogBinding.editReading.setText(card?.reading)
            dialogBinding.checkInvertible.isChecked = card?.isInvertible ?: false
            dialogBinding.checkReadingCheck.isChecked = card?.isReadingCheck ?: false
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton("Сохранить") { _, _ ->
                val question = dialogBinding.editQuestion.text.toString()
                val answer = dialogBinding.editAnswer.text.toString()
                // V3.0: Получаем новые поля
                val reading = dialogBinding.editReading.text.toString().takeIf { it.isNotBlank() } // null если пусто
                val isInvertible = dialogBinding.checkInvertible.isChecked
                val isReadingCheck = dialogBinding.checkReadingCheck.isChecked

                if (question.isBlank() || answer.isBlank()) {
                    Toast.makeText(this, "Поля 'Вопрос' и 'Ответ' не могут быть пустыми", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (isReadingCheck && reading.isNullOrBlank()) {
                    Toast.makeText(this, "Поле 'Чтение' должно быть заполнено, если включена 'Проверка чтения'", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    if (isEditing) {
                        // Обновляем существующую
                        val updatedCard = card!!.copy(
                            question = question,
                            answer = answer,
                            reading = reading,
                            isInvertible = isInvertible,
                            isReadingCheck = isReadingCheck
                        )
                        db.cardDao().updateCard(updatedCard)
                        Log.d(DEBUG_TAG, "CardListActivity: Карточка ${card.id} обновлена")
                    } else {
                        // Создаем новую
                        val newCard = Card(
                            deckId = deckId,
                            question = question,
                            answer = answer,
                            reading = reading,
                            isInvertible = isInvertible,
                            isReadingCheck = isReadingCheck
                        )
                        db.cardDao().insertCard(newCard)
                        Log.d(DEBUG_TAG, "CardListActivity: Новая карточка создана")
                    }
                    loadCards() // Обновляем список
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
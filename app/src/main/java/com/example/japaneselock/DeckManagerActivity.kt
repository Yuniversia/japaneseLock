package com.example.japaneselock

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.japaneselock.database.AppDatabase
import com.example.japaneselock.database.CardDao
import com.example.japaneselock.database.Deck
import com.example.japaneselock.databinding.ActivityDeckManagerBinding
import com.example.japaneselock.databinding.DialogDeckSettingsBinding
import com.example.japaneselock.databinding.ItemDeckBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

class DeckManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeckManagerBinding
    private lateinit var db: AppDatabase
    private lateinit var cardDao: CardDao
    private val DEBUG_TAG = "DEBUG_LOCK"
    private val SRS_LEVEL_LEARNED = 3 // Уровень, считаемый "изученным"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeckManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = AppDatabase.getDatabase(this)
        cardDao = db.cardDao()

        binding.toolbar.title = "Менеджер Колода"
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.fabAddDeck.setOnClickListener {
            showAddDeckDialog()
        }

        loadDecks()
    }

    private fun loadDecks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val decks = cardDao.getAllDecks()
            // (Req 4) - Загружаем статистику для каждой колоды
            val deckViews = decks.map { deck ->
                val stats = cardDao.getDeckStats(deck.id, SRS_LEVEL_LEARNED)
                val masteryStats = cardDao.getDeckMastery(deck.id)
                createDeckView(deck, stats, masteryStats)
            }

            withContext(Dispatchers.Main) {
                binding.decksContainer.removeAllViews()
                if (deckViews.isEmpty()) {
                    binding.decksContainer.addView(createTextView("Нет колод."))
                } else {
                    deckViews.forEach {
                        binding.decksContainer.addView(it)
                    }
                }
            }
        }
    }

    private fun createTextView(text: String): TextView {
        // ... (без изменений) ...
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@DeckManagerActivity, android.R.color.white))
        }
    }

    // (Req 4, 5) - Обновленная функция создания View
    private fun createDeckView(deck: Deck, stats: CardDao.DeckStats, masteryStats: CardDao.DeckMasteryStats): View {
        val itemBinding = ItemDeckBinding.inflate(LayoutInflater.from(this))

        itemBinding.deckName.text = deck.name

        // (Req 4) - Статистика изучения (по уровню SRS)
        val percentLearned = if (stats.totalCards > 0) {
            (stats.learnedCards.toDouble() / stats.totalCards.toDouble())
        } else 0.0
        val percentFormat = NumberFormat.getPercentInstance()
        itemBinding.deckStats.text = "Изучено: ${stats.learnedCards} из ${stats.totalCards} (${percentFormat.format(percentLearned)})"

        // (Req 4) - Статистика мастерства (по % правильных ответов)
        val totalMastery = masteryStats.totalCorrect.toDouble() + masteryStats.totalIncorrect.toDouble()
        val masteryPercent = if (totalMastery > 0) {
            (masteryStats.totalCorrect.toDouble() / totalMastery)
        } else 0.0

        if (totalMastery > 0) {
            itemBinding.deckMastery.text = percentFormat.format(masteryPercent)
            itemBinding.deckMastery.visibility = View.VISIBLE
        } else {
            itemBinding.deckMastery.visibility = View.GONE
        }

        // Клик для открытия списка карточек
        itemBinding.root.setOnClickListener {
            Log.d(DEBUG_TAG, "DeckManager: Нажата колода ${deck.name} (ID: ${deck.id})")
            val intent = Intent(this@DeckManagerActivity, CardListActivity::class.java).apply {
                putExtra("DECK_ID", deck.id)
                putExtra("DECK_NAME", deck.name)
            }
            startActivity(intent)
        }

        // (Req 5) - Долгий клик для открытия меню
        itemBinding.root.setOnLongClickListener {
            showDeckOptionsDialog(deck)
            true
        }
        return itemBinding.root
    }

    private fun showAddDeckDialog() {
        // ... (без изменений) ...
        val editText = EditText(this).apply { hint = "Название колоды" }
        AlertDialog.Builder(this)
            .setTitle("Создать новую колоду")
            .setView(editText)
            .setPositiveButton("Создать") { dialog, _ ->
                val name = editText.text.toString()
                if (name.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        cardDao.insertDeck(Deck(name = name))
                        loadDecks()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // (Req 5) - Новое меню опций
    private fun showDeckOptionsDialog(deck: Deck) {
        val options = arrayOf("Переименовать", "Настройки", "Удалить колоду")
        AlertDialog.Builder(this)
            .setTitle(deck.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDeckDialog(deck)
                    1 -> showDeckSettingsDialog(deck)
                    2 -> showDeleteDeckDialog(deck)
                }
            }
            .show()
    }

    private fun showRenameDeckDialog(deck: Deck) {
        val editText = EditText(this).apply { setText(deck.name) }
        AlertDialog.Builder(this)
            .setTitle("Переименовать колоду")
            .setView(editText)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = editText.text.toString()
                if (newName.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        cardDao.updateDeck(deck.copy(name = newName))
                        loadDecks()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // (Req 6) - Диалог настроек
    private fun showDeckSettingsDialog(deck: Deck) {
        val dialogBinding = DialogDeckSettingsBinding.inflate(LayoutInflater.from(this))

        // Заполняем текущими настройками
        dialogBinding.checkFullStudy.isChecked = deck.fullStudy
        dialogBinding.editBatchSize.setText(deck.batchSize.toString())
        dialogBinding.layoutBatchSize.isEnabled = !deck.fullStudy

        dialogBinding.checkFullStudy.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.layoutBatchSize.isEnabled = !isChecked
        }

        AlertDialog.Builder(this)
            .setTitle("Настройки колоды")
            .setView(dialogBinding.root)
            .setPositiveButton("Сохранить") { _, _ ->
                val fullStudy = dialogBinding.checkFullStudy.isChecked
                val batchSize = dialogBinding.editBatchSize.text.toString().toIntOrNull() ?: 5

                lifecycleScope.launch(Dispatchers.IO) {
                    cardDao.updateDeck(deck.copy(fullStudy = fullStudy, batchSize = batchSize))
                    loadDecks() // Перезагружаем (хотя здесь это не видно)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDeleteDeckDialog(deck: Deck) {
        // ... (без изменений, вызывается из showDeckOptionsDialog) ...
        AlertDialog.Builder(this)
            .setTitle("Удалить колоду?")
            .setMessage("Вы уверены, что хотите удалить колоду '${deck.name}'? Все карточки в ней будут удалены.")
            .setPositiveButton("Удалить") { dialog, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    cardDao.deleteDeck(deck)
                    loadDecks()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
package com.example.japaneselock

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast // ← ДОБАВЛЕНО
import android.widget.CheckBox // ← ИСПРАВЛЕНО (было androidReact.widget.CheckBox)
import android.widget.LinearLayout
import androidx.activity.result.ActivityResultLauncher
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
import android.content.Context
import android.content.SharedPreferences
import android.app.Activity
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.example.japaneselock.database.Card
import com.example.japaneselock.database.CardType
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader


class DeckManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeckManagerBinding
    private lateinit var db: AppDatabase
    private lateinit var cardDao: CardDao
    private val DEBUG_TAG = "DEBUG_LOCK"
    private val SRS_LEVEL_LEARNED = 3

    private lateinit var prefs: SharedPreferences
    private var selectedDeckIds = mutableSetOf<Long>()

    // Лаунчер для ИМПОРТА
    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                Log.d(DEBUG_TAG, "DeckManager: Выбран файл для импорта: $uri")
                importDeckFromJson(uri)
            }
        }
    }

    // Лаунчер для ЭКСПОРТА
    private var exportDeckId: Long = -1L
    private var exportSaveProgress: Boolean = false

    private val exportFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                Log.d(DEBUG_TAG, "DeckManager: Выбран файл для экспорта: $uri")
                exportDeckToJson(uri, exportDeckId, exportSaveProgress)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeckManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = AppDatabase.getDatabase(this)
        cardDao = db.cardDao()

        prefs = getSharedPreferences("JapaneseLockPrefs", Context.MODE_PRIVATE)
        loadSelectedDeckIds()

        binding.toolbar.title = "Менеджер Колода"
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.fabAddDeck.setOnClickListener {
            showAddDeckDialog()
        }

        loadDecks()
    }

    private fun loadSelectedDeckIds() {
        val defaultSelection = setOf("1")
        val savedIds = prefs.getStringSet("selected_deck_ids", defaultSelection) ?: defaultSelection
        selectedDeckIds = savedIds.mapNotNull { it.toLongOrNull() }.toMutableSet()
    }

    private fun loadDecks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val decks = cardDao.getAllDecks()
            val deckViews = decks.map { deck ->
                val stats = cardDao.getDeckStats(deck.id, SRS_LEVEL_LEARNED)
                val masteryStats = cardDao.getDeckMastery(deck.id)
                val isSelected = selectedDeckIds.contains(deck.id)
                createDeckView(deck, stats, masteryStats, isSelected)
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
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@DeckManagerActivity, android.R.color.white))
        }
    }

    private fun createDeckView(deck: Deck, stats: CardDao.DeckStats, masteryStats: CardDao.DeckMasteryStats, isSelected: Boolean): View {
        val itemBinding = ItemDeckBinding.inflate(LayoutInflater.from(this))

        itemBinding.deckName.text = deck.name

        itemBinding.deckCheckbox.isChecked = isSelected
        itemBinding.deckCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedDeckIds.add(deck.id)
            } else {
                selectedDeckIds.remove(deck.id)
            }
            prefs.edit().putStringSet("selected_deck_ids", selectedDeckIds.map { it.toString() }.toSet()).apply()
            Log.d(DEBUG_TAG, "DeckManager: Выбранные колоды: $selectedDeckIds")
        }

        val percentLearned = if (stats.totalCards > 0) {
            (stats.learnedCards.toDouble() / stats.totalCards.toDouble())
        } else 0.0
        val percentFormat = NumberFormat.getPercentInstance()
        itemBinding.deckStats.text = "Изучено: ${stats.learnedCards} из ${stats.totalCards} (${percentFormat.format(percentLearned)})"

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

        itemBinding.deckClickableArea.setOnClickListener {
            Log.d(DEBUG_TAG, "DeckManager: Нажата колода ${deck.name} (ID: ${deck.id})")
            val intent = Intent(this@DeckManagerActivity, CardListActivity::class.java).apply {
                putExtra("DECK_ID", deck.id)
                putExtra("DECK_NAME", deck.name)
            }
            startActivity(intent)
        }

        itemBinding.deckClickableArea.setOnLongClickListener {
            showDeckOptionsDialog(deck)
            true
        }
        return itemBinding.root
    }

    private fun showAddDeckDialog() {
        val options = arrayOf("Создать новую (вручную)", "Импортировать из JSON")

        AlertDialog.Builder(this)
            .setTitle("Добавить колоду")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreateDeckDialog()
                    1 -> launchImportFilePicker()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showCreateDeckDialog() {
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

    private fun showDeckOptionsDialog(deck: Deck) {
        val options = arrayOf("Переименовать", "Настройки", "Экспортировать (JSON)", "Удалить колоду")
        AlertDialog.Builder(this)
            .setTitle(deck.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDeckDialog(deck)
                    1 -> showDeckSettingsDialog(deck)
                    2 -> showExportOptionsDialog(deck)
                    3 -> showDeleteDeckDialog(deck)
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

    private fun showDeckSettingsDialog(deck: Deck) {
        val dialogBinding = DialogDeckSettingsBinding.inflate(LayoutInflater.from(this))

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
                    loadDecks()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDeleteDeckDialog(deck: Deck) {
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

    // --- ИМПОРТ ---

    private fun launchImportFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        try {
            importFileLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть выбор файла. Установите файловый менеджер.", Toast.LENGTH_LONG).show()
        }
    }

    private fun importDeckFromJson(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = readTextFromUri(uri)
                if (content.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@DeckManagerActivity, "Ошибка: Файл пуст", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val json = JSONObject(content)
                val deckName = json.optString("deckName", "Импорт ${System.currentTimeMillis() / 1000}")
                val cardsArray = json.optJSONArray("cards")

                val newDeck = Deck(name = deckName)
                val deckId = cardDao.insertDeck(newDeck)

                val globalSrs = json.optInt("srsLevel", -1)

                var importedCount = 0
                if (cardsArray != null) {
                    for (i in 0 until cardsArray.length()) {
                        val cardJson = cardsArray.getJSONObject(i)

                        val type = cardJson.optString("type", CardType.SYLLABLE)
                        val question = cardJson.optString("show")

                        // (ИСПРАВЛЕНИЕ V5.1) - Читаем "answer" (может быть String или Array)
                        val answerOpt = cardJson.opt("answer")
                        val answer: String
                        if (answerOpt is JSONArray) {
                            // Если это массив ["a", "b"]
                            val answers = mutableListOf<String>()
                            for (j in 0 until answerOpt.length()) {
                                answers.add(answerOpt.getString(j))
                            }
                            answer = answers.joinToString("/")
                        } else {
                            // Если это просто строка "a"
                            answer = answerOpt.toString()
                        }
                        // --- КОНЕЦ ИСПРАВЛЕНИЯ V5.1 ---

                        // Если нет "show" или "answer", пропускаем карточку
                        if (question.isBlank() || answer.isBlank()) {
                            continue
                        }

                        val sound = cardJson.optString("listening", null)
                        val reverse = cardJson.optBoolean("reverse", false)
                        val checkSound = cardJson.optBoolean("check_listening", false)

                        val srs = cardJson.optInt("srsLevel", if (globalSrs != -1) globalSrs else 0)
                        val srsInv = cardJson.optInt("srsLevelInverted", if (globalSrs != -1) globalSrs else 0)
                        val srsSound = cardJson.optInt("srsLevelSound", if (globalSrs != -1) globalSrs else 0)

                        val card = Card(
                            deckId = deckId,
                            question = question,
                            answer = answer,
                            cardType = type,
                            sound = if (type == CardType.KANJI || type == CardType.WORD) sound else null,
                            invertAnswer = if (type == CardType.KANJI || type == CardType.WORD) reverse else false,
                            checkSound = if (type == CardType.KANJI || type == CardType.WORD) checkSound else false,
                            srsLevel = srs,
                            srsLevelInverted = srsInv,
                            srsLevelSound = srsSound
                        )
                        cardDao.insertCard(card)
                        importedCount++
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DeckManagerActivity, "Успешно! Импортировано $importedCount карточек в '$deckName'", Toast.LENGTH_LONG).show()
                    loadDecks()
                }

            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "DeckManager: Ошибка импорта JSON!", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DeckManagerActivity, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun readTextFromUri(uri: Uri): String {
        val stringBuilder = StringBuilder()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    stringBuilder.append(line)
                    line = reader.readLine()
                }
            }
        }
        return stringBuilder.toString()
    }

    // --- ЭКСПОРТ ---

    // --- (Req 5.0) ЛОГИКА ЭКСПОРТА ---

    private fun showExportOptionsDialog(deck: Deck) {
        // (ИСПРАВЛЕНИЕ) - Создаем полный layout, т.к. .setMessage и .setView
        // не работают вместе в стандартном AlertDialog.Builder

        // 1. Создаем TextView для сообщения
        val messageView = TextView(this).apply {
            text = "Сохранить колоду как JSON файл?"
            textSize = 16f // (Опционально)
            setPadding(0, 24, 0, 24)
        }

        // 2. Создаем CheckBox
        val checkBox = CheckBox(this).apply {
            text = "Сохранить прогресс (SRS)"
            isChecked = false
        }

        // 3. Создаем layout и добавляем в него оба View
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0) // Стандартные отступы диалога
            addView(messageView) // Сначала сообщение
            addView(checkBox)    // Потом чекбокс
        }

        // 4. (ИСПРАВЛЕНИЕ) - Используем androidx.appcompat.app.AlertDialog
        // и УБИРАЕМ .setMessage()
        AlertDialog.Builder(this)
            .setTitle("Экспорт: ${deck.name}")
            // .setMessage("Сохранить колоду как JSON файл?") // <-- Эта строка УДАЛЕНА
            .setView(layout) // layout теперь содержит и сообщение, и чекбокс
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Экспорт") { _, _ ->
                val saveProgress = checkBox.isChecked
                launchExportFilePicker(deck, saveProgress)
            }
            .show()
    }

    private fun launchExportFilePicker(deck: Deck, saveProgress: Boolean) {
        this.exportDeckId = deck.id
        this.exportSaveProgress = saveProgress

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "${deck.name}.json")
        }
        try {
            exportFileLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть выбор файла. Установите файловый менеджер.", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportDeckToJson(uri: Uri, deckId: Long, saveProgress: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val deck = cardDao.getDeckById(deckId) ?: return@launch
                val cards = cardDao.getCardsForDeck(deckId)

                val root = JSONObject()
                root.put("deckName", deck.name)

                val cardsArray = JSONArray()
                cards.forEach { card ->
                    val cardJson = JSONObject()
                    cardJson.put("type", card.cardType)
                    cardJson.put("show", card.question)

                    // (ИСПРАВЛЕНИЕ V5.1) - Сохраняем "answer" как Array, если есть '/'
                    val answerString = card.answer
                    if (answerString.contains("/")) {
                        val answerArray = JSONArray(answerString.split("/").map { it.trim() })
                        cardJson.put("answer", answerArray)
                    } else {
                        cardJson.put("answer", answerString)
                    }
                    // --- КОНЕЦ ИСПРАВЛЕНИЯ V5.1 ---

                    // Добавляем только нужные поля
                    if (card.cardType == CardType.KANJI || card.cardType == CardType.WORD) {
                        cardJson.put("listening", card.sound ?: JSONObject.NULL)
                        cardJson.put("reverse", card.invertAnswer)
                        cardJson.put("check_listening", card.checkSound)
                    }

                    if (saveProgress) {
                        cardJson.put("srsLevel", card.srsLevel)
                        cardJson.put("srsLevelInverted", card.srsLevelInverted)
                        cardJson.put("srsLevelSound", card.srsLevelSound)
                    }
                    cardsArray.put(cardJson)
                }
                root.put("cards", cardsArray)

                val jsonString = root.toString(4)
                contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use {
                        it.write(jsonString.toByteArray())
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DeckManagerActivity, "Колода '${deck.name}' успешно экспортирована!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "DeckManager: Ошибка экспорта JSON!", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DeckManagerActivity, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
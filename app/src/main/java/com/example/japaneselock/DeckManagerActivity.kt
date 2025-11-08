package com.example.japaneselock

import android.os.Bundle
import android.util.Log
import android.content.Intent
import android.widget.Button
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
import com.example.japaneselock.database.Deck
import com.example.japaneselock.databinding.ActivityDeckManagerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// (Вам нужно будет создать layout 'activity_deck_manager.xml')
class DeckManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeckManagerBinding
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeckManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = AppDatabase.getDatabase(this)

        binding.toolbar.title = "Менеджер Колода"
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.fabAddDeck.setOnClickListener {
            showAddDeckDialog()
        }

        loadDecks()
    }

    private fun loadDecks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val decks = db.cardDao().getAllDecks()
            withContext(Dispatchers.Main) {
                binding.decksContainer.removeAllViews()
                if (decks.isEmpty()) {
                    binding.decksContainer.addView(createTextView("Нет колод."))
                } else {
                    decks.forEach { deck ->
                        binding.decksContainer.addView(createDeckView(deck))
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

    private fun createDeckView(deck: Deck): View {
        // (Здесь должен быть сложный layout 'deck_item.xml' с RecyclerView,
        // но для простоты я сделаю простой TextView)
        val tv = TextView(this)
        tv.text = deck.name
        tv.textSize = 20f
        tv.setPadding(16, 32, 16, 32) // Увеличил отступ
        tv.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        tv.setBackgroundResource(android.R.drawable.list_selector_background) // Эффект нажатия

        // --- ИЗМЕНЕННЫЙ ОБРАБОТЧИК НАЖАТИЯ ---
        tv.setOnClickListener {
            Log.d("DEBUG_LOCK", "DeckManager: Нажата колода ${deck.name} (ID: ${deck.id})")
            // Открываем CardListActivity
            val intent = Intent(this@DeckManagerActivity, CardListActivity::class.java).apply {
                putExtra("DECK_ID", deck.id)
                putExtra("DECK_NAME", deck.name)
            }
            startActivity(intent)
        }
        // --- КОНЕЦ ИЗМЕНЕНИЙ ---

        tv.setOnLongClickListener {
            showDeleteDeckDialog(deck)
            true
        }
        return tv
    }

    private fun showAddDeckDialog() {
        val editText = EditText(this).apply { hint = "Название колоды" }
        AlertDialog.Builder(this)
            .setTitle("Создать новую колоду")
            .setView(editText)
            .setPositiveButton("Создать") { dialog, _ ->
                val name = editText.text.toString()
                if (name.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.cardDao().insertDeck(Deck(name = name))
                        loadDecks() // Перезагружаем список
                    }
                }
                dialog.dismiss()
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
                    db.cardDao().deleteDeck(deck)
                    loadDecks() // Перезагружаем список
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
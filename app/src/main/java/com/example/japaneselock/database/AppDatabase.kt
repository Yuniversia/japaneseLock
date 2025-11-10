package com.example.japaneselock.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [Deck::class, Card::class], version = 3) // <-- ВЕРСИЯ 3
abstract class AppDatabase : RoomDatabase() {

    abstract fun cardDao(): CardDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "japanese_lock_database"
                )
                    .addCallback(DatabaseCallback(context))
                    .fallbackToDestructiveMigration() // <-- ДОБАВЛЕНО (Стирает данные при обновлении схемы)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class DatabaseCallback(private val context: Context) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    populateDatabase(database.cardDao())
                }
            }
        }

        suspend fun populateDatabase(cardDao: CardDao) {
            // --- СОЗДАЕМ ХИРАГАНУ ---
            val defaultSrsLevel = 0

            var deckId = cardDao.insertDeck(Deck(name = "Hiragana", fullStudy = false))
            cardDao.insertCard(Card(deckId = deckId, question = "あ", answer = "a", cardType = CardType.SYLLABLE, srsLevel = defaultSrsLevel))
            cardDao.insertCard(Card(deckId = deckId, question = "い", answer = "i", cardType = CardType.SYLLABLE, srsLevel = defaultSrsLevel))
            cardDao.insertCard(Card(deckId = deckId, question = "う", answer = "u", cardType = CardType.SYLLABLE, srsLevel = defaultSrsLevel))
            // ... (и так далее для всех остальных)
            cardDao.insertCard(Card(deckId = deckId, question = "ん", answer = "n", cardType = CardType.SYLLABLE, srsLevel = defaultSrsLevel))

            // --- СОЗДАЕМ КАТАКАНУ ---
            deckId = cardDao.insertDeck(Deck(name = "Katakana"))
            cardDao.insertCard(Card(deckId = deckId, question = "ア", answer = "a", cardType = CardType.SYLLABLE, srsLevel = defaultSrsLevel))
            cardDao.insertCard(Card(deckId = deckId, question = "イ", answer = "i", cardType = CardType.SYLLABLE, srsLevel = defaultSrsLevel))
            cardDao.insertCard(Card(deckId = deckId, question = "ウ", answer = "u", cardType = CardType.SYLLABLE, srsLevel = defaultSrsLevel))
            // ... (и так далее)
            cardDao.insertCard(Card(deckId = deckId, question = "ン", answer = "n", cardType = CardType.SYLLABLE, srsLevel = defaultSrsLevel))
        }
    }
}
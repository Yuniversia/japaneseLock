package com.example.japaneselock.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// --- V3.0: Версия обновлена до 2 ---
@Database(entities = [Deck::class, Card::class], version = 2)
abstract class AppDatabase : RoomDatabase() {

    abstract fun cardDao(): CardDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // --- V3.0: Миграция с 1 на 2 ---
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Добавляем новые столбцы в таблицу 'cards'
                db.execSQL("ALTER TABLE cards ADD COLUMN reading TEXT")
                db.execSQL("ALTER TABLE cards ADD COLUMN isInvertible INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cards ADD COLUMN isReadingCheck INTEGER NOT NULL DEFAULT 0")
            }
        }
        // --- Конец V3.0 ---

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "japanese_lock_database"
                )
                    .addCallback(DatabaseCallback(context)) // Добавляем стартовые данные
                    // --- V3.0: Добавляем миграцию ---
                    .addMigrations(MIGRATION_1_2)
                    // --- Конец V3.0 ---
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    // Этот колбэк заполнит базу данных (Хирагана, Катакана) при первом запуске
    // V3.0: Он НЕ изменился, т.к. новые поля имеют значения по умолчанию
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
            var deckId = cardDao.insertDeck(Deck(name = "Hiragana"))
            // a, i, u, e, o
            cardDao.insertCard(Card(deckId = deckId, question = "あ", answer = "a", reading = "a", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "い", answer = "i", reading = "i", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "う", answer = "u", reading = "u", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "え", answer = "e", reading = "e", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "お", answer = "o", reading = "o", isInvertible = true, isReadingCheck = true))
            // ka, ki, ku, ke, ko
            cardDao.insertCard(Card(deckId = deckId, question = "か", answer = "ka", reading = "ka", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "き", answer = "ki", reading = "ki", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "く", answer = "ku", reading = "ku", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "け", answer = "ke", reading = "ke", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "こ", answer = "ko", reading = "ko", isInvertible = true, isReadingCheck = true))
            // sa, shi, su, se, so
            cardDao.insertCard(Card(deckId = deckId, question = "さ", answer = "sa", reading = "sa", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "し", answer = "shi", reading = "shi", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "す", answer = "su", reading = "su", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "せ", answer = "se", reading = "se", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "そ", answer = "so", reading = "so", isInvertible = true, isReadingCheck = true))
            // ... (Остальные данные Hiragana)

            // --- СОЗДАЕМ КАТАКАНУ ---
            deckId = cardDao.insertDeck(Deck(name = "Katakana"))
            // a, i, u, e, o
            cardDao.insertCard(Card(deckId = deckId, question = "ア", answer = "a", reading = "a", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "イ", answer = "i", reading = "i", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "ウ", answer = "u", reading = "u", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "エ", answer = "e", reading = "e", isInvertible = true, isReadingCheck = true))
            cardDao.insertCard(Card(deckId = deckId, question = "オ", answer = "o", reading = "o", isInvertible = true, isReadingCheck = true))
            // ... (Остальные данные Katakana)
        }
    }
}
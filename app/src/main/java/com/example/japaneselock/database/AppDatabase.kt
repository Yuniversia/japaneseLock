package com.example.japaneselock.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [Deck::class, Card::class], version = 1)
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
                    .addCallback(DatabaseCallback(context)) // Добавляем стартовые данные
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    // Этот колбэк заполнит базу данных (Хирагана, Катакана) при первом запуске
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
            cardDao.insertCard(Card(deckId = deckId, question = "あ", answer = "a"))
            cardDao.insertCard(Card(deckId = deckId, question = "い", answer = "i"))
            cardDao.insertCard(Card(deckId = deckId, question = "う", answer = "u"))
            cardDao.insertCard(Card(deckId = deckId, question = "え", answer = "e"))
            cardDao.insertCard(Card(deckId = deckId, question = "お", answer = "o"))
// ka, ki, ku, ke, ko
            cardDao.insertCard(Card(deckId = deckId, question = "か", answer = "ka"))
            cardDao.insertCard(Card(deckId = deckId, question = "き", answer = "ki"))
            cardDao.insertCard(Card(deckId = deckId, question = "く", answer = "ku"))
            cardDao.insertCard(Card(deckId = deckId, question = "け", answer = "ke"))
            cardDao.insertCard(Card(deckId = deckId, question = "こ", answer = "ko"))
// sa, shi, su, se, so
            cardDao.insertCard(Card(deckId = deckId, question = "さ", answer = "sa"))
            cardDao.insertCard(Card(deckId = deckId, question = "し", answer = "shi"))
            cardDao.insertCard(Card(deckId = deckId, question = "す", answer = "su"))
            cardDao.insertCard(Card(deckId = deckId, question = "せ", answer = "se"))
            cardDao.insertCard(Card(deckId = deckId, question = "そ", answer = "so"))
// ta, chi, tsu, te, to
            cardDao.insertCard(Card(deckId = deckId, question = "た", answer = "ta"))
            cardDao.insertCard(Card(deckId = deckId, question = "ち", answer = "chi"))
            cardDao.insertCard(Card(deckId = deckId, question = "つ", answer = "tsu"))
            cardDao.insertCard(Card(deckId = deckId, question = "て", answer = "te"))
            cardDao.insertCard(Card(deckId = deckId, question = "と", answer = "to"))
// na, ni, nu, ne, no
            cardDao.insertCard(Card(deckId = deckId, question = "な", answer = "na"))
            cardDao.insertCard(Card(deckId = deckId, question = "に", answer = "ni"))
            cardDao.insertCard(Card(deckId = deckId, question = "ぬ", answer = "nu"))
            cardDao.insertCard(Card(deckId = deckId, question = "ね", answer = "ne"))
            cardDao.insertCard(Card(deckId = deckId, question = "の", answer = "no"))
// ha, hi, fu, he, ho
            cardDao.insertCard(Card(deckId = deckId, question = "は", answer = "ha"))
            cardDao.insertCard(Card(deckId = deckId, question = "ひ", answer = "hi"))
            cardDao.insertCard(Card(deckId = deckId, question = "ふ", answer = "fu"))
            cardDao.insertCard(Card(deckId = deckId, question = "へ", answer = "he"))
            cardDao.insertCard(Card(deckId = deckId, question = "ほ", answer = "ho"))
// ma, mi, mu, me, mo
            cardDao.insertCard(Card(deckId = deckId, question = "ま", answer = "ma"))
            cardDao.insertCard(Card(deckId = deckId, question = "み", answer = "mi"))
            cardDao.insertCard(Card(deckId = deckId, question = "む", answer = "mu"))
            cardDao.insertCard(Card(deckId = deckId, question = "め", answer = "me"))
            cardDao.insertCard(Card(deckId = deckId, question = "も", answer = "mo"))
// ya, (i), yu, (e), yo
            cardDao.insertCard(Card(deckId = deckId, question = "や", answer = "ya"))
            cardDao.insertCard(Card(deckId = deckId, question = "ゆ", answer = "yu"))
            cardDao.insertCard(Card(deckId = deckId, question = "よ", answer = "yo"))
// ra, ri, ru, re, ro
            cardDao.insertCard(Card(deckId = deckId, question = "ら", answer = "ra"))
            cardDao.insertCard(Card(deckId = deckId, question = "り", answer = "ri"))
            cardDao.insertCard(Card(deckId = deckId, question = "る", answer = "ru"))
            cardDao.insertCard(Card(deckId = deckId, question = "れ", answer = "re"))
            cardDao.insertCard(Card(deckId = deckId, question = "ろ", answer = "ro"))
// wa, (i), (u), (e), wo
            cardDao.insertCard(Card(deckId = deckId, question = "わ", answer = "wa"))
            cardDao.insertCard(Card(deckId = deckId, question = "を", answer = "wo"))
// n
            cardDao.insertCard(Card(deckId = deckId, question = "ん", answer = "n"))

            // --- СОЗДАЕМ КАТАКАНУ ---
            deckId = cardDao.insertDeck(Deck(name = "Katakana"))
            // a, i, u, e, o
            cardDao.insertCard(Card(deckId = deckId, question = "ア", answer = "a"))
            cardDao.insertCard(Card(deckId = deckId, question = "イ", answer = "i"))
            cardDao.insertCard(Card(deckId = deckId, question = "ウ", answer = "u"))
            cardDao.insertCard(Card(deckId = deckId, question = "エ", answer = "e"))
            cardDao.insertCard(Card(deckId = deckId, question = "オ", answer = "o"))
        // ka, ki, ku, ke, ko
            cardDao.insertCard(Card(deckId = deckId, question = "カ", answer = "ka"))
            cardDao.insertCard(Card(deckId = deckId, question = "キ", answer = "ki"))
            cardDao.insertCard(Card(deckId = deckId, question = "ク", answer = "ku"))
            cardDao.insertCard(Card(deckId = deckId, question = "ケ", answer = "ke"))
            cardDao.insertCard(Card(deckId = deckId, question = "コ", answer = "ko"))
        // sa, shi, su, se, so
            cardDao.insertCard(Card(deckId = deckId, question = "サ", answer = "sa"))
            cardDao.insertCard(Card(deckId = deckId, question = "シ", answer = "shi"))
            cardDao.insertCard(Card(deckId = deckId, question = "ス", answer = "su"))
            cardDao.insertCard(Card(deckId = deckId, question = "セ", answer = "se"))
            cardDao.insertCard(Card(deckId = deckId, question = "ソ", answer = "so"))
        // ta, chi, tsu, te, to
            cardDao.insertCard(Card(deckId = deckId, question = "タ", answer = "ta"))
            cardDao.insertCard(Card(deckId = deckId, question = "チ", answer = "chi"))
            cardDao.insertCard(Card(deckId = deckId, question = "ツ", answer = "tsu"))
            cardDao.insertCard(Card(deckId = deckId, question = "テ", answer = "te"))
            cardDao.insertCard(Card(deckId = deckId, question = "ト", answer = "to"))
        // na, ni, nu, ne, no
            cardDao.insertCard(Card(deckId = deckId, question = "ナ", answer = "na"))
            cardDao.insertCard(Card(deckId = deckId, question = "ニ", answer = "ni"))
            cardDao.insertCard(Card(deckId = deckId, question = "ヌ", answer = "nu"))
            cardDao.insertCard(Card(deckId = deckId, question = "ネ", answer = "ne"))
            cardDao.insertCard(Card(deckId = deckId, question = "ノ", answer = "no"))
        // ha, hi, fu, he, ho
            cardDao.insertCard(Card(deckId = deckId, question = "ハ", answer = "ha"))
            cardDao.insertCard(Card(deckId = deckId, question = "ヒ", answer = "hi"))
            cardDao.insertCard(Card(deckId = deckId, question = "フ", answer = "fu"))
            cardDao.insertCard(Card(deckId = deckId, question = "ヘ", answer = "he"))
            cardDao.insertCard(Card(deckId = deckId, question = "ホ", answer = "ho"))
        // ma, mi, mu, me, mo
            cardDao.insertCard(Card(deckId = deckId, question = "マ", answer = "ma"))
            cardDao.insertCard(Card(deckId = deckId, question = "ミ", answer = "mi"))
            cardDao.insertCard(Card(deckId = deckId, question = "ム", answer = "mu"))
            cardDao.insertCard(Card(deckId = deckId, question = "メ", answer = "me"))
            cardDao.insertCard(Card(deckId = deckId, question = "モ", answer = "mo"))
        // ya, (i), yu, (e), yo
            cardDao.insertCard(Card(deckId = deckId, question = "ヤ", answer = "ya"))
            cardDao.insertCard(Card(deckId = deckId, question = "ユ", answer = "yu"))
            cardDao.insertCard(Card(deckId = deckId, question = "ヨ", answer = "yo"))
        // ra, ri, ru, re, ro
            cardDao.insertCard(Card(deckId = deckId, question = "ラ", answer = "ra"))
            cardDao.insertCard(Card(deckId = deckId, question = "リ", answer = "ri"))
            cardDao.insertCard(Card(deckId = deckId, question = "ル", answer = "ru"))
            cardDao.insertCard(Card(deckId = deckId, question = "レ", answer = "re"))
            cardDao.insertCard(Card(deckId = deckId, question = "ロ", answer = "ro"))
        // wa, (i), (u), (e), wo
            cardDao.insertCard(Card(deckId = deckId, question = "ワ", answer = "wa"))
            cardDao.insertCard(Card(deckId = deckId, question = "ヲ", answer = "wo"))
        // n
            cardDao.insertCard(Card(deckId = deckId, question = "ン", answer = "n"))
        }
    }
}
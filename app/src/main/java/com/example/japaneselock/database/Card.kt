package com.example.japaneselock.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

// Таблица "Карточки"
@Entity(
    tableName = "cards",
    foreignKeys = [ForeignKey(
        entity = Deck::class,
        parentColumns = ["id"],
        childColumns = ["deckId"],
        onDelete = ForeignKey.CASCADE // При удалении колоды, удалить все ее карточки
    )]
)
data class Card(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deckId: Long, // Связь с колодой
    val question: String,
    val answer: String,
    // --- V3.0: Новые поля ---
    val reading: String?, // Необязательное поле для чтения (напр. "neko")
    val isInvertible: Boolean = false, // Можно ли инвертировать (показывать Answer, спрашивать Question)
    val isReadingCheck: Boolean = false // Нужно ли проверять поле "reading" вместо "answer"
    // --- Конец V3.0 ---
)

// Специальный класс для получения карточки вместе с названием колоды
data class CardWithDeck(
    val cardId: Long,
    val question: String,
    val answer: String,
    val deckName: String,
    // --- V3.0: Новые поля ---
    val reading: String?,
    val isInvertible: Boolean,
    val isReadingCheck: Boolean
    // --- Конец V3.0 ---
)
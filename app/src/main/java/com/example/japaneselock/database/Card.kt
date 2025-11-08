package com.example.japaneselock.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

// Определяем тип карточки
object CardType {
    const val SYLLABLE = "SYLLABLE" // Слог
    const val KANJI = "KANJI"
    const val WORD = "WORD"
    const val READING = "READING" // Чтение
}

@Entity(
    tableName = "cards",
    foreignKeys = [ForeignKey(
        entity = Deck::class,
        parentColumns = ["id"],
        childColumns = ["deckId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class Card(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deckId: Long,
    val question: String,
    val answer: String,

    // --- НОВЫЕ ПОЛЯ (Update 4.0) ---

    // Тип карточки (см. CardType)
    val cardType: String = CardType.SYLLABLE,

    // Звучание (для Kanji и Word)
    val sound: String? = null,

    // Настройки
    val invertAnswer: Boolean = false, // Инвертировать Вопрос/Ответ
    val checkSound: Boolean = false, // Проверять Звучание

    // Индексы SRS (Spaced Repetition System)
    // 0 = не изучено, 1 = первый показ, >1 = изучено.
    // Чем ВЫШЕ индекс, тем ЛУЧШЕ пользователь знает слово.
    // (Логика: 0-2 - показ-карточка, 3+ - только вопросы)
    val srsLevel: Int = 0, // Индекс для (Вопрос -> Ответ)
    val srsLevelInverted: Int = 0, // Индекс для (Ответ -> Вопрос)
    val srsLevelSound: Int = 0, // Индекс для (Вопрос -> Звучание)

    // Статистика
    val totalCorrect: Int = 0,
    val totalIncorrect: Int = 0
)

// Обновляем CardWithDeck, чтобы он получал ВСЕ поля
data class CardWithDeck(
    val cardId: Long,
    val deckId: Long,
    val question: String,
    val answer: String,
    val deckName: String,

    // Новые поля
    val cardType: String,
    val sound: String?,
    val invertAnswer: Boolean,
    val checkSound: Boolean,
    val srsLevel: Int,
    val srsLevelInverted: Int,
    val srsLevelSound: Int,
    val totalCorrect: Int,
    val totalIncorrect: Int
)
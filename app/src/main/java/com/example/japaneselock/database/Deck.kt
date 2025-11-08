package com.example.japaneselock.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "decks")
data class Deck(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,

    // --- НОВЫЕ ПОЛЯ (Update 4.0) ---

    // Включить "Полное изучение" (показывать все карточки)
    val fullStudy: Boolean = true,

    // Количество карточек, добавляемых в "постепенном" режиме
    val batchSize: Int = 5,

    // Статистика (для Req 4)
    val totalQuestionsAnswered: Int = 0,
    val correctAnswers: Int = 0
)
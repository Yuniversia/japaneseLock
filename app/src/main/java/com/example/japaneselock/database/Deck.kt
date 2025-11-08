package com.example.japaneselock.database

import androidx.room.Entity
import androidx.room.PrimaryKey

// Таблица "Колоды"
@Entity(tableName = "decks")
data class Deck(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String
)
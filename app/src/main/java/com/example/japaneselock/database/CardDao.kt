package com.example.japaneselock.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface CardDao {
    // --- Колоды (Decks) ---
    @Insert
    suspend fun insertDeck(deck: Deck): Long

    @Update
    suspend fun updateDeck(deck: Deck)

    @Delete
    suspend fun deleteDeck(deck: Deck)

    @Query("SELECT * FROM decks ORDER BY name ASC")
    suspend fun getAllDecks(): List<Deck>

    // --- Карточки (Cards) ---
    @Insert
    suspend fun insertCard(card: Card)

    @Update
    suspend fun updateCard(card: Card)

    @Delete
    suspend fun deleteCard(card: Card)

    @Query("SELECT * FROM cards WHERE deckId = :deckId ORDER BY question ASC")
    suspend fun getCardsForDeck(deckId: Long): List<Card>

    // --- Логика блокировки ---
    @Query("""
        SELECT 
            c.id as cardId, 
            c.question, 
            c.answer, 
            d.name as deckName 
        FROM cards c
        JOIN decks d ON c.deckId = d.id
        WHERE c.deckId IN (:selectedDeckIds) AND c.id NOT IN (:usedCardIds)
        ORDER BY RANDOM() 
        LIMIT 1
    """)
    suspend fun getRandomCardFromDecks(selectedDeckIds: List<Long>, usedCardIds: Set<Long>): CardWithDeck?
}
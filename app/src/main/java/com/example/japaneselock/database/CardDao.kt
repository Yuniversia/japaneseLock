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

    // (Req 6) - Получение колоды (для проверки настроек)
    @Query("SELECT * FROM decks WHERE id = :deckId")
    suspend fun getDeckById(deckId: Long): Deck?

    // --- Карточки (Cards) ---
    @Insert
    suspend fun insertCard(card: Card)

    @Update
    suspend fun updateCard(card: Card)

    @Delete
    suspend fun deleteCard(card: Card)

    @Query("SELECT * FROM cards WHERE deckId = :deckId ORDER BY question ASC")
    suspend fun getCardsForDeck(deckId: Long): List<Card>

    // --- Подсчет карточек для лимита вопросов (V3.0) ---
    @Query("SELECT COUNT(*) FROM cards WHERE deckId IN (:deckIds)")
    suspend fun getCardCountForDecks(deckIds: List<Long>): Int

    @Query("SELECT COUNT(*) FROM cards WHERE deckId IN (:deckIds) AND invertAnswer = 1")
    suspend fun getInvertibleCardCountForDecks(deckIds: List<Long>): Int

    // --- Статистика (Req 4) ---
    data class DeckStats(val totalCards: Int, val learnedCards: Int)
    @Query("""
        SELECT 
            COUNT(id) as totalCards, 
            SUM(CASE WHEN srsLevel >= :srsLevelLearned THEN 1 ELSE 0 END) as learnedCards
        FROM cards 
        WHERE deckId = :deckId
    """)
    suspend fun getDeckStats(deckId: Long, srsLevelLearned: Int): DeckStats

    data class DeckMasteryStats(val totalCorrect: Int, val totalIncorrect: Int)
    @Query("""
        SELECT 
            SUM(totalCorrect) as totalCorrect, 
            SUM(totalIncorrect) as totalIncorrect
        FROM cards 
        WHERE deckId = :deckId
    """)
    suspend fun getDeckMastery(deckId: Long): DeckMasteryStats

    // --- Логика блокировки (Req 1, 3) ---

    // Получаем ID карточек для постепенного изучения (Req 6)
    @Query("SELECT id FROM cards WHERE deckId = :deckId ORDER BY id ASC LIMIT :batchSize OFFSET :offset")
    suspend fun getProgressiveCardIds(deckId: Long, batchSize: Int, offset: Int): List<Long>

    // Основной запрос для выбора карточки (Req 1, 3, 6)
    @Query("""
        SELECT 
            c.id as cardId, 
            c.deckId,
            c.question, 
            c.answer, 
            d.name as deckName,
            c.cardType,
            c.sound,
            c.invertAnswer,
            c.checkSound,
            c.srsLevel,
            c.srsLevelInverted,
            c.srsLevelSound,
            c.totalCorrect,
            c.totalIncorrect
        FROM cards c
        JOIN decks d ON c.deckId = d.id
        WHERE c.deckId IN (:selectedDeckIds) 
          AND c.id NOT IN (:usedCardIds)
          AND c.id IN (:allowedCardIds) -- (Req 6) Для постепенного режима
        ORDER BY 
            (c.srsLevel + c.srsLevelInverted + c.srsLevelSound) ASC, -- Приоритет новым (низкий SRS)
            RANDOM()
        LIMIT 1
    """)
    suspend fun getCardForQuiz(selectedDeckIds: List<Long>, usedCardIds: Set<Long>, allowedCardIds: List<Long>): CardWithDeck?

    // Запрос-заглушка, если progressive-режим выключен
    @Query("""
        SELECT 
            c.id as cardId, 
            c.deckId,
            c.question, 
            c.answer, 
            d.name as deckName,
            c.cardType,
            c.sound,
            c.invertAnswer,
            c.checkSound,
            c.srsLevel,
            c.srsLevelInverted,
            c.srsLevelSound,
            c.totalCorrect,
            c.totalIncorrect
        FROM cards c
        JOIN decks d ON c.deckId = d.id
        WHERE c.deckId IN (:selectedDeckIds) 
          AND c.id NOT IN (:usedCardIds)
        ORDER BY 
            (c.srsLevel + c.srsLevelInverted + c.srsLevelSound) ASC, -- Приоритет новым (низкий SRS)
            RANDOM()
        LIMIT 1
    """)
    suspend fun getCardForQuiz(selectedDeckIds: List<Long>, usedCardIds: Set<Long>): CardWithDeck?
}
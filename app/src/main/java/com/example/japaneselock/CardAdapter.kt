package com.example.japaneselock

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.japaneselock.database.Card
import com.example.japaneselock.databinding.ItemCardBinding

class CardAdapter(
    private var cards: List<Card>,
    private val onEditClick: (Card) -> Unit,
    private val onDeleteClick: (Card) -> Unit
) : RecyclerView.Adapter<CardAdapter.CardViewHolder>() {

    private val DEBUG_TAG = "DEBUG_LOCK"

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        try {
            val binding = ItemCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return CardViewHolder(binding)
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "CardAdapter: Ошибка создания ViewHolder", e)
            throw e
        }
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        try {
            val card = cards[position]
            holder.bind(card)
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "CardAdapter: Ошибка привязки на позиции $position", e)
        }
    }

    override fun getItemCount(): Int = cards.size

    fun updateData(newCards: List<Card>) {
        cards = newCards
        notifyDataSetChanged()
        Log.d(DEBUG_TAG, "CardAdapter: Обновлено ${cards.size} карточек")
    }

    inner class CardViewHolder(private val binding: ItemCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(card: Card) {
            try {
                Log.d(DEBUG_TAG, "CardAdapter: Привязка карточки ${card.id}: ${card.question}")

                binding.textQuestion.text = card.question
                binding.textAnswer.text = card.answer

                binding.layoutEdit.setOnClickListener {
                    Log.d(DEBUG_TAG, "CardAdapter: Клик на редактирование карточки ${card.id}")
                    onEditClick(card)
                }

                binding.buttonDelete.setOnClickListener {
                    Log.d(DEBUG_TAG, "CardAdapter: Клик на удаление карточки ${card.id}")
                    onDeleteClick(card)
                }
            } catch (e: Exception) {
                Log.e(DEBUG_TAG, "CardAdapter: Ошибка в bind() для карточки ${card.id}", e)
            }
        }
    }
}
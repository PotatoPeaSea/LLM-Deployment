package com.qcs.geniechat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter : ListAdapter<ChatItem, ChatAdapter.Holder>(DIFF) {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1

        private val DIFF = object : DiffUtil.ItemCallback<ChatItem>() {
            // Positional identity: the list is append-only apart from the last
            // bubble, which mutates on every streamed fragment.
            override fun areItemsTheSame(a: ChatItem, b: ChatItem) = a === b
            override fun areContentsTheSame(a: ChatItem, b: ChatItem) = a == b
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.messageText)
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).role == Role.USER) TYPE_USER else TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == TYPE_USER) R.layout.item_message_user
        else R.layout.item_message_assistant
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        // An empty streaming bubble is the gap between sending and the first
        // token off the NPU; show a caret so the UI never looks stuck.
        holder.text.text = if (item.streaming && item.text.isEmpty()) "..." else item.text
    }
}

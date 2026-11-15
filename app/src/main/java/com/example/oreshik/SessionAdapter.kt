package com.example.oreshik

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.oreshik.data.SessionListItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionAdapter(
    private val onClick: (SessionListItem) -> Unit,
    private val onLongClick: (View, SessionListItem) -> Boolean,
) : RecyclerView.Adapter<SessionAdapter.VH>() {
    private val items = mutableListOf<SessionListItem>()

    // The whole backing list is replaced on each submit; per-item animations
    // would add complexity for no visible benefit here.
    @SuppressLint("NotifyDataSetChanged")
    fun submitList(sessions: List<SessionListItem>) {
        items.clear()
        items.addAll(sessions)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val session = items[position]
        holder.title.text = session.title
        holder.meta.text = formatTime(session.updatedAt)
        holder.itemView.setOnClickListener { onClick(session) }
        holder.itemView.setOnLongClickListener { onLongClick(holder.itemView, session) }
    }

    override fun getItemCount() = items.size

    private fun formatTime(time: Long): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(time))
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        return if (day == today) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
        else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time))
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvSessionTitle)
        val meta: TextView = view.findViewById(R.id.tvSessionMeta)
    }
}

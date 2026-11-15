package com.example.oreshik

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.oreshik.ai.ModelTester

/**
 * Adapter for the model-picker bottom sheets (main chat screen + Providers screen).
 * Each row shows the model name with a live status dot (ripple ring): green = online,
 * red = offline, orange = timeout/rate-limited, gray pulsing = still probing.
 */
class ModelAdapter(
    private var models: List<Model>,
    private val onModelSelected: (Model) -> Unit,
) : RecyclerView.Adapter<ModelAdapter.ModelViewHolder>() {

    /** modelId → live status override pushed in real time while probing runs. */
    private val statusOverrides = mutableMapOf<String, ModelTester.ModelStatus>()

    class ModelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvId: TextView = view.findViewById(R.id.tvModelId)
        val tvDesc: TextView = view.findViewById(R.id.tvModelDesc)
        val icon: ImageView = view.findViewById(R.id.ivModelIcon)
        val statusDot: ModelStatusDot = view.findViewById(R.id.dotModelStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model, parent, false)
        return ModelViewHolder(view)
    }

    override fun getItemCount(): Int = models.size

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val model = models[position]
        holder.tvId.text = model.id
        holder.icon.setImageResource(
            when (model.kind) {
                "vision" -> R.drawable.ic_image
                "audio" -> R.drawable.ic_waveform
                "reasoning" -> R.drawable.ic_psychology
                "coding" -> R.drawable.ic_build
                else -> R.drawable.ic_auto_awesome
            },
        )
        holder.tvDesc.text = buildString {
            append(kindLabel(model))
            append(" • ")
            append(model.description.ifBlank { "Model" })
            if (model.reasoning) append("  •  Thinking: ${model.thinkingLevels.joinToString(" / ")}")
            when (model.tested) {
                true -> append("  •  ✓ responds")
                false -> append("  •  ✗ ${model.testNote}")
                null -> {}
            }
        }
        // Live status dot: overrides carry in-flight probe results; the model's own
        // tested/testNote fields cover everything else (null → gray pulsing "checking").
        holder.statusDot.setStatus(statusOverrides[model.id] ?: ModelTester.statusFor(model))
        // Grey out models that failed the live test so they are visibly excluded.
        holder.itemView.alpha = if (model.tested == false) 0.45f else 1f
        holder.itemView.setOnClickListener { onModelSelected(model) }
    }

    private fun kindLabel(model: Model): String = when (model.kind) {
        "vision" -> "Vision"
        "audio" -> "Audio"
        "reasoning" -> "Reasoning"
        "coding" -> "Coding"
        else -> "General"
    }

    /**
     * Real-time status update for one model: flips only that row's dot without
     * re-binding the whole list (no flicker while probes stream in).
     */
    fun updateStatus(modelId: String, status: ModelTester.ModelStatus) {
        statusOverrides[modelId] = status
        models.forEachIndexed { index, m ->
            if (m.id == modelId) notifyItemChanged(index)
        }
    }

    /** Clears all live overrides (used when forcing a full re-probe). */
    @SuppressLint("NotifyDataSetChanged")
    fun resetStatuses() {
        statusOverrides.clear()
        notifyDataSetChanged()
    }

    // Whole list is replaced on refresh; incremental diffing adds no value here.
    @SuppressLint("NotifyDataSetChanged")
    fun updateData(newModels: List<Model>) {
        models = newModels
        notifyDataSetChanged()
    }
}

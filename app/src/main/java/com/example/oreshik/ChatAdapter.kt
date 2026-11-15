package com.example.oreshik

import android.annotation.SuppressLint
import android.content.Intent
import androidx.core.net.toUri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.example.oreshik.logging.AppLogger
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Message displayed in the chat RecyclerView.
 */
data class ChatMessage(
    val role: String,       // "user" | "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val model: String? = null,
    val kind: String = "text",       // "text" | "tool" | "work"
    val toolCallId: String? = null,  // only for kind == "tool"
    val toolStatus: String = "running", // "running" | "done", only for kind == "tool"
    val linkPreviews: List<MediaPreview> = emptyList(),
    val workStartedAt: Long? = null,
    val workFinishedAt: Long? = null,
    val activityItems: List<ChatActivityItem> = emptyList(),
    val activityExpanded: Boolean = true,
    val attachments: List<ChatAttachment> = emptyList(),
)

/** A private workspace copy of a user-selected chat attachment. */
data class ChatAttachment(
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
)

/** A visible progress note or collapsible tool step in an agent work timeline. */
data class ChatActivityItem(
    val type: String, // "progress" | "tool" | "changes"
    val title: String,
    val detail: String? = null,
    val status: String = "done", // "running" | "done" | "error"
    val timestamp: Long = System.currentTimeMillis(),
    val expanded: Boolean = false,
    val detailsExpanded: Boolean = false,
    val files: List<ChatFileChange>? = null,
)

/** File-level workspace changes from a single agent run. */
data class ChatFileChange(
    val path: String,
    val additions: Int? = null,
    val deletions: Int? = null,
    val changeType: String = "modified", // "added" | "modified" | "deleted"
    val diff: List<ChatDiffLine>? = null,
    val diffUnavailableReason: String? = null,
)

/** Line counts shared by the in-chat summary and the file diff drawer. */
internal fun ChatFileChange.lineCounts(): Pair<Int, Int>? {
    val preview = diff.orEmpty()
    val previewAdditions = preview.count { it.kind == "added" }
    val previewDeletions = preview.count { it.kind == "removed" }
    val resolvedAdditions = additions ?: previewAdditions.takeIf { preview.isNotEmpty() }
    val resolvedDeletions = deletions ?: previewDeletions.takeIf { preview.isNotEmpty() }
    return if (resolvedAdditions == null && resolvedDeletions == null) {
        null
    } else {
        (resolvedAdditions ?: 0) to (resolvedDeletions ?: 0)
    }
}

/** One row in a bounded unified diff preview. */
data class ChatDiffLine(
    val kind: String, // "context" | "added" | "removed" | "hunk"
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

sealed interface MediaPreview {
    val url: String

    data class Link(
        override val url: String,
        val title: String? = null,
        val description: String? = null,
        val faviconUrl: String? = null,
        val domain: String? = null
    ) : MediaPreview

    data class Image(
        override val url: String,
        val width: Int? = null,
        val height: Int? = null,
        val altText: String? = null,
        val isBase64: Boolean = false
    ) : MediaPreview

    data class Video(
        override val url: String,
        val title: String? = null,
        val duration: Long? = null, // in milliseconds
        val thumbnailUrl: String? = null,
        val mimeType: String? = null
    ) : MediaPreview

    data class WebView(
        override val url: String,
        val title: String? = null,
        val content: String? = null,
        val faviconUrl: String? = null
    ) : MediaPreview

    /** Live in-app browser bubble — renders the shared live WebView in real time. */
    data class InAppBrowser(
        override val url: String
    ) : MediaPreview

    companion object {
        /** Public factory kept for callers that already hold base64 image data. */
        @Suppress("unused")
        fun fromBase64(base64: String, mimeType: String = "image/png", altText: String? = null): Image {
            val dataUri = "data:$mimeType;base64,$base64"
            return Image(url = dataUri, altText = altText, isBase64 = true)
        }
    }
}

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_ASSISTANT = 1
        private const val VIEW_TYPE_STATUS = 3

        /** Fallback label shown before any reasoning arrives. */
        private const val DEFAULT_THINKING_LABEL = "Thinking"
        private const val MAX_ACTIVITY_ITEMS = 48
        private const val COLLAPSIBLE_PROGRESS_MIN_CHARS = 180
        private const val COLLAPSED_PROGRESS_MAX_LINES = 4
    }

    /** Markdown renderer for ChatGPT-style assistant messages; built lazily on first bind. */
    private var markwon: Markwon? = null

    private fun markwonFor(view: View): Markwon {
        markwon?.let { return it }
        val mw = Markwon.builder(view.context)
            .usePlugin(CorePlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(view.context))
            .usePlugin(LinkifyPlugin.create())
            .build()
        markwon = mw
        return mw
    }

    val messages = mutableListOf<ChatMessage>()
    val selectedIndices = mutableSetOf<Int>()
    var isSelectionMode = false
    var onSelectionModeChanged: ((Boolean) -> Unit)? = null
    var onSelectionCountChanged: ((Int) -> Unit)? = null
    var onEditUserMessage: ((String) -> Unit)? = null
    var onFileChangeClick: ((ChatFileChange, List<ChatFileChange>) -> Unit)? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var activeWorkTimestamp: Long? = null
    private var boundWorkHolder: VH? = null
    private var boundWorkMessage: ChatMessage? = null
    private val workTicker = object : Runnable {
        override fun run() {
            val message = boundWorkMessage ?: return
            boundWorkHolder?.statusText?.text = workHeading(message)
            if (message.workFinishedAt == null) uiHandler.postDelayed(this, 1_000L)
        }
    }

    override fun getItemViewType(position: Int): Int = when {
        messages[position].kind == "status" || messages[position].kind == "work" -> VIEW_TYPE_STATUS
        messages[position].role == "user" -> VIEW_TYPE_USER
        else -> VIEW_TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> {
                val view = inflater.inflate(R.layout.item_message, parent, false)
                VH(view).apply {
                    userText = view.findViewById(R.id.tvUserText)
                    userTimestamp = view.findViewById(R.id.tvUserTimestamp)
                    userAttachmentContainer = view.findViewById(R.id.userAttachmentContainer)
                    checkbox = view.findViewById(R.id.cbSelect)
                }
            }
            VIEW_TYPE_STATUS -> {
                val view = inflater.inflate(R.layout.item_message_status, parent, false)
                VH(view).apply {
                    statusText = view.findViewById(R.id.tvStatus)
                    statusProgress = view.findViewById(R.id.progressStatus)
                    activityContainer = view.findViewById(R.id.activityContainer)
                    activityToggle = view.findViewById(R.id.btnActivityToggle)
                    checkbox = view.findViewById(R.id.cbSelect)
                }
            }
            else -> {
                val view = inflater.inflate(R.layout.item_message_assistant, parent, false)
                val tvText = view.findViewById<TextView>(R.id.tvText)
                val tvMeta = view.findViewById<TextView>(R.id.tvMeta)
                val linkContainer = view.findViewById<ViewGroup>(R.id.linkPreviewContainer)
                VH(view).apply {
                    assistantText = tvText
                    assistantMeta = tvMeta
                    copyButton = view.findViewById(R.id.btnCopy)
                    linkPreviewContainer = linkContainer
                    checkbox = view.findViewById(R.id.cbSelect)
                }
            }
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = messages[position]
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFormat.format(Date(msg.timestamp))
        val isSelected = selectedIndices.contains(position)

        // The work row was recycled — drop its live elapsed-time ticker target.
        if (msg.kind != "status" && msg.kind != "work" && boundWorkHolder === holder) {
            boundWorkHolder = null
            boundWorkMessage = null
            uiHandler.removeCallbacks(workTicker)
        }

        holder.itemView.isSelected = isSelected
        holder.itemView.alpha = if (isSelected) 0.55f else 1.0f

        holder.itemView.setOnLongClickListener {
            toggleSelection(position)
            true
        }

        holder.itemView.setOnClickListener {
            if (isSelectionMode) {
                toggleSelection(position)
            }
        }

        // Multi-select checkbox: visible only while selection mode is active,
        // state mirrors selectedIndices so recycled rows stay in sync, and
        // tapping it toggles that message's selection.
        val checkbox = holder.checkbox
        if (checkbox != null) {
            checkbox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            checkbox.isChecked = isSelected
            checkbox.setOnClickListener { toggleSelection(position) }
        }

        when {
            msg.kind == "status" || msg.kind == "work" -> {
                renderWorkTimeline(holder, msg)
            }
            msg.role == "user" && holder.userText != null -> {
                holder.userText!!.text = msg.content
                holder.userTimestamp?.text = userTimestamp(msg.timestamp)
                val attachmentContainer = holder.userAttachmentContainer
                if (attachmentContainer != null) {
                    attachmentContainer.removeAllViews()
                    val attachments = msg.attachments.orEmpty()
                    attachmentContainer.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
                    if (attachments.isNotEmpty()) {
                        attachments.forEach { attachment ->
                            val file = com.example.oreshik.agent.core.AgentConfig.chatAttachmentFile(
                                holder.itemView.context,
                                attachment.relativePath,
                            ) ?: return@forEach
                            if (attachment.mimeType.startsWith("image/", ignoreCase = true) && file.isFile) {
                                val preview = ImageView(holder.itemView.context).apply {
                                    val size = (88 * resources.displayMetrics.density).toInt()
                                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                                        marginEnd = (8 * resources.displayMetrics.density).toInt()
                                    }
                                    contentDescription = attachment.displayName
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    setBackgroundResource(R.drawable.bg_message_bubble)
                                    clipToOutline = true
                                }
                                Glide.with(holder.itemView.context).load(file)
                                    .apply(RequestOptions().placeholder(R.drawable.ic_image_placeholder).error(R.drawable.ic_image_error))
                                    .into(preview)
                                attachmentContainer.addView(preview)
                            } else {
                                val label = TextView(holder.itemView.context).apply {
                                    text = "${attachmentIcon(attachment.mimeType)}  ${attachment.displayName}"
                                    textSize = 12f
                                    setTextColor(ContextCompat.getColor(context, R.color.text_on_bubble))
                                    setPadding((8 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt())
                                    maxLines = 2
                                }
                                attachmentContainer.addView(label)
                            }
                        }
                    }
                }
            }
            holder.assistantText != null -> {
                // Markdown rendering (ChatGPT-style): bold, lists, code blocks, tables, links.
                if (msg.content.length > 50_000) {
                    holder.assistantText!!.text = msg.content
                } else {
                    try {
                        markwonFor(holder.itemView).setMarkdown(holder.assistantText!!, msg.content)
                    } catch (failure: RuntimeException) {
                        AppLogger.e("ChatAdapter", "Markdown rendering failed; showing plain text (chars=${msg.content.length})", failure)
                        holder.assistantText!!.text = msg.content
                    }
                }
                val metaParts = mutableListOf(timeStr)
                msg.model?.let { metaParts.add(it) }
                holder.assistantMeta?.text = metaParts.joinToString(" · ")
                holder.copyButton?.setOnClickListener {
                    if (isSelectionMode) toggleSelection(position) else copyMessage(holder.itemView, msg.content)
                }

                // Bind media previews
                val container = holder.linkPreviewContainer
                if (container != null) {
                    val mediaPreviews: List<MediaPreview> = msg.linkPreviews
                    if (mediaPreviews.isNotEmpty()) {
                        container.visibility = View.VISIBLE
                        container.removeAllViews()
                        val inflater = LayoutInflater.from(container.context)
                        mediaPreviews.forEach { preview ->
                            when (preview) {
                                is MediaPreview.Link -> {
                                    val previewView = inflater.inflate(R.layout.item_link_preview, container, false)

                                    previewView.findViewById<TextView>(R.id.tvLinkTitle).text = preview.title ?: "Link"
                                    previewView.findViewById<TextView>(R.id.tvLinkDescription).text = preview.description ?: ""
                                    previewView.findViewById<TextView>(R.id.tvLinkUrl).text = preview.domain ?: extractDomain(preview.url)

                                    val iconView = previewView.findViewById<ImageView>(R.id.ivLinkIcon)
                                    val favicon = preview.faviconUrl
                                    if (!favicon.isNullOrBlank()) {
                                        Glide.with(container.context)
                                            .load(preview.faviconUrl)
                                            .apply(RequestOptions().placeholder(R.drawable.ic_link).error(R.drawable.ic_link).circleCrop())
                                            .into(iconView)
                                    } else {
                                        iconView.setImageResource(R.drawable.ic_link)
                                    }

                                    previewView.setOnClickListener {
                                        val intent = Intent(Intent.ACTION_VIEW, preview.url.toUri())
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        container.context.startActivity(intent)
                                    }

                                    container.addView(previewView)
                                }
                                is MediaPreview.Image -> {
                                    val previewView = inflater.inflate(R.layout.item_image_preview, container, false)
                                    val imageView = previewView.findViewById<ImageView>(R.id.ivImagePreview)

                                    // Handle URLs, data URIs, and file paths
                                    val imageUrl = preview.url
                                    when {
                                        imageUrl.startsWith("data:image/") -> {
                                            // Load base64 data URI directly
                                            Glide.with(container.context)
                                                .load(imageUrl)
                                                .apply(RequestOptions().placeholder(R.drawable.ic_image_placeholder).error(R.drawable.ic_image_error))
                                                .into(imageView)
                                        }
                                        imageUrl.startsWith("file:" + "//") -> {
                                            // Load file from local storage
                                            Glide.with(container.context)
                                                .load(imageUrl.toUri())
                                                .apply(RequestOptions().placeholder(R.drawable.ic_image_placeholder).error(R.drawable.ic_image_error))
                                                .into(imageView)
                                        }
                                        imageUrl.startsWith("/") -> {
                                            // Load plain absolute file path (e.g. browser screenshot preview)
                                            Glide.with(container.context)
                                                .load(java.io.File(imageUrl))
                                                .apply(RequestOptions().placeholder(R.drawable.ic_image_placeholder).error(R.drawable.ic_image_error))
                                                .into(imageView)
                                        }
                                        else -> {
                                            // Load regular URL
                                            Glide.with(container.context)
                                                .load(imageUrl)
                                                .apply(RequestOptions().placeholder(R.drawable.ic_image_placeholder).error(R.drawable.ic_image_error))
                                                .into(imageView)
                                        }
                                    }

                                    // Set content description for accessibility
                                    imageView.contentDescription = preview.altText ?: "Image preview"

                                    previewView.setOnClickListener {
                                        if (!imageUrl.startsWith("data:image/")) {
                                            val intent = Intent(Intent.ACTION_VIEW, imageUrl.toUri())
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            container.context.startActivity(intent)
                                        }
                                    }

                                    container.addView(previewView)
                                }
                                is MediaPreview.Video -> {
                                    val previewView = inflater.inflate(R.layout.item_video_preview, container, false)
                                    val playButton = previewView.findViewById<ImageButton>(R.id.btnPlayVideo)
                                    val thumbView = previewView.findViewById<ImageView>(R.id.ivVideoThumbnail)
                                    val titleView = previewView.findViewById<TextView>(R.id.tvVideoTitle)

                                    // Show thumbnail if available
                                    preview.thumbnailUrl?.let { thumbUrl ->
                                        thumbView.visibility = View.VISIBLE
                                        Glide.with(container.context)
                                            .load(thumbUrl)
                                            .apply(RequestOptions().placeholder(R.drawable.ic_video_placeholder).error(R.drawable.ic_video_error))
                                            .into(thumbView)
                                    }

                                    // Show title if available
                                    preview.title?.let { title ->
                                        titleView.text = title
                                        titleView.visibility = View.VISIBLE
                                    }

                                    // Set up video playback on play button
                                    playButton.setOnClickListener {
                                        val intent = Intent(Intent.ACTION_VIEW, preview.url.toUri())
                                        preview.mimeType?.let { mimeType -> intent.setDataAndType(preview.url.toUri(), mimeType) }
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        container.context.startActivity(intent)
                                    }

                                    // Also make the whole preview clickable
                                    previewView.setOnClickListener {
                                        val intent = Intent(Intent.ACTION_VIEW, preview.url.toUri())
                                        preview.mimeType?.let { mimeType -> intent.setDataAndType(preview.url.toUri(), mimeType) }
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        container.context.startActivity(intent)
                                    }

                                    container.addView(previewView)
                                }
                                is MediaPreview.WebView -> {
                                    val previewView = inflater.inflate(R.layout.item_web_preview, container, false)
                                    val titleView = previewView.findViewById<TextView>(R.id.tvWebTitle)
                                    val contentView = previewView.findViewById<TextView>(R.id.tvWebContent)
                                    val urlView = previewView.findViewById<TextView>(R.id.tvWebUrl)
                                    val iconView = previewView.findViewById<ImageView>(R.id.ivWebIcon)

                                    titleView.text = preview.title ?: "Web Page"
                                    contentView.text = preview.content?.take(200) ?: ""
                                    urlView.text = extractDomain(preview.url)

                                    val webViewFavicon = preview.faviconUrl
                                    if (!webViewFavicon.isNullOrBlank()) {
                                        Glide.with(container.context)
                                            .load(preview.faviconUrl)
                                            .apply(RequestOptions().placeholder(R.drawable.ic_link).error(R.drawable.ic_link).circleCrop())
                                            .into(iconView)
                                    } else {
                                        iconView.setImageResource(R.drawable.ic_link)
                                    }

                                    previewView.setOnClickListener {
                                        val intent = Intent(Intent.ACTION_VIEW, preview.url.toUri())
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        container.context.startActivity(intent)
                                    }

                                    container.addView(previewView)
                                }
                                is MediaPreview.InAppBrowser -> {
                                    // Live in-app browser: hosts the shared live WebView so
                                    // the user watches the agent browsing in real time and can
                                    // navigate with back/forward/reload arrows.
                                    val overlay = com.example.oreshik.browser.LiveBrowserOverlayView(container.context)
                                    overlay.layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                    container.addView(overlay)
                                }
                            }
                        }
                    } else {
                        container.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun extractDomain(url: String): String {
        return try {
            val uri = url.toUri()
            uri.host ?: url
        } catch (_: Exception) {
            url
        }
    }

    override fun getItemCount(): Int = messages.size

    fun toggleSelection(position: Int) {
        if (position < 0 || position >= messages.size) return
        if (selectedIndices.contains(position)) {
            selectedIndices.remove(position)
        } else {
            selectedIndices.add(position)
        }
        if (selectedIndices.isEmpty()) {
            exitSelectionMode()
        } else {
            if (!isSelectionMode) {
                isSelectionMode = true
                onSelectionModeChanged?.invoke(true)
            }
            onSelectionCountChanged?.invoke(selectedIndices.size)
            notifyItemChanged(position)
        }
    }

    fun selectAll() {
        selectedIndices.clear()
        selectedIndices.addAll(messages.indices)
        onSelectionCountChanged?.invoke(selectedIndices.size)
        refreshAll()
    }

    fun exitSelectionMode() {
        val hadSelection = selectedIndices.isNotEmpty()
        isSelectionMode = false
        selectedIndices.clear()
        onSelectionModeChanged?.invoke(false)
        if (hadSelection) {
            refreshAll()
        }
    }

    fun deleteSelected(): List<ChatMessage> {
        val sorted = selectedIndices.sortedDescending()
        val removed = mutableListOf<ChatMessage>()
        for (i in sorted) {
            if (i in messages.indices) {
                removed.add(messages.removeAt(i))
            }
        }
        exitSelectionMode()
        refreshAll()
        return removed
    }

    /** Full refresh used by selection-mode transitions and bulk deletes. */
    @SuppressLint("NotifyDataSetChanged")
    private fun refreshAll() {
        notifyDataSetChanged()
    }

    fun addMessage(msg: ChatMessage) {
        // A new user message starts a fresh agent turn; keep earlier work rows
        // in history but stop the live timer from targeting an older turn.
        if (msg.role == "user") resetThinkingState()
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    /**
     * Replaces the entire backing list with [newMessages] in one full refresh.
     * Callers restore whole conversations (rotation snapshot / DB row), where a
     * per-item diff adds no value — hence the intentional notifyDataSetChanged.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun replaceAll(newMessages: List<ChatMessage>) {
        uiHandler.removeCallbacks(workTicker)
        boundWorkHolder = null
        boundWorkMessage = null
        messages.clear()
        messages.addAll(newMessages)
        activeWorkTimestamp = messages.lastOrNull { it.kind == "work" && it.workFinishedAt == null }?.workStartedAt
        notifyDataSetChanged()
    }

    /** Streaming update for the last assistant message (used by live TTS). */
    @Suppress("unused")
    fun updateLast(content: String) {
        if (messages.isNotEmpty()) {
            val idx = messages.size - 1
            messages[idx] = messages[idx].copy(content = content)
            notifyItemChanged(idx)
        }
    }

    // ================================================================================
    // Provider work timeline
    // ================================================================================

    /** Append a provider progress note to the current turn's persistent work row. */
    fun showProcessing(label: String = DEFAULT_THINKING_LABEL) {
        val text = label.trim().removePrefix("[model]").trim().ifBlank { DEFAULT_THINKING_LABEL }
        val index = ensureWorkRow()
        val current = messages[index]
        val thinking = text == DEFAULT_THINKING_LABEL || text == "Thinking…"
        if (thinking) {
            val items = current.activityItems.orEmpty().toMutableList()
            val thinkingIndex = items.indexOfLast { it.type == "progress" && it.title == DEFAULT_THINKING_LABEL }
            if (thinkingIndex >= 0) {
                items[thinkingIndex] = items[thinkingIndex].copy(status = "running")
            } else {
                items += ChatActivityItem(type = "progress", title = DEFAULT_THINKING_LABEL, status = "running")
            }
            messages[index] = current.copy(content = DEFAULT_THINKING_LABEL, activityItems = items.takeLast(MAX_ACTIVITY_ITEMS))
            notifyItemChanged(index)
            return
        }
        if (text.matches(Regex("""Agent step \d+"""))) {
            notifyItemChanged(index)
            return
        }
        val items = current.activityItems.orEmpty().map {
            if (it.type == "progress" && it.status == "running") it.copy(status = "done") else it
        }
        if (items.lastOrNull()?.let { it.type == "progress" && it.title == text } != true) {
            val updated = (items + ChatActivityItem(type = "progress", title = text)).takeLast(MAX_ACTIVITY_ITEMS)
            messages[index] = current.copy(content = text, activityItems = updated)
            notifyItemChanged(index)
        } else {
            notifyItemChanged(index)
        }
    }

    /** Add one chronological tool row; completion updates its latest running entry. */
    fun addToolActivity(name: String, description: String = "Running…") {
        if (name.isBlank()) return
        val index = ensureWorkRow()
        val current = messages[index]
        val title = name.substringAfterLast('/').substringAfterLast('.')
        val items = current.activityItems.orEmpty().map {
            if (it.type == "progress" && it.status == "running") it.copy(status = "done") else it
        } + ChatActivityItem(
            type = "tool",
            title = title,
            detail = description.takeIf { it.isNotBlank() && it != "Running…" },
            status = "running"
        )
        messages[index] = current.copy(activityItems = items.takeLast(MAX_ACTIVITY_ITEMS))
        notifyItemChanged(index)
    }

    fun completeToolActivity(name: String, status: String, detail: String? = null) {
        val index = ensureWorkRow()
        val current = messages[index]
        val title = name.substringAfterLast('/').substringAfterLast('.')
        val items = current.activityItems.orEmpty().toMutableList()
        val runningIndex = items.indexOfLast { it.type == "tool" && it.title == title && it.status == "running" }
        if (runningIndex >= 0) {
            items[runningIndex] = items[runningIndex].copy(
                status = if (status == "done") "done" else "error",
                detail = detail?.takeIf { it.isNotBlank() } ?: items[runningIndex].detail
            )
        } else {
            items += ChatActivityItem(
                type = "tool",
                title = title,
                detail = detail?.takeIf { it.isNotBlank() },
                status = if (status == "done") "done" else "error"
            )
        }
        messages[index] = current.copy(activityItems = items.takeLast(MAX_ACTIVITY_ITEMS))
        notifyItemChanged(index)
    }

    /** Append a file summary to the live work row so it is visible even when history saving is off. */
    fun addFileChanges(files: List<ChatFileChange>) {
        if (files.isEmpty()) return
        val index = ensureWorkRow()
        val current = messages[index]
        val count = files.size
        val item = ChatActivityItem(
            type = "changes",
            title = "$count file${if (count == 1) "" else "s"} changed",
            status = "done",
            expanded = true,
            files = files,
        )
        val items = current.activityItems.orEmpty().filterNot { it.type == "changes" } + item
        messages[index] = current.copy(activityItems = items.takeLast(MAX_ACTIVITY_ITEMS))
        notifyItemChanged(index)
    }

    /** Finalize the visible work row instead of removing it from the conversation. */
    fun hideProcessing(terminalStatus: String = "done") {
        val timestamp = activeWorkTimestamp ?: return
        val index = messages.indexOfFirst { it.kind == "work" && it.workStartedAt == timestamp }
        if (index >= 0) {
            val current = messages[index]
            val items = current.activityItems.orEmpty().map {
                if (it.status == "running") it.copy(status = if (terminalStatus == "done") "done" else "error") else it
            }
            messages[index] = current.copy(
                toolStatus = terminalStatus,
                workFinishedAt = current.workFinishedAt ?: System.currentTimeMillis(),
                activityItems = items
            )
            notifyItemChanged(index)
        }
        activeWorkTimestamp = null
        boundWorkHolder = null
        boundWorkMessage = null
        uiHandler.removeCallbacks(workTicker)
    }

    private fun resetThinkingState() {
        activeWorkTimestamp = null
        boundWorkMessage = null
        uiHandler.removeCallbacks(workTicker)
    }

    private fun ensureWorkRow(): Int {
        val activeTimestamp = activeWorkTimestamp
        if (activeTimestamp != null) {
            val activeIndex = messages.indexOfFirst { it.kind == "work" && it.workStartedAt == activeTimestamp }
            if (activeIndex >= 0) return activeIndex
        }
        val startedAt = System.currentTimeMillis()
        activeWorkTimestamp = startedAt
        val row = ChatMessage(
            role = "assistant",
            content = DEFAULT_THINKING_LABEL,
            kind = "work",
            toolStatus = "running",
            workStartedAt = startedAt
        )
        messages.add(row)
        notifyItemInserted(messages.size - 1)
        return messages.lastIndex
    }

    private fun renderWorkTimeline(holder: VH, message: ChatMessage) {
        val startedAt = message.workStartedAt ?: message.timestamp
        val running = message.workFinishedAt == null && message.toolStatus != "done"
        holder.statusProgress?.visibility = if (running) View.VISIBLE else View.GONE
        holder.statusText?.text = workHeading(message)
        (holder.statusText as? ShimmerTextView)?.setShimmerEnabled(
            running,
            ContextCompat.getColor(holder.itemView.context, R.color.text_secondary),
            ContextCompat.getColor(holder.itemView.context, R.color.text_primary),
        )
        holder.activityContainer?.let { container ->
            val expanded = message.activityExpanded
            container.visibility = if (expanded) View.VISIBLE else View.GONE
            container.removeAllViews()
            if (expanded) {
                message.activityItems.orEmpty().forEachIndexed { itemIndex, item ->
                    container.addView(buildActivityItem(holder.itemView, message, item, itemIndex))
                }
            }
        }
        holder.activityToggle?.let { toggle ->
            toggle.setImageResource(if (message.activityExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
            toggle.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(toggle.context, R.color.text_secondary)
            )
            toggle.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(holder.bindingAdapterPosition)
                    return@setOnClickListener
                }
                val index = messages.indexOfFirst { it.kind == "work" && it.workStartedAt == message.workStartedAt }
                if (index >= 0) {
                    messages[index] = messages[index].copy(activityExpanded = !messages[index].activityExpanded)
                    notifyItemChanged(index)
                }
            }
        }
        if (activeWorkTimestamp == startedAt && running) {
            boundWorkHolder = holder
            boundWorkMessage = message
            uiHandler.removeCallbacks(workTicker)
            uiHandler.post(workTicker)
        } else if (boundWorkHolder === holder) {
            boundWorkHolder = null
            boundWorkMessage = null
            uiHandler.removeCallbacks(workTicker)
        }
    }

    private fun workHeading(message: ChatMessage): String {
        val startedAt = message.workStartedAt ?: message.timestamp
        val finishedAt = message.workFinishedAt
        val seconds = (((finishedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(0L) / 1000L)
        val elapsed = when {
            seconds >= 3600 -> String.format(Locale.getDefault(), "%dh %02dm %02ds", seconds / 3600, (seconds / 60) % 60, seconds % 60)
            seconds >= 60 -> String.format(Locale.getDefault(), "%dm %02ds", seconds / 60, seconds % 60)
            else -> String.format(Locale.getDefault(), "%ds", seconds)
        }
        return when {
            finishedAt == null && message.toolStatus != "done" -> "Working for $elapsed"
            message.toolStatus == "done" -> "Worked for $elapsed"
            else -> "Stopped after $elapsed"
        }
    }

    private fun buildActivityItem(
        anchor: View,
        message: ChatMessage,
        item: ChatActivityItem,
        itemIndex: Int
    ): View {
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val muted = ContextCompat.getColor(context, R.color.text_secondary)
        val primary = ContextCompat.getColor(context, R.color.text_primary)
        val success = ContextCompat.getColor(context, R.color.status_online)
        val error = ContextCompat.getColor(context, R.color.chip_error_fg)

        if (item.type == "changes") return buildFileChangesItem(anchor, message, item, itemIndex)

        if (item.type == "progress") {
            val isLong = item.title.length > COLLAPSIBLE_PROGRESS_MIN_CHARS
            val progressText = (if (item.status == "running") ShimmerTextView(context) else TextView(context)).apply {
                text = item.title
                textSize = 14f
                setTextColor(primary)
                includeFontPadding = false
                setLineSpacing(dp(4).toFloat(), 1f)
                maxLines = if (isLong && !item.expanded) COLLAPSED_PROGRESS_MAX_LINES else Int.MAX_VALUE
                ellipsize = if (isLong && !item.expanded) android.text.TextUtils.TruncateAt.END else null
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            if (progressText is ShimmerTextView) progressText.setShimmerEnabled(true, primary, ContextCompat.getColor(context, R.color.text_tertiary))
            if (!isLong) return progressText

            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(progressText)
                addView(TextView(context).apply {
                    text = if (item.expanded) "Show less⌃" else "Show more⌄"
                    textSize = 12f
                    setTextColor(muted)
                    includeFontPadding = false
                    setPadding(0, dp(6), dp(8), dp(2))
                    isClickable = true
                    isFocusable = true
                    contentDescription = if (item.expanded) "Collapse progress details" else "Expand progress details"
                    setOnClickListener {
                        updateActivityItem(message, itemIndex) { it.copy(expanded = !it.expanded) }
                    }
                })
            }
        }

        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(8), 0, dp(4))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            setImageResource(R.drawable.ic_terminal)
            setColorFilter(muted)
            contentDescription = "Tool activity"
        }
        row.addView(icon)
        val label = (if (item.status == "running") ShimmerTextView(context) else TextView(context)).apply {
            val verb = when (item.status) {
                "running" -> "Running"
                "error" -> "Failed"
                else -> "Ran"
            }
            text = "$verb ${item.title}"
            textSize = 13f
            setTextColor(muted)
            includeFontPadding = false
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (this is ShimmerTextView) setShimmerEnabled(true, muted, primary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        row.addView(label)
        val state = TextView(context).apply {
            text = when (item.status) {
                "running" -> "…"
                "error" -> "!"
                else -> "✓"
            }
            textSize = 14f
            setTextColor(if (item.status == "error") error else success)
            includeFontPadding = false
        }
        row.addView(state)
        block.addView(row)

        val detail = item.detail?.takeIf { it.isNotBlank() }
        if (item.expanded && detail != null) {
            block.addView(TextView(context).apply {
                text = detail
                textSize = 12f
                setTextColor(muted)
                includeFontPadding = false
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(24), dp(6), dp(8), dp(4))
            })
        }
        block.setOnClickListener {
            if (isSelectionMode) return@setOnClickListener
            val index = messages.indexOfFirst { it.kind == "work" && it.workStartedAt == message.workStartedAt }
            if (index < 0) return@setOnClickListener
            val updatedItems = messages[index].activityItems.orEmpty().toMutableList()
            if (itemIndex !in updatedItems.indices) return@setOnClickListener
            updatedItems[itemIndex] = updatedItems[itemIndex].copy(expanded = !updatedItems[itemIndex].expanded)
            messages[index] = messages[index].copy(activityItems = updatedItems)
            notifyItemChanged(index)
        }
        return block
    }

    private fun buildFileChangesItem(
        anchor: View,
        message: ChatMessage,
        item: ChatActivityItem,
        itemIndex: Int,
    ): View {
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val files = item.files.orEmpty()
        val primary = ContextCompat.getColor(context, R.color.text_primary)
        val muted = ContextCompat.getColor(context, R.color.text_secondary)
        val additionsColor = ContextCompat.getColor(context, R.color.status_online)
        val deletionsColor = ContextCompat.getColor(context, R.color.chip_error_fg)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.bg_card))
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
            setPadding(dp(12), dp(6), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(34)
        }
        val title = TextView(context).apply {
            text = "${files.size} file${if (files.size == 1) "" else "s"} changed"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(primary)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)
        val fileLineCounts = files.map { it.lineCounts() }
        if (fileLineCounts.any { it != null }) {
            val additions = fileLineCounts.sumOf { it?.first?.toLong() ?: 0L }
            val deletions = fileLineCounts.sumOf { it?.second?.toLong() ?: 0L }
            header.addView(TextView(context).apply {
                text = "+$additions"
                textSize = 12f
                setTextColor(additionsColor)
                includeFontPadding = false
            })
            header.addView(TextView(context).apply {
                text = " -$deletions"
                textSize = 12f
                setTextColor(deletionsColor)
                includeFontPadding = false
                setPadding(dp(8), 0, dp(6), 0)
            })
        }
        val toggle = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            setImageResource(if (item.expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
            imageTintList = android.content.res.ColorStateList.valueOf(muted)
            contentDescription = if (item.expanded) "Collapse changed files" else "Expand changed files"
        }
        header.addView(toggle)
        card.addView(header)
        header.setOnClickListener {
            updateActivityItem(message, itemIndex) { it.copy(expanded = !it.expanded) }
        }

        if (item.expanded) {
            val visibleCount = if (item.detailsExpanded) files.size else minOf(3, files.size)
            files.take(visibleCount).forEach { file ->
                val fileRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                    isClickable = true
                    isFocusable = true
                    background = android.util.TypedValue().let { value ->
                        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                        ContextCompat.getDrawable(context, value.resourceId)
                    }
                }
                fileRow.setOnClickListener {
                    if (!isSelectionMode) onFileChangeClick?.invoke(file, files)
                }
                fileRow.addView(TextView(context).apply {
                    text = file.path
                    textSize = 12f
                    setTextColor(primary)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                val lineCounts = file.lineCounts()
                if (lineCounts != null) {
                    fileRow.addView(TextView(context).apply {
                        text = "+${lineCounts.first}"
                        textSize = 11f
                        setTextColor(additionsColor)
                        includeFontPadding = false
                    })
                    fileRow.addView(TextView(context).apply {
                        text = " -${lineCounts.second}"
                        textSize = 11f
                        setTextColor(deletionsColor)
                        includeFontPadding = false
                        setPadding(dp(6), 0, 0, 0)
                    })
                } else {
                    fileRow.addView(TextView(context).apply {
                        text = when (file.changeType) {
                            "added" -> "New file"
                            "deleted" -> "Deleted"
                            else -> "Binary"
                        }
                        textSize = 11f
                        setTextColor(muted)
                        includeFontPadding = false
                    })
                }
                card.addView(fileRow)
            }
            if (files.size > 3) {
                val remaining = files.size - visibleCount
                card.addView(TextView(context).apply {
                    text = if (item.detailsExpanded) "Show less⌃" else "Show $remaining more file${if (remaining == 1) "" else "s"}⌄"
                    textSize = 12f
                    setTextColor(muted)
                    includeFontPadding = false
                    setPadding(0, dp(7), 0, dp(3))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        updateActivityItem(message, itemIndex) { it.copy(detailsExpanded = !it.detailsExpanded) }
                    }
                })
            }
        }
        return card
    }

    private fun updateActivityItem(
        message: ChatMessage,
        itemIndex: Int,
        update: (ChatActivityItem) -> ChatActivityItem,
    ) {
        val index = messages.indexOfFirst { it.kind == "work" && it.workStartedAt == message.workStartedAt }
        if (index < 0) return
        val items = messages[index].activityItems.orEmpty().toMutableList()
        if (itemIndex !in items.indices) return
        items[itemIndex] = update(items[itemIndex])
        messages[index] = messages[index].copy(activityItems = items)
        notifyItemChanged(index)
    }
    private fun copyMessage(view: View, content: String) {
        val clipboard = view.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        @Suppress("UsePropertyAccessSyntax")
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Chat message", content))
        android.widget.Toast.makeText(view.context, view.context.getString(R.string.copied), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun userTimestamp(timestamp: Long): String {
        val current = java.util.Calendar.getInstance()
        val message = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
        return if (
            current.get(java.util.Calendar.YEAR) == message.get(java.util.Calendar.YEAR) &&
            current.get(java.util.Calendar.DAY_OF_YEAR) == message.get(java.util.Calendar.DAY_OF_YEAR)
        ) "Today $time" else SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
    }

    private fun attachmentIcon(mimeType: String): String = when {
        mimeType.startsWith("audio/", ignoreCase = true) -> "♫"
        mimeType.startsWith("video/", ignoreCase = true) -> "▶"
        mimeType.startsWith("image/", ignoreCase = true) -> "▧"
        mimeType == "application/pdf" -> "PDF"
        else -> "FILE"
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        var userText: TextView? = null
        var userTimestamp: TextView? = null
        var userAttachmentContainer: LinearLayout? = null
        var assistantText: TextView? = null
        var copyButton: ImageButton? = null
        var statusText: TextView? = null
        var statusProgress: ProgressBar? = null
        var activityContainer: LinearLayout? = null
        var activityToggle: ImageButton? = null
        var assistantMeta: TextView? = null
        var linkPreviewContainer: ViewGroup? = null
        var checkbox: CheckBox? = null
    }
}

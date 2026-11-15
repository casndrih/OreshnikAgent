package com.example.oreshik

import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import java.io.File
import java.security.MessageDigest

/** Bounded before/after snapshots for the private agent workspace. */
internal object WorkspaceChangeTracker {
    internal class Snapshot(
        val rootPath: String,
        val files: Map<String, FileState>,
    )

    internal class FileState(
        val length: Long,
        val modifiedAt: Long,
        val digest: String?,
        val text: String?,
    )

    private const val MAX_FILES = 10_000
    private const val MAX_CONTENT_FILE_BYTES = 512L * 1024L
    private const val MAX_CONTENT_BYTES = 5L * 1024L * 1024L
    private const val MAX_HASH_FILE_BYTES = 2L * 1024L * 1024L
    private const val MAX_HASH_BYTES = 16L * 1024L * 1024L
    private const val DIFF_CONTEXT_LINES = 3
    private const val MAX_DIFF_CHARS_PER_FILE = 96_000
    private const val MAX_DIFF_CHARS_TOTAL = 768_000

    private val ignoredDirectories = setOf(
        ".git",
        ".python-packages",
        ".gradle",
        ".cache",
        "__pycache__",
        "node_modules",
        "build",
        "dist",
    )

    fun capture(workspace: File): Snapshot {
        val root = runCatching { workspace.canonicalFile }.getOrElse { workspace.absoluteFile }
        if (!root.isDirectory) return Snapshot(root.path, emptyMap())
        val files = LinkedHashMap<String, FileState>()
        var retainedContentBytes = 0L
        var hashedBytes = 0L
        val iterator = root.walkTopDown()
            .onEnter { directory -> directory == root || directory.name !in ignoredDirectories }
            .filter { it.isFile }
            .iterator()
        while (iterator.hasNext() && files.size < MAX_FILES) {
            val file = iterator.next()
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: continue
            if (!canonical.path.startsWith(root.path + File.separator)) continue
            val relative = canonical.path.removePrefix(root.path + File.separator)
                .replace(File.separatorChar, '/')
            val length = runCatching { canonical.length() }.getOrDefault(0L)
            val modifiedAt = runCatching { canonical.lastModified() }.getOrDefault(0L)
            val mayRead = length <= MAX_HASH_FILE_BYTES && hashedBytes + length <= MAX_HASH_BYTES
            val bytes = if (mayRead) runCatching { canonical.readBytes() }.getOrNull() else null
            if (bytes != null) hashedBytes += bytes.size
            val text = if (
                bytes != null &&
                length <= MAX_CONTENT_FILE_BYTES &&
                retainedContentBytes + bytes.size <= MAX_CONTENT_BYTES &&
                bytes.none { it == 0.toByte() }
            ) {
                runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
            } else null
            if (text != null) retainedContentBytes += bytes?.size ?: 0
            val digest = bytes?.let { sha256(it) }
            files[relative] = FileState(length, modifiedAt, digest, text)
        }
        return Snapshot(root.path, files)
    }

    fun compare(before: Snapshot, after: Snapshot): List<ChatFileChange> {
        if (before.rootPath != after.rootPath) return emptyList()
        val paths = (before.files.keys + after.files.keys).toSortedSet()
        val changes = ArrayList<ChatFileChange>()
        var remainingDiffChars = MAX_DIFF_CHARS_TOTAL
        for (path in paths) {
            val old = before.files[path]
            val new = after.files[path]
            when {
                old == null && new != null -> {
                    val (diff, reason, cost) = previewDiff(null, new.text, oldIsText = true, newIsText = new.text != null, remainingDiffChars)
                    remainingDiffChars -= cost
                    changes += ChatFileChange(
                        path = path,
                        additions = new.text?.let(::lineCount),
                        deletions = new.text?.let { 0 },
                        changeType = "added",
                        diff = diff,
                        diffUnavailableReason = reason,
                    )
                }
                old != null && new == null -> {
                    val (diff, reason, cost) = previewDiff(old.text, null, oldIsText = old.text != null, newIsText = true, remainingDiffChars)
                    remainingDiffChars -= cost
                    changes += ChatFileChange(
                        path = path,
                        additions = old.text?.let { 0 },
                        deletions = old.text?.let(::lineCount),
                        changeType = "deleted",
                        diff = diff,
                        diffUnavailableReason = reason,
                    )
                }
                old != null && new != null && changed(old, new) -> {
                    val counts = if (old.text != null && new.text != null) lineChanges(old.text, new.text) else null
                    val (diff, reason, cost) = previewDiff(
                        old.text,
                        new.text,
                        oldIsText = old.text != null,
                        newIsText = new.text != null,
                        remainingDiffChars,
                    )
                    remainingDiffChars -= cost
                    changes += ChatFileChange(
                        path = path,
                        additions = counts?.first,
                        deletions = counts?.second,
                        changeType = "modified",
                        diff = diff,
                        diffUnavailableReason = reason,
                    )
                }
            }
        }
        return changes
    }

    private fun previewDiff(
        oldText: String?,
        newText: String?,
        oldIsText: Boolean,
        newIsText: Boolean,
        remainingChars: Int,
    ): Triple<List<ChatDiffLine>?, String?, Int> {
        if (!oldIsText || !newIsText) {
            return Triple(null, "A text preview is unavailable for binary or oversized files.", 0)
        }
        if (remainingChars <= 0) {
            return Triple(null, "Diff preview omitted to keep this chat compact.", 0)
        }
        val lines = createUnifiedDiff(oldText.orEmpty(), newText.orEmpty())
        val cost = lines.sumOf { it.text.length + 24 }
        if (lines.isEmpty()) return Triple(null, "No line-level preview is available for this change.", 0)
        if (cost > minOf(MAX_DIFF_CHARS_PER_FILE, remainingChars)) {
            return Triple(null, "Diff preview is too large to include in this chat.", 0)
        }
        return Triple(lines, null, cost)
    }

    private fun createUnifiedDiff(oldText: String, newText: String): List<ChatDiffLine> {
        val oldRaw = RawText(oldText.toByteArray(Charsets.UTF_8))
        val newRaw = RawText(newText.toByteArray(Charsets.UTF_8))
        val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, oldRaw, newRaw)
        if (edits.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<org.eclipse.jgit.diff.Edit>>()
        edits.forEach { edit ->
            val previousGroup = groups.lastOrNull()
            val previousEdit = previousGroup?.lastOrNull()
            if (previousEdit == null ||
                edit.beginA - previousEdit.endA > DIFF_CONTEXT_LINES * 2 ||
                edit.beginB - previousEdit.endB > DIFF_CONTEXT_LINES * 2
            ) {
                groups += mutableListOf(edit)
            } else {
                previousGroup += edit
            }
        }

        val output = mutableListOf<ChatDiffLine>()
        groups.forEach { group ->
            val first = group.first()
            val last = group.last()
            val startA = (first.beginA - DIFF_CONTEXT_LINES).coerceAtLeast(0)
            val startB = (first.beginB - DIFF_CONTEXT_LINES).coerceAtLeast(0)
            val endA = (last.endA + DIFF_CONTEXT_LINES).coerceAtMost(oldRaw.size())
            val endB = (last.endB + DIFF_CONTEXT_LINES).coerceAtMost(newRaw.size())
            output += ChatDiffLine(
                kind = "hunk",
                text = "@@ -${formatRange(startA, endA - startA)} +${formatRange(startB, endB - startB)} @@",
            )
            var oldIndex = startA
            var newIndex = startB
            group.forEach { edit ->
                while (oldIndex < edit.beginA && newIndex < edit.beginB) {
                    output += ChatDiffLine("context", oldRaw.getString(oldIndex), oldIndex + 1, newIndex + 1)
                    oldIndex++
                    newIndex++
                }
                while (oldIndex < edit.endA) {
                    output += ChatDiffLine("removed", oldRaw.getString(oldIndex), oldIndex + 1, null)
                    oldIndex++
                }
                while (newIndex < edit.endB) {
                    output += ChatDiffLine("added", newRaw.getString(newIndex), null, newIndex + 1)
                    newIndex++
                }
            }
            while (oldIndex < endA && newIndex < endB) {
                output += ChatDiffLine("context", oldRaw.getString(oldIndex), oldIndex + 1, newIndex + 1)
                oldIndex++
                newIndex++
            }
        }
        return output
    }

    private fun formatRange(start: Int, count: Int): String {
        val displayStart = if (count == 0) start else start + 1
        return if (count == 1) displayStart.toString() else "$displayStart,$count"
    }

    private fun changed(old: FileState, new: FileState): Boolean {
        if (old.digest != null && new.digest != null) return old.digest != new.digest
        if (old.text != null && new.text != null) return old.text != new.text
        return old.length != new.length || old.modifiedAt != new.modifiedAt
    }

    private fun lineChanges(old: String, new: String): Pair<Int, Int> {
        if (old == new) return 0 to 0
        return runCatching {
            val edits = HistogramDiff().diff(
                RawTextComparator.DEFAULT,
                RawText(old.toByteArray(Charsets.UTF_8)),
                RawText(new.toByteArray(Charsets.UTF_8)),
            )
            var additions = 0
            var deletions = 0
            edits.forEach { edit ->
                additions += edit.endB - edit.beginB
                deletions += edit.endA - edit.beginA
            }
            additions to deletions
        }.getOrElse {
            lineCount(new) to lineCount(old)
        }
    }

    private fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        val newlines = text.count { it == '\n' }
        return if (text.last() == '\n') newlines else newlines + 1
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

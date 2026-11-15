package com.example.oreshik.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.example.oreshik.ChatAttachment
import com.example.oreshik.agent.core.AgentConfig
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/** Converts private attachment references into model-compatible input parts for one API request. */
object AttachmentRequestBuilder {
    const val REFERENCES_KEY = "_oreshik_attachments"
    private const val MAX_FILE_BYTES = 20L * 1024L * 1024L
    private const val MAX_TEXT_CHARS = 180_000
    private const val MAX_PDF_PAGES = 12
    private const val MAX_VIDEO_FRAMES = 8

    fun addReferences(message: JsonObject, attachments: List<ChatAttachment>): JsonObject = message.apply {
        if (attachments.isNotEmpty()) add(REFERENCES_KEY, com.google.gson.Gson().toJsonTree(attachments))
    }

    /** The stored conversation keeps references; only the outgoing request contains file bytes. */
    fun materialize(
        history: List<JsonObject>,
        context: Context,
        providerId: String,
    ): List<JsonObject> = history.map { stored ->
        val references = stored.get(REFERENCES_KEY)?.takeUnless { it.isJsonNull }?.asJsonArray ?: return@map stored
        val outgoing = stored.deepCopy()
        outgoing.remove(REFERENCES_KEY)
        val content = JsonArray()
        val prompt = stored.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        if (prompt.isNotBlank()) content.add(textPart(prompt))
        references.forEach { element ->
            val attachment = com.google.gson.Gson().fromJson(element, ChatAttachment::class.java)
            val file = resolveAttachmentFile(context, attachment.relativePath)
            require(file.isFile) { "Attached file is no longer available: ${attachment.displayName}" }
            require(file.length() <= MAX_FILE_BYTES) { "${attachment.displayName} is larger than the 20 MB model-input limit." }
            attachmentParts(context, providerId, attachment, file).forEach(content::add)
        }
        require(content.size() > 0) { "The message has no readable text or attachments." }
        outgoing.add("content", content)
        outgoing
    }

    fun needsVision(attachment: ChatAttachment): Boolean = attachment.mimeType.startsWith("image/", true) ||
        attachment.mimeType.startsWith("video/", true) || attachment.mimeType.equals("application/pdf", true) ||
        extension(attachment) in setOf("pdf", "heic", "heif")

    fun needsAudio(attachment: ChatAttachment): Boolean = attachment.mimeType.startsWith("audio/", true) ||
        extension(attachment) in setOf("mp3", "wav", "m4a", "aac", "ogg", "opus", "flac", "3gp", "amr")

    private fun attachmentParts(context: Context, providerId: String, attachment: ChatAttachment, file: File): List<JsonObject> {
        val ext = extension(attachment)
        val mime = attachment.mimeType.ifBlank { mimeFromExtension(ext) }

        if (mime.startsWith("image/", true) || ext in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")) {
            return listOf(textPart("Attached image: ${attachment.displayName}"), imagePart(encodeImage(file)))
        }
        if (mime.startsWith("audio/", true) || ext in setOf("mp3", "wav", "m4a", "aac", "ogg", "opus", "flac", "3gp", "amr")) {
            require(providerId != "chatgpt_plan") {
                "The ChatGPT plan connector does not accept audio attachments yet. Choose an audio-capable Chat Completions provider."
            }
            val (bytes, format) = encodeAudio(file, ext)
            val inputAudio = JsonObject().apply {
                addProperty("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                addProperty("format", format)
            }
            return listOf(textPart("Attached audio: ${attachment.displayName}"), JsonObject().apply {
                addProperty("type", "input_audio")
                add("input_audio", inputAudio)
            })
        }
        if (mime.startsWith("video/", true) || ext in setOf("mp4", "mov", "mkv", "webm", "3gp")) {
            val (frames, durationMs) = videoFrames(file)
            require(frames.isNotEmpty()) { "Could not read video frames from ${attachment.displayName}." }
            return buildList {
                add(textPart("Attached video: ${attachment.displayName} (${durationMs / 1000}s). Review the sampled frames in order."))
                frames.forEach { jpeg -> add(imagePart(jpeg)) }
            }
        }
        if (mime.equals("application/pdf", true) || ext == "pdf") {
            if (providerId == "chatgpt_plan") return listOf(inputFilePart(attachment, file, "application/pdf"))
            val (pages, totalPages) = renderPdf(file)
            require(pages.isNotEmpty()) { "Could not render pages from ${attachment.displayName}." }
            return buildList {
                add(textPart("Attached PDF: ${attachment.displayName}; showing pages 1–${pages.size} of $totalPages."))
                pages.forEach { jpeg -> add(imagePart(jpeg)) }
            }
        }

        if (providerId == "chatgpt_plan" && isSupportedPlanFile(ext)) {
            return listOf(inputFilePart(attachment, file, mime))
        }

        val extracted = extractText(file, ext, mime)
            ?: throw IllegalArgumentException(
                "${attachment.displayName} (${mime.ifBlank { "unknown file type" }}) cannot be read as model input. Attach an image, audio, PDF, text/code file, or DOCX/PPTX/XLSX document."
            )
        val boundedText = if (extracted.length > MAX_TEXT_CHARS) {
            extracted.take(MAX_TEXT_CHARS) + "\n\n[File text truncated at $MAX_TEXT_CHARS characters.]"
        } else extracted
        return listOf(textPart("Attached file: ${attachment.displayName}\n\n$boundedText"))
    }

    private fun resolveAttachmentFile(context: Context, relativePath: String): File {
        val root = AgentConfig.chatAttachmentsRoot(context).canonicalFile
        require(relativePath.isNotBlank() && !relativePath.contains('/') && !relativePath.contains('\\')) {
            "Invalid chat attachment path."
        }
        val file = File(root, relativePath).canonicalFile
        require(file.parentFile == root) { "Attachment path is outside the private chat attachment folder." }
        return file
    }

    private fun textPart(text: String) = JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
    }

    private fun imagePart(jpeg: ByteArray) = JsonObject().apply {
        addProperty("type", "image_url")
        add("image_url", JsonObject().apply {
            addProperty("url", "data:image/jpeg;base64,${Base64.encodeToString(jpeg, Base64.NO_WRAP)}")
        })
    }

    private fun inputFilePart(attachment: ChatAttachment, file: File, mime: String) = JsonObject().apply {
        addProperty("type", "input_file")
        addProperty("filename", attachment.displayName)
        addProperty("file_data", "data:${mime.ifBlank { "application/octet-stream" }};base64,${Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)}")
    }

    private fun encodeImage(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Could not decode image ${file.name}." }
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 1800) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Could not decode image ${file.name}.")
        return bitmap.useBitmap { resized ->
            ByteArrayOutputStream().use { output ->
                check(resized.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "Could not encode image ${file.name}." }
                output.toByteArray()
            }
        }
    }

    private fun renderPdf(file: File): Pair<List<ByteArray>, Int> {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return descriptor.use { fd -> PdfRenderer(fd).use { renderer ->
            val total = renderer.pageCount
            val pages = ArrayList<ByteArray>(min(total, MAX_PDF_PAGES))
            for (index in 0 until min(total, MAX_PDF_PAGES)) {
                renderer.openPage(index).use { page ->
                    val scale = min(1f, 1600f / max(page.width, page.height))
                    val width = max(1, (page.width * scale).toInt())
                    val height = max(1, (page.height * scale).toInt())
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).apply { drawColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    pages += bitmap.useBitmap { image ->
                        ByteArrayOutputStream().use { output ->
                            check(image.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "Could not encode PDF page ${index + 1}." }
                            output.toByteArray()
                        }
                    }
                }
            }
            pages to total
        } }
    }

    private fun videoFrames(file: File): Pair<List<ByteArray>, Long> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val count = MAX_VIDEO_FRAMES.coerceAtMost(max(1, (duration / 2_000L).toInt() + 1))
            val frames = ArrayList<ByteArray>(count)
            for (index in 0 until count) {
                val timeMs = if (count <= 1 || duration <= 0) 0L else duration * index / (count - 1)
                val frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                frames += frame.useBitmap { bitmap ->
                    ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "Could not encode video frame." }
                        output.toByteArray()
                    }
                }
            }
            return frames to duration
        } finally {
            retriever.release()
        }
    }

    private fun encodeAudio(file: File, ext: String): Pair<ByteArray, String> {
        if (ext == "mp3") return file.readBytes() to "mp3"
        if (ext == "wav") return file.readBytes() to "wav"
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio stream found in ${file.name}.")
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME type.")
            extractor.selectTrack(track)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            val pcm = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var loops = 0
            while (!outputDone && loops++ < 100_000) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: error("Audio decoder input buffer unavailable.")
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) Thread.yield()
                    else -> if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (bufferInfo.size > 0 && output != null) {
                            output.position(bufferInfo.offset)
                            output.limit(bufferInfo.offset + bufferInfo.size)
                            appendPcm16(pcm, output.slice(), pcmEncoding)
                            require(pcm.size() <= MAX_FILE_BYTES.toInt()) { "Decoded audio is larger than the 20 MB model-input limit." }
                        }
                        outputDone = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            check(outputDone) { "Audio conversion timed out for ${file.name}." }
            require(pcm.size() > 0) { "No audio samples could be decoded from ${file.name}." }
            val raw = pcm.toByteArray()
            return wavBytes(raw, sampleRate, channels) to "wav"
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    private fun appendPcm16(destination: ByteArrayOutputStream, input: ByteBuffer, encoding: Int) {
        input.order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val chunk = ByteArray(input.remaining())
                input.get(chunk)
                destination.write(chunk)
            }
            AudioFormat.ENCODING_PCM_FLOAT -> while (input.remaining() >= 4) {
                val value = input.float.coerceIn(-1f, 1f)
                val sample = (value * if (value < 0f) 32768 else 32767).toInt()
                destination.write(sample and 0xff)
                destination.write((sample shr 8) and 0xff)
            }
            AudioFormat.ENCODING_PCM_8BIT -> while (input.hasRemaining()) {
                val sample = ((input.get().toInt() and 0xff) - 128) shl 8
                destination.write(sample and 0xff)
                destination.write((sample shr 8) and 0xff)
            }
            else -> throw IllegalArgumentException("The device audio decoder returned an unsupported PCM format.")
        }
    }

    private fun wavBytes(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        require(sampleRate > 0 && channels in 1..2) { "Audio stream has an unsupported sample rate or channel count." }
        val bytesPerSample = 2
        val byteRate = sampleRate * channels * bytesPerSample
        return ByteArrayOutputStream(pcm.size + 44).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII)); output.writeLeInt(pcm.size + 36)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); output.writeLeInt(16)
            output.writeLeShort(1); output.writeLeShort(channels); output.writeLeInt(sampleRate)
            output.writeLeInt(byteRate); output.writeLeShort(channels * bytesPerSample); output.writeLeShort(16)
            output.write("data".toByteArray(Charsets.US_ASCII)); output.writeLeInt(pcm.size); output.write(pcm)
            output.toByteArray()
        }
    }

    private fun ByteArrayOutputStream.writeLeInt(value: Int) {
        write(value and 0xff); write((value shr 8) and 0xff); write((value shr 16) and 0xff); write((value shr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLeShort(value: Int) {
        write(value and 0xff); write((value shr 8) and 0xff)
    }

    private fun extractText(file: File, ext: String, mime: String): String? {
        if (ext in setOf("docx", "pptx", "xlsx", "odt", "ods", "odp")) return extractOfficeText(file, ext)
        if (ext == "rtf") return file.readText(Charsets.UTF_8)
            .replace(Regex("\\\\par\\b ?"), "\n")
            .replace(Regex("\\\\'[0-9a-fA-F]{2}"), " ")
            .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?|[{}]"), " ")
        val textLike = mime.startsWith("text/", true) || ext in setOf(
            "txt", "md", "markdown", "json", "xml", "html", "htm", "csv", "tsv", "yaml", "yml",
            "kt", "java", "kts", "gradle", "properties", "toml", "ini", "cfg", "conf", "py", "js", "ts",
            "tsx", "jsx", "css", "scss", "sql", "sh", "ps1", "bat", "c", "h", "cpp", "hpp", "go", "rs",
            "swift", "rb", "php", "log", "diff", "patch", "tex", "svg",
        )
        if (!textLike) return null
        val bytes = file.readBytes()
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
            else -> bytes.toString(Charsets.UTF_8)
        }
        return if (ext in setOf("html", "htm")) org.jsoup.Jsoup.parse(text).text() else text
    }

    private fun extractOfficeText(file: File, ext: String): String {
        val entries = ZipFile(file).use { zip ->
            val wanted = when (ext) {
                "docx" -> listOf("word/document.xml")
                "pptx" -> zip.entries().asSequence().map { it.name }.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }.sorted().toList()
                "xlsx" -> listOfNotNull("xl/sharedStrings.xml".takeIf { zip.getEntry(it) != null }) +
                    zip.entries().asSequence().map { it.name }.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.sorted().toList()
                else -> listOf("content.xml")
            }
            wanted.mapNotNull { name ->
                zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { name to it.readText() } }
            }
        }
        require(entries.isNotEmpty()) { "No readable document text found in ${file.name}." }
        return entries.joinToString("\n") { (name, xml) ->
            val text = Regex("<(?:\\w+:)?t(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?t>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).joinToString(" ") { it.groupValues[1] }
                .replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            if (ext == "pptx") "$name\n$text" else text
        }.trim()
    }

    private fun isSupportedPlanFile(ext: String) = ext in setOf(
        "pdf", "txt", "md", "json", "xml", "html", "csv", "tsv", "doc", "docx", "rtf", "odt", "ppt", "pptx", "xls", "xlsx",
    )

    private fun extension(attachment: ChatAttachment): String = attachment.displayName.substringAfterLast('.', "").lowercase()

    private fun mimeFromExtension(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "txt", "md", "csv", "tsv", "log", "kt", "java", "py", "js", "ts", "xml", "html", "css", "sql", "sh", "ps1" -> "text/plain"
        else -> "application/octet-stream"
    }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T = try { block(this) } finally { recycle() }
}

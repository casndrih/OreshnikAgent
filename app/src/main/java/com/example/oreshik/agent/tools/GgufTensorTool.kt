package com.example.oreshik.agent.tools

import com.example.oreshik.ai.OfflineModelStore
import com.example.oreshik.agent.core.AgentTool
import com.example.oreshik.agent.core.ToolCategory
import com.example.oreshik.agent.core.ToolContext
import com.example.oreshik.agent.core.ToolDefinition
import com.example.oreshik.agent.core.ToolPermission
import com.example.oreshik.agent.core.ToolResult
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.math.floor

/** Inspect GGUF tensor headers and edit supported tensor elements into a new file. */
class GgufTensorTool : AgentTool {
    override val definition = ToolDefinition(
        name = "gguf.tensor.edit",
        category = ToolCategory.FILESYSTEM,
        description = "Inspect tensors in a saved GGUF model, or set selected elements of an F32, F16, BF16, Q4_0, Q4_1, or Q8_0 tensor. Quantized values snap to the nearest level using the existing block scale. Edits are written to a new GGUF copy.",
        permissions = setOf(ToolPermission.READ_WORKSPACE, ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("list", "edit"), "description" to "List tensor metadata or edit selected tensor elements."),
                "source_path" to mapOf("type" to "string", "description" to "Optional absolute or workspace-relative GGUF path. Defaults to the currently selected saved model."),
                "output_name" to mapOf("type" to "string", "description" to "Optional output filename in the source model's folder; the source is never overwritten."),
                "tensor_name" to mapOf("type" to "string", "description" to "Exact tensor name. Required for edit; optional for list to show one tensor."),
                "indices" to mapOf("type" to "array", "items" to mapOf("type" to "integer"), "description" to "Zero-based flattened tensor indices to change."),
                "values" to mapOf("type" to "array", "items" to mapOf("type" to "number"), "description" to "Finite replacement values. Provide one value to broadcast or one per index."),
                "limit" to mapOf("type" to "integer", "description" to "Maximum tensor rows returned by list; defaults to 100."),
                "offset" to mapOf("type" to "integer", "description" to "Tensor row offset for paginating list results."),
            ),
            "required" to listOf("action"),
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        try {
            val args = arguments.asJsonObject
            val model = resolveSource(context, args.get("source_path")?.takeUnless { it.isJsonNull }?.asString)
                ?: return@withContext ToolResult.failure("No source GGUF was specified or selected.")
            val index = GgufFile.readIndex(model)
            when (args.get("action")?.asString?.lowercase(Locale.ROOT)) {
                "list" -> {
                    val name = args.get("tensor_name")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
                    val limit = (args.get("limit")?.takeUnless { it.isJsonNull }?.asInt ?: 100).coerceIn(1, 500)
                    val offset = (args.get("offset")?.takeUnless { it.isJsonNull }?.asInt ?: 0).coerceAtLeast(0)
                    val matched = if (name.isBlank()) index.tensors else index.tensors.filter { it.name == name }
                    if (name.isNotBlank() && matched.isEmpty()) return@withContext ToolResult.failure("Tensor not found: $name")
                    val page = matched.drop(offset).take(limit)
                    val text = buildString {
                        append("GGUF v${index.version}: ${index.tensors.size} tensors, ")
                        append(OfflineModelStore.formatBytes(model.length())).append('\n')
                        if (page.isEmpty()) append("No tensors at offset $offset.")
                        page.forEach { tensor ->
                            append(tensor.name).append(" | shape=").append(tensor.dimensions.joinToString("×"))
                                .append(" | type=").append(tensor.typeName)
                                .append(" | elements=").append(tensor.elements).append('\n')
                        }
                        val nextOffset = offset.toLong() + page.size
                        if (nextOffset < matched.size) append("Next offset: $nextOffset")
                    }
                    ToolResult.success(text.trimEnd())
                }
                "edit" -> edit(model, index, args)
                else -> ToolResult.failure("action must be 'list' or 'edit'.")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "GGUF tensor operation failed: ${e.message}")
            ToolResult.failure(e.message ?: "GGUF tensor operation failed.")
        }
    }

    private fun resolveSource(context: ToolContext, sourcePath: String?): File? {
        val modelsDir = OfflineModelStore.directory(context.app).canonicalFile
        val requested = sourcePath.orEmpty().trim()
        val model = if (requested.isBlank()) {
            OfflineModelStore.selectedFile(context.app)
        } else {
            val path = File(requested)
            if (path.isAbsolute) path else File(context.workspace, requested)
        } ?: return null
        val canonical = model.canonicalFile
        val workspace = context.workspace.canonicalFile
        require(isWithin(workspace, canonical) || isWithin(modelsDir, canonical)) {
            "Source must be inside this workspace or the app's saved-model folder."
        }
        require(canonical.isFile && canonical.canRead() && canonical.extension.equals("gguf", ignoreCase = true)) {
            "Source GGUF is missing or unreadable: ${canonical.path}"
        }
        return canonical
    }

    private fun edit(model: File, index: GgufIndex, args: com.google.gson.JsonObject): ToolResult {
        val name = args.get("tensor_name")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
        require(name.isNotBlank()) { "tensor_name is required for edit." }
        val tensor = index.tensors.firstOrNull { it.name == name } ?: error("Tensor not found: $name")
        require(tensor.type in SUPPORTED_EDIT_TYPES) {
            "Tensor '$name' uses ${tensor.typeName}. Direct editing supports F32, F16, BF16, Q4_0, Q4_1, and Q8_0. Other quantized types must be converted with llama.cpp first."
        }
        val indices = args.getAsJsonArray("indices")?.map { it.asLong }
            ?: error("indices is required for edit.")
        val values = args.getAsJsonArray("values")?.map { it.asDouble }
            ?: error("values is required for edit.")
        require(indices.isNotEmpty()) { "Provide at least one tensor index." }
        require(values.isNotEmpty() && (values.size == 1 || values.size == indices.size)) {
            "Provide one replacement value or one value for each index."
        }
        require(indices.distinct().size == indices.size) { "indices must not contain duplicates." }
        require(indices.all { it in 0 until tensor.elements }) { "Each index must be between 0 and ${tensor.elements - 1}." }
        require(indices.size <= MAX_EDIT_ELEMENTS) { "A single call can edit at most $MAX_EDIT_ELEMENTS elements." }
        require(values.all { it.isFinite() && it.toFloat().isFinite() }) { "Tensor values must be finite and representable as 32-bit floats." }
        if (tensor.type in QUANTIZED_EDIT_TYPES) {
            require(tensor.elements % QUANT_BLOCK_ELEMENTS == 0L) { "${tensor.typeName} tensor element count must be divisible by $QUANT_BLOCK_ELEMENTS." }
        }

        val elementSize = when (tensor.type) {
            TYPE_F32 -> 4L
            TYPE_F16, TYPE_BF16 -> 2L
            else -> 0L
        }
        if (tensor.type == TYPE_F16) {
            require(values.all { kotlin.math.abs(it) <= 65504.0 }) { "F16 values must be between -65504 and 65504." }
        }
        val suppliedOutputName = args.get("output_name")?.takeUnless { it.isJsonNull }?.asString.orEmpty().trim()
        var outputName = suppliedOutputName.ifBlank { "${model.nameWithoutExtension}-edited-${System.currentTimeMillis()}.gguf" }
        if (!outputName.endsWith(".gguf", ignoreCase = true)) outputName += ".gguf"
        require(outputName.isNotBlank() && outputName != ".gguf" && outputName != "." && outputName != ".." &&
            !outputName.contains('/') && !outputName.contains('\\') && !outputName.contains('\u0000')) {
            "output_name must be a filename, not a path."
        }
        val output = File(model.parentFile, outputName).canonicalFile
        require(output.parentFile == model.parentFile && output != model) {
            "Output must be a new GGUF file in the source model's folder."
        }
        val freeBytes = runCatching { android.os.StatFs(model.parentFile.absolutePath).availableBytes }.getOrDefault(0L)
        require(freeBytes >= model.length() + MIN_FREE_SPACE_BYTES) {
            "Not enough free space for an edited model copy; need about ${OfflineModelStore.formatBytes(model.length() + MIN_FREE_SPACE_BYTES)} free."
        }
        val temporary = File(model.parentFile, ".${output.name}.partial-${java.util.UUID.randomUUID()}")
        require(!output.exists() && !temporary.exists()) { "An output file with that name already exists; retry the edit." }
        return try {
            copyFile(model, temporary)
            RandomAccessFile(temporary, "rw").use { file ->
                indices.forEachIndexed { position, elementIndex ->
                    val value = values[if (values.size == 1) 0 else position]
                    if (tensor.type in QUANTIZED_EDIT_TYPES) {
                        editQuantizedValue(file, index.dataStart, tensor, elementIndex, value.toFloat())
                    } else {
                        val byteOffset = Math.addExact(index.dataStart, Math.addExact(tensor.offset, Math.multiplyExact(elementIndex, elementSize)))
                        file.seek(byteOffset)
                        when (tensor.type) {
                            TYPE_F32 -> file.writeIntLE(java.lang.Float.floatToIntBits(value.toFloat()))
                            TYPE_F16 -> file.writeShortLE(floatToHalf(value.toFloat()))
                            TYPE_BF16 -> {
                                val bits = java.lang.Float.floatToIntBits(value.toFloat())
                                val roundingBias = 0x7fff + ((bits ushr 16) and 1)
                                file.writeShortLE((bits + roundingBias) ushr 16)
                            }
                        }
                    }
                }
                file.fd.sync()
            }
            check(!output.exists() && temporary.renameTo(output)) { "Could not safely finalize edited model copy." }
            ToolResult.success(
                output = buildString {
                    append("Edited ${indices.size} element(s) in '$name'.")
                    if (tensor.type in QUANTIZED_EDIT_TYPES) append(" Quantized values were rounded to the nearest representable levels.")
                    append("\nSaved a new model copy: ${output.absolutePath}\nOriginal preserved: ${model.name}")
                },
                metadata = mapOf(
                    "source_path" to model.absolutePath,
                    "output_path" to output.absolutePath,
                    "tensor_name" to name,
                    "edited_elements" to indices.size,
                    "quantized_values_snapped" to (tensor.type in QUANTIZED_EDIT_TYPES),
                ),
            )
        } catch (e: Exception) {
            temporary.delete()
            output.delete()
            throw e
        }
    }

    private fun copyFile(source: File, destination: File) {
        FileChannel.open(source.toPath()).use { input ->
            FileChannel.open(destination.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { output ->
                var position = 0L
                val size = input.size()
                while (position < size) {
                    val copied = input.transferTo(position, minOf(COPY_CHUNK_BYTES, size - position), output)
                    if (copied <= 0L) {
                        val buffer = ByteBuffer.allocate(64 * 1024)
                        input.position(position)
                        val read = input.read(buffer)
                        check(read > 0) { "Could not copy the complete GGUF model." }
                        buffer.flip()
                        while (buffer.hasRemaining()) output.write(buffer)
                        position += read
                    } else {
                        position += copied
                    }
                }
                output.force(true)
            }
        }
    }

    private fun isWithin(root: File, file: File): Boolean = file.path.startsWith(root.path + File.separator)

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun editQuantizedValue(file: RandomAccessFile, dataStart: Long, tensor: GgufTensor, index: Long, value: Float) {
        val blockIndex = index / QUANT_BLOCK_ELEMENTS
        val withinBlock = (index % QUANT_BLOCK_ELEMENTS).toInt()
        val (blockBytes, scaleOffset, minimumOffset, quantsOffset) = when (tensor.type) {
            TYPE_Q4_0 -> listOf(18L, 0L, -1L, 2L)
            TYPE_Q4_1 -> listOf(20L, 0L, 2L, 4L)
            TYPE_Q8_0 -> listOf(34L, 0L, -1L, 2L)
            else -> error("Unsupported quantized tensor type.")
        }
        val blockStart = Math.addExact(dataStart, Math.addExact(tensor.offset, Math.multiplyExact(blockIndex, blockBytes)))
        file.seek(blockStart + scaleOffset)
        val scale = halfToFloat(file.readUnsignedShortLE())
        require(scale.isFinite()) { "Tensor '${tensor.name}' contains an invalid quantization scale." }
        val min = if (minimumOffset >= 0L) {
            file.seek(blockStart + minimumOffset)
            halfToFloat(file.readUnsignedShortLE())
        } else 0f
        require(min.isFinite()) { "Tensor '${tensor.name}' contains an invalid quantization minimum." }

        when (tensor.type) {
            TYPE_Q4_0, TYPE_Q4_1 -> {
                val code = when {
                    tensor.type == TYPE_Q4_0 && scale == 0f -> 8
                    scale == 0f -> 0
                    tensor.type == TYPE_Q4_0 -> floor((value / scale + 8.5f).toDouble()).toInt().coerceIn(0, 15)
                    else -> floor(((value - min) / scale + 0.5f).toDouble()).toInt().coerceIn(0, 15)
                }
                val byteOffset = blockStart + quantsOffset + (withinBlock % 16)
                file.seek(byteOffset)
                val current = file.readUnsignedByte()
                val updated = if (withinBlock < 16) (current and 0xf0) or code else (current and 0x0f) or (code shl 4)
                file.seek(byteOffset)
                file.write(updated)
            }
            TYPE_Q8_0 -> {
                val quantized = if (scale == 0f) 0 else roundNearestAway(value / scale).coerceIn(-127, 127)
                file.seek(blockStart + quantsOffset + withinBlock)
                file.write(quantized and 0xff)
            }
        }
    }

    private fun RandomAccessFile.readUnsignedShortLE(): Int {
        val low = readUnsignedByte()
        val high = readUnsignedByte()
        return low or (high shl 8)
    }

    private fun halfToFloat(half: Int): Float {
        val sign = if ((half and 0x8000) != 0) -1f else 1f
        val exponent = (half ushr 10) and 0x1f
        val mantissa = half and 0x03ff
        return when (exponent) {
            0 -> sign * mantissa.toFloat() * Math.scalb(1f, -24)
            0x1f -> if (mantissa == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            else -> sign * (1f + mantissa / 1024f) * Math.scalb(1f, exponent - 15)
        }
    }

    private fun roundNearestAway(value: Float): Int =
        if (value >= 0f) floor((value + 0.5f).toDouble()).toInt() else kotlin.math.ceil((value - 0.5f).toDouble()).toInt()

    private fun floatToHalf(value: Float): Int {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exponent = ((bits ushr 23) and 0xff) - 127 + 15
        val mantissa = bits and 0x7fffff
        return when {
            exponent <= 0 -> {
                if (exponent < -10) sign
                else {
                    val shifted = (mantissa or 0x800000) ushr (1 - exponent)
                    sign or ((shifted + 0x1000) ushr 13)
                }
            }
            exponent >= 31 -> sign or 0x7c00
            else -> {
                val rounded = mantissa + 0x1000
                if ((rounded and 0x800000) != 0) {
                    val nextExponent = exponent + 1
                    if (nextExponent >= 31) sign or 0x7c00 else sign or (nextExponent shl 10)
                } else sign or (exponent shl 10) or (rounded ushr 13)
            }
        } and 0xffff
    }

    private companion object {
        const val TAG = "GgufTensorTool"
        const val COPY_CHUNK_BYTES = 8L * 1024L * 1024L
        const val MAX_EDIT_ELEMENTS = 1_000_000
        const val MIN_FREE_SPACE_BYTES = 16L * 1024L * 1024L
        const val TYPE_F32 = 0
        const val TYPE_F16 = 1
        const val TYPE_Q4_0 = 2
        const val TYPE_Q4_1 = 3
        const val TYPE_Q8_0 = 8
        const val TYPE_BF16 = 30
        const val QUANT_BLOCK_ELEMENTS = 32L
        val QUANTIZED_EDIT_TYPES = setOf(TYPE_Q4_0, TYPE_Q4_1, TYPE_Q8_0)
        val SUPPORTED_EDIT_TYPES = setOf(TYPE_F32, TYPE_F16, TYPE_BF16) + QUANTIZED_EDIT_TYPES
    }
}

private data class GgufTensor(
    val name: String,
    val dimensions: List<Long>,
    val type: Int,
    val offset: Long,
    val elements: Long,
) {
    val typeName: String get() = GGUF_TYPE_NAMES[type] ?: "type-$type"
}

private data class GgufIndex(
    val version: Int,
    val dataStart: Long,
    val tensors: List<GgufTensor>,
)

/** Small bounded GGUF v2/v3 header reader; it never loads tensor payloads into memory. */
private object GgufFile {
    private const val MAGIC_GGUF = 0x46554747
    private const val MAX_TENSOR_COUNT = 100_000L
    private const val MAX_METADATA_COUNT = 100_000L
    private const val MAX_ARRAY_ITEMS = 20_000_000L
    private const val MAX_NAME_BYTES = 1024L * 1024L

    fun readIndex(file: File): GgufIndex = RandomAccessFile(file, "r").use { input ->
        require(input.length() >= 24L) { "File is too small to be a GGUF model." }
        require(input.readIntLE() == MAGIC_GGUF) { "Invalid GGUF magic; expected GGUF." }
        val version = input.readIntLE()
        require(version == 2 || version == 3) { "Unsupported GGUF version $version; expected version 2 or 3." }
        val tensorCount = input.readCount("tensor count", MAX_TENSOR_COUNT)
        val metadataCount = input.readCount("metadata count", MAX_METADATA_COUNT)
        var alignment = 32L

        repeat(metadataCount.toInt()) {
            val key = input.readString()
            val valueType = input.readIntLE()
            if (key == "general.alignment" && valueType == 4) {
                alignment = input.readUInt32()
                require(alignment > 0L && alignment <= 4096L && (alignment and (alignment - 1L)) == 0L) {
                    "Invalid GGUF data alignment: $alignment"
                }
            }
            input.skipMetadataValue(valueType)
        }

        val tensors = ArrayList<GgufTensor>(tensorCount.toInt())
        repeat(tensorCount.toInt()) {
            val name = input.readString()
            val dimensionsCount = input.readUInt32().toInt()
            require(dimensionsCount in 1..4) { "Tensor '$name' has unsupported rank $dimensionsCount." }
            val dimensions = List(dimensionsCount) {
                val dimension = input.readUInt64()
                require(dimension > 0L) { "Tensor '$name' has an invalid dimension." }
                dimension
            }
            val type = input.readUInt32().toInt()
            val offset = input.readUInt64()
            val elements = dimensions.fold(1L) { total, dimension -> Math.multiplyExact(total, dimension) }
            tensors += GgufTensor(name, dimensions, type, offset, elements)
        }

        val dataStart = align(input.filePointer, alignment)
        val fileLength = input.length()
        tensors.forEach { tensor ->
            val byteLength = when (tensor.type) {
                0 -> Math.multiplyExact(tensor.elements, 4L) // F32
                1, 30 -> Math.multiplyExact(tensor.elements, 2L) // F16, BF16
                2 -> quantizedByteLength(tensor, 32L, 18L) // Q4_0
                3 -> quantizedByteLength(tensor, 32L, 20L) // Q4_1
                8 -> quantizedByteLength(tensor, 32L, 34L) // Q8_0
                else -> null
            }
            if (byteLength != null) {
                val start = Math.addExact(dataStart, tensor.offset)
                require(start >= dataStart && Math.addExact(start, byteLength) <= fileLength) {
                    "Tensor '${tensor.name}' extends past the end of the GGUF file."
                }
            }
        }
        GgufIndex(version, dataStart, tensors)
    }

    private fun quantizedByteLength(tensor: GgufTensor, blockElements: Long, blockBytes: Long): Long {
        require(tensor.elements % blockElements == 0L) {
            "Tensor '${tensor.name}' has an invalid element count for ${tensor.typeName}."
        }
        return Math.multiplyExact(tensor.elements / blockElements, blockBytes)
    }

    private fun RandomAccessFile.skipMetadataValue(type: Int) {
        when (type) {
            0, 1, 7 -> skipBytesExact(1)
            2, 3 -> skipBytesExact(2)
            4, 5, 6 -> skipBytesExact(4)
            10, 11, 12 -> skipBytesExact(8)
            8 -> skipString()
            9 -> {
                val itemType = readIntLE()
                require(itemType != 9) { "Nested GGUF metadata arrays are invalid." }
                val count = readCount("metadata array size", MAX_ARRAY_ITEMS)
                val fixedWidth = when (itemType) {
                    0, 1, 7 -> 1L
                    2, 3 -> 2L
                    4, 5, 6 -> 4L
                    10, 11, 12 -> 8L
                    else -> 0L
                }
                if (fixedWidth > 0L) skipBytesExact(Math.multiplyExact(count, fixedWidth))
                else if (itemType == 8) repeat(count.toInt()) { skipString() }
                else error("Unsupported GGUF metadata array element type $itemType.")
            }
            else -> error("Unsupported GGUF metadata value type $type.")
        }
    }

    private fun RandomAccessFile.readString(): String {
        val length = readUInt64()
        require(length <= MAX_NAME_BYTES && length <= this.length() - filePointer) { "GGUF header string is too large or truncated." }
        val bytes = ByteArray(length.toInt())
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun RandomAccessFile.skipString() {
        val length = readUInt64()
        require(length <= this.length() - filePointer) { "Truncated GGUF string value." }
        skipBytesExact(length)
    }

    private fun RandomAccessFile.readCount(label: String, maximum: Long): Long {
        val count = readUInt64()
        require(count in 0..maximum) { "GGUF $label $count exceeds the supported limit." }
        return count
    }

    private fun RandomAccessFile.readUInt32(): Long = readIntLE().toLong() and 0xffffffffL

    private fun RandomAccessFile.readUInt64(): Long {
        val low = readIntLE().toLong() and 0xffffffffL
        val high = readIntLE().toLong() and 0xffffffffL
        require(high <= 0x7fffffffL) { "GGUF contains an unsigned value too large for this device." }
        return (high shl 32) or low
    }

    private fun RandomAccessFile.readIntLE(): Int {
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        val b2 = readUnsignedByte()
        val b3 = readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun RandomAccessFile.skipBytesExact(bytes: Long) {
        require(bytes >= 0L && bytes <= length() - filePointer) { "Truncated GGUF header." }
        seek(Math.addExact(filePointer, bytes))
    }

    private fun align(value: Long, alignment: Long): Long {
        val remainder = value % alignment
        return if (remainder == 0L) value else Math.addExact(value, alignment - remainder)
    }
}

private val GGUF_TYPE_NAMES = mapOf(
    0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 6 to "Q5_0", 7 to "Q5_1",
    8 to "Q8_0", 9 to "Q8_1", 10 to "Q2_K", 11 to "Q3_K", 12 to "Q4_K",
    13 to "Q5_K", 14 to "Q6_K", 15 to "Q8_K", 16 to "IQ2_XXS", 17 to "IQ2_XS",
    18 to "IQ3_XXS", 19 to "IQ1_S", 20 to "IQ4_NL", 21 to "IQ3_S", 22 to "IQ2_S",
    23 to "IQ4_XS", 24 to "I8", 25 to "I16", 26 to "I32", 27 to "I64",
    28 to "F64", 29 to "IQ1_M", 30 to "BF16",
)

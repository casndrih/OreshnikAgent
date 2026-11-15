package com.example.oreshik.agent.tools

import android.annotation.SuppressLint
import com.example.oreshik.agent.core.*
import com.example.oreshik.agent.runtime.RuntimeManager
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * IDA Pro integration and built-in ELF / Unity IL2CPP analysis.
 *
 * The public tool classes, constructors, registration names, parameter names and
 * ToolResult conventions in this file are intentionally kept compatible with the
 * application. IDA and radare2 are optional enrichments; the built-in Kotlin
 * parsers do not require Python, r2, a remote IDA host, or external scripts.
 *
 * Process-memory reads remain subject to Android/Linux access controls. This file
 * never escalates privileges automatically and never writes to target process memory.
 */
private val gson = Gson()
private const val MAX_TOOL_OUTPUT = 4 * 1024 * 1024
private const val MAX_JSON_FILE_OUTPUT = 16 * 1024 * 1024
private const val MAX_STRING_RESULTS = 600
private const val MAX_SYMBOL_RESULTS = 1200
private const val MAX_CLASS_RESULTS = 15000
private const val MAX_MEM_SCAN_BYTES = 64L * 1024L * 1024L
private const val ELF_MAGIC = 0x464C457F
private const val IL2CPP_SANITY = 0xFAB11BAF.toInt()

private fun parseJson(text: String): JsonElement = JsonParser.parseString(text)

private fun java.io.Reader.readTextLimited(max: Int): String {
    val buf = CharArray(8192)
    val out = StringBuilder(minOf(max, 32768))
    var total = 0
    var truncated = false
    while (true) {
        val n = read(buf)
        if (n < 0) break
        val take = minOf(n, (max - total).coerceAtLeast(0))
        if (take > 0) out.appendRange(buf, 0, take)
        total += take
        if (take < n) truncated = true
    }
    if (truncated) out.append("\n[output truncated]")
    return out.toString()
}

private fun jsonText(value: JsonElement?, default: String = ""): String =
    runCatching { value?.takeUnless { it.isJsonNull }?.asString ?: default }.getOrDefault(default)

private fun jsonInt(value: JsonElement?, default: Int): Int =
    runCatching { value?.takeUnless { it.isJsonNull }?.asInt ?: default }.getOrDefault(default)

private fun jsonLong(value: JsonElement?, default: Long = 0L): Long =
    runCatching { value?.takeUnless { it.isJsonNull }?.asLong ?: default }.getOrDefault(default)

private fun jsonBool(value: JsonElement?, default: Boolean): Boolean =
    runCatching { value?.takeUnless { it.isJsonNull }?.asBoolean ?: default }.getOrDefault(default)

private fun hex(value: Long): String = "0x" + java.lang.Long.toUnsignedString(value, 16).uppercase(Locale.ROOT)

private fun parseHexOrDec(s: String): Long? {
    val t = s.trim().lowercase(Locale.ROOT).replace("_", "")
    return try {
        when {
            t.startsWith("0x") -> java.lang.Long.parseUnsignedLong(t.substring(2), 16)
            t.startsWith("-0x") -> -java.lang.Long.parseUnsignedLong(t.substring(3), 16)
            else -> t.toLongOrNull()
        }
    } catch (_: Exception) { null }
}

private data class BytePattern(val bytes: ByteArray, val mask: BooleanArray) {
    val size: Int get() = bytes.size
    fun matches(data: ByteArray, at: Int): Boolean {
        if (at < 0 || at + bytes.size > data.size) return false
        for (i in bytes.indices) if (mask[i] && data[at + i] != bytes[i]) return false
        return true
    }
}

/** Supports IDA forms such as "48 8B ?? 11", and compact hex strings. */
private fun parseIdaPattern(text: String): BytePattern? {
    val source = text.trim()
    if (source.isEmpty()) return null
    val tokens = if (source.any { it.isWhitespace() || it == '?' }) {
        source.split(Regex("\\s+")).filter { it.isNotBlank() }
    } else {
        val clean = source.removePrefix("0x").replace(Regex("[^0-9A-Fa-f]"), "")
        if (clean.length % 2 != 0 || clean.isEmpty()) return null
        clean.chunked(2)
    }
    if (tokens.isEmpty() || tokens.size > 4096) return null
    val bytes = ByteArray(tokens.size)
    val mask = BooleanArray(tokens.size)
    for (i in tokens.indices) {
        val token = tokens[i]
        if (token == "?" || token == "??" || token == "**") {
            mask[i] = false
        } else {
            val hexToken = token.removePrefix("0x")
            if (hexToken.length != 2 || hexToken.any { it.digitToIntOrNull(16) == null }) return null
            bytes[i] = hexToken.toInt(16).toByte()
            mask[i] = true
        }
    }
    if (mask.none { it }) return null
    return BytePattern(bytes, mask)
}

private fun parseHexPattern(s: String): ByteArray? {
    val parsed = parseIdaPattern(s) ?: return null
    if (parsed.mask.any { !it }) return null
    return parsed.bytes
}

private fun resolveInputFile(workspace: File, path: String): File? {
    if (path.isBlank()) return null
    return File(workspace, path).takeIf { it.isFile && it.canRead() }
        ?: File(path).takeIf { it.isFile && it.canRead() }
}

private fun resolveOutputFile(workspace: File, path: String): File {
    val f = File(path)
    return if (f.isAbsolute) f else File(workspace, path)
}

private fun writeTextBounded(file: File, text: String, maxBytes: Int = MAX_JSON_FILE_OUTPUT): Boolean {
    return runCatching {
        val parent = file.absoluteFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) return@runCatching false
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > maxBytes) return@runCatching false
        file.writeBytes(bytes)
        true
    }.getOrDefault(false)
}

private fun readAt(file: RandomAccessFile, offset: Long, length: Int): ByteArray? {
    if (offset < 0 || length < 0 || length > 32 * 1024 * 1024) return null
    if (offset > file.length() || length.toLong() > file.length() - offset) return null
    return runCatching {
        val out = ByteArray(length)
        file.seek(offset)
        file.readFully(out)
        out
    }.getOrNull()
}

private fun readStringAt(file: RandomAccessFile, offset: Long, maxLength: Int = 4096): String {
    if (offset < 0 || offset >= file.length()) return ""
    val length = minOf(maxLength.toLong(), file.length() - offset).toInt()
    val bytes = readAt(file, offset, length) ?: return ""
    val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
    return bytes.copyOfRange(0, end).toString(StandardCharsets.UTF_8).replace("\u0000", "")
}

private data class ProcMap(
    val start: Long,
    val end: Long,
    val permissions: String,
    val fileOffset: Long,
    val path: String,
)

private fun parseProcMaps(pid: Int): List<ProcMap> {
    val f = File("/proc/$pid/maps")
    if (!f.isFile || !f.canRead()) return emptyList()
    val out = ArrayList<ProcMap>()
    runCatching {
        f.useLines { lines ->
            lines.take(100000).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 6)
                if (parts.size < 5) return@forEach
                val range = parts[0].split('-', limit = 2)
                if (range.size != 2) return@forEach
                val start = range[0].toLongOrNull(16) ?: return@forEach
                val end = range[1].toLongOrNull(16) ?: return@forEach
                val off = parts[2].toLongOrNull(16) ?: 0L
                out.add(ProcMap(start, end, parts[1], off, parts.getOrNull(5).orEmpty()))
            }
        }
    }
    return out
}

/** Returns ELF load bias (mapping start minus file offset), not merely the RX mapping. */
private fun findModuleBase(pid: Int, moduleName: String): Long? {
    val targetName = moduleName.ifBlank { "libil2cpp.so" }
    val matching = parseProcMaps(pid).filter { it.path.contains(targetName) }
    val selected = matching.firstOrNull { it.fileOffset == 0L }
        ?: matching.firstOrNull { it.permissions.contains('x') }
        ?: matching.firstOrNull()
        ?: return null
    return selected.start - selected.fileOffset
}

private fun findMapForAddress(maps: List<ProcMap>, address: Long): ProcMap? =
    maps.firstOrNull { address >= it.start && address < it.end }

private data class ScanResult(val addresses: List<Long>, val bytesScanned: Long, val error: String? = null)

/** Scans a bounded virtual-memory range. Reads are permission-aware and fail closed. */
private fun scanRegion(memFile: File, start: Long, end: Long, pattern: BytePattern, maxMatches: Int = 1,
                       maxBytes: Long = MAX_MEM_SCAN_BYTES): ScanResult {
    if (start < 0 || end <= start || pattern.size == 0) return ScanResult(emptyList(), 0, "Invalid scan range or pattern")
    if (!memFile.canRead()) return ScanResult(emptyList(), 0, "Process memory is not readable; OS/root/ptrace permissions may be required")
    val hits = ArrayList<Long>()
    val chunkSize = 64 * 1024
    var offset = start
    var scanned = 0L
    return try {
        RandomAccessFile(memFile, "r").use { raf ->
            var carry = ByteArray(0)
            while (offset < end && hits.size < maxMatches && scanned < maxBytes) {
                val request = minOf(chunkSize.toLong(), end - offset, maxBytes - scanned).toInt()
                if (request <= 0) break
                raf.seek(offset)
                val buffer = ByteArray(request)
                val count = raf.read(buffer)
                if (count <= 0) break
                val current = if (count == buffer.size) buffer else buffer.copyOf(count)
                val merged = if (carry.isEmpty()) current else carry + current
                val base = offset - carry.size
                var index = if (carry.isEmpty()) 0 else maxOf(0, carry.size - pattern.size + 1)
                while (index <= merged.size - pattern.size && hits.size < maxMatches) {
                    if (pattern.matches(merged, index)) {
                        hits.add(base + index)
                        index += maxOf(1, pattern.size)
                    } else index++
                }
                val keep = minOf(maxOf(0, pattern.size - 1), merged.size)
                carry = if (keep == 0) ByteArray(0) else merged.copyOfRange(merged.size - keep, merged.size)
                offset += count
                scanned += count
                if (count < request) break
            }
        }
        ScanResult(hits, scanned)
    } catch (e: SecurityException) {
        ScanResult(hits, scanned, "Permission denied reading process memory: ${e.message ?: "access denied"}")
    } catch (e: Exception) {
        ScanResult(hits, scanned, e.message ?: "Memory scan failed")
    }
}

private fun scanFile(file: File, pattern: BytePattern, maxMatches: Int = 64,
                     maxBytes: Long = 128L * 1024L * 1024L): List<Long> {
    if (pattern.size == 0 || !file.isFile || !file.canRead()) return emptyList()
    val result = ArrayList<Long>()
    runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val limit = minOf(raf.length(), maxBytes)
            val chunkSize = 128 * 1024
            var offset = 0L
            var carry = ByteArray(0)
            while (offset < limit && result.size < maxMatches) {
                val size = minOf(chunkSize.toLong(), limit - offset).toInt()
                val data = ByteArray(size)
                raf.seek(offset)
                val n = raf.read(data)
                if (n <= 0) break
                val current = if (n == size) data else data.copyOf(n)
                val merged = if (carry.isEmpty()) current else carry + current
                val base = offset - carry.size
                var i = if (carry.isEmpty()) 0 else maxOf(0, carry.size - pattern.size + 1)
                while (i <= merged.size - pattern.size && result.size < maxMatches) {
                    if (pattern.matches(merged, i)) { result.add(base + i); i += maxOf(1, pattern.size) } else i++
                }
                val keep = minOf(maxOf(0, pattern.size - 1), merged.size)
                carry = if (keep == 0) ByteArray(0) else merged.copyOfRange(merged.size - keep, merged.size)
                offset += n
                if (n < size) break
            }
        }
    }
    return result
}

private fun ByteArray.hexBytes(limit: Int = size): String =
    take(minOf(size, limit)).joinToString(" ") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }

private fun File.nameWithoutExtension(): String =
    name.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: name

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun validRemoteHost(host: String): Boolean = host.length <= 255 &&
        !host.startsWith("-") && host.matches(Regex("[A-Za-z0-9_.@:-]+"))

private data class ProcessOutput(val exitCode: Int, val output: String, val timedOut: Boolean)

private fun runBoundedProcess(command: List<String>, cwd: File, timeoutSeconds: Long,
                              outputLimit: Int = 2_000_000): ProcessOutput {
    val process = ProcessBuilder(command).directory(cwd).redirectErrorStream(true).start()
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    val future = executor.submit<String> { process.inputStream.bufferedReader().use { it.readTextLimited(outputLimit) } }
    val finished = try { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) } catch (e: InterruptedException) {
        Thread.currentThread().interrupt(); false
    }
    if (!finished) {
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        val partial = runCatching { future.get(1, TimeUnit.SECONDS) }.getOrDefault("[process output unavailable]")
        future.cancel(true)
        executor.shutdownNow()
        return ProcessOutput(-1, partial, true)
    }
    val output = runCatching { future.get(2, TimeUnit.SECONDS) }.getOrDefault("[process output unavailable]")
    executor.shutdownNow()
    return ProcessOutput(process.exitValue(), output, false)
}

// ---------------------------------------------------------------------------
// ELF analyzer (pure Kotlin; no native or Python dependency)
// ---------------------------------------------------------------------------

private data class ElfSection(
    val index: Int, val nameOffset: Long, val name: String, val type: Long, val flags: Long,
    val address: Long, val offset: Long, val size: Long, val link: Int, val info: Int, val entrySize: Long,
)

private data class ElfSymbol(
    val name: String, val value: Long, val size: Long, val binding: Int, val type: Int,
    val sectionIndex: Int, val table: String,
) {
    val defined: Boolean get() = sectionIndex != 0
}

private class ElfAnalyzer(private val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val is64: Boolean
    val littleEndian: Boolean
    val elfType: Int
    val machine: Int
    val entryPoint: Long
    private val sectionOffset: Long
    private val sectionEntrySize: Int
    private val sectionNameIndex: Int
    val sections: List<ElfSection>
    val symbols: List<ElfSymbol>

    init {
        require(raf.length() >= 52) { "File is too small to be an ELF image" }
        val ident = readAt(raf, 0, 16) ?: error("Cannot read ELF identification")
        require(ident[0] == 0x7F.toByte() && ident[1] == 'E'.code.toByte() && ident[2] == 'L'.code.toByte() && ident[3] == 'F'.code.toByte()) { "Not an ELF file" }
        is64 = when (ident[4].toInt() and 0xFF) { 1 -> false; 2 -> true; else -> error("Unsupported ELF class") }
        littleEndian = when (ident[5].toInt() and 0xFF) { 1 -> true; 2 -> false; else -> error("Unsupported ELF byte order") }
        val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val headerSize = if (is64) 64 else 52
        val header = readAt(raf, 0, headerSize) ?: error("Incomplete ELF header")
        val bb = ByteBuffer.wrap(header).order(order)
        elfType = bb.getShort(16).toInt() and 0xFFFF
        machine = bb.getShort(18).toInt() and 0xFFFF
        entryPoint = if (is64) bb.getLong(24) else bb.getInt(24).toLong() and 0xFFFFFFFFL
        sectionOffset = if (is64) bb.getLong(40) else bb.getInt(32).toLong() and 0xFFFFFFFFL
        sectionEntrySize = bb.getShort(if (is64) 58 else 46).toInt() and 0xFFFF
        val sectionCount = bb.getShort(if (is64) 60 else 48).toInt() and 0xFFFF
        sectionNameIndex = bb.getShort(if (is64) 62 else 50).toInt() and 0xFFFF
        require(sectionEntrySize >= if (is64) 64 else 40) { "Invalid ELF section header size" }
        require(sectionOffset >= 0 && sectionOffset < raf.length()) { "Invalid ELF section table offset" }
        val count = minOf(sectionCount, 16384)
        val raw = ArrayList<RawSection>(count)
        repeat(count) { i ->
            val off = sectionOffset + i.toLong() * sectionEntrySize
            val bytes = readAt(raf, off, if (is64) 64 else 40) ?: return@repeat
            val h = ByteBuffer.wrap(bytes).order(order)
            raw.add(if (is64) {
                RawSection(i, h.getInt(0).toLong() and 0xFFFFFFFFL, h.getInt(4).toLong() and 0xFFFFFFFFL,
                    h.getLong(8), h.getLong(16), h.getLong(24), h.getLong(32), h.getInt(40), h.getInt(44), h.getLong(56))
            } else {
                RawSection(i, h.getInt(0).toLong() and 0xFFFFFFFFL, h.getInt(4).toLong() and 0xFFFFFFFFL,
                    h.getInt(8).toLong() and 0xFFFFFFFFL, h.getInt(12).toLong() and 0xFFFFFFFFL,
                    h.getInt(16).toLong() and 0xFFFFFFFFL, h.getInt(20).toLong() and 0xFFFFFFFFL,
                    h.getInt(24), h.getInt(28), h.getInt(36).toLong() and 0xFFFFFFFFL)
            })
        }
        val nameRaw = raw.getOrNull(sectionNameIndex)
        val names = if (nameRaw != null && nameRaw.size in 0..raf.length()) readAt(raf, nameRaw.offset, minOf(nameRaw.size, 8L * 1024 * 1024).toInt()) else null
        fun nameAt(offset: Long): String {
            if (names == null || offset < 0 || offset >= names.size) return ""
            var end = offset.toInt()
            while (end < names.size && names[end].toInt() != 0) end++
            return names.copyOfRange(offset.toInt(), end).toString(StandardCharsets.UTF_8)
        }
        sections = raw.map { r -> ElfSection(r.index, r.nameOffset, nameAt(r.nameOffset), r.type, r.flags, r.address, r.offset, r.size, r.link, r.info, r.entrySize) }
        val syms = ArrayList<ElfSymbol>()
        for (section in sections.filter { it.type == 2L || it.type == 11L }) {
            val strSection = sections.getOrNull(section.link) ?: continue
            if (strSection.size <= 0 || strSection.size > 32L * 1024 * 1024) continue
            val strings = readAt(raf, strSection.offset, strSection.size.toInt()) ?: continue
            val defaultEntry = if (is64) 24L else 16L
            val entrySize = section.entrySize.takeIf { it >= defaultEntry } ?: defaultEntry
            val n = minOf(section.size / entrySize, 500000L).toInt()
            for (i in 0 until n) {
                val sym = readAt(raf, section.offset + i.toLong() * entrySize, defaultEntry.toInt()) ?: break
                val b = ByteBuffer.wrap(sym).order(order)
                val nameOff = b.getInt(0).toLong() and 0xFFFFFFFFL
                val binding: Int
                val type: Int
                val shndx: Int
                val value: Long
                val size: Long
                if (is64) {
                    val infoByte = b.get(4).toInt() and 0xFF
                    binding = infoByte ushr 4; type = infoByte and 0xF
                    shndx = b.getShort(6).toInt() and 0xFFFF
                    value = b.getLong(8); size = b.getLong(16)
                } else {
                    value = b.getInt(4).toLong() and 0xFFFFFFFFL
                    size = b.getInt(8).toLong() and 0xFFFFFFFFL
                    val infoByte = b.get(12).toInt() and 0xFF
                    binding = infoByte ushr 4; type = infoByte and 0xF
                    shndx = b.getShort(14).toInt() and 0xFFFF
                }
                val name = cString(strings, nameOff.toInt())
                if (name.isNotBlank()) syms.add(ElfSymbol(name, value, size, binding, type, shndx, if (section.type == 11L) "dynsym" else "symtab"))
            }
        }
        symbols = syms.distinctBy { "${it.name}:${it.value}:${it.sectionIndex}" }.take(500000)
    }

    private data class RawSection(val index: Int, val nameOffset: Long, val type: Long, val flags: Long,
                                  val address: Long, val offset: Long, val size: Long,
                                  val link: Int, val info: Int, val entrySize: Long)

    override fun close() = raf.close()

    fun readSection(section: ElfSection, maxBytes: Int = 16 * 1024 * 1024): ByteArray? {
        if (section.size < 0 || section.size > maxBytes) return null
        return readAt(raf, section.offset, section.size.toInt())
    }

    fun sectionForAddress(address: Long): ElfSection? = sections.firstOrNull {
        it.size > 0 && address >= it.address && address - it.address < it.size
    }

    fun strings(maxResults: Int = MAX_STRING_RESULTS): JsonArray {
        val result = JsonArray()
        val candidates = sections.filter { it.name in setOf(".rodata", ".dynstr", ".strtab", ".data.rel.ro", ".data.rel.ro.local", ".text") }
        val seen = HashSet<String>()
        for (section in candidates) {
            if (result.size() >= maxResults || section.size > 64L * 1024 * 1024) continue
            val data = readSection(section, 64 * 1024 * 1024) ?: continue
            var start = 0
            while (start < data.size && result.size() < maxResults) {
                while (start < data.size && data[start].toInt() == 0) start++
                var end = start
                while (end < data.size && data[end].toInt() != 0 && end - start < 512) {
                    val c = data[end].toInt() and 0xFF
                    if (c < 0x20 || c > 0x7E) break
                    end++
                }
                val length = end - start
                if (length >= 4 && end < data.size && data[end].toInt() == 0) {
                    val value = data.copyOfRange(start, end).toString(StandardCharsets.UTF_8)
                    if (seen.add(value)) {
                        result.add(JsonObject().apply {
                            addProperty("value", value)
                            addProperty("section", section.name)
                            addProperty("file_offset", hex(section.offset + start))
                            addProperty("address", hex(section.address + start))
                        })
                    }
                    start = end + 1
                } else start = maxOf(start + 1, end + 1)
            }
        }
        return result
    }

    fun dynamicStrings(maxResults: Int = MAX_STRING_RESULTS): JsonArray {
        val result = JsonArray()
        val dynstr = sections.firstOrNull { it.name == ".dynstr" } ?: return result
        val data = readSection(dynstr, 32 * 1024 * 1024) ?: return result
        var pos = 0
        while (pos < data.size && result.size() < maxResults) {
            val start = pos
            while (pos < data.size && data[pos].toInt() != 0 && pos - start <= 4096) pos++
            if (pos > start) {
                val s = data.copyOfRange(start, pos).toString(StandardCharsets.UTF_8)
                if (s.any { it.isLetterOrDigit() }) result.add(JsonObject().apply {
                    addProperty("value", s); addProperty("file_offset", hex(dynstr.offset + start)); addProperty("address", hex(dynstr.address + start))
                })
            }
            pos++
        }
        return result
    }

    fun summary(includeStrings: Boolean = true): JsonObject {
        val out = JsonObject()
        out.addProperty("file", file.absolutePath)
        out.addProperty("file_size", file.length())
        out.addProperty("format", "ELF")
        out.addProperty("class", if (is64) "ELF64" else "ELF32")
        out.addProperty("endianness", if (littleEndian) "little" else "big")
        out.addProperty("type", when (elfType) { 1 -> "REL"; 2 -> "EXEC"; 3 -> "DYN"; 4 -> "CORE"; else -> "UNKNOWN($elfType)" })
        out.addProperty("machine", machineName(machine))
        out.addProperty("machine_id", machine)
        out.addProperty("entry_point", hex(entryPoint))
        val sectionArray = JsonArray()
        sections.take(512).forEach { s -> sectionArray.add(JsonObject().apply {
            addProperty("index", s.index); addProperty("name", s.name); addProperty("type", s.type)
            addProperty("flags", hex(s.flags)); addProperty("address", hex(s.address)); addProperty("offset", hex(s.offset))
            addProperty("size", s.size); addProperty("entry_size", s.entrySize); addProperty("link", s.link)
        }) }
        out.add("sections", sectionArray)
        out.addProperty("section_count", sections.size)
        out.addProperty("symbol_count", symbols.size)
        val symbolArray = JsonArray()
        symbols.asSequence().filter { it.defined }.sortedBy { it.value }.take(MAX_SYMBOL_RESULTS).forEach { s -> symbolArray.add(JsonObject().apply {
            addProperty("name", s.name); addProperty("value", hex(s.value)); addProperty("size", s.size)
            addProperty("binding", s.binding); addProperty("type", s.type); addProperty("table", s.table); addProperty("section_index", s.sectionIndex)
        }) }
        out.add("symbols", symbolArray)
        val interesting = symbols.filter { it.name.contains("il2cpp", true) || it.name.contains("codegen", true) || it.name.contains("metadata", true) || it.name.contains("unity", true) }
            .take(300)
        out.add("unity_il2cpp_symbols", gson.toJsonTree(interesting.map { mapOf("name" to it.name, "value" to hex(it.value), "size" to it.size, "defined" to it.defined) }))
        val dynamic = dynamicStrings()
        out.add("dynamic_strings", dynamic)
        if (includeStrings) out.add("strings", strings())
        return out
    }

    fun symbol(name: String): List<ElfSymbol> = symbols.filter { it.name == name || it.name.substringAfterLast('@') == name }

    fun approximateXrefs(address: Long, max: Int = 200): JsonArray {
        val result = JsonArray()
        val raw = address
        val candidates = if (is64) {
            listOf(ByteBuffer.allocate(8).order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).putLong(raw).array())
        } else {
            listOf(ByteBuffer.allocate(4).order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).putInt(raw.toInt()).array())
        }
        val ptrPattern = BytePattern(candidates[0], BooleanArray(candidates[0].size) { true })
        for (hit in scanFile(file, ptrPattern, max, 128L * 1024L * 1024L)) {
            val section = sections.firstOrNull { hit >= it.offset && hit - it.offset < it.size }
            result.add(JsonObject().apply {
                addProperty("file_offset", hex(hit)); addProperty("section", section?.name ?: "unknown")
                addProperty("address", hex((section?.address ?: 0L) + (hit - (section?.offset ?: hit))))
                addProperty("kind", "raw pointer-byte match; semantic code xref not proven")
            })
        }
        return result
    }

    private fun cString(data: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= data.size) return ""
        var end = offset
        while (end < data.size && data[end].toInt() != 0 && end - offset < 4096) end++
        return data.copyOfRange(offset, end).toString(StandardCharsets.UTF_8)
    }

    private fun machineName(id: Int): String = when (id) {
        3 -> "x86"; 8 -> "MIPS"; 20 -> "PPC"; 21 -> "PPC64"; 40 -> "ARM"; 62 -> "x86_64"
        183 -> "AArch64"; 243 -> "RISC-V"; 247 -> "BPF"; else -> "unknown"
    }
}

private fun analyzeElfFile(file: File, script: String = "elf_analyze", args: String = ""): JsonObject {
    ElfAnalyzer(file).use { elf ->
        val out = elf.summary(includeStrings = script == "unity_mono" || script == "string_xref" || script == "il2cpp_dump" || script == "analysis_pipeline")
        out.addProperty("analysis_engine", "built-in Kotlin ELF analyzer")
        out.addProperty("script", script)
        val argAddress = Regex("--address\\s+(0x[0-9A-Fa-f]+|[0-9]+)").find(args)?.groupValues?.get(1)?.let(::parseHexOrDec)
        val symbolQuery = Regex("--symbol\\s+([^\\s]+)").find(args)?.groupValues?.get(1)
        if (!symbolQuery.isNullOrBlank()) {
            out.add("symbol_resolution", gson.toJsonTree(elf.symbols.filter { it.name == symbolQuery || it.name.contains(symbolQuery, true) }.take(256).map {
                mapOf("name" to it.name, "address" to hex(it.value), "size" to it.size, "defined" to it.defined, "table" to it.table)
            }))
        }
        val sectionQuery = Regex("--section\\s+([^\\s]+)").find(args)?.groupValues?.get(1)
        if (!sectionQuery.isNullOrBlank()) {
            out.add("section_resolution", gson.toJsonTree(elf.sections.filter { it.name == sectionQuery || it.name.contains(sectionQuery, true) }.take(128).map {
                mapOf("name" to it.name, "address" to hex(it.address), "file_offset" to hex(it.offset), "size" to it.size, "flags" to hex(it.flags))
            }))
        }
        when (script) {
            "analysis_pipeline" -> {
                out.add("pipeline_steps", gson.toJsonTree(listOf(
                    mapOf("step" to "ELF header and architecture", "status" to "complete"),
                    mapOf("step" to "section table and address mapping", "status" to "complete"),
                    mapOf("step" to "static/dynamic symbol tables", "status" to "complete"),
                    mapOf("step" to "dynamic strings and printable strings", "status" to "complete"),
                    mapOf("step" to "IL2CPP/code-generation indicators", "status" to "heuristic; full metadata parser requires global-metadata.dat"),
                    mapOf("step" to "semantic control-flow/xref analysis", "status" to "requires installed IDA or radare2")
                )))
                out.add("analysis_recommendations", gson.toJsonTree(listOf(
                    "Use re.il2cpp.metadata with global-metadata.dat to parse managed types/methods/fields/images/string literals",
                    "Use --symbol NAME to resolve symbols or --section NAME to resolve ELF sections",
                    "Use re.il2cpp.resolve with a PID for mapped-memory verification"
                )))
            }
            "string_xref" -> {
                if (argAddress != null) out.add("xrefs", elf.approximateXrefs(argAddress, Regex("--max\\s+(\\d+)").find(args)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 1000) ?: 200))
            }
            "hex_pattern" -> {
                val valuesArg = Regex("--values\\s+(.+?)(?:\\s+--[A-Za-z_-]+|$)").find(args)?.groupValues?.get(1).orEmpty()
                val hits = JsonArray()
                valuesArg.split(',').take(256).forEach { raw ->
                    val value = raw.trim().trim('"', '\'')
                    if (value.isEmpty()) return@forEach
                    val number = parseHexOrDec(value)
                    val mapped = number?.let { elf.sectionForAddress(it) }
                    val pattern = parseIdaPattern(value)
                    val matches = if (pattern != null && pattern.mask.all { it } && pattern.size in 1..128) scanFile(file, pattern, 32) else emptyList()
                    hits.add(JsonObject().apply {
                        addProperty("input", value)
                        if (number != null) { addProperty("numeric_value", hex(number)); addProperty("mapped_section", mapped?.name ?: "not mapped to a section") }
                        add("file_matches", gson.toJsonTree(matches.map(::hex)))
                    })
                }
                out.add("identified_values", hits)
            }
            "offset_calc" -> {
                if (argAddress != null) out.add("offset_calculation", JsonObject().apply {
                    addProperty("input_address", hex(argAddress)); addProperty("image_relative_address", hex(argAddress))
                    addProperty("note", "Built-in ELF parsing reports ELF virtual addresses; runtime load bias must be taken from /proc/PID/maps.")
                })
            }
            "struct_recovery" -> {
                out.add("structure_recovery", JsonObject().apply {
                    addProperty("status", "static heuristic")
                    addProperty("note", "ELF section/symbol information collected. Full C++ type recovery requires IDA/r2 analysis data.")
                    add("candidate_symbols", gson.toJsonTree(elf.symbols.filter { it.name.contains("vtable", true) || it.name.contains("typeinfo", true) || it.name.contains("rgctx", true) }.take(500).map { mapOf("name" to it.name, "address" to hex(it.value)) }))
                })
            }
            "il2cpp_dump" -> {
                out.add("il2cpp_native_indicators", gson.toJsonTree(elf.symbols.filter { it.name.contains("il2cpp", true) || it.name.contains("CodeGen", true) || it.name.contains("Metadata", true) }.take(800).map { mapOf("name" to it.name, "address" to hex(it.value), "size" to it.size) }))
                out.addProperty("metadata_note", "Native ELF information only. Provide global-metadata.dat to re.il2cpp.metadata for managed type/method parsing.")
            }
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// IL2CPP metadata file parsing (version-aware, bounded and best effort)
// ---------------------------------------------------------------------------

private data class MetadataTable(val name: String, val offset: Long, val byteCount: Long, val headerIndex: Int)

private class Il2cppMetadataParser(private val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val version: Int
    private val tables = LinkedHashMap<String, MetadataTable>()
    private val tableNames = listOf(
        "string_literals", "string_literal_data", "strings", "events", "properties", "methods",
        "parameter_default_values", "field_default_values", "default_value_data", "field_marshaled_sizes",
        "parameters", "fields", "generic_parameters", "generic_parameter_constraints", "generic_containers",
        "nested_types", "interfaces", "vtable_methods", "interface_offsets", "type_definitions", "images",
        "assemblies", "metadata_usage_lists", "metadata_usage_pairs", "field_refs", "referenced_assemblies",
        "attributes_info", "attribute_types", "unresolved_virtual_call_parameter_types",
        "unresolved_virtual_call_parameter_ranges", "windows_runtime_type_names", "exported_type_definitions",
        "windows_runtime_strings", "rgctx_entries", "rgctx_ranges", "metadata_registration"
    )

    init {
        require(raf.length() >= 8) { "Metadata file is too small" }
        val header = readAt(raf, 0, minOf(4096L, raf.length()).toInt()) ?: error("Unable to read metadata header")
        val magicLe = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(magicLe == IL2CPP_SANITY) { "Invalid IL2CPP metadata sanity value (expected 0xFAB11BAF)" }
        version = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(version in 16..100) { "Unsupported IL2CPP metadata version: $version" }
        val maxPairs = minOf((header.size - 8) / 8, tableNames.size)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until maxPairs) {
            val offset = bb.getInt(8 + i * 8).toLong() and 0xFFFFFFFFL
            val size = bb.getInt(12 + i * 8).toLong() and 0xFFFFFFFFL
            if (offset <= raf.length() && size <= raf.length() && offset + size >= offset && offset + size <= raf.length()) {
                tables[tableNames[i]] = MetadataTable(tableNames[i], offset, size, i)
            }
        }
    }

    override fun close() = raf.close()

    fun parse(libFile: File?, includeMethods: Boolean, includeFields: Boolean, namespaceFilter: String): JsonObject {
        val output = JsonObject()
        output.addProperty("format", "Unity IL2CPP metadata")
        output.addProperty("metadata_file", file.absolutePath)
        output.addProperty("metadata_size", file.length())
        output.addProperty("metadata_version", version)
        output.addProperty("analysis_engine", "built-in Kotlin metadata parser")
        output.addProperty("parsing_mode", "best-effort; layouts vary by Unity/metadata version")
        val tableJson = JsonArray()
        tables.values.forEach { t -> tableJson.add(JsonObject().apply {
            addProperty("name", t.name); addProperty("offset", hex(t.offset)); addProperty("byte_count", t.byteCount); addProperty("header_index", t.headerIndex)
        }) }
        output.add("tables", tableJson)

        val stringTable = tables["strings"]
        fun readManagedString(index: Long): String {
            if (index < 0 || index > Int.MAX_VALUE || stringTable == null) return ""
            val absolute = stringTable.offset + index
            if (absolute < stringTable.offset || absolute >= stringTable.offset + stringTable.byteCount) return ""
            return readStringAt(raf, absolute, 4096)
        }

        val literals = JsonArray()
        val literalTable = tables["string_literals"]
        val literalData = tables["string_literal_data"]
        if (literalTable != null && literalData != null && literalTable.byteCount >= 8) {
            val count = minOf(literalTable.byteCount / 8, 20000L).toInt()
            repeat(count) { i ->
                val bytes = readAt(raf, literalTable.offset + i * 8L, 8) ?: return@repeat
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val length = b.int.toLong() and 0xFFFFFFFFL
                val dataIndex = b.int.toLong() and 0xFFFFFFFFL
                if (length <= 0 || length > 4096 || dataIndex > literalData.byteCount || length > literalData.byteCount - dataIndex) return@repeat
                val data = readAt(raf, literalData.offset + dataIndex, length.toInt()) ?: return@repeat
                val decoded = data.toString(StandardCharsets.UTF_8).take(512)
                literals.add(JsonObject().apply { addProperty("index", i); addProperty("length", length); addProperty("value", decoded) })
            }
        }
        output.add("string_literals", literals)
        output.addProperty("string_literal_count_reported", literalTable?.byteCount?.div(8) ?: 0)

        val fieldTable = tables["fields"]
        val methodTable = tables["methods"]
        val typeTable = tables["type_definitions"]
        val imageTable = tables["images"]
        val fieldRecordSize = chooseRecordSize(fieldTable?.byteCount ?: 0L, listOf(16, 12))
        val methodRecordSize = chooseRecordSize(methodTable?.byteCount ?: 0L, listOf(56, 52, 48, 44))
        val typeRecordSize = chooseRecordSize(typeTable?.byteCount ?: 0L, if (version >= 29) listOf(100, 104, 96, 92, 88, 84, 80) else listOf(100, 96, 92, 88, 84, 80))
        val typeCount = if (typeTable == null || typeRecordSize <= 0) 0 else minOf(typeTable.byteCount / typeRecordSize, MAX_CLASS_RESULTS.toLong()).toInt()
        val fieldsCount = if (fieldTable == null || fieldRecordSize <= 0) 0 else minOf(fieldTable.byteCount / fieldRecordSize, 500000L).toInt()
        val methodsCount = if (methodTable == null || methodRecordSize <= 0) 0 else minOf(methodTable.byteCount / methodRecordSize, 500000L).toInt()
        val parsedFields = HashMap<Int, Pair<String, Long>>()
        if (includeFields && fieldTable != null && fieldRecordSize >= 12) {
            repeat(fieldsCount) { index ->
                val bytes = readAt(raf, fieldTable.offset + index.toLong() * fieldRecordSize, fieldRecordSize) ?: return@repeat
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val nameIndex = b.int.toLong() and 0xFFFFFFFFL
                val typeIndex = b.int.toLong() and 0xFFFFFFFFL
                parsedFields[index] = readManagedString(nameIndex) to typeIndex
            }
        }
        val parsedMethods = HashMap<Int, JsonObject>()
        if (includeMethods && methodTable != null && methodRecordSize >= 48) {
            repeat(methodsCount) { index ->
                val bytes = readAt(raf, methodTable.offset + index.toLong() * methodRecordSize, methodRecordSize) ?: return@repeat
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val nameIndex = b.getInt(0).toLong() and 0xFFFFFFFFL
                val declaringType = b.getInt(4)
                val returnType = b.getInt(8)
                val parameterStart = b.getInt(12)
                val genericContainerIndex = b.getInt(20)
                val methodIndex = b.getInt(24)
                val invokerIndex = b.getInt(28)
                val token = if (methodRecordSize >= 48) b.getInt(44).toLong() and 0xFFFFFFFFL else 0L
                val flags = if (methodRecordSize >= 52) b.getShort(48).toInt() and 0xFFFF else 0
                val slot = if (methodRecordSize >= 56) b.getShort(52).toInt() and 0xFFFF else -1
                val parameterCount = if (methodRecordSize >= 56) b.getShort(54).toInt() and 0xFFFF else -1
                parsedMethods[index] = JsonObject().apply {
                    addProperty("index", index); addProperty("name", readManagedString(nameIndex))
                    addProperty("declaring_type_index", declaringType); addProperty("return_type_index", returnType)
                    addProperty("parameter_start", parameterStart); addProperty("generic_container_index", genericContainerIndex)
                    addProperty("method_index", methodIndex); addProperty("invoker_index", invokerIndex)
                    addProperty("token", hex(token)); addProperty("flags", flags); addProperty("slot", slot)
                    addProperty("parameter_count", parameterCount)
                    // methodIndex is an index into runtime code-registration data, not a byte RVA.
                    // Do not mislabel it as an offset: native pointers need symbols or runtime tables.
                    addProperty("offset", -1L)
                    addProperty("rva", "UNRESOLVED")
                    addProperty("signature", "")
                }
            }
        }

        val classes = JsonArray()
        var totalTypes = 0
        var emittedTypes = 0
        if (typeTable != null && typeRecordSize >= 80) {
            totalTypes = (typeTable.byteCount / typeRecordSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            for (index in 0 until typeCount) {
                val bytes = readAt(raf, typeTable.offset + index.toLong() * typeRecordSize, typeRecordSize) ?: continue
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val nameIndex = b.getInt(0).toLong() and 0xFFFFFFFFL
                val namespaceIndex = b.getInt(4).toLong() and 0xFFFFFFFFL
                val parentIndex = b.getInt(20)
                val flags = b.getInt(40).toLong() and 0xFFFFFFFFL
                val fieldStart = b.getInt(44).toLong() and 0xFFFFFFFFL
                val methodStart = b.getInt(48).toLong() and 0xFFFFFFFFL
                val eventStart = b.getInt(52).toLong() and 0xFFFFFFFFL
                val propertyStart = b.getInt(56).toLong() and 0xFFFFFFFFL
                val nestedStart = b.getInt(60).toLong() and 0xFFFFFFFFL
                val vtableStart = b.getInt(68).toLong() and 0xFFFFFFFFL
                val methodCount = if (typeRecordSize >= 80) b.getShort(76).toInt() and 0xFFFF else 0
                val propertyCount = if (typeRecordSize >= 80) b.getShort(78).toInt() and 0xFFFF else 0
                val fieldCount = if (typeRecordSize >= 82) b.getShort(80).toInt() and 0xFFFF else 0
                val eventCount = if (typeRecordSize >= 84) b.getShort(82).toInt() and 0xFFFF else 0
                val vtableCount = if (typeRecordSize >= 88) b.getShort(86).toInt() and 0xFFFF else 0
                val typeName = readManagedString(nameIndex)
                val ns = readManagedString(namespaceIndex)
                if (typeName.isBlank()) continue
                if (namespaceFilter.isNotBlank() && !"$ns.$typeName".contains(namespaceFilter, ignoreCase = true) && !ns.contains(namespaceFilter, ignoreCase = true)) continue
                val fullName = if (ns.isBlank()) typeName else "$ns.$typeName"
                val entry = JsonObject().apply {
                    addProperty("index", index); addProperty("name", typeName); addProperty("namespace", ns); addProperty("full_name", fullName)
                    addProperty("parent_type_index", parentIndex); addProperty("flags", hex(flags))
                    addProperty("field_start", fieldStart); addProperty("method_start", methodStart)
                    addProperty("event_start", eventStart); addProperty("property_start", propertyStart)
                    addProperty("nested_type_start", nestedStart); addProperty("vtable_start", vtableStart)
                    addProperty("field_count", fieldCount); addProperty("method_count", methodCount)
                    addProperty("event_count", eventCount); addProperty("property_count", propertyCount); addProperty("vtable_count", vtableCount)
                    if (includeFields) {
                        val fieldArray = JsonArray()
                        for (j in 0 until minOf(fieldCount, 512)) {
                            val fIndex = fieldStart + j
                            if (fIndex > Int.MAX_VALUE) break
                            val f = parsedFields[fIndex.toInt()] ?: continue
                            fieldArray.add(JsonObject().apply {
                                addProperty("index", fIndex); addProperty("name", f.first); addProperty("type_index", f.second)
                                addProperty("offset", -1); addProperty("offset_status", "not stored in portable metadata; runtime layout needed")
                            })
                        }
                        add("fields", fieldArray)
                    }
                    if (includeMethods) {
                        val methodArray = JsonArray()
                        for (j in 0 until minOf(methodCount, 512)) {
                            val mIndex = methodStart + j
                            if (mIndex > Int.MAX_VALUE) break
                            parsedMethods[mIndex.toInt()]?.let { methodArray.add(it.deepCopy()) }
                        }
                        add("methods", methodArray)
                    }
                }
                classes.add(entry)
                emittedTypes++
                if (classes.size() >= MAX_CLASS_RESULTS) break
            }
        }
        output.add("classes", classes)
        output.addProperty("class_count", emittedTypes)
        output.addProperty("class_count_in_table", totalTypes)
        output.addProperty("types_truncated", totalTypes > emittedTypes)
        output.addProperty("method_record_size", methodRecordSize)
        output.addProperty("field_record_size", fieldRecordSize)
        output.addProperty("type_definition_record_size", typeRecordSize)
        output.addProperty("method_records_parsed", parsedMethods.size)
        output.addProperty("field_records_parsed", parsedFields.size)

        val images = parseImages(imageTable, ::readManagedString)
        output.add("images", images)
        output.addProperty("image_count", images.size())
        val assemblies = tables["assemblies"]
        output.addProperty("assemblies_table_bytes", assemblies?.byteCount ?: 0)
        output.addProperty("assemblies_count_estimate", (assemblies?.byteCount ?: 0L) / 64L)
        if (libFile != null && libFile.isFile) {
            runCatching {
                ElfAnalyzer(libFile).use { elf ->
                    val il2cppSymbols = elf.symbols.filter { it.name.contains("il2cpp", true) || it.name.contains("CodeGen", true) || it.name.contains("MetadataRegistration", true) || it.name.contains("CodeRegistration", true) }
                    output.add("native_registration_candidates", gson.toJsonTree(il2cppSymbols.take(1000).map { mapOf("name" to it.name, "address" to hex(it.value), "size" to it.size, "defined" to it.defined) }))
                    output.addProperty("native_binary_architecture", elf.summary(false)["machine"]?.asString ?: "unknown")
                    output.addProperty("native_symbol_count", elf.symbols.size)
                    // Associate exported/mangled native symbols with methods when class and method
                    // names are both present. These remain candidates until runtime mapping verifies them.
                    val classArray = output.getAsJsonArray("classes")
                    classArray?.forEach classLoop@{ classElement ->
                        val classObject = runCatching { classElement.asJsonObject }.getOrNull() ?: return@classLoop
                        val classShort = readManagedStringSafe(classObject, "name")
                        val fullName = readManagedStringSafe(classObject, "full_name").replace('.', '_').replace('+', '_')
                        val methodArray = classObject.getAsJsonArray("methods") ?: return@classLoop
                        methodArray.forEach methodLoop@{ methodElement ->
                            val methodObject = runCatching { methodElement.asJsonObject }.getOrNull() ?: return@methodLoop
                            val methodName = jsonText(methodObject["name"])
                            if (methodName.isBlank()) return@methodLoop
                            val candidates = elf.symbols.filter { symbol ->
                                symbol.defined && symbol.type == 2 && symbol.value != 0L && symbol.name.contains(methodName, true) &&
                                        (symbol.name.contains(classShort, true) || (fullName.length > classShort.length && symbol.name.contains(fullName, true)))
                            }.take(8)
                            if (candidates.isNotEmpty()) {
                                methodObject.add("native_symbol_candidates", gson.toJsonTree(candidates.map { mapOf("name" to it.name, "rva_candidate" to hex(it.value), "size" to it.size, "table" to it.table) }))
                                if (candidates.size == 1 && elf.elfType == 3) methodObject.addProperty("native_symbol_rva_candidate", hex(candidates[0].value))
                            }
                        }
                    }
                }
            }.onFailure { output.addProperty("native_binary_analysis_error", it.message ?: "ELF parsing failed") }
        }
        output.add("code_generation_modules", findCodegenIndicators(libFile))
        output.addProperty("runtime_method_pointers", "Static metadata contains method indices, not necessarily runtime pointers. Resolve addresses using module load bias, symbols, or a live runtime dump.")
        return output
    }


    private fun readManagedStringSafe(obj: JsonObject, key: String): String = jsonText(obj[key])

    private fun chooseRecordSize(byteCount: Long, candidates: List<Int>): Int {
        if (byteCount <= 0) return candidates.firstOrNull() ?: 0
        return candidates.firstOrNull { byteCount % it == 0L } ?: candidates.lastOrNull() ?: 0
    }

    private fun parseImages(table: MetadataTable?, readManagedString: (Long) -> String): JsonArray {
        val arr = JsonArray()
        if (table == null) return arr
        // ImageDefinition is typically 40 bytes in v24+, with older compact layouts also seen.
        val recordSize = chooseRecordSize(table.byteCount, listOf(40, 32, 36, 28))
        if (recordSize <= 0) return arr
        val count = minOf(table.byteCount / recordSize, 10000L).toInt()
        repeat(count) { i ->
            val data = readAt(raf, table.offset + i.toLong() * recordSize, recordSize) ?: return@repeat
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val nameIndex = b.getInt(0).toLong() and 0xFFFFFFFFL
            val assemblyIndex = b.getInt(4)
            val typeStart = b.getInt(8)
            val typeCount = b.getInt(12)
            val exportedStart = if (recordSize >= 24) b.getInt(16) else -1
            val exportedCount = if (recordSize >= 24) b.getInt(20) else -1
            val entryPoint = if (recordSize >= 28) b.getInt(24) else -1
            arr.add(JsonObject().apply {
                addProperty("index", i); addProperty("name", readManagedString(nameIndex)); addProperty("assembly_index", assemblyIndex)
                addProperty("type_start", typeStart); addProperty("type_count", typeCount)
                addProperty("exported_type_start", exportedStart); addProperty("exported_type_count", exportedCount)
                addProperty("entry_point_index", entryPoint)
            })
        }
        return arr
    }

    private fun findCodegenIndicators(libFile: File?): JsonArray {
        val arr = JsonArray()
        if (libFile == null || !libFile.isFile) return arr
        runCatching {
            ElfAnalyzer(libFile).use { elf ->
                elf.symbols.filter {
                    it.name.contains("CodeGenModule") || it.name.contains("CodeRegistration") || it.name.contains("MetadataRegistration") ||
                            it.name.contains("il2cpp_codegen_register") || it.name.contains("g_CodeGenModules")
                }.take(500).forEach { s -> arr.add(JsonObject().apply {
                    addProperty("name", s.name); addProperty("address", hex(s.value)); addProperty("size", s.size); addProperty("kind", "ELF symbol candidate")
                }) }
                // The module list is sometimes represented by names in read-only data rather than symbols.
                elf.strings(200).asSequence().map { it.asJsonObject }.filter { obj ->
                    val v = jsonText(obj["value"])
                    v.endsWith(".dll", true) || v.contains("Assembly-CSharp", true) || v.contains("CodeGenModule", true)
                }.take(100).forEach { arr.add(JsonObject().apply {
                    addProperty("name", jsonText(it["value"])); addProperty("address", jsonText(it["address"])); addProperty("kind", "module/string indicator")
                }) }
            }
        }
        return arr
    }
}

// ---------------------------------------------------------------------------
// IDA Pro capability and execution tools (names/parameters preserved)
// ---------------------------------------------------------------------------

class IDAProCapabilitiesTool : AgentTool {
    override val definition = ToolDefinition(
        name = "re.ida.capabilities",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Detect IDA Pro availability and built-in ELF/IL2CPP analysis support. IDA/r2 are optional; core parsers do not require Python or a remote host.",
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.EXECUTE_PROCESS),
        parameters = mapOf(
            "check_remote" to mapOf("type" to "boolean", "description" to "Test SSH connection to remote IDA host (default: false)"),
            "remote_host" to mapOf("type" to "string", "description" to "Remote host for IDA (user@host)"),
            "remote_idat_path" to mapOf("type" to "string", "description" to "Path to idat64 on remote host (default: /opt/ida/idat64)"),
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        try {
            val o = arguments.asJsonObject
            val checkRemote = jsonBool(o["check_remote"], false)
            val remoteHost = jsonText(o["remote_host"])
            if (checkRemote && remoteHost.isNotBlank() && !validRemoteHost(remoteHost)) {
                return@withContext ToolResult(ok = false, error = "Invalid remote_host; provide a host or user@host without spaces or shell characters")
            }
            val remoteIdat = jsonText(o["remote_idat_path"], "/opt/ida/idat64").ifBlank { "/opt/ida/idat64" }
            @SuppressLint("SdCardPath", "HardcodedPath")
            val localPaths = listOf(
                "/opt/ida/idat64", "/opt/ida/idat", "/usr/local/bin/idat64", "/usr/bin/idat64"
            )
            val idat = localPaths.firstOrNull { File(it).isFile && File(it).canExecute() }
            val r2 = listOf("r2", "radare2").firstNotNullOfOrNull { name ->
                runCatching { RuntimeManagerExecutable.resolveByName(name) }.getOrNull()
            }
            val ssh = listOf("ssh").firstNotNullOfOrNull { name -> runCatching { RuntimeManagerExecutable.resolveByName(name) }.getOrNull() }
            val result = JsonObject()
            val caps = JsonObject()
            caps.addProperty("local_available", idat != null)
            idat?.let { caps.addProperty("local_path", it) }
            caps.addProperty("radare2_available", r2 != null)
            r2?.let { caps.addProperty("radare2_path", it) }
            caps.addProperty("ssh_available", ssh != null)
            caps.addProperty("built_in_elf_analyzer", true)
            caps.addProperty("built_in_il2cpp_metadata_parser", true)
            caps.addProperty("python_required", false)
            caps.addProperty("remote_ida_required", false)
            var remoteAvailable = false
            if (checkRemote && remoteHost.isNotBlank() && ssh != null) {
                val cmd = listOf(ssh, "-o", "ConnectTimeout=5", "-o", "BatchMode=yes", remoteHost,
                    "test -x ${shellQuote(remoteIdat)} && ${shellQuote(remoteIdat)} -v")
                val check = runCatching { runBoundedProcess(cmd, context.workspace, 10, 16_000) }.getOrNull()
                remoteAvailable = check != null && !check.timedOut && check.exitCode == 0
                caps.addProperty("remote_available", remoteAvailable)
                if (check != null) caps.addProperty("remote_output", check.output.take(4000))
            } else {
                caps.addProperty("remote_available", false)
                caps.addProperty("remote_error", if (!checkRemote) "Not checked (set check_remote=true)" else if (ssh == null) "SSH executable unavailable" else "remote_host not provided")
            }
            caps.add("supported_architectures", gson.toJsonTree(listOf("ARM64/AArch64", "ARM32", "x86_64", "x86", "MIPS", "PPC/PPC64", "RISC-V")))
            caps.add("available_scripts", gson.toJsonTree(listOf(
                "elf_analyze - ELF headers, sections, symbols and dynamic strings (built-in)",
                "analysis_pipeline - combined ELF inventory, Unity/IL2CPP indicators, and next-step recommendations (built-in)",
                "il2cpp_dump - native ELF indicators; use re.il2cpp.metadata with global-metadata.dat for managed metadata",
                "struct_recovery - static section/symbol heuristics, optional IDA/r2 enrichment",
                "hex_pattern - pattern and numeric value identification",
                "unity_mono - Unity/IL2CPP native symbols and strings",
                "offset_calc - address/RVA helpers",
                "string_xref - string and raw pointer-byte references; semantic xrefs require IDA/r2"
            )))
            result.add("capabilities", caps)
            result.addProperty("status", if (idat != null || remoteAvailable) "READY" else "BUILT_IN_READY")
            result.addProperty("message", if (idat != null || remoteAvailable) "IDA Pro and built-in analysis available" else "Built-in Kotlin analyzers ready; IDA/r2/SSH are optional")
            ToolResult(ok = true, output = gson.toJson(result))
        } catch (e: Exception) {
            ToolResult(ok = false, error = "Capability detection failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

class IDAProAnalyzeTool(@Suppress("unused") private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.ida.analyze",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Analyze ELF/PE/DEX/Mach-O files with optional IDA or built-in Kotlin ELF analysis. Known built-in tasks work without Python, radare2, or remote IDA.",
        parameters = mapOf(
            "file_path" to mapOf("type" to "string", "description" to "Path to binary (ELF, DEX, Mach-O, PE)"),
            "script" to mapOf("type" to "string", "description" to "Built-in task: elf_analyze, il2cpp_dump, struct_recovery, hex_pattern, unity_mono, offset_calc, string_xref; or an IDA script name/path"),
            "script_args" to mapOf("type" to "string", "description" to "Additional task arguments (space-separated)"),
            "timeout_seconds" to mapOf("type" to "integer", "description" to "Timeout in seconds (default: 300, max: 1800)"),
            "use_remote" to mapOf("type" to "boolean", "description" to "Use remote SSH IDA host (default: false)"),
            "remote_host" to mapOf("type" to "string", "description" to "Remote host (user@host)"),
            "output_json" to mapOf("type" to "string", "description" to "Output JSON file path in workspace (default: auto-generated)"),
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.EXECUTE_PROCESS, ToolPermission.WRITE_WORKSPACE),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        try {
            val o = arguments.asJsonObject
            val filePath = jsonText(o["file_path"])
            if (filePath.isBlank()) return@withContext ToolResult(ok = false, error = "Missing file_path")
            val scriptName = jsonText(o["script"])
            if (scriptName.isBlank()) return@withContext ToolResult(ok = false, error = "Missing script")
            val scriptArgs = jsonText(o["script_args"])
            val timeout = jsonLong(o["timeout_seconds"], 300L).coerceIn(5L, 1800L)
            val useRemote = jsonBool(o["use_remote"], false)
            val remoteHost = jsonText(o["remote_host"])
            val target = resolveInputFile(context.workspace, filePath)
                ?: return@withContext ToolResult(ok = false, error = "File not found or unreadable: $filePath")
            val outPath = jsonText(o["output_json"]).ifBlank { "ida_${scriptName}_${target.nameWithoutExtension()}_${System.currentTimeMillis()}.json" }
            val outFile = resolveOutputFile(context.workspace, outPath)
            val normalized = scriptName.substringBeforeLast('.').substringAfterLast('/').lowercase(Locale.ROOT)
            val builtIn = setOf("elf_analyze", "analysis_pipeline", "il2cpp_dump", "struct_recovery", "hex_pattern", "unity_mono", "offset_calc", "string_xref")
            val scriptFile = if (normalized == "elf_analyze") null else resolveScriptPath(scriptName, context.workspace)
            val localIdat = if (!useRemote) resolveIdatCommand(false, "") else null
            when {
                useRemote && scriptFile != null -> {
                    if (remoteHost.isBlank()) return@withContext ToolResult(ok = false, error = "use_remote=true requires remote_host")
                    runRemoteIda(context.workspace, remoteHost, "/opt/ida/idat64", target, scriptFile, outFile, scriptArgs, timeout)
                }
                !useRemote && localIdat != null && scriptFile != null -> {
                    val cmd = listOf(localIdat.first, "-A", "-S${scriptFile.absolutePath}${if (scriptArgs.isBlank()) "" else " $scriptArgs"}", "-o${outFile.absolutePath}", target.absolutePath)
                    val proc = runBoundedProcess(cmd, context.workspace, timeout)
                    val jsonResult = readJsonObjectIfExists(outFile)
                    val metadata = mutableMapOf<String, Any>("output_file" to outFile.absolutePath, "exit_code" to proc.exitCode, "analysis_engine" to "IDA Pro")
                    jsonResult?.let { metadata["json_result"] = it.toString() }
                    if (proc.timedOut) ToolResult(ok = false, output = proc.output, error = "IDA analysis timed out after ${timeout}s", metadata = metadata)
                    else ToolResult(ok = proc.exitCode == 0, output = proc.output, error = if (proc.exitCode == 0) null else "IDA exited with code ${proc.exitCode}", exitCode = proc.exitCode, metadata = metadata)
                }
                normalized in builtIn -> {
                    // IDA is absent or the matching script is not installed: use the Java/Kotlin parser.
                    val result = runCatching { analyzeElfFile(target, normalized, scriptArgs) }.getOrElse { e ->
                        return@withContext ToolResult(ok = false, error = "Built-in ELF analysis failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                    result.addProperty("source_file", target.absolutePath)
                    val completeJson = gson.toJson(result)
                    val wrote = writeTextBounded(outFile, completeJson)
                    val finalText = if (completeJson.length <= MAX_TOOL_OUTPUT) completeJson else gson.toJson(JsonObject().apply {
                        addProperty("status", "output_truncated")
                        addProperty("analysis_engine", "built-in Kotlin ELF analyzer")
                        addProperty("source_file", target.absolutePath)
                        addProperty("output_file", if (wrote) outFile.absolutePath else "")
                        addProperty("output_file_written", wrote)
                        addProperty("note", "Full structured result exceeded the inline output limit; inspect the saved JSON file or narrow the analysis.")
                    })
                    ToolResult(ok = true, output = finalText, metadata = mapOf("analysis_engine" to "built-in Kotlin ELF analyzer", "output_file" to if (wrote) outFile.absolutePath else "", "output_file_written" to wrote))
                }
                else -> ToolResult(ok = false, error = if (scriptFile == null) {
                    "Script not found: $scriptName. Built-in tasks: ${builtIn.joinToString()}"
                } else {
                    "IDA is unavailable and '$scriptName' is not a built-in task. Built-in ELF tasks do not require Python/r2/remote IDA."
                })
            }
        } catch (e: Exception) {
            ToolResult(ok = false, error = "Analysis failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    @SuppressLint("SdCardPath", "HardcodedPath")
    private fun resolveScriptPath(name: String, workspace: File): File? {
        val builtInScripts = mapOf(
            "il2cpp_dump" to "ida_il2cpp_dump.py", "struct_recovery" to "ida_struct_recovery.py",
            "hex_pattern" to "ida_hex_pattern.py", "unity_mono" to "ida_unity_mono.py",
            "offset_calc" to "ida_offset_calc.py", "string_xref" to "ida_string_xref.py"
        )
        val mapped = builtInScripts[name.substringBeforeLast('.').substringAfterLast('/').lowercase(Locale.ROOT)] ?: name
        val paths = listOf(File(workspace, "scripts/$mapped"), File(workspace, mapped),
            File("/opt/ida/scripts/$mapped"))
        return paths.firstOrNull { it.isFile && it.canRead() }
    }

    @SuppressLint("SdCardPath", "HardcodedPath")
    private fun resolveIdatCommand(useRemote: Boolean, remoteHost: String): Pair<String, Boolean>? {
        if (useRemote && remoteHost.isNotBlank()) return "/opt/ida/idat64" to true
        val candidates = listOf("/opt/ida/idat64", "/opt/ida/idat")
        val path = candidates.firstOrNull { File(it).isFile && File(it).canExecute() } ?: return null
        return path to false
    }

    private fun readJsonObjectIfExists(file: File): JsonObject? = try { if (file.isFile && file.length() <= MAX_JSON_FILE_OUTPUT) parseJson(file.readText(StandardCharsets.UTF_8)).asJsonObject else null } catch (_: Exception) { null }

    private fun runRemoteIda(workspace: File, host: String, remoteIdat: String, target: File, script: File, output: File,
                             args: String, timeout: Long): ToolResult {
        if (!validRemoteHost(host)) return ToolResult(ok = false, error = "Invalid remote_host; provide a host or user@host without spaces or shell characters")
        val ssh = RuntimeManagerExecutable.resolveByName("ssh") ?: return ToolResult(ok = false, error = "SSH executable unavailable")
        val scp = RuntimeManagerExecutable.resolveByName("scp") ?: return ToolResult(ok = false, error = "SCP executable unavailable")
        // Transfer from local to remote (the original implementation accidentally reversed this direction).
        val remoteTarget = "/tmp/ida_${target.name}"
        val remoteScript = "/tmp/ida_${script.name}"
        val remoteOutput = "/tmp/ida_${output.name}"
        val copyTarget = runBoundedProcess(listOf(scp, target.absolutePath, "$host:$remoteTarget"), workspace, minOf(timeout, 60))
        if (copyTarget.timedOut || copyTarget.exitCode != 0) return ToolResult(ok = false, output = copyTarget.output, error = "Unable to stage target file on remote host")
        val copyScript = runBoundedProcess(listOf(scp, script.absolutePath, "$host:$remoteScript"), workspace, minOf(timeout, 60))
        if (copyScript.timedOut || copyScript.exitCode != 0) return ToolResult(ok = false, output = copyScript.output, error = "Unable to stage IDA script on remote host")
        val argsTokens = args.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { shellQuote(it) }
        val remoteCommand = "${shellQuote(remoteIdat)} -A -S${shellQuote(remoteScript + if (argsTokens.isBlank()) "" else " $argsTokens")} -o${shellQuote(remoteOutput)} ${shellQuote(remoteTarget)}"
        val result = runBoundedProcess(listOf(ssh, "-o", "ConnectTimeout=8", host, remoteCommand), workspace, timeout)
        if (result.timedOut || result.exitCode != 0) return ToolResult(ok = false, output = result.output, error = "Remote IDA analysis failed")
        val parent = output.absoluteFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val copied = runBoundedProcess(listOf(scp, "$host:$remoteOutput", output.absolutePath), workspace, minOf(timeout, 60))
        if (copied.timedOut || copied.exitCode != 0) return ToolResult(ok = false, output = copied.output, error = "IDA finished but output transfer failed")
        return ToolResult(ok = true, output = result.output, metadata = mapOf("analysis_engine" to "remote IDA Pro", "output_file" to output.absolutePath))
    }
}

private object RuntimeManagerExecutable {
    // Optional command-line tools are resolved from app-owned runtime packs.
    // explicit probe is needed because this app's PATH does not include it.
    @SuppressLint("SdCardPath")
    fun resolveByName(name: String): String? {
        val candidates = listOf("/system/bin/$name", "/usr/bin/$name", "/usr/local/bin/$name")
        return candidates.firstOrNull { File(it).isFile && File(it).canExecute() }
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).asSequence().map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }
}

// ---------------------------------------------------------------------------
// IL2CPP metadata, managed method resolution and vtable tools
// ---------------------------------------------------------------------------

class Il2cppMetadataTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.il2cpp.metadata", category = ToolCategory.REVERSE_ENGINEERING,
        description = "Parse Unity IL2CPP global-metadata.dat with version-aware built-in Kotlin parsing, including images, type definitions, methods, fields, string literals, native code-generation indicators and symbol correlation. IDA/Python are optional.",
        parameters = mapOf(
            "libil2cpp_path" to mapOf("type" to "string", "description" to "Path to libil2cpp.so (required)"),
            "metadata_path" to mapOf("type" to "string", "description" to "Path to global-metadata.dat (optional, auto-detected if in same dir)"),
            "output_json" to mapOf("type" to "string", "description" to "Output JSON file in workspace"),
            "include_methods" to mapOf("type" to "boolean", "description" to "Include method records (default: true)"),
            "include_fields" to mapOf("type" to "boolean", "description" to "Include field records (default: true)"),
            "filter_namespace" to mapOf("type" to "string", "description" to "Filter by namespace or fully-qualified class text"),
            "use_ida" to mapOf("type" to "boolean", "description" to "Attempt external analysis when available; built-in parser always remains available (default: true)"),
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.EXECUTE_PROCESS, ToolPermission.WRITE_WORKSPACE),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        try {
            val o = arguments.asJsonObject
            val libPath = jsonText(o["libil2cpp_path"])
            if (libPath.isBlank()) return@withContext ToolResult(ok = false, error = "Missing libil2cpp_path")
            val metaPath = jsonText(o["metadata_path"])
            val outPath = jsonText(o["output_json"]).ifBlank { "il2cpp_metadata_${System.currentTimeMillis()}.json" }
            val includeMethods = jsonBool(o["include_methods"], true)
            val includeFields = jsonBool(o["include_fields"], true)
            val filterNs = jsonText(o["filter_namespace"])
            val useIda = jsonBool(o["use_ida"], true)
            val libFile = resolveInputFile(context.workspace, libPath)
                ?: return@withContext ToolResult(ok = false, error = "libil2cpp.so not found or unreadable: $libPath")
            val metaFile = if (metaPath.isNotBlank()) resolveInputFile(context.workspace, metaPath) else findMetadataDat(libFile.parentFile)
            if (metaFile == null || !metaFile.isFile) return@withContext ToolResult(ok = false, error = "global-metadata.dat not found. Provide metadata_path or place it beside libil2cpp.so")
            val outFile = resolveOutputFile(context.workspace, outPath)
            val parsed = Il2cppMetadataParser(metaFile).use { it.parse(libFile, includeMethods, includeFields, filterNs) }
            parsed.addProperty("requested_external_analysis", useIda)
            parsed.addProperty("external_ida_available", resolveIdaAvailable())
            parsed.addProperty("r2_available", RuntimeManagerExecutable.resolveByName("r2") != null || RuntimeManagerExecutable.resolveByName("radare2") != null)
            parsed.addProperty("runtime_manager_integration", runCatching { runtime.resolveExecutable("r2")?.absolutePath ?: "not resolved by RuntimeManager" }.getOrDefault("not resolved by RuntimeManager"))
            val text = gson.toJson(parsed)
            if (text.toByteArray(StandardCharsets.UTF_8).size > MAX_JSON_FILE_OUTPUT) {
                return@withContext ToolResult(ok = false, error = "Parsed metadata is larger than the ${MAX_JSON_FILE_OUTPUT} byte output limit. Narrow filter_namespace or disable methods/fields.")
            }
            val wrote = writeTextBounded(outFile, text)
            if (!wrote) return@withContext ToolResult(ok = false, error = "Unable to write output JSON: ${outFile.absolutePath}")
            ToolResult(ok = true, output = "IL2CPP metadata parsed: ${parsed["class_count"]?.asInt ?: 0} classes, ${parsed["image_count"]?.asInt ?: 0} images. Output: ${outFile.absolutePath}", metadata = mapOf("output_file" to outFile.absolutePath, "metadata_version" to (parsed["metadata_version"]?.asInt ?: -1), "analysis_engine" to "built-in Kotlin parser"))
        } catch (e: Exception) {
            ToolResult(ok = false, error = "IL2CPP metadata parsing failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun findMetadataDat(dir: File?): File? = dir?.listFiles()?.firstOrNull { it.isFile && it.name == "global-metadata.dat" && it.canRead() }
    // Resolve IDA from supported app-owned and system runtime locations.
    @SuppressLint("SdCardPath")
    private fun resolveIdaAvailable(): Boolean {
        val candidates = listOf("/opt/ida/idat64", "/usr/bin/idat64")
        return candidates.any { File(it).isFile && File(it).canExecute() }
    }
}

class Il2cppResolveTool : AgentTool {
    override val definition = ToolDefinition(
        name = "re.il2cpp.resolve", category = ToolCategory.REVERSE_ENGINEERING,
        description = "Resolve a class method or field in an IL2CPP metadata JSON, calculate the module-relative runtime address, optionally verify executable mapping and readable bytes, and perform bounded signature scanning when an IDA-style byte signature exists.",
        parameters = mapOf(
            "metadata_json" to mapOf("type" to "string", "description" to "Path to IL2CPP metadata JSON"),
            "pid" to mapOf("type" to "integer", "description" to "Target process PID"),
            "class_name" to mapOf("type" to "string", "description" to "Full class name such as Game.PlayerController"),
            "method_name" to mapOf("type" to "string", "description" to "Method name (optional)"),
            "field_name" to mapOf("type" to "string", "description" to "Field name (optional)"),
            "verify_signature" to mapOf("type" to "boolean", "description" to "Verify address with readable memory/signature (default: true)"),
        ),
        permissions = setOf(ToolPermission.MEMORY_SCAN, ToolPermission.PROCESS_MEMORY, ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        try {
            val o = arguments.asJsonObject
            val metaPath = jsonText(o["metadata_json"])
            val pid = jsonInt(o["pid"], -1)
            val className = jsonText(o["class_name"])
            val methodName = jsonText(o["method_name"])
            val fieldName = jsonText(o["field_name"])
            val verify = jsonBool(o["verify_signature"], true)
            if (metaPath.isBlank() || pid <= 0 || className.isBlank()) return@withContext ToolResult(ok = false, error = "metadata_json, a positive pid, and class_name are required")
            if (methodName.isBlank() && fieldName.isBlank()) return@withContext ToolResult(ok = false, error = "Provide method_name and/or field_name")
            val metaFile = resolveInputFile(context.workspace, metaPath)
                ?: return@withContext ToolResult(ok = false, error = "Metadata JSON not found or unreadable: $metaPath")
            val meta = parseJson(metaFile.readText(StandardCharsets.UTF_8)).asJsonObject
            val classArray = meta.getAsJsonArray("classes") ?: return@withContext ToolResult(ok = false, error = "No classes in metadata JSON")
            val clazz = classArray.asSequence().mapNotNull { runCatching { it.asJsonObject }.getOrNull() }.firstOrNull {
                jsonText(it["full_name"]).equals(className, true) || jsonText(it["name"]).equals(className, true) ||
                        "${jsonText(it["namespace"])}.${jsonText(it["name"])}".trimStart('.').equals(className, true)
            } ?: return@withContext ToolResult(ok = false, error = "Class not found in metadata: $className")
            val result = JsonObject().apply { addProperty("class", className); addProperty("pid", pid) }
            val maps = parseProcMaps(pid)
            val moduleName = "libil2cpp.so"
            val moduleBase = findModuleBase(pid, moduleName)
            if (!methodName.isBlank()) {
                val methodArray = clazz.getAsJsonArray("methods") ?: JsonArray()
                val method = methodArray.asSequence().mapNotNull { runCatching { it.asJsonObject }.getOrNull() }.firstOrNull { jsonText(it["name"]).equals(methodName, true) }
                    ?: return@withContext ToolResult(ok = false, error = "Method not found: $methodName")
                val offset = jsonLong(method["offset"], -1L)
                val directRva = parseHexOrDec(jsonText(method["rva"])) ?: jsonLong(method["rva"], -1L)
                val nativeCandidates = method.getAsJsonArray("native_symbol_candidates")
                val singleNativeCandidate = if (nativeCandidates != null && nativeCandidates.size() == 1) {
                    runCatching { parseHexOrDec(jsonText(nativeCandidates[0].asJsonObject["rva_candidate"])) }.getOrNull()
                } else null
                val symbolRvaCandidate = parseHexOrDec(jsonText(method["native_symbol_rva_candidate"]))
                val rva = when { directRva >= 0 -> directRva; symbolRvaCandidate != null -> symbolRvaCandidate; singleNativeCandidate != null -> singleNativeCandidate; else -> -1L }
                val resolutionSource = when { directRva >= 0 -> "metadata-json RVA"; symbolRvaCandidate != null || singleNativeCandidate != null -> "single native ELF symbol candidate"; else -> "unresolved" }
                val signature = jsonText(method["signature"])
                result.addProperty("method", methodName)
                result.addProperty("static_offset", offset)
                result.addProperty("resolution_source", resolutionSource)
                result.addProperty("rva", if (rva >= 0) hex(rva) else "UNRESOLVED")
                result.addProperty("signature", signature)
                if (moduleBase != null && rva >= 0) {
                    val address = moduleBase + rva
                    result.addProperty("module_base", hex(moduleBase)); result.addProperty("candidate_runtime_address", hex(address))
                    val map = findMapForAddress(maps, address)
                    result.addProperty("candidate_mapping_permissions", map?.permissions ?: "unmapped")
                    var verified = false
                    var verification = "not attempted"
                    var byteText = ""
                    if (verify) {
                        if (map == null || !map.permissions.contains('x') || !map.path.contains(moduleName)) {
                            verification = "candidate is not inside an executable libil2cpp mapping"
                        } else {
                            val bytes = readProcessMemory(pid, address, 16)
                            if (bytes != null) { verified = true; verification = "readable executable mapping"; byteText = bytes.hexBytes() }
                            else verification = "memory unreadable; check OS/root/ptrace permissions"
                        }
                    }
                    result.addProperty("verified", verified); result.addProperty("verification", verification)
                    if (byteText.isNotEmpty()) result.addProperty("verified_bytes", byteText)
                    if (verify && signature.isNotBlank()) {
                        val pattern = parseIdaPattern(signature)
                        if (pattern != null && pattern.size in 1..256) {
                            val scanned = scanExecutableMappings(pid, maps.filter { it.path.contains(moduleName) && it.permissions.contains('x') }, pattern, 8)
                            result.add("signature_matches", gson.toJsonTree(scanned.addresses.map(::hex)))
                            result.addProperty("signature_scan_bytes", scanned.bytesScanned)
                            scanned.error?.let { result.addProperty("signature_scan_error", it) }
                        }
                    }
                } else {
                    result.addProperty("runtime_address", "UNRESOLVED")
                    result.addProperty("resolution_note", "Metadata method indices are not guaranteed native RVAs; a symbol map/code-registration dump or a runtime-derived pointer is required.")
                }
            }
            if (!fieldName.isBlank()) {
                val fieldArray = clazz.getAsJsonArray("fields") ?: JsonArray()
                val field = fieldArray.asSequence().mapNotNull { runCatching { it.asJsonObject }.getOrNull() }.firstOrNull { jsonText(it["name"]).equals(fieldName, true) }
                    ?: return@withContext ToolResult(ok = false, error = "Field not found: $fieldName")
                result.addProperty("field", fieldName)
                result.addProperty("offset", jsonLong(field["offset"], -1L))
                result.addProperty("type_index", jsonLong(field["type_index"], -1L))
                result.addProperty("offset_status", jsonText(field["offset_status"], "field offset may require runtime class layout"))
            }
            ToolResult(ok = true, output = gson.toJson(result))
        } catch (e: Exception) { ToolResult(ok = false, error = "IL2CPP resolution failed: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun readProcessMemory(pid: Int, address: Long, length: Int): ByteArray? {
        if (address < 0 || length !in 1..4096) return null
        val mem = File("/proc/$pid/mem")
        if (!mem.canRead()) return null
        return runCatching { RandomAccessFile(mem, "r").use { raf -> raf.seek(address); ByteArray(length).also { raf.readFully(it) } } }.getOrNull()
    }

    private fun scanExecutableMappings(pid: Int, mappings: List<ProcMap>, pattern: BytePattern, maxHits: Int): ScanResult {
        val mem = File("/proc/$pid/mem")
        var scanned = 0L
        val hits = ArrayList<Long>()
        var error: String? = null
        for (mapping in mappings) {
            if (hits.size >= maxHits || scanned >= MAX_MEM_SCAN_BYTES) break
            val remain = MAX_MEM_SCAN_BYTES - scanned
            val capEnd = minOf(mapping.end, mapping.start + remain)
            val part = scanRegion(mem, mapping.start, capEnd, pattern, maxHits - hits.size, remain)
            hits.addAll(part.addresses); scanned += part.bytesScanned
            if (part.error != null) error = part.error
        }
        return ScanResult(hits, scanned, error)
    }
}

private fun readProcessMemory(pid: Int, address: Long, length: Int): ByteArray? {
    if (pid <= 0 || address < 0 || length !in 1..4096) return null
    val mem = File("/proc/$pid/mem")
    if (!mem.canRead()) return null
    return runCatching { RandomAccessFile(mem, "r").use { raf -> raf.seek(address); ByteArray(length).also { raf.readFully(it) } } }.getOrNull()
}

class Il2cppVtableTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.il2cpp.vtable", category = ToolCategory.REVERSE_ENGINEERING,
        description = "Analyze IL2CPP vtable metadata, virtual method slots, native symbols, and candidate module offsets. Output distinguishes metadata slot information from pointers verified in a live runtime.",
        parameters = mapOf(
            "libil2cpp_path" to mapOf("type" to "string", "description" to "Path to libil2cpp.so"),
            "metadata_json" to mapOf("type" to "string", "description" to "Path to IL2CPP metadata JSON"),
            "class_name" to mapOf("type" to "string", "description" to "Target class name"),
            "output_json" to mapOf("type" to "string", "description" to "Output JSON file for vtable analysis"),
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.EXECUTE_PROCESS, ToolPermission.WRITE_WORKSPACE),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        try {
            val o = arguments.asJsonObject
            val libPath = jsonText(o["libil2cpp_path"])
            val metaPath = jsonText(o["metadata_json"])
            val className = jsonText(o["class_name"])
            if (libPath.isBlank() || metaPath.isBlank() || className.isBlank()) return@withContext ToolResult(ok = false, error = "libil2cpp_path, metadata_json, and class_name are required")
            val lib = resolveInputFile(context.workspace, libPath) ?: return@withContext ToolResult(ok = false, error = "libil2cpp.so not found")
            val metadata = resolveInputFile(context.workspace, metaPath) ?: return@withContext ToolResult(ok = false, error = "Metadata JSON not found")
            val meta = parseJson(metadata.readText(StandardCharsets.UTF_8)).asJsonObject
            val classes = meta.getAsJsonArray("classes") ?: JsonArray()
            val clazz = classes.asSequence().mapNotNull { runCatching { it.asJsonObject }.getOrNull() }.firstOrNull {
                jsonText(it["full_name"]).equals(className, true) || jsonText(it["name"]).equals(className, true) || "${jsonText(it["namespace"])}.${jsonText(it["name"])}".trimStart('.').equals(className, true)
            } ?: return@withContext ToolResult(ok = false, error = "Class not found: $className")
            val out = JsonObject().apply {
                addProperty("class", className); addProperty("method_start", jsonLong(clazz["method_start"], -1)); addProperty("vtable_start", jsonLong(clazz["vtable_start"], -1))
                addProperty("vtable_count", jsonInt(clazz["vtable_count"], 0)); addProperty("analysis_engine", "built-in metadata plus ELF symbol correlation")
                addProperty("runtime_manager_r2", runCatching { runtime.resolveExecutable("r2")?.absolutePath ?: "not available" }.getOrDefault("not available"))
            }
            val methods = clazz.getAsJsonArray("methods") ?: JsonArray()
            val virtuals = JsonArray()
            methods.forEach { elem ->
                val m = runCatching { elem.asJsonObject }.getOrNull() ?: return@forEach
                val slot = jsonInt(m["slot"], -1)
                if (slot >= 0) virtuals.add(JsonObject().apply {
                    addProperty("name", jsonText(m["name"])); addProperty("slot", slot); addProperty("method_index", jsonLong(m["method_index"], -1)); addProperty("token", jsonText(m["token"]))
                    addProperty("pointer", "UNRESOLVED"); addProperty("note", "Metadata slot is known; native vtable pointer needs runtime class data or IDA/r2 structure recovery")
                })
            }
            out.add("virtual_methods", virtuals)
            ElfAnalyzer(lib).use { elf ->
                val symbolHints = JsonArray()
                val classShort = className.substringAfterLast('.')
                elf.symbols.filter { it.name.contains(classShort, true) && (it.name.contains("vtable", true) || it.name.contains("_m", true) || it.name.contains("method", true)) }.take(300).forEach { s ->
                    symbolHints.add(JsonObject().apply { addProperty("symbol", s.name); addProperty("address", hex(s.value)); addProperty("size", s.size) })
                }
                out.add("native_symbol_hints", symbolHints)
                out.addProperty("binary_architecture", elf.summary(false)["machine"]?.asString ?: "unknown")
            }
            val outPath = jsonText(o["output_json"]).ifBlank { "il2cpp_vtable_${className.replace('.', '_')}_${System.currentTimeMillis()}.json" }
            val outFile = resolveOutputFile(context.workspace, outPath)
            if (!writeTextBounded(outFile, gson.toJson(out))) return@withContext ToolResult(ok = false, error = "Unable to write output JSON")
            ToolResult(ok = true, output = gson.toJson(out), metadata = mapOf("output_file" to outFile.absolutePath))
        } catch (e: Exception) { ToolResult(ok = false, error = "Vtable analysis failed: ${e.message ?: e.javaClass.simpleName}") }
    }
}

// ---------------------------------------------------------------------------
// Hex identification, offset calculation, xrefs and memory correlation
// ---------------------------------------------------------------------------

class HexIdentifyTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.hex.identify", category = ToolCategory.REVERSE_ENGINEERING,
        description = "Identify hex values through ELF sections, symbols, strings, pointer candidates and IDA-style patterns; optionally correlate mapped runtime addresses.",
        parameters = mapOf(
            "ida_database" to mapOf("type" to "string", "description" to "Path to IDA database (.idb/.i64) or binary file"),
            "values" to mapOf("type" to "array", "description" to "Array of hex values or byte patterns", "items" to mapOf("type" to "string")),
            "pid" to mapOf("type" to "integer", "description" to "Optional target PID to verify runtime mapping"),
            "context_type" to mapOf("type" to "string", "description" to "Context hint: code, data, heap, stack, vtable, got, string, auto"),
            "search_strings" to mapOf("type" to "boolean", "description" to "Search ELF string literals (default: true)"),
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.EXECUTE_PROCESS, ToolPermission.MEMORY_SCAN),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        try {
            val o = arguments.asJsonObject
            val binaryPath = jsonText(o["ida_database"])
            val values = o["values"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { runCatching { it.asString }.getOrNull() }?.filter { it.isNotBlank() } ?: emptyList()
            if (binaryPath.isBlank() || values.isEmpty()) return@withContext ToolResult(ok = false, error = "ida_database and a non-empty values array are required")
            val file = resolveInputFile(context.workspace, binaryPath) ?: return@withContext ToolResult(ok = false, error = "File not found or unreadable: $binaryPath")
            val pid = o["pid"]?.let { runCatching { it.asInt }.getOrNull() }
            val contextType = jsonText(o["context_type"], "auto")
            val searchStrings = jsonBool(o["search_strings"], true)
            val output = JsonObject().apply { addProperty("binary", file.absolutePath); addProperty("context_type", contextType); addProperty("analysis_engine", "built-in Kotlin ELF and byte-pattern analyzer") }
            ElfAnalyzer(file).use { elf ->
                val results = JsonArray()
                values.take(256).forEach { value ->
                    val numeric = parseHexOrDec(value)
                    val pattern = parseIdaPattern(value)
                    val matches = if (pattern != null && pattern.size in 1..128) scanFile(file, pattern, 32) else emptyList()
                    val section = numeric?.let { elf.sectionForAddress(it) }
                    val sym = numeric?.let { n -> elf.symbols.filter { it.value == n }.take(20) }.orEmpty()
                    val entry = JsonObject().apply {
                        addProperty("input", value); if (numeric != null) addProperty("numeric_value", hex(numeric))
                        addProperty("classification", when {
                            section != null && section.name.contains("text") -> "address in executable section"
                            section != null -> "address in ELF section ${section.name}"
                            sym.isNotEmpty() -> "ELF symbol address"
                            pattern != null && pattern.mask.all { it } -> "byte pattern"
                            numeric != null -> "numeric value; no direct section match"
                            else -> "unrecognized"
                        })
                        section?.let { addProperty("section", it.name); addProperty("section_file_offset", hex(it.offset + numeric - it.address)) }
                        if (sym.isNotEmpty()) add("symbols", gson.toJsonTree(sym.map { mapOf("name" to it.name, "address" to hex(it.value), "size" to it.size) }))
                        add("file_matches", gson.toJsonTree(matches.map(::hex)))
                        if (pid != null && numeric != null) {
                            val maps = parseProcMaps(pid)
                            val map = findMapForAddress(maps, numeric)
                            addProperty("runtime_mapped", map != null); addProperty("runtime_permissions", map?.permissions ?: "unmapped")
                            addProperty("runtime_module", map?.path ?: "")
                        }
                    }
                    if (searchStrings && pattern == null && numeric != null) {
                        val stringMatches = elf.strings(400).asSequence().mapNotNull { runCatching { it.asJsonObject }.getOrNull() }.filter { jsonText(it["address"]).equals(hex(numeric), true) }.take(10).toList()
                        if (stringMatches.isNotEmpty()) entry.add("strings", gson.toJsonTree(stringMatches))
                    }
                    results.add(entry)
                }
                output.add("results", results)
                output.addProperty("symbol_count", elf.symbols.size)
                output.addProperty("runtime_manager_r2_available", runCatching { runtime.resolveExecutable("r2") != null }.getOrDefault(false))
            }
            ToolResult(ok = true, output = gson.toJson(output))
        } catch (e: Exception) { ToolResult(ok = false, error = "Hex identification failed: ${e.message ?: e.javaClass.simpleName}") }
    }
}

class IdaOffsetCalcTool : AgentTool {
    override val definition = ToolDefinition(
        name = "re.ida.offset", category = ToolCategory.REVERSE_ENGINEERING,
        description = "Calculate runtime addresses from ELF addresses/RVAs and /proc/PID/maps load bias; verify executable mapping and read a small memory window when OS permissions permit.",
        parameters = mapOf(
            "ida_database" to mapOf("type" to "string", "description" to "Path to IDA database (.idb/.i64) or ELF binary"),
            "ida_address" to mapOf("type" to "string", "description" to "Address from IDA (RVA or VA, e.g. 0x1A2B3C)"),
            "pid" to mapOf("type" to "integer", "description" to "Target process PID"),
            "module_name" to mapOf("type" to "string", "description" to "Module name in maps (e.g. libil2cpp.so, default auto)"),
            "is_rva" to mapOf("type" to "boolean", "description" to "Whether ida_address is an RVA (default: true)"),
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.MEMORY_SCAN, ToolPermission.PROCESS_MEMORY),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        try {
            val o = arguments.asJsonObject
            val path = jsonText(o["ida_database"])
            val addressText = jsonText(o["ida_address"])
            val pid = jsonInt(o["pid"], -1)
            val module = jsonText(o["module_name"]).ifBlank { "libil2cpp.so" }
            val isRva = jsonBool(o["is_rva"], true)
            if (path.isBlank() || addressText.isBlank() || pid <= 0) return@withContext ToolResult(ok = false, error = "ida_database, ida_address and a positive pid are required")
            val file = resolveInputFile(context.workspace, path) ?: return@withContext ToolResult(ok = false, error = "IDA database/binary not found: $path")
            val address = parseHexOrDec(addressText) ?: return@withContext ToolResult(ok = false, error = "Invalid ida_address: $addressText")
            val base = findModuleBase(pid, module) ?: return@withContext ToolResult(ok = false, error = "Module not found in /proc/$pid/maps: $module")
            var rva = address
            if (!isRva) {
                val imageBase = runCatching { ElfAnalyzer(file).use { elf -> elf.sections.filter { it.size > 0 }.minOfOrNull { it.address } ?: 0L } }.getOrDefault(0L)
                rva = address - imageBase
            }
            if (rva < 0 || base > Long.MAX_VALUE - rva) return@withContext ToolResult(ok = false, error = "Address conversion overflow/underflow")
            val runtimeAddress = base + rva
            val maps = parseProcMaps(pid)
            val map = findMapForAddress(maps, runtimeAddress)
            val bytes = if (map != null && map.permissions.contains('r')) readProcessMemory(pid, runtimeAddress, 16) else null
            val out = JsonObject().apply {
                addProperty("input_address", hex(address)); addProperty("rva", hex(rva)); addProperty("module_base", hex(base))
                addProperty("is_rva", isRva); addProperty("runtime_address", hex(runtimeAddress)); addProperty("pid", pid); addProperty("module", module)
                addProperty("mapped", map != null); addProperty("mapping_permissions", map?.permissions ?: "unmapped"); addProperty("mapping_path", map?.path ?: "")
                addProperty("verified", bytes != null); if (bytes != null) addProperty("verified_bytes", bytes.hexBytes())
                if (map == null) addProperty("verify_note", "Address is not in a current process mapping")
                else if (bytes == null) addProperty("verify_note", "Memory read denied/unavailable; check process ownership and OS/root/ptrace permissions")
            }
            ToolResult(ok = true, output = gson.toJson(out))
        } catch (e: Exception) { ToolResult(ok = false, error = "Offset calculation failed: ${e.message ?: e.javaClass.simpleName}") }
    }
}

class IdaXrefTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.ida.xref", category = ToolCategory.REVERSE_ENGINEERING,
        description = "Find ELF symbols and raw pointer-byte references to an address. Reports clearly when a semantic code xref needs IDA/r2 rather than claiming byte matches are code references.",
        parameters = mapOf(
            "ida_database" to mapOf("type" to "string", "description" to "Path to IDA database (.idb/.i64) or binary"),
            "address" to mapOf("type" to "string", "description" to "Target address in IDA (RVA or VA)"),
            "direction" to mapOf("type" to "string", "description" to "xrefs_to (default), xrefs_from, or both"),
            "type_filter" to mapOf("type" to "string", "description" to "Filter: code, data, offset, string, all"),
            "max_results" to mapOf("type" to "integer", "description" to "Maximum results (default 200)"),
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.EXECUTE_PROCESS),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        try {
            val o = arguments.asJsonObject
            val path = jsonText(o["ida_database"]); val addrStr = jsonText(o["address"])
            if (path.isBlank() || addrStr.isBlank()) return@withContext ToolResult(ok = false, error = "ida_database and address are required")
            val target = resolveInputFile(context.workspace, path) ?: return@withContext ToolResult(ok = false, error = "IDA database/binary not found: $path")
            val addr = parseHexOrDec(addrStr) ?: return@withContext ToolResult(ok = false, error = "Invalid address: $addrStr")
            val direction = jsonText(o["direction"], "xrefs_to")
            val typeFilter = jsonText(o["type_filter"], "all")
            val max = jsonInt(o["max_results"], 200).coerceIn(1, 1000)
            val output = JsonObject().apply {
                addProperty("address", hex(addr)); addProperty("direction", direction); addProperty("type_filter", typeFilter)
                addProperty("analysis_engine", "built-in ELF symbol and raw-pointer matcher")
                addProperty("semantic_xrefs", "Not inferred from byte matches. IDA or radare2 analysis is required for reliable code xrefs.")
                addProperty("r2_available", RuntimeManagerExecutable.resolveByName("r2") != null || RuntimeManagerExecutable.resolveByName("radare2") != null)
            }
            ElfAnalyzer(target).use { elf ->
                output.add("symbols_at_address", gson.toJsonTree(elf.symbols.filter { it.value == addr }.take(max).map { mapOf("name" to it.name, "address" to hex(it.value), "size" to it.size, "table" to it.table) }))
                output.add("raw_pointer_xrefs_to", elf.approximateXrefs(addr, max))
                if (direction == "xrefs_from" || direction == "both") {
                    val section = elf.sectionForAddress(addr)
                    output.add("address_context", JsonObject().apply { addProperty("section", section?.name ?: "not section mapped"); addProperty("file_offset", section?.let { hex(it.offset + addr - it.address) } ?: "UNRESOLVED") })
                }
            }
            val r2 = runCatching { runtime.resolveExecutable("r2")?.absolutePath }.getOrNull() ?: RuntimeManagerExecutable.resolveByName("r2")
            if (r2 != null) {
                val command = when (direction) {
                    "xrefs_from" -> "axf @ ${hex(addr)}; q"
                    "both" -> "axt @ ${hex(addr)}; axf @ ${hex(addr)}; q"
                    else -> "axt @ ${hex(addr)}; q"
                }
                val r2Result = runCatching { runBoundedProcess(listOf(r2, "-2", "-A", "-q", "-c", command, target.absolutePath), context.workspace, 45, 256_000) }.getOrNull()
                if (r2Result != null && !r2Result.timedOut && r2Result.exitCode == 0 && r2Result.output.isNotBlank()) {
                    output.addProperty("radare2_status", "completed")
                    output.addProperty("radare2_xrefs", r2Result.output.take(200_000))
                } else output.addProperty("radare2_status", "unavailable_or_failed; built-in pointer matching retained")
            } else output.addProperty("radare2_status", "not installed; built-in pointer matching retained")
            AppLogger.d("IDAProTools", "Built-in xref analysis completed for ${hex(addr)}")
            ToolResult(ok = true, output = gson.toJson(output))
        } catch (e: Exception) { ToolResult(ok = false, error = "Xref analysis failed: ${e.message ?: e.javaClass.simpleName}") }
    }
}

class MemoryIdaCorrelateTool(private val runtime: RuntimeManager) : AgentTool {
    override val definition = ToolDefinition(
        name = "re.mem.ida_correlate", category = ToolCategory.REVERSE_ENGINEERING,
        description = "Correlate memory scan addresses with ELF module maps, load bias, section ranges, symbols and optional runtime verification.",
        parameters = mapOf(
            "ida_database" to mapOf("type" to "string", "description" to "Path to IDA database (.idb/.i64) or ELF binary"),
            "scan_results" to mapOf("type" to "string", "description" to "Path to mem.scan output (one hex address per line) or JSON array"),
            "pid" to mapOf("type" to "integer", "description" to "Optional target PID"),
            "output_json" to mapOf("type" to "string", "description" to "Output JSON file"),
        ),
        permissions = setOf(ToolPermission.REVERSE_ENGINEERING, ToolPermission.READ_WORKSPACE, ToolPermission.EXECUTE_PROCESS, ToolPermission.MEMORY_SCAN),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        try {
            val o = arguments.asJsonObject
            val dbPath = jsonText(o["ida_database"]); val scanPath = jsonText(o["scan_results"])
            if (dbPath.isBlank() || scanPath.isBlank()) return@withContext ToolResult(ok = false, error = "ida_database and scan_results are required")
            val target = resolveInputFile(context.workspace, dbPath) ?: return@withContext ToolResult(ok = false, error = "Binary not found: $dbPath")
            val scanFile = resolveInputFile(context.workspace, scanPath) ?: return@withContext ToolResult(ok = false, error = "Scan results not found: $scanPath")
            if (scanFile.length() > MAX_JSON_FILE_OUTPUT) return@withContext ToolResult(ok = false, error = "Scan results exceed bounded input size")
            val content = scanFile.readText(StandardCharsets.UTF_8).trim()
            val addresses = ArrayList<Long>()
            if (content.startsWith("[")) {
                parseJson(content).asJsonArray.take(10000).forEach { value -> parseHexOrDec(if (value.isJsonPrimitive) value.asString else "")?.let(addresses::add) }
            } else content.lineSequence().take(10000).forEach { parseHexOrDec(it.trim())?.let(addresses::add) }
            if (addresses.isEmpty()) return@withContext ToolResult(ok = false, error = "No valid addresses in scan results")
            val pid = o["pid"]?.let { runCatching { it.asInt }.getOrNull() }
            val output = JsonObject().apply { addProperty("pid", pid ?: -1); addProperty("analysis_engine", "built-in ELF/runtime correlation") }
            val array = JsonArray()
            val maps = if (pid != null && pid > 0) parseProcMaps(pid) else emptyList()
            val matchingMap = if (pid != null) maps.firstOrNull { it.path.contains(target.name) } else null
            val base = if (pid != null) findModuleBase(pid, matchingMap?.path?.substringAfterLast('/') ?: target.name) else null
            ElfAnalyzer(target).use { elf ->
                addresses.distinct().take(2000).forEach { address ->
                    val runtimeAddr = address
                    val map = findMapForAddress(maps, runtimeAddr)
                    val belongsToTarget = map?.path?.contains(target.name) == true
                    val moduleRva = if (belongsToTarget && base != null && runtimeAddr >= base) runtimeAddr - base else address
                    val section = if (belongsToTarget) elf.sectionForAddress(moduleRva) else elf.sectionForAddress(address)
                    val nearSymbols = elf.symbols.filter { sym ->
                        val d = if (sym.value >= moduleRva) sym.value - moduleRva else moduleRva - sym.value
                        d < 0x1000
                    }.sortedBy { kotlin.math.abs(it.value - moduleRva) }.take(8)
                    array.add(JsonObject().apply {
                        addProperty("runtime_address", hex(runtimeAddr)); addProperty("module_rva_candidate", hex(moduleRva))
                        addProperty("mapped", map != null); addProperty("mapping_permissions", map?.permissions ?: "unmapped"); addProperty("mapping_path", map?.path ?: "")
                        addProperty("section", section?.name ?: "unknown")
                        add("near_symbols", gson.toJsonTree(nearSymbols.map { mapOf("name" to it.name, "address" to hex(it.value), "distance" to (it.value - moduleRva)) }))
                    })
                }
                output.add("correlations", array)
                output.addProperty("binary", target.absolutePath)
                output.addProperty("binary_architecture", elf.summary(false)["machine"]?.asString ?: "unknown")
            }
            val outPath = jsonText(o["output_json"]).ifBlank { "mem_ida_correlate_${System.currentTimeMillis()}.json" }
            val outFile = resolveOutputFile(context.workspace, outPath)
            val text = gson.toJson(output)
            if (!writeTextBounded(outFile, text)) return@withContext ToolResult(ok = false, error = "Unable to write correlation output or output exceeds configured limit")
            ToolResult(ok = true, output = "Correlated ${array.size()} address(es). Output: ${outFile.absolutePath}", metadata = mapOf("output_file" to outFile.absolutePath, "correlation_count" to array.size()))
        } catch (e: Exception) { ToolResult(ok = false, error = "Memory correlation failed: ${e.message ?: e.javaClass.simpleName}") }
    }
}

// ---------------------------------------------------------------------------
// Unity runtime dump: mappings, threads and metadata summary without fake heap walks
// ---------------------------------------------------------------------------

class UnityRuntimeDumpTool : AgentTool {
    override val definition = ToolDefinition(
        name = "re.unity.runtime_dump", category = ToolCategory.REVERSE_ENGINEERING,
        description = "Collect bounded Unity/IL2CPP process thread and module-map information, metadata counts, and memory permission status. Heap dumping is not falsely reported as implemented.",
        parameters = mapOf(
            "pid" to mapOf("type" to "integer", "description" to "Target process PID"),
            "metadata_json" to mapOf("type" to "string", "description" to "Path to IL2CPP metadata JSON"),
            "dump_heap" to mapOf("type" to "boolean", "description" to "Request managed heap dump (not performed unless a supported GC walker exists)"),
            "dump_threads" to mapOf("type" to "boolean", "description" to "Dump thread summaries (default true)"),
            "output_dir" to mapOf("type" to "string", "description" to "Output directory in workspace"),
        ),
        permissions = setOf(ToolPermission.MEMORY_SCAN, ToolPermission.PROCESS_MEMORY, ToolPermission.READ_WORKSPACE, ToolPermission.WRITE_WORKSPACE, ToolPermission.ROOT),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        try {
            val o = arguments.asJsonObject
            val pid = jsonInt(o["pid"], -1); val metaPath = jsonText(o["metadata_json"])
            if (pid <= 0 || metaPath.isBlank()) return@withContext ToolResult(ok = false, error = "Positive pid and metadata_json are required")
            val metaFile = resolveInputFile(context.workspace, metaPath) ?: return@withContext ToolResult(ok = false, error = "Metadata JSON not found: $metaPath")
            val dumpHeap = jsonBool(o["dump_heap"], false)
            val dumpThreads = jsonBool(o["dump_threads"], true)
            val outPath = jsonText(o["output_dir"]).ifBlank { "unity_dump_${System.currentTimeMillis()}" }
            val outDir = resolveOutputFile(context.workspace, outPath)
            if (!outDir.exists() && !outDir.mkdirs()) return@withContext ToolResult(ok = false, error = "Cannot create output directory: ${outDir.absolutePath}")
            val maps = parseProcMaps(pid)
            if (maps.isEmpty()) return@withContext ToolResult(ok = false, error = "Cannot read /proc/$pid/maps; process missing or access denied")
            val il2cppMaps = maps.filter { it.path.contains("libil2cpp.so") }
            val unityMaps = maps.filter { it.path.contains("libunity.so") }
            val moduleBase = findModuleBase(pid, "libil2cpp.so")
            val result = JsonObject().apply {
                addProperty("pid", pid); addProperty("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
                addProperty("il2cpp_base", moduleBase?.let(::hex) ?: "NOT_FOUND")
                addProperty("process_memory_readable", File("/proc/$pid/mem").canRead())
                addProperty("memory_access_note", "Reads rely on kernel process permissions. No automatic root escalation is attempted.")
                add("il2cpp_mappings", gson.toJsonTree(il2cppMaps.take(1000).map { mapOf("start" to hex(it.start), "end" to hex(it.end), "permissions" to it.permissions, "file_offset" to hex(it.fileOffset), "path" to it.path) }))
                add("unity_mappings", gson.toJsonTree(unityMaps.take(1000).map { mapOf("start" to hex(it.start), "end" to hex(it.end), "permissions" to it.permissions, "file_offset" to hex(it.fileOffset), "path" to it.path) }))
            }
            if (dumpThreads) result.add("threads", dumpUnityThreads(pid))
            val meta = parseJson(metaFile.readText(StandardCharsets.UTF_8)).asJsonObject
            val domain = JsonObject().apply {
                addProperty("metadata_version", jsonInt(meta["metadata_version"], -1))
                addProperty("classes_count", meta.getAsJsonArray("classes")?.size() ?: jsonInt(meta["class_count"], 0))
                addProperty("images_count", meta.getAsJsonArray("images")?.size() ?: jsonInt(meta["image_count"], 0))
                addProperty("assemblies_count_estimate", jsonInt(meta["assemblies_count_estimate"], 0))
                addProperty("runtime_base", moduleBase?.let(::hex) ?: "NOT_FOUND")
            }
            result.add("managed_metadata_summary", domain)
            if (dumpHeap) result.add("heap_dump", JsonObject().apply {
                addProperty("status", "NOT_PERFORMED")
                addProperty("reason", "A safe, version-matched IL2CPP GC heap walker is not implemented in this tool; no raw heap is dumped.")
            })
            val json = gson.toJson(result)
            if (!writeTextBounded(File(outDir, "unity_runtime_dump.json"), json)) return@withContext ToolResult(ok = false, error = "Failed to save runtime dump or output exceeds limit")
            val inline = if (json.length <= MAX_TOOL_OUTPUT) json else gson.toJson(JsonObject().apply {
                addProperty("status", "output_saved_with_inline_summary")
                addProperty("output_file", File(outDir, "unity_runtime_dump.json").absolutePath)
                addProperty("pid", pid)
                addProperty("note", "Full JSON exceeded inline output limit and was saved to the output file.")
            })
            ToolResult(ok = true, output = inline, metadata = mapOf("output_dir" to outDir.absolutePath, "output_file" to File(outDir, "unity_runtime_dump.json").absolutePath))
        } catch (e: Exception) { ToolResult(ok = false, error = "Unity runtime dump failed: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun dumpUnityThreads(pid: Int): JsonArray {
        val result = JsonArray()
        val taskDir = File("/proc/$pid/task")
        if (!taskDir.isDirectory || !taskDir.canRead()) return result
        taskDir.listFiles()?.take(4096)?.forEach { dir ->
            val tid = dir.name.toIntOrNull() ?: return@forEach
            val comm = runCatching { File(dir, "comm").readText().trim().take(256) }.getOrDefault("unknown")
            val stat = runCatching { File(dir, "stat").readText().trim().take(256) }.getOrDefault("")
            result.add(JsonObject().apply { addProperty("tid", tid); addProperty("comm", comm); addProperty("stat_summary", stat) })
        }
        return result
    }
}

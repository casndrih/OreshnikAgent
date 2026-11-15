package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.AgentTool
import com.example.oreshik.agent.core.ToolCategory
import com.example.oreshik.agent.core.ToolContext
import com.example.oreshik.agent.core.ToolDefinition
import com.example.oreshik.agent.core.ToolPermission
import com.example.oreshik.agent.core.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.log2

private val gson = Gson()

private fun JsonObject.addStringArray(
    name: String,
    values: Collection<String>,
) {
    val array = JsonArray()
    values.forEach { array.add(it) }
    add(name, array)
}

private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
    val shortEnough = size >= prefix.size
    val matched = shortEnough && copyOfRange(0, prefix.size).contentEquals(prefix)
    return matched
}

private fun readUInt16LE(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}

private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
    return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}

@Suppress("SameParameterValue")
private fun readUInt64LE(bytes: ByteArray, offset: Int = 24): Long {
    return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 4].toLong() and 0xFF) shl 32) or
            ((bytes[offset + 5].toLong() and 0xFF) shl 40) or
            ((bytes[offset + 6].toLong() and 0xFF) shl 48) or
            ((bytes[offset + 7].toLong() and 0xFF) shl 56)
}

/**
 * Public API kept for the agent tool surface; APK analysis is also available
 * through re.apktool.decode and re.apk.manifest for currently registered flows.
 */
@Suppress("unused")
class ApkAnalyzeTool : AgentTool {

    override val definition = ToolDefinition(
        name = "re.apk.analyze",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Analyze an APK file: inspect ZIP contents, DEX files, native libraries, resources, assets, and signatures.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "apk_path" to mapOf(
                    "type" to "string",
                    "description" to "Path to the APK file",
                ),
                "extract_resources" to mapOf(
                    "type" to "boolean",
                    "description" to "List resource names (default: false)",
                ),
                "extract_smali" to mapOf(
                    "type" to "boolean",
                    "description" to "List DEX files (full class extraction requires a DEX parser)",
                ),
            ),
            "required" to listOf("apk_path"),
        ),
        permissions = setOf(
            ToolPermission.REVERSE_ENGINEERING,
            ToolPermission.READ_WORKSPACE
        )
    )

    override suspend fun execute(
        context: ToolContext,
        arguments: JsonElement
    ): ToolResult {
        return try {
            val args = arguments.asJsonObject

            val apkPath = args["apk_path"]?.asString
                ?: return ToolResult.failure("Missing required parameter: apk_path")

            val extractResources =
                args["extract_resources"]?.asBoolean ?: false

            val extractSmali =
                args["extract_smali"]?.asBoolean ?: false

            val apkFile = File(apkPath)

            if (!apkFile.exists()) {
                return ToolResult.failure("APK file not found: $apkPath")
            }

            if (!apkFile.isFile) {
                return ToolResult.failure("APK path is not a file: $apkPath")
            }

            val resultJson = JsonObject()

            @Suppress("BlockingMethodInNonBlockingContext")
            ZipFile(apkFile).use { zipFile ->

                resultJson.addProperty("file_name", apkFile.name)
                resultJson.addProperty("file_size", apkFile.length())
                resultJson.addProperty(
                    "file_size_mb",
                    "%.2f".format(Locale.US, apkFile.length() / 1024.0 / 1024.0)
                )

                val entries = zipFile.entries().asSequence()
                    .map { it.name }
                    .toList()

                resultJson.addProperty("total_entries", entries.size)

                val hasManifest = entries.contains("AndroidManifest.xml")
                resultJson.addProperty("has_manifest", hasManifest)

                val dexFiles = entries.filter {
                    it.matches(Regex("""classes(\d*)\.dex"""))
                }

                resultJson.addProperty("has_dex", dexFiles.isNotEmpty())
                resultJson.addProperty("dex_count", dexFiles.size)
                resultJson.addStringArray("dex_files", dexFiles)

                val nativeLibs = entries.filter {
                    it.startsWith("lib/") && it.endsWith(".so")
                }

                resultJson.addProperty(
                    "has_native_libs",
                    nativeLibs.isNotEmpty()
                )

                if (nativeLibs.isNotEmpty()) {
                    resultJson.addStringArray(
                        "native_libraries",
                        nativeLibs
                    )
                }

                val resources = entries.filter {
                    it.startsWith("res/")
                }

                resultJson.addProperty(
                    "resource_count",
                    resources.size
                )

                if (extractResources) {
                    resultJson.addStringArray(
                        "resources",
                        resources.take(500)
                    )

                    if (resources.size > 500) {
                        resultJson.addProperty(
                            "resources_truncated",
                            true
                        )
                    }
                }

                val assets = entries.filter {
                    it.startsWith("assets/")
                }

                resultJson.addProperty(
                    "asset_count",
                    assets.size
                )

                val metaInf = entries.filter {
                    it.startsWith("META-INF/")
                }

                resultJson.addProperty(
                    "meta_inf_count",
                    metaInf.size
                )

                val certFiles = metaInf.filter {
                    it.endsWith(".RSA", ignoreCase = true) ||
                            it.endsWith(".DSA", ignoreCase = true) ||
                            it.endsWith(".EC", ignoreCase = true)
                }

                if (certFiles.isNotEmpty()) {
                    resultJson.addStringArray(
                        "certificates",
                        certFiles
                    )
                }

                if (extractSmali) {
                    resultJson.addProperty(
                        "smali_note",
                        "Class/method extraction requires a DEX parser such as dexlib2. DEX files are listed here."
                    )
                }
            }

            ToolResult.success(
                metadata = mapOf("result" to resultJson)
            )

        } catch (e: Exception) {
            ToolResult.failure(
                "APK analysis failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }
}


@Suppress("unused")
class ApkPermissionsTool : AgentTool {

    override val definition = ToolDefinition(
        name = "re.apk.permissions",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Analyze known Android permissions and provide risk classifications.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "apk_path" to mapOf(
                    "type" to "string",
                    "description" to "Path to the APK file"
                ),
                "group_by_category" to mapOf(
                    "type" to "boolean",
                    "description" to "Group permissions by category (default: true)"
                )
            ),
            "required" to listOf("apk_path")
        ),
        permissions = setOf(
            ToolPermission.REVERSE_ENGINEERING,
            ToolPermission.READ_WORKSPACE
        )
    )

    override suspend fun execute(
        context: ToolContext,
        arguments: JsonElement
    ): ToolResult {
        return try {
            val args = arguments.asJsonObject

            val apkPath = args["apk_path"]?.asString
                ?: return ToolResult.failure("Missing required parameter: apk_path")

            val groupByCategory =
                args["group_by_category"]?.asBoolean ?: true

            val apkFile = File(apkPath)

            if (!apkFile.exists()) {
                return ToolResult.failure("APK file not found: $apkPath")
            }

            if (!apkFile.isFile) {
                return ToolResult.failure("APK path is not a file: $apkPath")
            }

            val dangerousPerms = mapOf(
                "android.permission.CAMERA" to "HIGH",
                "android.permission.RECORD_AUDIO" to "HIGH",
                "android.permission.READ_CONTACTS" to "HIGH",
                "android.permission.WRITE_CONTACTS" to "HIGH",
                "android.permission.READ_CALL_LOG" to "HIGH",
                "android.permission.WRITE_CALL_LOG" to "HIGH",
                "android.permission.READ_SMS" to "HIGH",
                "android.permission.SEND_SMS" to "CRITICAL",
                "android.permission.RECEIVE_SMS" to "HIGH",
                "android.permission.READ_EXTERNAL_STORAGE" to "MEDIUM",
                "android.permission.WRITE_EXTERNAL_STORAGE" to "HIGH",
                "android.permission.ACCESS_FINE_LOCATION" to "HIGH",
                "android.permission.ACCESS_COARSE_LOCATION" to "MEDIUM",
                "android.permission.READ_PHONE_STATE" to "HIGH",
                "android.permission.CALL_PHONE" to "CRITICAL",
                "android.permission.SYSTEM_ALERT_WINDOW" to "HIGH",
                "android.permission.WRITE_SETTINGS" to "HIGH",
                "android.permission.PACKAGE_USAGE_STATS" to "HIGH",
                "android.permission.BIND_ACCESSIBILITY_SERVICE" to "CRITICAL",
                "android.permission.BIND_DEVICE_ADMIN" to "CRITICAL",
                "android.permission.REQUEST_INSTALL_PACKAGES" to "HIGH",
                "android.permission.MANAGE_EXTERNAL_STORAGE" to "CRITICAL"
            )

            val normalPerms = mapOf(
                "android.permission.INTERNET" to "LOW",
                "android.permission.ACCESS_NETWORK_STATE" to "LOW",
                "android.permission.ACCESS_WIFI_STATE" to "LOW",
                "android.permission.WAKE_LOCK" to "LOW",
                "android.permission.VIBRATE" to "LOW",
                "android.permission.RECEIVE_BOOT_COMPLETED" to "LOW",
                "android.permission.FOREGROUND_SERVICE" to "LOW"
            )

            val resultJson = JsonObject()
            val permissionsJson = JsonArray()

            fun category(permission: String): String {
                return when {
                    permission.contains("CAMERA") ||
                            permission.contains("RECORD_AUDIO") ->
                        "Sensors"

                    permission.contains("CONTACTS") ||
                            permission.contains("CALL_LOG") ||
                            permission.contains("SMS") ->
                        "Personal Info"

                    permission.contains("LOCATION") ->
                        "Location"

                    permission.contains("STORAGE") ||
                            permission.contains("EXTERNAL") ->
                        "Storage"

                    permission.contains("PHONE") ||
                            permission.contains("CALL") ->
                        "Phone"

                    permission.contains("ACCESSIBILITY") ||
                            permission.contains("DEVICE_ADMIN") ||
                            permission.contains("INSTALL_PACKAGES") ||
                            permission.contains("MANAGE_EXTERNAL") ||
                            permission.contains("SYSTEM_ALERT") ||
                            permission.contains("WRITE_SETTINGS") ||
                            permission.contains("USAGE_STATS") ->
                        "System"

                    else ->
                        "Other"
                }
            }

            fun addPermission(permission: String, risk: String) {
                val item = JsonObject()

                item.addProperty(
                    "permission",
                    permission
                )

                item.addProperty(
                    "risk_level",
                    risk
                )

                if (groupByCategory) {
                    item.addProperty(
                        "category",
                        category(permission)
                    )
                }

                permissionsJson.add(item)
            }

            dangerousPerms.forEach { (permission, risk) ->
                addPermission(permission, risk)
            }

            normalPerms.forEach { (permission, risk) ->
                addPermission(permission, risk)
            }

            resultJson.add(
                "permission_analysis",
                permissionsJson
            )

            resultJson.addProperty(
                "total_permissions_analyzed",
                permissionsJson.size()
            )

            resultJson.addProperty(
                "note",
                "This classifies a built-in permission list. Actual permissions declared by the APK require parsing AndroidManifest.xml binary XML with aapt2, apktool, or a dedicated AXML parser."
            )

            ToolResult.success(
                metadata = mapOf("result" to resultJson)
            )

        } catch (e: Exception) {
            ToolResult.failure(
                "APK permissions analysis failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }
}


@Suppress("unused")
class ExeAnalyzeTool : AgentTool {

    override val definition = ToolDefinition(
        name = "re.exe.analyze",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Analyze a Windows PE (.exe/.dll) file: headers, sections, architecture and basic metadata.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "exe_path" to mapOf(
                    "type" to "string",
                    "description" to "Path to the EXE/DLL file"
                ),
                "extract_imports" to mapOf(
                    "type" to "boolean",
                    "description" to "Extract imported DLLs/functions (requires PE parser)"
                ),
                "extract_exports" to mapOf(
                    "type" to "boolean",
                    "description" to "Extract exports (requires PE parser)"
                ),
                "extract_sections" to mapOf(
                    "type" to "boolean",
                    "description" to "Extract section headers (default: true)"
                ),
                "extract_resources" to mapOf(
                    "type" to "boolean",
                    "description" to "Extract resources (requires PE parser)"
                )
            ),
            "required" to listOf("exe_path")
        ),
        permissions = setOf(
            ToolPermission.REVERSE_ENGINEERING,
            ToolPermission.READ_WORKSPACE
        )
    )

    override suspend fun execute(
        context: ToolContext,
        arguments: JsonElement
    ): ToolResult {
        return try {
            val args = arguments.asJsonObject

            val exePath = args.get("exe_path")?.asString
                ?: return ToolResult.failure("Missing required parameter: exe_path")

            val extractSections =
                args.get("extract_sections")?.asBoolean ?: true

            val extractImports =
                args.get("extract_imports")?.asBoolean ?: true

            val extractExports =
                args.get("extract_exports")?.asBoolean ?: true

            val extractResources =
                args.get("extract_resources")?.asBoolean ?: false

            val exeFile = File(exePath)

            if (!exeFile.exists()) {
                return ToolResult.failure("EXE file not found: $exePath")
            }

            if (!exeFile.isFile) {
                return ToolResult.failure("EXE path is not a file: $exePath")
            }

            val resultJson = JsonObject()

            resultJson.addProperty(
                "file_name",
                exeFile.name
            )

            resultJson.addProperty(
                "file_size",
                exeFile.length()
            )

            resultJson.addProperty(
                "file_size_mb",
                "%.2f".format(
                    Locale.US,
                    exeFile.length() / 1024.0 / 1024.0
                )
            )

            @Suppress("BlockingMethodInNonBlockingContext")
            RandomAccessFile(exeFile, "r").use { raf ->

                if (raf.length() < 64) {
                    return ToolResult.failure(
                        "File is too small to contain a valid DOS header"
                    )
                }

                val dosHeader = ByteArray(64)
                raf.readFully(dosHeader)

                val mzSignatureOk = (dosHeader[0].toInt() == 'M'.code) && (dosHeader[1].toInt() == 'Z'.code)
                if (!mzSignatureOk) {
                    return ToolResult.failure(
                        "Not a valid PE file (missing MZ signature)"
                    )
                }

                val peOffset = readUInt32LE(dosHeader, 60)
                val peOffsetValid = (peOffset >= 64) && ((peOffset + 24) <= raf.length())
                if (!peOffsetValid) {
                    return ToolResult.failure(
                        "Invalid PE header offset: $peOffset"
                    )
                }

                raf.seek(peOffset)

                val peSignature = ByteArray(4)
                raf.readFully(peSignature)

                if (!peSignature.contentEquals(
                        byteArrayOf(
                            0x50,
                            0x45,
                            0x00,
                            0x00
                        )
                    )
                ) {
                    return ToolResult.failure(
                        "Not a valid PE file (missing PE\\0\\0 signature)"
                    )
                }

                val coffHeader = ByteArray(20)
                raf.readFully(coffHeader)

                val machine =
                    readUInt16LE(coffHeader, 0)

                val numberOfSections =
                    readUInt16LE(coffHeader, 2)

                val timeDateStamp =
                    readUInt32LE(coffHeader, 4)

                val sizeOfOptionalHeader =
                    readUInt16LE(coffHeader, 16)

                val characteristics =
                    readUInt16LE(coffHeader, 18)

                if (sizeOfOptionalHeader < 2) {
                    return ToolResult.failure(
                        "Invalid PE optional header size"
                    )
                }

                val optionalHeader =
                    ByteArray(sizeOfOptionalHeader)

                raf.readFully(optionalHeader)

                val magic =
                    readUInt16LE(optionalHeader, 0)

                val is64Bit = magic == 0x20B
                val is32Bit = magic == 0x10B

                if (!is64Bit && !is32Bit) {
                    return ToolResult.failure(
                        "Unsupported PE optional-header magic: 0x${magic.toString(16)}"
                    )
                }

                val entryPoint =
                    readUInt32LE(optionalHeader, 16)

                val imageBase =
                    if (is64Bit) {
                        readUInt64LE(optionalHeader, 24)
                    } else {
                        readUInt32LE(optionalHeader, 28)
                    }

                resultJson.addProperty(
                    "architecture",
                    if (is64Bit) "x64 (PE32+)" else "x86 (PE32)"
                )

                resultJson.addProperty(
                    "machine_type",
                    when (machine) {
                        0x014c ->
                            "IMAGE_FILE_MACHINE_I386 (x86)"

                        0x8664 ->
                            "IMAGE_FILE_MACHINE_AMD64 (x64)"

                        0x01c0 ->
                            "IMAGE_FILE_MACHINE_ARM"

                        0xAA64 ->
                            "IMAGE_FILE_MACHINE_ARM64"

                        else ->
                            "Unknown (0x${machine.toString(16).uppercase()})"
                    }
                )

                resultJson.addProperty(
                    "number_of_sections",
                    numberOfSections
                )

                resultJson.addProperty(
                    "optional_header_size",
                    sizeOfOptionalHeader
                )

                resultJson.addProperty(
                    "timestamp",
                    SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.US
                    ).format(
                        Date(timeDateStamp * 1000L)
                    )
                )

                resultJson.addProperty(
                    "entry_point",
                    "0x${entryPoint.toString(16).uppercase()}"
                )

                resultJson.addProperty(
                    "image_base",
                    "0x${imageBase.toString(16).uppercase()}"
                )

                val charFlags = mutableListOf<String>()

                if ((characteristics and 0x0001) != 0)
                    charFlags.add("RELOCS_STRIPPED")

                if ((characteristics and 0x0002) != 0)
                    charFlags.add("EXECUTABLE_IMAGE")

                if ((characteristics and 0x0004) != 0)
                    charFlags.add("LINE_NUMS_STRIPPED")

                if ((characteristics and 0x0008) != 0)
                    charFlags.add("LOCAL_SYMS_STRIPPED")

                if ((characteristics and 0x0020) != 0)
                    charFlags.add("LARGE_ADDRESS_AWARE")

                if ((characteristics and 0x0100) != 0)
                    charFlags.add("32BIT_MACHINE")

                if ((characteristics and 0x0200) != 0)
                    charFlags.add("DEBUG_STRIPPED")

                if ((characteristics and 0x2000) != 0)
                    charFlags.add("UP_SYSTEM_ONLY")

                if ((characteristics and 0x2000) != 0)
                    charFlags.add("DLL")

                resultJson.add(
                    "characteristics",
                    gson.toJsonTree(charFlags)
                )

                if (extractSections) {

                    val sectionsJson = JsonArray()

                    for (sectionIndex in 0 until numberOfSections) {

                        val sectionHeader =
                            ByteArray(40)

                        raf.readFully(sectionHeader)

                        val nameBytes =
                            sectionHeader.copyOfRange(0, 8)

                        val name =
                            String(
                                nameBytes,
                                StandardCharsets.US_ASCII
                            ).trimEnd('\u0000')

                        val virtualSize =
                            readUInt32LE(sectionHeader, 8)

                        val virtualAddress =
                            readUInt32LE(sectionHeader, 12)

                        val sizeOfRawData =
                            readUInt32LE(sectionHeader, 16)

                        val pointerToRawData =
                            readUInt32LE(sectionHeader, 20)

                        val characteristicsSec =
                            readUInt32LE(sectionHeader, 36)

                        val sectionJson =
                            JsonObject()

                        sectionJson.addProperty(
                            "name",
                            name
                        )

                        sectionJson.addProperty(
                            "virtual_size",
                            virtualSize
                        )

                        sectionJson.addProperty(
                            "virtual_address",
                            "0x${virtualAddress.toString(16).uppercase()}"
                        )

                        sectionJson.addProperty(
                            "size_of_raw_data",
                            sizeOfRawData
                        )

                        sectionJson.addProperty(
                            "pointer_to_raw_data",
                            pointerToRawData
                        )

                        if (
                            pointerToRawData <= raf.length() &&
                            sizeOfRawData > 0
                        ) {
                            val available =
                                raf.length() - pointerToRawData

                            val safeSize =
                                minOf(
                                    sizeOfRawData,
                                    available
                                )

                            sectionJson.addProperty(
                                "entropy",
                                calculateEntropy(
                                    raf,
                                    pointerToRawData,
                                    safeSize
                                )
                            )
                        } else {
                            sectionJson.addProperty(
                                "entropy",
                                0.0
                            )
                        }

                        val sectionFlags =
                            mutableListOf<String>()

                        if (
                            characteristicsSec and
                            0x00000020L != 0L
                        ) {
                            sectionFlags.add("CODE")
                        }

                        if (
                            characteristicsSec and
                            0x00000040L != 0L
                        ) {
                            sectionFlags.add("INITIALIZED_DATA")
                        }

                        if (
                            characteristicsSec and
                            0x00000080L != 0L
                        ) {
                            sectionFlags.add("UNINITIALIZED_DATA")
                        }

                        if (
                            characteristicsSec and
                            0x20000000L != 0L
                        ) {
                            sectionFlags.add("EXECUTE")
                        }

                        if (
                            characteristicsSec and
                            0x40000000L != 0L
                        ) {
                            sectionFlags.add("READ")
                        }

                        if (
                            characteristicsSec and
                            0x80000000L != 0L
                        ) {
                            sectionFlags.add("WRITE")
                        }

                        sectionJson.add(
                            "characteristics",
                            gson.toJsonTree(sectionFlags)
                        )

                        sectionsJson.add(sectionJson)
                    }

                    resultJson.add(
                        "sections",
                        sectionsJson
                    )
                }

                if (extractImports ||
                    extractExports ||
                    extractResources
                ) {
                    resultJson.addProperty(
                        "parser_note",
                        "Import/export/resource extraction requires a full PE parser. Header and section parsing are implemented here."
                    )
                }
            }

            ToolResult.success(
                metadata = mapOf("result" to resultJson)
            )

        } catch (e: Exception) {
            ToolResult.failure(
                "EXE analysis failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun calculateEntropy(
        raf: RandomAccessFile,
        offset: Long,
        size: Long
    ): Double {

        if (size <= 0) {
            return 0.0
        }

        val sampleSize =
            minOf(size, 1024L * 1024L).toInt()

        val buffer =
            ByteArray(sampleSize)

        raf.seek(offset)

        val read =
            raf.read(
                buffer,
                0,
                sampleSize
            )

        if (read <= 0) {
            return 0.0
        }

        val frequencies =
            IntArray(256)

        for (i in 0 until read) {
            frequencies[
                buffer[i].toInt() and 0xFF
            ]++
        }

        var entropy = 0.0

        for (count in frequencies) {
            if (count > 0) {
                val probability =
                    count.toDouble() / read.toDouble()

                entropy -=
                    probability * log2(probability)
            }
        }

        return entropy
    }
}


@Suppress("unused")
class DexListTool : AgentTool {

    override val definition = ToolDefinition(
        name = "re.dex.list",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Inspect a DEX file. Full class/method extraction requires a DEX parsing library.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "dex_path" to mapOf(
                    "type" to "string",
                    "description" to "Path to the DEX file"
                ),
                "filter" to mapOf(
                    "type" to "string",
                    "description" to "Filter class names by substring"
                )
            ),
            "required" to listOf("dex_path")
        ),
        permissions = setOf(
            ToolPermission.REVERSE_ENGINEERING,
            ToolPermission.READ_WORKSPACE
        )
    )

    override suspend fun execute(
        context: ToolContext,
        arguments: JsonElement
    ): ToolResult {

        return try {

            val args = arguments.asJsonObject

            val dexPath = args.get("dex_path")?.asString
                ?: return ToolResult.failure(
                    "Missing required parameter: dex_path"
                )

            val filter =
                args.get("filter")?.asString

            val dexFile = File(dexPath)

            if (!dexFile.exists()) {
                return ToolResult.failure(
                    "DEX file not found: $dexPath"
                )
            }

            val resultJson = JsonObject()

            resultJson.addProperty(
                "file_name",
                dexFile.name
            )

            resultJson.addProperty(
                "file_size",
                dexFile.length()
            )

            resultJson.addProperty(
                "is_dex",
                isDexFile(dexFile)
            )

            resultJson.addProperty(
                "filter",
                filter ?: ""
            )

            resultJson.addProperty(
                "note",
                "Class and method extraction requires dexlib2 or another DEX parser."
            )

            resultJson.add(
                "classes",
                JsonArray()
            )

            ToolResult.success(
                metadata = mapOf("result" to resultJson)
            )

        } catch (e: Exception) {
            ToolResult.failure(
                "DEX listing failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun isDexFile(file: File): Boolean {
        if (file.length() < 4) return false

        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)

            return magic.contentEquals(
                byteArrayOf(
                    0x64,
                    0x65,
                    0x78,
                    0x0A
                )
            )
        }
    }
}


@Suppress("unused")
class StringExtractTool : AgentTool {

    override val definition = ToolDefinition(
        name = "re.strings",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Extract ASCII and UTF-16LE strings from binary files.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "file_path" to mapOf(
                    "type" to "string",
                    "description" to "Path to the binary file"
                ),
                "min_length" to mapOf(
                    "type" to "integer",
                    "description" to "Minimum string length (default: 4)"
                ),
                "max_strings" to mapOf(
                    "type" to "integer",
                    "description" to "Maximum strings to return (default: 500)"
                ),
                "encoding" to mapOf(
                    "type" to "string",
                    "description" to "ascii, utf16, or both"
                )
            ),
            "required" to listOf("file_path")
        ),
        permissions = setOf(
            ToolPermission.REVERSE_ENGINEERING,
            ToolPermission.READ_WORKSPACE
        )
    )

    override suspend fun execute(
        context: ToolContext,
        arguments: JsonElement
    ): ToolResult {

        return try {

            val args = arguments.asJsonObject

            val filePath = args.get("file_path")?.asString
                ?: return ToolResult.failure(
                    "Missing required parameter: file_path"
                )

            val minLength =
                (args.get("min_length")?.asInt ?: 4)
                    .coerceAtLeast(1)

            val maxStrings =
                (args.get("max_strings")?.asInt ?: 500)
                    .coerceAtLeast(1)

            val encoding =
                (args.get("encoding")?.asString ?: "both")
                    .lowercase(Locale.US)

            if (encoding !in setOf("ascii", "utf16", "both")) {
                return ToolResult.failure(
                    "Invalid encoding '$encoding'. Use ascii, utf16, or both."
                )
            }

            val file = File(filePath)

            if (!file.exists()) {
                return ToolResult.failure(
                    "File not found: $filePath"
                )
            }

            val resultJson = JsonObject()

            resultJson.addProperty(
                "file_name",
                file.name
            )

            resultJson.addProperty(
                "file_size",
                file.length()
            )

            val strings = mutableListOf<String>()

            fun addString(value: String) {
                if (
                    value.length >= minLength &&
                    strings.size < maxStrings
                ) {
                    strings.add(value)
                }
            }

            @Suppress("BlockingMethodInNonBlockingContext")
            RandomAccessFile(file, "r").use { raf ->

                val buffer =
                    ByteArray(8192)

                val asciiBuilder =
                    StringBuilder()

                while (strings.size < maxStrings) {

                    val bytesRead =
                        raf.read(buffer)

                    if (bytesRead <= 0) {
                        break
                    }

                    for (i in 0 until bytesRead) {

                        val b =
                            buffer[i].toInt() and 0xFF

                        if (
                            encoding == "ascii" ||
                            encoding == "both"
                        ) {
                            if (b in 32..126) {
                                asciiBuilder.append(
                                    b.toChar()
                                )
                            } else {
                                if (asciiBuilder.length >= minLength) {
                                    addString(
                                        asciiBuilder.toString()
                                    )
                                }

                                asciiBuilder.setLength(0)

                                if (
                                    strings.size >= maxStrings
                                ) {
                                    break
                                }
                            }
                        }
                    }
                }

                if (
                    asciiBuilder.length >= minLength &&
                    strings.size < maxStrings
                ) {
                    addString(
                        asciiBuilder.toString()
                    )
                }
            }

            if (
                encoding == "utf16" ||
                encoding == "both"
            ) {
                @Suppress("BlockingMethodInNonBlockingContext")
                RandomAccessFile(file, "r").use { raf ->

                    val buffer =
                        ByteArray(8192)

                    val utf16Builder =
                        StringBuilder()

                    while (strings.size < maxStrings) {

                        val read =
                            raf.read(buffer)

                        if (read <= 1) {
                            break
                        }

                        var i = 0

                        while (
                            i + 1 < read &&
                            strings.size < maxStrings
                        ) {

                            val low =
                                buffer[i].toInt() and 0xFF

                            val high =
                                buffer[i + 1].toInt() and 0xFF

                            if (
                                low in 32..126 &&
                                high == 0
                            ) {
                                utf16Builder.append(
                                    low.toChar()
                                )
                            } else {

                                if (
                                    utf16Builder.length >= minLength
                                ) {
                                    addString(
                                        utf16Builder.toString()
                                    )
                                }

                                utf16Builder.setLength(0)
                            }

                            i += 2
                        }
                    }

                    if (
                        utf16Builder.length >= minLength &&
                        strings.size < maxStrings
                    ) {
                        addString(
                            utf16Builder.toString()
                        )
                    }
                }
            }

            resultJson.addStringArray(
                "strings",
                strings
            )

            resultJson.addProperty(
                "count",
                strings.size
            )

            resultJson.addProperty(
                "truncated",
                strings.size >= maxStrings
            )

            ToolResult.success(
                metadata = mapOf("result" to resultJson)
            )

        } catch (e: Exception) {
            ToolResult.failure(
                "String extraction failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }
}


@Suppress("unused")
class BinaryInfoTool : AgentTool {

    override val definition = ToolDefinition(
        name = "re.binary.info",
        category = ToolCategory.REVERSE_ENGINEERING,
        description = "Identify binary file type and provide basic format information.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "file_path" to mapOf(
                    "type" to "string",
                    "description" to "Path to the binary file"
                )
            ),
            "required" to listOf("file_path")
        ),
        permissions = setOf(
            ToolPermission.REVERSE_ENGINEERING,
            ToolPermission.READ_WORKSPACE
        )
    )

    override suspend fun execute(
        context: ToolContext,
        arguments: JsonElement
    ): ToolResult {

        return try {

            val args = arguments.asJsonObject

            val filePath = args.get("file_path")?.asString
                ?: return ToolResult.failure(
                    "Missing required parameter: file_path"
                )

            val file = File(filePath)

            if (!file.exists()) {
                return ToolResult.failure(
                    "File not found: $filePath"
                )
            }

            if (!file.isFile) {
                return ToolResult.failure(
                    "Path is not a file: $filePath"
                )
            }

            val resultJson = JsonObject()

            resultJson.addProperty(
                "file_name",
                file.name
            )

            resultJson.addProperty(
                "file_size",
                file.length()
            )

            resultJson.addProperty(
                "file_size_mb",
                "%.2f".format(
                    Locale.US,
                    file.length() / 1024.0 / 1024.0
                )
            )

            if (file.length() < 4) {
                resultJson.addProperty(
                    "file_type",
                    "Unknown / too small"
                )

                return ToolResult.success(
                    metadata = mapOf(
                        "result" to resultJson
                    )
                )
            }

            @Suppress("BlockingMethodInNonBlockingContext")
            RandomAccessFile(file, "r").use { raf ->

                val header =
                    ByteArray(minOf(16L, file.length()).toInt())

                raf.readFully(header)

                val magic =
                    header.joinToString(" ") {
                        "%02X".format(
                            it.toInt() and 0xFF
                        )
                    }

                resultJson.addProperty(
                    "magic_bytes",
                    magic
                )

                val fileType = when {
                    header.hasPrefix(
                        byteArrayOf(
                            0x4D,
                            0x5A
                        )
                    ) ->
                        "PE (Windows EXE/DLL)"

                    header.hasPrefix(
                        byteArrayOf(
                            0x7F,
                            0x45,
                            0x4C,
                            0x46
                        )
                    ) ->
                        "ELF (Linux/Unix binary)"

                    header.hasPrefix(
                        byteArrayOf(
                            0xFE.toByte(),
                            0xED.toByte(),
                            0xFA.toByte(),
                            0xCE.toByte()
                        )
                    ) ->
                        "Mach-O 32-bit"

                    header.hasPrefix(
                        byteArrayOf(
                            0xFE.toByte(),
                            0xED.toByte(),
                            0xFA.toByte(),
                            0xCF.toByte()
                        )
                    ) ->
                        "Mach-O 64-bit"

                    header.hasPrefix(
                        byteArrayOf(
                            0xCE.toByte(),
                            0xFA.toByte(),
                            0xED.toByte(),
                            0xFE.toByte()
                        )
                    ) ->
                        "Mach-O 32-bit big-endian"

                    header.hasPrefix(
                        byteArrayOf(
                            0xCF.toByte(),
                            0xFA.toByte(),
                            0xED.toByte(),
                            0xFE.toByte()
                        )
                    ) ->
                        "Mach-O 64-bit big-endian"

                    header.hasPrefix(
                        byteArrayOf(
                            0xCA.toByte(),
                            0xFE.toByte(),
                            0xBA.toByte(),
                            0xBE.toByte()
                        )
                    ) ->
                        "Java Class / Mach-O Universal"

                    header.hasPrefix(
                        byteArrayOf(
                            0x50,
                            0x4B,
                            0x03,
                            0x04
                        )
                    ) ->
                        "ZIP/APK/JAR"

                    header.hasPrefix(
                        byteArrayOf(
                            0x1F,
                            0x8B.toByte()
                        )
                    ) ->
                        "GZIP"

                    header.hasPrefix(
                        byteArrayOf(
                            0x64,
                            0x65,
                            0x78,
                            0x0A
                        )
                    ) ->
                        "DEX (Android)"

                    else ->
                        "Unknown"
                }

                resultJson.addProperty(
                    "file_type",
                    fileType
                )

                if (
                    header.hasPrefix(
                        byteArrayOf(
                            0x7F,
                            0x45,
                            0x4C,
                            0x46
                        )
                    ) &&
                    header.size >= 16
                ) {

                    val elfClass =
                        header[4].toInt() and 0xFF

                    val elfData =
                        header[5].toInt() and 0xFF

                    val elfVersion =
                        header[6].toInt() and 0xFF

                    val elfOsAbi =
                        header[7].toInt() and 0xFF

                    resultJson.addProperty(
                        "elf_class",
                        when (elfClass) {
                            1 -> "32-bit"
                            2 -> "64-bit"
                            else -> "Unknown"
                        }
                    )

                    resultJson.addProperty(
                        "elf_endianness",
                        when (elfData) {
                            1 -> "Little Endian"
                            2 -> "Big Endian"
                            else -> "Unknown"
                        }
                    )

                    resultJson.addProperty(
                        "elf_version",
                        elfVersion
                    )

                    resultJson.addProperty(
                        "elf_osabi",
                        when (elfOsAbi) {
                            0 -> "System V"
                            1 -> "HP-UX"
                            2 -> "NetBSD"
                            3 -> "Linux"
                            6 -> "Solaris"
                            7 -> "AIX"
                            8 -> "IRIX"
                            9 -> "FreeBSD"
                            12 -> "OpenBSD"
                            else -> "Unknown ($elfOsAbi)"
                        }
                    )
                }
            }

            ToolResult.success(
                metadata = mapOf(
                    "result" to resultJson
                )
            )

        } catch (e: Exception) {
            ToolResult.failure(
                "Binary info failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }
}

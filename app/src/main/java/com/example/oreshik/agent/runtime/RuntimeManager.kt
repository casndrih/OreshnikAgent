package com.example.oreshik.agent.runtime

import android.content.Context
import android.os.storage.StorageManager
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.agent.security.RootManager
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** App-owned runtime registry with optional checksum-verified runtime ZIP downloads. */
class RuntimeManager(private val context: Context) {

    init {
        AppLogger.i(
            TAG,
            "RuntimeManager initialized (workspace=${AgentConfig.workspaceRoot(context).absolutePath})",
        )
    }

    val workspace: File get() = AgentConfig.workspaceRoot(context)
    val runtimes: File = File(context.filesDir, "runtimes").apply { mkdirs() }
    val python: PythonRuntime by lazy { PythonRuntime(context) }
    private val installer = ToolPackInstaller(context)
    private val gson = Gson()

    companion object {
        private const val TAG = "RuntimeManager"
        private const val RUNTIME_CATALOG_URL = "https://raw.githubusercontent.com/casndrih/OreshnikAgent/main/runtime-catalog.json"


        /** Bundled packs in extraction-priority order (core first, heavy last). */
        val CORE_PACKS = listOf(
            "libs", "jdk", "node", "cli-tools", "php", "gradle", "gradle9", "kotlin",
            "android-sdk", "clang", "apktool", "radare2", "sqlite",
        )
        val HEAVY_PACKS = listOf("jadx", "ndk", "ghidra")
        val ALL_PACKS = CORE_PACKS + HEAVY_PACKS
        // 1.0.5: gradle pack rebuilt as Gradle 8.14.6 (was 9.6.1 content, 8.13 metadata)
        // and new gradle9 pack added (Gradle 9.8.1).
        // 1.0.6: gradle launchers patched for Android toybox sh (xargs quote-stripping bug).
        // 1.0.7: android-sdk pack extended with build-tools 35.0.0/36.0.0 and platforms
        // android-35/android-36 (aarch64 natives reused; official Google jars/scripts).
        // 1.0.8: android-sdk pack includes Android 17 SDK platforms android-37.0/android-37.1.
        const val PACK_SET_VERSION = "1.0.7"
        private const val ANDROID_SDK_PACK_VERSION = "1.0.8"
        // Reinstall PHP so existing packs receive executable SELinux labels on php/composer.
        private const val PHP_PACK_VERSION = "1.0.8"
        // Refresh only this shared-libraries pack for existing installs.
        private const val LIBS_PACK_VERSION = "1.0.8"
        /** Official sqlmap source wheel bundled in toolpacks/sqlmap.whl. */
        const val SQLMAP_VERSION = "1.10.10"
        private const val MAX_RUNTIME_ARCHIVE_ENTRIES = 100_000
        private const val MAX_RUNTIME_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_SQLMAP_ARCHIVE_BYTES = 512L * 1024 * 1024
        private const val MIN_FREE_SPACE_AFTER_IMPORT = 32L * 1024 * 1024
        private val runtimeMutationMutex = Mutex()
        private val provisionListeners = mutableListOf<(String, Int) -> Unit>()
        @Volatile
        private var lastProvisionProgress: Pair<String, Int>? = null
        private val packIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        @Volatile
        private var provisioningInProgress = false

        private fun packVersion(packId: String): String =
            when (packId) {
                "libs" -> LIBS_PACK_VERSION
                "php" -> PHP_PACK_VERSION
                "sqlmap" -> SQLMAP_VERSION
                "android-sdk" -> ANDROID_SDK_PACK_VERSION
                else -> PACK_SET_VERSION
            }
    }

    data class RuntimeInfo(
        val id: String,
        val version: String,
        val description: String,
        val entrypoints: Map<String, String>,
        val ready: Boolean,
        val root: File,
    )

    private data class ManifestData(
        val id: String,
        val version: String,
        val entrypoints: Map<String, String>,
    )

    // Bundled pack ids → (id, title, description)
    private val known = mapOf(
        "libs" to Triple(
            "libs",
            "Shared Libraries",
            "Shared native libraries and TLS certificates for runtime packs",
        ),
        "jdk" to Triple("jdk", "Java/JDK 21", "OpenJDK 21 runtime, javac, jar and JVM tools"),
        "gradle" to Triple(
            "gradle",
            "Gradle 8",
            "Gradle 8.14.6 build engine. Its Linux ARM64 native-platform JAR is bundled; Android builds use Gradle's JVM fallback because Android uses Bionic.",
        ),
        "gradle9" to Triple(
            "gradle9",
            "Gradle 9",
            "Gradle 9.8.1 build engine. Its Linux ARM64 native-platform JAR is bundled; Android builds use Gradle's JVM fallback because Android uses Bionic.",
        ),
        "kotlin" to Triple("kotlin", "Kotlin", "Kotlin compiler (kotlinc) and standard tools"),
        "node" to Triple("node", "Node.js & npm", "JavaScript runtime and npm package manager"),
        "php" to Triple("php", "PHP & Composer", "PHP interpreter and Composer dependency manager"),
        "clang" to Triple(
            "clang",
            "Clang & LLVM",
            "Clang C/C++ compiler, LLD linker, LLVM binutils, make, cmake, ninja",
        ),
        "cli-tools" to Triple(
            "cli-tools",
            "CLI Tools",
            "git, gh, bash, ssh, curl, wget, openssl, zip, jq, rg, vim, nano and more",
        ),
        "android-sdk" to Triple(
            "android-sdk",
            "Android SDK",
            "build-tools 34.0.0/35.0.0/36.0.0 (aapt2, apksigner, d8, zipalign per version; " +
                "-35/-36 entrypoint aliases), ARM64 platform-tools 37.0.0, " +
                "android-34/35/36/37.0/37.1 platforms, licenses",
        ),
        "apktool" to Triple(
            "apktool",
            "Apktool",
            "APK decode/rebuild (resources, manifest, smali)",
        ),
        "jadx" to Triple("jadx", "Jadx", "DEX/Java decompiler (jadx, jadx-gui)"),
        "radare2" to Triple(
            "radare2",
            "radare2",
            "Reverse-engineering framework (r2, rabin2, rasm2, rax2)",
        ),
        "ndk" to Triple(
            "ndk",
            "Android NDK (sysroot)",
            "NDK r29 sysroot + build/ + sources/ — pair with bundled clang via --sysroot",
        ),
        "ghidra" to Triple(
            "ghidra",
            "Ghidra",
            "Ghidra reverse-engineering suite (headless analyzeHeadless + core)",
        ),
        "sqlite" to Triple(
            "sqlite",
            "SQLite 3",
            "SQLite database engine: sqlite3 shell + libsqlite3",
        ),
        "sqlmap" to Triple(
            "sqlmap",
            "sqlmap",
            "Bundled official Python SQL injection testing engine",
        ),
        "databases" to Triple(
            "databases",
            "Database Tools (download)",
            "mdbtools (Access/Jet .mdb/.accdb) + unixODBC runtime tools",
        ),
    )


    val provisioning: Boolean get() = provisioningInProgress

    @Volatile
    var lastProvisionError: String? = null
        private set

    fun addProvisionListener(l: (String, Int) -> Unit) {
        val current = synchronized(provisionListeners) {
            provisionListeners.add(l)
            lastProvisionProgress
        }
        current?.let { (step, percent) -> runCatching { l(step, percent) } }
    }

    // Public API kept for listener lifecycle symmetry with addProvisionListener.
    @Suppress("unused")
    fun removeProvisionListener(l: (String, Int) -> Unit) {
        synchronized(provisionListeners) { provisionListeners.remove(l) }
    }

    private fun notifyProgress(step: String, percent: Int) {
        val listeners = synchronized(provisionListeners) {
            lastProvisionProgress = step to percent
            provisionListeners.toList()
        }
        listeners.forEach { listener -> runCatching { listener(step, percent) } }
    }

    /** Resolve a runtime archive from app-packaged assets. */
    private suspend fun resolvePackArchive(packId: String, stagingDir: File): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = context.assets.open("toolpacks/$packId.tgz")
                stagingDir.mkdirs()
                val out = File(stagingDir, "$packId.tgz")
                stream.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
                out
            }.getOrNull()
        }

    /**
     * Make sure a bundled runtime pack is complete, not just marked installed.
     * The version marker can outlive deleted or partially extracted executables.
     */
    private fun isBundledPackReady(packId: String, destination: File): Boolean {
        val marker = File(destination, ".pack-version")
        if (!marker.isFile || runCatching { marker.readText().trim() }.getOrNull() != packVersion(packId)) {
            return false
        }
        val manifest = manifestFor(packId) ?: return true
        if (manifest.entrypoints.values.any { relative ->
                val executable = File(destination, relative)
                !executable.isFile || !executable.canExecute()
            }
        ) return false
        when (packId) {
            "jdk" -> if (!File(destination, "lib/modules").isFile) return false
            "gradle", "gradle9" -> {
                if (!File(destination, "lib").listFiles().orEmpty().any { it.isFile && it.extension == "jar" }) return false
                if (!hasEmbeddedAndroidArm64NativePlatform(destination)) return false
            }
            "android-sdk" -> {
                val requiredSdkFiles = listOf(
                    "licenses/android-sdk-license",
                    "platform-tools/package.xml",
                    "build-tools/34.0.0/package.xml",
                    "build-tools/35.0.0/package.xml",
                    "build-tools/36.0.0/package.xml",
                    "platforms/android-34/android.jar",
                    "platforms/android-35/android.jar",
                    "platforms/android-36/android.jar",
                    "platforms/android-37.0/android.jar",
                    "platforms/android-37.0/package.xml",
                    "platforms/android-37.1/android.jar",
                    "platforms/android-37.1/package.xml",
                )
                if (requiredSdkFiles.any { !File(destination, it).isFile }) return false
            }
        }
        val installedManifest = readManifest(destination) ?: return false
        return installedManifest.id == packId
    }

    /**
     * Gradle ships libnative-platform.so inside its versioned Linux ARM64 JAR.
     * Check that the runtime archive includes the file so a damaged Gradle pack
     * is restored from the APK. Android cannot load that glibc-targeted binary,
     * so Android builds explicitly use Gradle's JVM native-services fallback.
     */
    private fun hasEmbeddedAndroidArm64NativePlatform(gradleRoot: File): Boolean = runCatching {
        val nativeJar = File(gradleRoot, "lib").listFiles().orEmpty().firstOrNull {
            it.isFile && it.name.startsWith("native-platform-linux-aarch64-") && it.extension == "jar"
        } ?: return false
        ZipFile(nativeJar).use { zip ->
            zip.getEntry("net/rubygrapefruit/platform/linux-aarch64/libnative-platform.so") != null
        }
    }.getOrDefault(false)

    /**
     * Repair the packs needed by an Android APK build. Only the relevant packs are
     * reinstalled, so a damaged aapt2 or Gradle install does not trigger a full
     * reinstall of the much larger optional runtimes.
     *
     * Android Gradle Plugin can download missing SDK platforms/build-tools while
     * building; this method restores the bundled ARM64-compatible SDK tools and
     * accepted SDK licenses that make that fallback work.
     */
    suspend fun ensureAndroidBuildToolchain(
        gradlePack: String,
        onProgress: (suspend (String) -> Unit)? = null,
    ): String? {
        require(gradlePack == "gradle" || gradlePack == "gradle9") {
            "Unsupported managed Gradle runtime: $gradlePack"
        }
        val repairError = withContext(Dispatchers.IO) {
            runtimeMutationMutex.lock()
            try {
                val packIds = listOf("libs", "jdk", gradlePack, "android-sdk")
                val missingOrDamaged = packIds.filter { packId ->
                    !isBundledPackReady(packId, runtimePath(packId))
                }
                if (missingOrDamaged.isEmpty()) return@withContext null
                onProgress?.invoke("Repairing Android build runtime: ${missingOrDamaged.joinToString()}")

                val root = RootManager(context)
                if (!root.canUseRoot()) {
                    return@withContext "Cannot repair ${missingOrDamaged.joinToString()} because root access is unavailable. " +
                        "Grant root access, then retry the build."
                }

                for (packId in missingOrDamaged) {
                    val destination = runtimePath(packId)
                    val manifest = manifestFor(packId)
                        ?: return@withContext "No bundled runtime manifest is available for $packId."
                    val staging = File(context.cacheDir, "packs").apply { mkdirs() }
                    val archive = resolvePackArchive(packId, staging)
                        ?: return@withContext "$packId is missing or damaged, and its repair archive is not present in this APK."
                    try {
                        onProgress?.invoke("Restoring $packId from the APK runtime pack…")
                        val report = installer.installPack(
                            packId = packId,
                            archive = archive,
                            destination = destination,
                            expectedFiles = manifest.entrypoints.values,
                        ) { step -> onProgress?.invoke("$packId: $step") }
                        if (!report.ok) {
                            return@withContext "Could not repair $packId: ${report.detail}"
                        }
                        File(destination, "runtime.json").writeText(gson.toJson(manifest))
                        File(destination, ".pack-version").writeText(packVersion(packId))
                        if (!isBundledPackReady(packId, destination)) {
                            return@withContext "The repaired $packId runtime is still incomplete."
                        }
                    } finally {
                        archive.delete()
                    }
                }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "Android build runtime repair failed: ${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                runtimeMutationMutex.unlock()
            }
        }

        if (repairError != null) return repairError

        return null
    }

    /** Manifest definition per pack id (entrypoints relative to pack root). */
    fun manifestFor(packId: String): RuntimeInfo? = when (packId) {
        "sqlmap" -> RuntimeInfo(
            "sqlmap",
            SQLMAP_VERSION,
            knownDescription("sqlmap"),
            emptyMap(),
            ready = true,
            root = runtimePath("sqlmap"),
        )

        "libs" -> RuntimeInfo(
            "libs",
            "1.0",
            knownDescription("libs"),
            emptyMap(),
            ready = true,
            root = runtimePath("libs"),
        )

        "jdk" -> RuntimeInfo(
            "jdk",
            "21.0.12",
            knownDescription("jdk"),
            mapOf(
                "java" to "bin/java",
                "javac" to "bin/javac",
                "jar" to "bin/jar",
                "keytool" to "bin/keytool",
                "jshell" to "bin/jshell",
            ),
            ready = true,
            root = runtimePath("jdk"),
        )

        "gradle" -> RuntimeInfo(
            "gradle",
            "8.14.6",
            knownDescription("gradle"),
            // Only the default pack exposes the `gradle` entrypoint so that
            // resolution is deterministic when both Gradle packs are installed.
            mapOf("gradle" to "bin/gradle"),
            ready = true,
            root = runtimePath("gradle"),
        )

        "gradle9" -> RuntimeInfo(
            "gradle9",
            "9.8.1",
            knownDescription("gradle9"),
            // The binary keeps its standard `gradle` name inside the pack; the
            // entrypoint key `gradle9` is the tool-facing name so both runtimes
            // stay resolvable side by side.
            mapOf("gradle9" to "bin/gradle"),
            ready = true,
            root = runtimePath("gradle9"),
        )

        "kotlin" -> RuntimeInfo(
            "kotlin",
            "2.x",
            knownDescription("kotlin"),
            mapOf(
                "kotlinc" to "bin/kotlinc",
                "kotlin" to "bin/kotlin",
            ),
            ready = true,
            root = runtimePath("kotlin"),
        )

        "node" -> RuntimeInfo(
            "node",
            "26.x",
            knownDescription("node"),
            mapOf(
                "node" to "bin/node",
                "npm" to "bin/npm",
                "npx" to "bin/npx",
            ),
            ready = true,
            root = runtimePath("node"),
        )

        "php" -> RuntimeInfo(
            "php",
            "8.5.1",
            knownDescription("php"),
            mapOf(
                "php" to "bin/php",
                "composer" to "bin/composer",
            ),
            ready = true,
            root = runtimePath("php"),
        )

        "clang" -> RuntimeInfo(
            "clang",
            "21.x",
            knownDescription("clang"),
            mapOf(
                "clang" to "bin/clang",
                "clang++" to "bin/clang++",
                "ld.lld" to "bin/ld.lld",
                "llvm-ar" to "bin/llvm-ar",
                "make" to "bin/make",
                "cmake" to "bin/cmake",
                "ninja" to "bin/ninja",
            ),
            ready = true,
            root = runtimePath("clang"),
        )

        "cli-tools" -> RuntimeInfo(
            "cli-tools", "1.0", knownDescription("cli-tools"), mapOf(
                "git" to "bin/git", "gh" to "bin/gh", "bash" to "bin/bash", "ssh" to "bin/ssh",
                "curl" to "bin/curl", "wget" to "bin/wget", "openssl" to "bin/openssl",
                "zip" to "bin/zip", "unzip" to "bin/unzip", "jq" to "bin/jq", "rg" to "bin/rg",
                "less" to "bin/less", "file" to "bin/file",
                "sed" to "bin/sed", "gawk" to "bin/gawk", "vim" to "bin/vim", "nano" to "bin/nano",
            ),
            ready = true,
            root = runtimePath("cli-tools"),
        )

        "android-sdk" -> RuntimeInfo(
            "android-sdk", "platforms 34/35/36/37.0/37.1; platform-tools 37.0.0; build-tools 34/35/36",
            knownDescription("android-sdk"), mapOf(
                // Default build-tools (PATH exposes these directly).
                "aapt2" to "build-tools/34.0.0/aapt2", "aapt" to "build-tools/34.0.0/aapt",
                "apksigner" to "build-tools/34.0.0/apksigner", "d8" to "build-tools/34.0.0/d8",
                "zipalign" to "build-tools/34.0.0/zipalign", "adb" to "platform-tools/adb",
                // Build-tools 35.0.0 (aarch64 natives + official Google jars, POSIX wrappers).
                "aapt2-35" to "build-tools/35.0.0/aapt2",
                "apksigner-35" to "build-tools/35.0.0/apksigner",
                "d8-35" to "build-tools/35.0.0/d8",
                "zipalign-35" to "build-tools/35.0.0/zipalign",
                // Build-tools 36.0.0 (aarch64 natives + official Google jars, POSIX wrappers).
                "aapt2-36" to "build-tools/36.0.0/aapt2",
                "apksigner-36" to "build-tools/36.0.0/apksigner",
                "d8-36" to "build-tools/36.0.0/d8",
                "zipalign-36" to "build-tools/36.0.0/zipalign",
            ),
            ready = true,
            root = runtimePath("android-sdk"),
        )

        "apktool" -> RuntimeInfo(
            "apktool",
            "2.x",
            knownDescription("apktool"),
            mapOf(
                "apktool" to "bin/apktool",
                "apktool.jar" to "bin/apktool.jar",
            ),
            ready = true,
            root = runtimePath("apktool"),
        )

        "jadx" -> RuntimeInfo(
            "jadx",
            "1.5.x",
            knownDescription("jadx"),
            mapOf(
                "jadx" to "bin/jadx",
                "jadx-gui" to "bin/jadx-gui",
            ),
            ready = true,
            root = runtimePath("jadx"),
        )

        "radare2" -> RuntimeInfo(
            "radare2", "6.x", knownDescription("radare2"), mapOf(
                "r2" to "bin/r2", "rabin2" to "bin/rabin2", "rasm2" to "bin/rasm2",
                "rax2" to "bin/rax2", "radiff2" to "bin/radiff2", "rafind2" to "bin/rafind2",
                "rahash2" to "bin/rahash2",
            ),
            ready = true,
            root = runtimePath("radare2"),
        )

        "ndk" -> RuntimeInfo(
            "ndk",
            "r29",
            knownDescription("ndk"),
            mapOf(
                "ndk-build" to "ndk-build",
                "ndk-which" to "ndk-which",
            ),
            ready = true,
            root = runtimePath("ndk"),
        )

        "ghidra" -> RuntimeInfo(
            "ghidra",
            "12.1.2",
            knownDescription("ghidra"),
            mapOf(
                "analyzeHeadless" to "support/analyzeHeadless",
                "ghidraRun" to "ghidraRun",
            ),
            ready = true,
            root = runtimePath("ghidra"),
        )

        "sqlite" -> RuntimeInfo(
            "sqlite",
            "3.54.1",
            knownDescription("sqlite"),
            mapOf(
                "sqlite3" to "bin/sqlite3",
            ),
            ready = true,
            root = runtimePath("sqlite"),
        )

        "databases" -> RuntimeInfo(
            "databases",
            "1.0.1-1",
            knownDescription("databases"),
            mapOf(
                "mdb-tables" to "bin/mdb-tables",
                "mdb-export" to "bin/mdb-export",
                "mdb-schema" to "bin/mdb-schema",
                "mdb-ver" to "bin/mdb-ver",
                "odbcinst" to "bin/odbcinst",
            ),
            ready = true,
            root = runtimePath("databases"),
        )

        else -> null
    }

    private fun knownDescription(id: String): String {
        val (_, title, description) = (known[id] ?: return id)
        return "$title — $description"
    }

    /**
     * Provision all bundled packs. Idempotent: skips packs whose .pack-version
     * marker matches its current pack version. The pure-Python sqlmap pack is
     * provisioned before root-dependent native packs. Returns true when none fail.
     */
    suspend fun ensureProvisioned(): Boolean = withContext(Dispatchers.IO) {
        runtimeMutationMutex.lock()
        provisioningInProgress = true
        notifyProgress("Starting bundled APK toolpack setup…", 0)
        var finished = false
        var succeeded = false
        try {
            lastProvisionError = null
            val failures = mutableListOf<String>()
            try {
                ensureSqlmapPackLocked { step ->
                    notifyProgress(step, if (step.contains("MB extracted")) -1 else 1)
                }
            } catch (t: Exception) {
                AppLogger.e(TAG, "Bundled sqlmap runtime provisioning failed", t)
                notifyProgress("sqlmap failed: ${t.message ?: t.javaClass.simpleName}", -1)
                failures.add("sqlmap")
            }

            val root = RootManager(context)
            if (!root.canUseRoot()) {
                val msg = "Root unavailable — bundled toolpacks need root tar extraction. " +
                        "Grant su access to ${context.packageName} and reopen the app."
                AppLogger.e("RuntimeManager", msg)
                lastProvisionError = (listOf(msg) + failures.map { "Failed pack: $it" }).joinToString("\n")
                notifyProgress(msg, -1)
                finished = true
                return@withContext false
            }

            cleanLegacyStubDirs()

            val total = ALL_PACKS.size
            var done = 0

            for (packId in ALL_PACKS) {
                done++
                val dest = File(runtimes, packId)
                if (isBundledPackReady(packId, dest)) {
                    notifyProgress("$packId up to date", (done * 100) / total)
                    continue
                }

                val staging = File(context.cacheDir, "packs").apply { mkdirs() }
                val archive = resolvePackArchive(packId, staging)
                if (archive == null) {
                    val msg =
                        "$packId not found in packaged assets — skipping"
                    AppLogger.w("RuntimeManager", msg)
                    notifyProgress(msg, (done * 100) / total)
                    failures.add(packId)
                    continue
                }

                val manifest = manifestFor(packId)
                val installReport = installer.installPack(
                    packId = packId,
                    archive = archive,
                    destination = dest,
                    expectedFiles = manifest?.entrypoints?.values.orEmpty(),
                ) { step ->
                    notifyProgress(step, (done * 100) / total)
                }
                archive.delete()
                if (!installReport.ok) {
                    AppLogger.e(TAG, "Pack $packId failed: ${installReport.detail}")
                    notifyProgress("$packId failed", (done * 100) / total)
                    failures.add(packId)
                    continue
                }

                if (manifest != null) {
                    val missing = manifest.entrypoints.values.filter { !File(dest, it).isFile }
                    if (missing.isNotEmpty()) {
                        AppLogger.e("RuntimeManager", "Pack $packId missing entrypoints: $missing")
                        notifyProgress("$packId incomplete", (done * 100) / total)
                        failures.add(packId)
                        continue
                    }
                    File(dest, "runtime.json").writeText(gson.toJson(manifest))
                }
                File(dest, ".pack-version").writeText(packVersion(packId))
                notifyProgress("$packId installed", (done * 100) / total)
                AppLogger.i("RuntimeManager", "Pack $packId provisioned at ${dest.absolutePath}")
            }

            if (failures.isNotEmpty()) {
                lastProvisionError = "Failed packs: ${failures.joinToString(", ")}"
            }
            succeeded = failures.isEmpty()
            finished = true
            succeeded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            lastProvisionError = t.message ?: t.javaClass.simpleName
            AppLogger.e("RuntimeManager", "Provisioning failed", t)
            finished = true
            false
        } finally {
            provisioningInProgress = false
            if (finished) {
                notifyProgress(
                    if (succeeded) "Bundled APK toolpacks are ready"
                    else "Bundled toolpack setup finished with issues: ${lastProvisionError ?: "unknown error"}",
                    if (succeeded) 100 else -1,
                )
            } else {
                notifyProgress("Bundled toolpack setup stopped", -1)
            }
            runtimeMutationMutex.unlock()
        }
    }

    /**
     * Install the official sqlmap wheel bundled in the APK into the app-private
     * runtime directory. This pure-Python pack deliberately needs no root access.
     */
    suspend fun ensureSqlmapPack(onProgress: (suspend (String) -> Unit)? = null): RuntimeInfo =
        withContext(Dispatchers.IO) {
            runtimeMutationMutex.lock()
            try {
                ensureSqlmapPackLocked { step ->
                    val percent = when {
                        step.contains("MB extracted") -> -1
                        step.startsWith("Unpacking bundled sqlmap") -> 1
                        else -> 100
                    }
                    notifyProgress(step, percent)
                    onProgress?.invoke(step)
                }
            } finally {
                runtimeMutationMutex.unlock()
            }
        }

    /** Caller must hold [runtimeMutationMutex]. */
    private suspend fun ensureSqlmapPackLocked(onProgress: suspend (String) -> Unit): RuntimeInfo {
        val destination = runtimePath("sqlmap")
        if (isSqlmapPackReady(destination)) {
            onProgress("Bundled sqlmap $SQLMAP_VERSION is ready")
            return checkNotNull(manifestFor("sqlmap"))
        }

        onProgress("Unpacking bundled sqlmap $SQLMAP_VERSION…")
        val staging = File(runtimes, ".staging-sqlmap-${UUID.randomUUID()}")
        val backup = File(runtimes, ".backup-sqlmap-${UUID.randomUUID()}")
        try {
            check(staging.mkdirs()) { "Unable to create sqlmap runtime staging directory" }
            val storageManager = checkNotNull(context.getSystemService(StorageManager::class.java))
            val storageUuid = storageManager.getUuidForPath(staging)
            // StorageManager calls cross into system_server. Check once up front,
            // then at 4 MiB intervals instead of issuing a Binder call for every
            // 64 KiB ZIP read while unpacking the wheel.
            var availableAtLastCheck = storageManager.getAllocatableBytes(storageUuid)
            if (availableAtLastCheck <= MIN_FREE_SPACE_AFTER_IMPORT) {
                throw java.io.IOException("Not enough free space to unpack bundled sqlmap")
            }
            var bytesAtLastCheck = 0L
            val freeSpaceCheckInterval = 4L * 1024 * 1024
            context.assets.open("toolpacks/sqlmap.whl").use { asset ->
                ZipInputStream(asset.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    var entryCount = 0
                    var expandedBytes = 0L
                    var lastReportedBytes = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (entry != null) {
                        entryCount++
                        if (entryCount > MAX_RUNTIME_ARCHIVE_ENTRIES) {
                            throw SecurityException("sqlmap wheel has too many entries")
                        }
                        val normalizedName = entry.name.replace('\\', '/').trimEnd('/')
                        if (normalizedName.isBlank() || normalizedName.startsWith('/') ||
                            Regex("^[A-Za-z]:").containsMatchIn(normalizedName) ||
                            normalizedName.split('/').any { it.isBlank() || it == ".." }
                        ) {
                            throw SecurityException("Unsafe sqlmap wheel path: ${entry.name}")
                        }
                        val safeName = normalizedName.split('/').filterNot { it == "." }.joinToString("/")
                        val target = safeChildFile(staging, safeName)
                            ?: throw SecurityException("sqlmap wheel path escapes staging: ${entry.name}")
                        if (entry.isDirectory) {
                            if (!target.exists() && !target.mkdirs()) error("Unable to create ${entry.name}")
                        } else {
                            target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("Unable to create ${it.path}") }
                            target.outputStream().use { out ->
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    if (expandedBytes > MAX_SQLMAP_ARCHIVE_BYTES - count) {
                                        throw SecurityException("sqlmap wheel expands beyond the size limit")
                                    }
                                    if (expandedBytes - bytesAtLastCheck >= freeSpaceCheckInterval) {
                                        availableAtLastCheck = storageManager.getAllocatableBytes(storageUuid)
                                        bytesAtLastCheck = expandedBytes
                                    }
                                    val usable = availableAtLastCheck - (expandedBytes - bytesAtLastCheck)
                                    if (usable <= MIN_FREE_SPACE_AFTER_IMPORT ||
                                        count.toLong() > usable - MIN_FREE_SPACE_AFTER_IMPORT
                                    ) {
                                        throw java.io.IOException("Not enough free space to unpack bundled sqlmap")
                                    }
                                    expandedBytes += count
                                    out.write(buffer, 0, count)
                                    if (expandedBytes - lastReportedBytes >= freeSpaceCheckInterval) {
                                        lastReportedBytes = expandedBytes
                                        val extractedMb = expandedBytes / (1024 * 1024)
                                        onProgress("Unpacking bundled sqlmap $SQLMAP_VERSION… ($extractedMb MB extracted)")
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            check(File(staging, "sqlmap/sqlmap.py").isFile) { "Bundled sqlmap wheel is missing sqlmap/sqlmap.py" }
            check(File(staging, "sqlmap/LICENSE").isFile) { "Bundled sqlmap wheel is missing its license" }
            check(File(staging, "sqlmap/lib/core/settings.py").isFile) { "Bundled sqlmap wheel is incomplete" }
            File(staging, "runtime.json").writeText(
                gson.toJson(ManifestData("sqlmap", SQLMAP_VERSION, emptyMap())),
            )
            File(staging, ".pack-version").writeText(SQLMAP_VERSION)

            if (destination.exists() && !destination.renameTo(backup)) {
                error("Unable to preserve the current sqlmap runtime")
            }
            if (!staging.renameTo(destination)) {
                if (backup.exists() && !backup.renameTo(destination)) {
                    error("Unable to activate bundled sqlmap or restore the previous runtime")
                }
                error("Unable to activate bundled sqlmap runtime")
            }
            runCatching { backup.deleteRecursively() }
            onProgress("Bundled sqlmap $SQLMAP_VERSION is ready")
            return checkNotNull(manifestFor("sqlmap"))
        } catch (t: Exception) {
            staging.deleteRecursively()
            if (!destination.exists() && backup.exists()) backup.renameTo(destination)
            throw t
        }
    }

    /** Remove tiny stub dirs created by older app versions. */
    private fun cleanLegacyStubDirs() {
        runCatching {
            runtimes.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                if (File(dir, ".pack-version").isFile) return@forEach
                val files = dir.walkTopDown().filter { it.isFile }.toList()
                if ((files.isNotEmpty()) && (files.size < 40) && (files.all { it.length() < 600 })) {
                    AppLogger.i("RuntimeManager", "Removing legacy stub dir: ${dir.name}")
                    installer.removePackDirectory(dir.name, dir)
                }
            }
        }
    }

    fun runtimePath(name: String): File = File(runtimes, name)

    fun gradleUserHome(): File = File(context.filesDir, "gradle-home")

    // Public API kept for external binary capability checks.
    @Suppress("unused")
    fun managedBinary(name: String): Boolean =
        resolveExecutable(name)?.let { (it.isFile) && (it.canExecute()) } == true

    /** Resolve an installed executable: manifests first, then known layouts, then system PATH. */
    fun resolveExecutable(name: String): File? {
        runCatching {
            runtimes.listFiles()?.filter { it.isDirectory }?.forEach { root ->
                val manifestData = readManifest(root) ?: return@forEach
                val relative = manifestData.entrypoints[name] ?: return@forEach
                val candidate = File(root, relative)
                if (candidate.isFile && candidate.canExecute()) return candidate
            }
        }
        val direct = listOf(
            File(runtimes, "$name/bin/$name"),
            File(runtimes, "jdk/bin/$name"),
            File(runtimes, "gradle/bin/$name"),
            File(runtimes, "kotlin/bin/$name"),
            File(runtimes, "node/bin/$name"),
            File(runtimes, "php/bin/$name"),
            File(runtimes, "clang/bin/$name"),
            File(runtimes, "cli-tools/bin/$name"),
            File(runtimes, "cli-tools/libexec/git-core/$name"),
            File(runtimes, "android-sdk/build-tools/34.0.0/$name"),
            File(runtimes, "android-sdk/platform-tools/$name"),
            File(runtimes, "apktool/bin/$name"),
            File(runtimes, "jadx/bin/$name"),
            File(runtimes, "radare2/bin/$name"),
            File(runtimes, "ndk/$name"),
            File(runtimes, "ghidra/support/$name"),
        )
        direct.firstOrNull { it.isFile && it.canExecute() }?.let { return it }

        val pathDirs = (System.getenv("PATH") ?: "/system/bin:/system/xbin:/vendor/bin").split(':')
        for (dir in pathDirs) {
            File(dir.trim(), name).takeIf { it.isFile && it.canExecute() }?.let { return it }
        }
        return null
    }

    fun javaHome(): File? =
        resolveExecutable("java")?.parentFile?.parentFile?.takeIf { it.isDirectory }

    /** Optional JDK 25 home, resolved only when a compatible pack is installed. */
    fun java25Home(): File? =
        resolveExecutable("java25")?.parentFile?.parentFile?.takeIf { it.isDirectory }

    fun gradleHome(): File? =
        resolveExecutable("gradle")?.parentFile?.parentFile?.takeIf { it.isDirectory }

    fun nodeHome(): File? =
        resolveExecutable("node")?.parentFile?.parentFile?.takeIf { it.isDirectory }

    fun kotlinHome(): File? =
        resolveExecutable("kotlinc")?.parentFile?.parentFile?.takeIf { it.isDirectory }

    fun ndkHome(): File? = runtimePath("ndk").takeIf { File(it, "ndk-build").isFile }
    fun ghidraHome(): File? =
        runtimePath("ghidra").takeIf { File(it, "support/analyzeHeadless").isFile }

    /**
     * Copy the packaged JDBC/Access jars (sqlite-jdbc + jackcess and their dependencies)
     * from APK assets (assets/databases-jars) into runtimes/databases/lib/ so the bundled
     * JVM — which runs as a separate process via java.exec — can load them with -cp / CLASSPATH.
     * Returns the absolute paths of all copied jars; empty when no jar is bundled.
     */
    fun installDatabaseJars(): List<File> {
        val libDir = File(runtimePath("databases"), "lib")
        val names = runCatching { context.assets.list("databases-jars").orEmpty().toList() }
            .getOrDefault(emptyList())
            .filter { it.endsWith(".jar") }
        if (names.isEmpty()) return emptyList()
        if (!libDir.isDirectory && !libDir.mkdirs()) return emptyList()
        val copied = mutableListOf<File>()
        for (name in names) {
            runCatching {
                val target = File(libDir, name)
                if ((!target.isFile) || (target.length() == 0L)) {
                    context.assets.open("databases-jars/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                    }
                }
                copied.add(target)
            }.onFailure { AppLogger.e(TAG, "installDatabaseJars: failed copying $name", it) }
        }
        return copied.distinctBy { it.name }
    }

    /** Full environment for executing bundled tools. */
    fun environment(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val env = linkedMapOf<String, String>()
        AppLogger.d(TAG, "Building runtime environment (extra=${extra.keys})")
        val pathParts = mutableListOf<String>()

        fun addBinDir(packId: String, subDir: String = "bin") {
            val d = File(runtimes, "$packId/$subDir")
            if (d.isDirectory) pathParts += d.absolutePath
        }

        addBinDir("node")
        addBinDir("nodejs")
        addBinDir("jdk")
        addBinDir("kotlin")
        addBinDir("php")
        addBinDir("gradle")
        addBinDir("gradle9")
        addBinDir("clang")
        addBinDir("cli-tools")
        addBinDir("termux-arm64")
        addBinDir("apktool")
        addBinDir("jadx")
        addBinDir("radare2")
        addBinDir("ghidra", "support")
        addBinDir("sqlite")
        addBinDir("databases")

        File(runtimes, "android-sdk/platform-tools").takeIf { it.isDirectory }
            ?.let { pathParts += it.absolutePath }
        File(runtimes, "android-sdk/build-tools/34.0.0").takeIf { it.isDirectory }
            ?.let { pathParts += it.absolutePath }
        File(runtimes, "android-sdk/build-tools/35.0.0").takeIf { it.isDirectory }
            ?.let { pathParts += it.absolutePath }
        File(runtimes, "android-sdk/build-tools/36.0.0").takeIf { it.isDirectory }
            ?.let { pathParts += it.absolutePath }
        File(runtimes, "ndk").takeIf { it.isDirectory }?.let { pathParts += it.absolutePath }

        val current = System.getenv("PATH").orEmpty()
        env["PATH"] = (pathParts + current.split(File.pathSeparator).filter { it.isNotBlank() })
            .asSequence().distinct().joinToString(File.pathSeparator)

        javaHome()?.let { env["JAVA_HOME"] = it.absolutePath }
        nodeHome()?.let { env["NODE_HOME"] = it.absolutePath }
        gradleHome()?.let { env["GRADLE_HOME"] = it.absolutePath }
        kotlinHome()?.let { env["KOTLIN_HOME"] = it.absolutePath }
        ndkHome()?.let {
            env["NDK_HOME"] = it.absolutePath
            env["ANDROID_NDK_HOME"] = it.absolutePath
            env["ANDROID_NDK_ROOT"] = it.absolutePath
        }
        ghidraHome()?.let { env["GHIDRA_HOME"] = it.absolutePath }
        val dbJars = installDatabaseJars()
        if (dbJars.isNotEmpty()) {
            env["CLASSPATH"] = dbJars.joinToString(File.pathSeparator) { it.absolutePath }
        }
        env["ANDROID_HOME"] = File(runtimes, "android-sdk").absolutePath
        env["ANDROID_SDK_ROOT"] = env["ANDROID_HOME"] ?: ""
        listOf(File(runtimes, "cli-tools/libexec/git-core"), File(runtimes, "termux-arm64/libexec/git-core"))
            .firstOrNull { it.isDirectory }?.let { env["GIT_EXEC_PATH"] = it.absolutePath }
        val certFile = listOf(
            File(runtimes, "libs/etc/tls/cert.pem"),
            File(runtimes, "cli-tools/etc/tls/cert.pem"),
            File(runtimes, "termux-arm64/etc/tls/cert.pem"),
        ).firstOrNull { it.isFile }
        certFile?.let { env["SSL_CERT_FILE"] = it.absolutePath }
        val bundledLdPaths = listOf(
            File(runtimes, "libs/lib"),
            File(runtimes, "clang/lib"),
            File(runtimes, "jdk/lib"),
            File(runtimes, "jadx/lib"),
            File(runtimes, "databases/lib"),
            File(runtimes, "termux-arm64/lib"),
        ).asSequence().filter { it.isDirectory }.map { it.absolutePath }
        // Keep Android's inherited linker paths as well: replacing them with only
        // tool-pack directories can hide app/system native libraries from children.
        val inheritedLdPaths = System.getenv("LD_LIBRARY_PATH").orEmpty()
            .split(File.pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
        val ldPaths = (bundledLdPaths + inheritedLdPaths).distinct().toList()
        if (ldPaths.isNotEmpty()) env["LD_LIBRARY_PATH"] = ldPaths.joinToString(File.pathSeparator)
        // fmt headers (libfmt) — CPATH lets #include <fmt/...> resolve for the bundled clang.
        File(runtimes, "libs/include").takeIf { it.isDirectory }?.let { fmtInclude ->
            val inherited = System.getenv("CPATH").orEmpty()
            env["CPATH"] = listOf(fmtInclude.absolutePath, inherited)
                .filter { it.isNotBlank() }
                .joinToString(File.pathSeparator)
        }
        // Link-time discovery for -lfmt — inherited system LDFLAGS (if any) is preserved and
        // our search path is prepended; `extra` overrides below still win when provided.
        File(runtimes, "libs/lib").takeIf { it.isDirectory }?.let { libsLib ->
            val flag = "-L${libsLib.absolutePath}"
            val inherited = System.getenv("LDFLAGS").orEmpty()
            env["LDFLAGS"] = if (inherited.isBlank()) flag else "$flag $inherited"
        }
        env["HOME"] = workspace.absolutePath
        val termuxPrefix = runtimePath("termux-arm64")
        if (termuxPrefix.isDirectory) {
            env["PREFIX"] = termuxPrefix.absolutePath
            env["TERMUX_PREFIX"] = termuxPrefix.absolutePath
            env["TERMUX_HOME"] = termuxPrefix.parentFile?.absolutePath ?: termuxPrefix.absolutePath
            val termuxTmp = File(context.cacheDir, "termux-tmp").apply { mkdirs() }
            env["TMPDIR"] = termuxTmp.absolutePath
        }
        val gradleUserHome = gradleUserHome()
        if (!gradleUserHome.isDirectory && !gradleUserHome.mkdirs() && !gradleUserHome.isDirectory) {
            throw IllegalStateException("Could not create Gradle user home: ${gradleUserHome.absolutePath}")
        }
        if (!gradleUserHome.canWrite()) {
            throw IllegalStateException("Gradle user home is not writable: ${gradleUserHome.absolutePath}")
        }
        env["GRADLE_USER_HOME"] = gradleUserHome.absolutePath
        val inheritedJavaOptions = System.getenv("JAVA_TOOL_OPTIONS").orEmpty().trim()
        val appHomeOption = "-Duser.home=${context.filesDir.absolutePath}"
        env["JAVA_TOOL_OPTIONS"] = listOf(inheritedJavaOptions, appHomeOption)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val inheritedGradleOptions = System.getenv("GRADLE_OPTS").orEmpty().trim()
        val androidNativeFallback = "-Dorg.gradle.native=false"
        env["GRADLE_OPTS"] = listOf(inheritedGradleOptions, androidNativeFallback)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val tempDirectory = File(context.filesDir, "runtime-tmp")
        if (!tempDirectory.isDirectory && !tempDirectory.mkdirs() && !tempDirectory.isDirectory) {
            throw IllegalStateException("Could not create runtime temporary directory: ${tempDirectory.absolutePath}")
        }
        if (!tempDirectory.canWrite()) {
            throw IllegalStateException("Runtime temporary directory is not writable: ${tempDirectory.absolutePath}")
        }
        val tempPath = tempDirectory.absolutePath
        env["TMPDIR"] = tempPath
        env["TMP"] = tempPath
        env["TEMP"] = tempPath
        // The bundled JDK does not reliably derive java.io.tmpdir from TMPDIR.
        // Point Java tools at the same private, persistent app-owned directory.
        val inheritedJdkOptions = System.getenv("JDK_JAVA_OPTIONS").orEmpty().trim()
        val javaTmpOption = "-Djava.io.tmpdir=$tempPath"
        env["JDK_JAVA_OPTIONS"] = listOf(inheritedJdkOptions, javaTmpOption)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        env.putAll(extra)
        return env.filterValues { it.isNotBlank() }
    }

    /** Capability report — real checks against installed toolchains. */
    fun capabilityReport(): Map<String, Boolean> {
        val checks = linkedMapOf(
            // Python ships as the embedded Chaquopy CPython (no bin/python3 ELF
            // in the runtime layout), so it must be checked via the live
            // interpreter, not the filesystem probe below.
            "python" to null,
            "pip" to null,
            "sqlmap" to null,
            "android_shell" to "sh",
            "git" to "git",
            "node" to "node",
            "npm" to "npm",
            "java" to "java",
            "javac" to "javac",
            "java25" to "java25",
            "javac25" to "javac25",
            "gradle" to "gradle",
            "gradle9" to "gradle9",
            "kotlinc" to "kotlinc",
            "php" to "php",
            "composer" to "composer",
            "clang" to "clang",
            "make" to "make",
            "cmake" to "cmake",
            "ninja" to "ninja",
            "zip" to "zip",
            "unzip" to "unzip",
            "tar" to "tar",
            "curl" to "curl",
            "wget" to "wget",
            "ssh" to "ssh",
            "openssl" to "openssl",
            "jq" to "jq",
            "rg" to "rg",
            "gh" to "gh",
            "aapt2" to "aapt2",
            "apksigner" to "apksigner",
            "d8" to "d8",
            "adb" to "adb",
            "apktool" to "apktool",
            "jadx" to "jadx",
            "r2" to "r2",
            "sqlite3" to "sqlite3",
            "mdbtools" to "mdb-tables",
            "odbcinst" to "odbcinst",
            "ndk-build" to "ndk-build",
            "analyzeHeadless" to "analyzeHeadless",
        )
        val out = linkedMapOf<String, Boolean>()
        for ((cap, bin) in checks) {
            out[cap] = if (bin == null) {
                when (cap) {
                    "python" -> python.isAvailable()
                    "pip" -> python.isPipAvailable()
                    "sqlmap" -> isSqlmapPackReady()
                    else -> false
                }
            } else {
                resolveExecutable(bin) != null
            }
        }
        return out
    }

    /** Inventory of known + installed runtime packs (Runtime UI). */
    fun inventory(): List<RuntimeInfo> {
        val results = mutableListOf<RuntimeInfo>()
        for (id in known.keys) {
            val dest = runtimePath(id)
            val installed = when (id) {
                "sqlmap" -> isSqlmapPackReady(dest)
                        else -> File(dest, ".pack-version").isFile
            }
            val manifest = readManifest(dest)
            results.add(
                RuntimeInfo(
                    id = id,
                    version = manifest?.version ?: if (installed) packVersion(id) else "—",
                    description = knownDescription(id),
                    entrypoints = manifest?.entrypoints ?: emptyMap(),
                    ready = installed,
                    root = dest,
                ),
            )
        }
        runtimes.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && it.name !in known.keys && it.name != "sqlmap" }.forEach { dir ->
            val manifest = readManifest(dir) ?: return@forEach
            results += RuntimeInfo(manifest.id, manifest.version, knownDescription(manifest.id), manifest.entrypoints, true, dir)
        }
        return results
    }

    private fun isSqlmapPackReady(root: File = runtimePath("sqlmap")): Boolean = runCatching {
        val marker = File(root, ".pack-version")
        marker.isFile && marker.readText().trim() == SQLMAP_VERSION &&
            File(root, "runtime.json").isFile &&
            File(root, "sqlmap/sqlmap.py").isFile &&
            File(root, "sqlmap/LICENSE").isFile &&
            File(root, "sqlmap/lib/core/settings.py").isFile
    }.getOrDefault(false)

    private fun readManifest(root: File): ManifestData? = runCatching {
        val f = File(root, "runtime.json")
        if (!f.isFile) return null
        val o = gson.fromJson(f.readText(), JsonObject::class.java)
        val id = o["id"]?.asString ?: return null
        val version = o["version"]?.asString ?: "bundled"
        val ep = mutableMapOf<String, String>()
        o.getAsJsonObject("entrypoints")?.entrySet()?.forEach { (k, v) -> ep[k] = v.asString }
        if (ep.values.any { safeChildFile(root, it) == null }) return null
        ManifestData(id, version, ep)
    }.getOrNull()

    private fun safeChildFile(root: File, relativePath: String): File? = runCatching {
        val normalized = relativePath.replace('\\', '/').trimEnd('/')
        if (normalized.isBlank() || normalized.startsWith('/') ||
            Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
            normalized.split('/').any { it.isBlank() || it == ".." }
        ) {
            return null
        }
        val safeRelativePath = normalized.split('/').filterNot { it == "." }.joinToString("/")
        if (safeRelativePath.isBlank()) return null
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, safeRelativePath).canonicalFile
        candidate.takeIf { it.path.startsWith(canonicalRoot.path + File.separator) }
    }.getOrNull()

    /** Replace relocatable Termux shebang tokens after the final app-private path is known. */
    private fun relocateRuntimeShebangs(root: File) {
        root.walkTopDown().filter { it.isFile && it.length() in 3..(8L * 1024 * 1024) }.forEach { file ->
            runCatching {
                java.io.RandomAccessFile(file, "rw").use { raf ->
                    val firstLine = raf.readLine() ?: return@use
                    if (!firstLine.startsWith("#!") || !firstLine.contains("@RUNTIME_PREFIX@")) return@use
                    val remaining = (raf.length() - raf.filePointer).toInt()
                    val tail = ByteArray(remaining)
                    raf.readFully(tail)
                    val relocated = firstLine.replace("@RUNTIME_PREFIX@", root.absolutePath)
                    raf.seek(0)
                    raf.setLength(0)
                    raf.write((relocated + "\n").toByteArray(Charsets.UTF_8))
                    raf.write(tail)
                }
            }.getOrElse { throw IllegalStateException("Could not relocate runtime script ${file.name}", it) }
        }
    }

    /** Install a runtime pack from a user-selected ZIP (must contain runtime.json at root). */
    suspend fun installPack(input: InputStream, expectedPackId: String? = null): RuntimeInfo = withContext(Dispatchers.IO) {
        runtimeMutationMutex.lock()
        val staging = File(runtimes, ".staging-${UUID.randomUUID()}")
        try {
            check(staging.mkdirs()) { "Unable to create runtime staging directory" }
            val storageManager = checkNotNull(context.getSystemService(StorageManager::class.java))
            val storageUuid = storageManager.getUuidForPath(staging)
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                var entryCount = 0
                var expandedBytes = 0L
                val buffer = ByteArray(64 * 1024)
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_RUNTIME_ARCHIVE_ENTRIES) {
                        throw SecurityException("Runtime archive has too many entries")
                    }
                    val normalizedName = entry.name.replace('\\', '/').trimEnd('/')
                    if (normalizedName.isBlank() || normalizedName.startsWith('/') ||
                        Regex("^[A-Za-z]:").containsMatchIn(normalizedName) ||
                        normalizedName.split('/').any { it.isBlank() || it == ".." }
                    ) {
                        throw SecurityException("Unsafe runtime archive path: ${entry.name}")
                    }
                    val safeName = normalizedName.split('/').filterNot { it == "." }.joinToString("/")
                    if (safeName.isBlank()) {
                        if (!entry.isDirectory) throw SecurityException("Invalid runtime archive path: ${entry.name}")
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }
                    val target = safeChildFile(staging, safeName)
                        ?: throw SecurityException("Archive path escapes staging: ${entry.name}")
                    if (entry.isDirectory) {
                        if (!target.exists() && !target.mkdirs()) error("Unable to create ${entry.name}")
                    } else {
                        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("Unable to create ${it.path}") }
                        target.outputStream().use { out ->
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                if (expandedBytes > MAX_RUNTIME_ARCHIVE_BYTES - count) {
                                    throw SecurityException("Runtime archive expands beyond the size limit")
                                }
                                expandedBytes += count
                                val usable = storageManager.getAllocatableBytes(storageUuid)
                                if (usable <= MIN_FREE_SPACE_AFTER_IMPORT ||
                                    count.toLong() > usable - MIN_FREE_SPACE_AFTER_IMPORT
                                ) {
                                    throw java.io.IOException("Not enough free space to safely extract runtime archive")
                                }
                                out.write(buffer, 0, count)
                            }
                        }
                        target.setExecutable(true, false)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val stagedManifest = readManifest(staging) ?: error("runtime.json is missing or invalid")
            val stagedPackId = stagedManifest.id
            relocateRuntimeShebangs(staging)
            if (expectedPackId != null && stagedPackId != expectedPackId) {
                throw SecurityException("Runtime manifest ID does not match the selected catalogue entry")
            }
            if (!packIdPattern.matches(stagedPackId) || stagedPackId in known.keys || stagedPackId == "sqlmap"
            ) {
                throw SecurityException("Unsupported runtime id: $stagedPackId")
            }
            for (relativePath in stagedManifest.entrypoints.values) {
                val entrypoint = safeChildFile(staging, relativePath)
                    ?: throw SecurityException("Unsafe runtime entrypoint: $relativePath")
                check(entrypoint.isFile) { "Runtime entrypoint is missing: $relativePath" }
            }

            // Executables in app data need the same root SELinux setup as bundled packs.
            if (stagedManifest.entrypoints.isNotEmpty()) {
                val root = RootManager(context)
                check(root.canUseRoot()) { "Root access is required to install executable runtimes" }
            }
            File(staging, ".pack-version").writeText(packVersion(stagedPackId))
            if (stagedManifest.entrypoints.isNotEmpty()) {
                val (fixed, detail) = RootManager(context).fixSelinuxContextForRuntime(
                    staging.absolutePath,
                    executablePaths = stagedManifest.entrypoints.values,
                )
                check(fixed) { detail }
            }

            val destination = runtimePath(stagedPackId)
            val backup = File(runtimes, ".backup-$stagedPackId-${UUID.randomUUID()}")
            if (destination.exists() && !destination.renameTo(backup)) {
                error("Unable to preserve the current runtime pack")
            }
            if (!staging.renameTo(destination)) {
                if (backup.exists() && !backup.renameTo(destination)) {
                    error("Unable to activate runtime pack or restore the previous pack; backup is at ${backup.absolutePath}")
                }
                error("Unable to activate runtime pack")
            }
            runCatching { backup.deleteRecursively() }
            RuntimeInfo(
                id = stagedPackId,
                version = stagedManifest.version,
                description = knownDescription(stagedPackId),
                entrypoints = stagedManifest.entrypoints,
                ready = true,
                root = destination,
            )
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        } finally {
            runtimeMutationMutex.unlock()
        }
    }

    suspend fun removePack(id: String) = withContext(Dispatchers.IO) {
        require(packIdPattern.matches(id) && id != "sqlmap") { "Invalid runtime id: $id" }
        runtimeMutationMutex.lock()
        try {
            installer.removePackDirectory(id, runtimePath(id))
        } finally {
            runtimeMutationMutex.unlock()
        }
    }

    data class RemoteRuntimePack(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val url: String,
        val sha256: String,
    )

    /** Fetch the public runtime catalogue. Entries must use HTTPS and SHA-256. */
    suspend fun fetchRemotePacks(): List<RemoteRuntimePack> = withContext(Dispatchers.IO) {
        val url = java.net.URL(RUNTIME_CATALOG_URL)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/json")
        try {
            check(connection.responseCode == 200) { "Runtime catalogue HTTP ${connection.responseCode}" }
            val root = gson.fromJson(connection.inputStream.bufferedReader().use { it.readText() }, JsonObject::class.java)
            val packs = root.getAsJsonArray("packs") ?: return@withContext emptyList()
            packs.mapNotNull { element ->
                runCatching {
                    val item = element.asJsonObject
                    val id = item["id"].asString
                    val urlText = item["url"].asString
                    val checksum = item["sha256"].asString.lowercase()
                    require(packIdPattern.matches(id) && id !in known.keys && id != "sqlmap")
                    require(urlText.startsWith("https://")) { "Runtime pack URLs must use HTTPS" }
                    require(checksum.matches(Regex("^[a-f0-9]{64}$"))) { "Invalid SHA-256 for $id" }
                    RemoteRuntimePack(
                        id, item["name"]?.asString ?: id, item["version"]?.asString ?: "unknown",
                        item["description"]?.asString ?: "Optional runtime tools", urlText, checksum,
                    )
                }.getOrNull()
            }
        } finally { connection.disconnect() }
    }

    /** Download, verify and install an optional runtime ZIP without bundling it in the APK. */
    suspend fun downloadRuntimePack(
        pack: RemoteRuntimePack,
        onProgress: suspend (String, Int) -> Unit,
    ): RuntimeInfo = withContext(Dispatchers.IO) {
        require(pack.url.startsWith("https://")) { "Only HTTPS runtime downloads are allowed" }
        val archive = File(context.cacheDir, "${pack.id}-${UUID.randomUUID()}.zip")
        try {
            onProgress("Downloading ${pack.name}…", 0)
            downloadFileWithProgress(pack.url, archive) { downloaded, total ->
                onProgress("Downloading ${pack.name}…", if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 99) else -1)
            }
            onProgress("Verifying SHA-256…", 99)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            archive.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == pack.sha256) { "Runtime archive checksum mismatch; download rejected" }
            val installed = archive.inputStream().use { installPack(it, expectedPackId = pack.id) }
            check(installed.id == pack.id) { "Downloaded manifest ID does not match catalogue entry" }
            onProgress("${pack.name} installed", 100)
            installed.copy(description = pack.description)
        } finally { archive.delete() }
    }

    private suspend fun downloadFileWithProgress(
        urlStr: String,
        destination: File,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 10) {
            val url = java.net.URL(currentUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Wget/1.21.4")
            conn.setRequestProperty("Accept", "*/*")

            val code = conn.responseCode
            if ((code == java.net.HttpURLConnection.HTTP_MOVED_PERM) ||
                (code == java.net.HttpURLConnection.HTTP_MOVED_TEMP) ||
                (code == java.net.HttpURLConnection.HTTP_SEE_OTHER) ||
                (code == 307) || (code == 308)
            ) {
                val loc = conn.getHeaderField("Location")
                if (!loc.isNullOrBlank()) {
                    currentUrl =
                        if ((loc.startsWith("http:" + "//")) || (loc.startsWith("https:" + "//"))) {
                            loc
                        } else {
                            java.net.URL(url, loc).toString()
                        }
                    redirects++
                    conn.disconnect()
                    continue
                }
            }

            if (code != java.net.HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw java.io.IOException("HTTP $code ${conn.responseMessage} for $currentUrl")
            }

            val contentLength = conn.contentLengthLong
            var downloaded = 0L

            conn.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress(downloaded, if (contentLength > 0) contentLength else -1L)
                    }
                }
            }
            conn.disconnect()
            return
        }
        throw java.io.IOException("Too many HTTP redirects for $urlStr")
    }
}

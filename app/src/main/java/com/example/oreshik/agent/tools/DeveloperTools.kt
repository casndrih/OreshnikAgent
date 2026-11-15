package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

private fun repository(workspace: File) = FileRepositoryBuilder().findGitDir(workspace).build()

class GitStatusTool : AgentTool {
    override val definition = ToolDefinition(
        name = "git.status",
        category = ToolCategory.GIT,
        description = "Show Git working-tree status for the workspace using the app-owned Git engine.",
        permissions = setOf(ToolPermission.GIT),
        parameters = emptyMap(),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            repository(context.workspace).use { repo ->
                val status = Git(repo).use { git -> git.status().call() }
                buildString {
                    append("branch: ").append(repo.branch).append('\n')
                    status.added.forEach { path -> append("A  ").append(path).append('\n') }
                    status.changed.forEach { path -> append("M  ").append(path).append('\n') }
                    status.modified.forEach { path -> append(" M ").append(path).append('\n') }
                    status.removed.forEach { path -> append("D  ").append(path).append('\n') }
                    status.untracked.forEach { path -> append("?? ").append(path).append('\n') }
                    if (isEmpty()) append("working tree clean")
                }
                ToolResult(ok = true, output = toString())
            }
        }.getOrElse { failure -> ToolResult(ok = false, error = "Git: ${failure.message}") }
    }
}

class GitDiffTool : AgentTool {
    override val definition = ToolDefinition(
        name = "git.diff",
        category = ToolCategory.GIT,
        description = "Show the current Git diff for the workspace.",
        permissions = setOf(ToolPermission.GIT),
        parameters = mapOf("staged" to "boolean"),
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            repository(context.workspace).use { repo ->
                val out = ByteArrayOutputStream()
                DiffFormatter(out).use { formatter ->
                    formatter.setRepository(repo)
                    val git = Git(repo)
                    val diffs = if (arguments.asJsonObject["staged"]?.asBoolean == true) {
                        git.diff().setCached(true).call()
                    } else git.diff().call()
                    diffs.forEach { formatter.format(it) }
                }
                ToolResult(ok = true, output = String(out.toByteArray(), Charsets.UTF_8))
            }
        }.getOrElse { failure -> ToolResult(ok = false, error = "Git diff: ${failure.message}") }
    }
}

class GitLogTool : AgentTool {
    override val definition = ToolDefinition(
        name = "git.log",
        category = ToolCategory.GIT,
        description = "Show recent Git commits for the workspace.",
        permissions = setOf(ToolPermission.GIT),
        parameters = mapOf("count" to "integer")
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            repository(context.workspace).use { repo ->
                val count = (arguments.asJsonObject["count"]?.asInt ?: 10).coerceIn(1, 50)
                val lines = Git(repo).use { git -> git.log().setMaxCount(count).call().map { c -> "${c.name.take(8)} ${c.shortMessage}" }.toList() }
                ToolResult(true, lines.joinToString("\n").ifBlank { "No commits." })
            }
        }.getOrElse { ToolResult(false, error = "Git log: ${it.message}") }
    }
}

class AndroidBuildTool : AgentTool {
    override val definition = ToolDefinition(
        name = "android.build",
        category = ToolCategory.BUILD,
        description = "Build an Android project with the app-owned Android SDK and AAPT2. Repairs bundled Gradle/JDK/SDK packs, uses the Android-safe Gradle native fallback, and provisions JDK 25 for Gradle 9 when needed.",
        permissions = setOf(ToolPermission.BUILD, ToolPermission.EXECUTE_PROCESS),
        parameters = mapOf("task" to "string", "timeout_seconds" to "integer")
    )
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val task = o["task"]?.asString?.trim().orEmpty().ifBlank { "assembleDebug" }
        val timeout = (o["timeout_seconds"]?.asLong ?: 900).coerceIn(1, 3600)
        if (!Regex("^[A-Za-z0-9_:.-]+$").matches(task)) return@withContext ToolResult(false, error = "Invalid Gradle task")
        val runtime = context.runtime
        val gradlePack = preferredManagedGradlePack(context.workspace)
        val repairError = runtime.ensureAndroidBuildToolchain(gradlePack, context.onProgress)
        if (repairError != null) return@withContext ToolResult(false, error = repairError)

        val sdkRoot = runtime.runtimePath("android-sdk").absolutePath
        val managedGradle = runtime.resolveExecutable(gradlePack)
            ?: return@withContext ToolResult(false, error = "The repaired $gradlePack runtime has no executable Gradle launcher.")
        val managedGradleHome = managedGradle.parentFile?.parentFile?.absolutePath
            ?: return@withContext ToolResult(false, error = "Could not determine the managed $gradlePack home directory.")
        val buildJdkHome = if (gradlePack == "gradle9") {
            runtime.java25Home() ?: runtime.javaHome()
        } else {
            runtime.javaHome()
        } ?: return@withContext ToolResult(false, error = "No usable app-managed JDK was found for $gradlePack.")
        val aapt2 = selectManagedAapt2(context.workspace, File(sdkRoot))
            ?: return@withContext ToolResult(false, error = "No executable AAPT2 was found in the repaired app-managed Android SDK.")
        val wrapper = File(context.workspace, "gradlew")
        val gradleArgs = listOf(
            "--no-daemon",
            "--stacktrace",
            "--no-watch-fs",
            "-Dorg.gradle.native=false",
            "-Duser.home=${runtime.gradleUserHome().absolutePath}",
            "-Pandroid.builder.sdkDownload=true",
            "-Pandroid.aapt2FromMavenOverride=${aapt2.absolutePath}",
            task,
        )
        val runtimeEnvironment = runtime.environment(
            mapOf(
                "ANDROID_HOME" to sdkRoot,
                "ANDROID_SDK_ROOT" to sdkRoot,
                "GRADLE_HOME" to managedGradleHome,
                "JAVA_HOME" to buildJdkHome.absolutePath,
            )
        )
        val restoreSdkPath = try {
            temporarilySelectManagedSdk(context.workspace, File(sdkRoot))
        } catch (failure: Exception) {
            return@withContext ToolResult(false, error = "Could not point this build at the app-managed Android SDK: ${failure.message}")
        }

        fun runBuild(command: List<String>, label: String): ToolResult = try {
            val pb = ProcessBuilder(command).directory(context.workspace).redirectErrorStream(true)
            pb.environment().putAll(runtimeEnvironment)
            val p = pb.start()
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val future = executor.submit<String> { p.inputStream.bufferedReader().use { it.readTextLimited(2_000_000) } }
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                p.destroy(); if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
                val output = runCatching { future.get(2, TimeUnit.SECONDS) }.getOrDefault("[build output unavailable]")
                future.cancel(true); executor.shutdownNow()
                ToolResult(false, output, "$label timed out after ${timeout}s")
            } else {
                val output = runCatching { future.get(2, TimeUnit.SECONDS) }.getOrDefault("[build output unavailable]")
                executor.shutdownNow()
                ToolResult(p.exitValue() == 0, output, if (p.exitValue() == 0) null else "$label exited ${p.exitValue()}", p.exitValue())
            }
        } catch (e: Exception) {
            ToolResult(false, error = "$label unavailable: ${e.message ?: e.javaClass.simpleName}")
        }

        val managedCommand = listOf(managedGradle.absolutePath) + gradleArgs
        val header = "Android SDK: $sdkRoot\nAAPT2: ${aapt2.absolutePath}\nGradle: ${managedGradle.absolutePath}\nJDK: ${buildJdkHome.absolutePath}\nAndroid native-services fallback: enabled\n"
        try {
            if (!wrapper.isFile) {
                context.onProgress?.invoke("Building Android project with the app-managed $gradlePack runtime…")
                val managedResult = runBuild(managedCommand, "Managed Gradle")
                return@withContext managedResult.copy(output = header + managedResult.output)
            }

            context.onProgress?.invoke("Building Android project with its Gradle wrapper and the app-managed Android SDK…")
            val wrapperResult = runBuild(listOf("/system/bin/sh", wrapper.absolutePath) + gradleArgs, "Project Gradle wrapper")
            if (wrapperResult.ok || !isGradleWrapperSetupFailure(wrapperResult)) {
                return@withContext wrapperResult.copy(output = header + wrapperResult.output)
            }

            context.onProgress?.invoke("Project Gradle wrapper failed to start; retrying with the app-managed $gradlePack runtime…")
            val managedResult = runBuild(managedCommand, "Managed Gradle fallback")
            ToolResult(
                ok = managedResult.ok,
                output = header + "Project wrapper could not start or obtain its Gradle distribution; retried with the app-managed runtime.\n" +
                    listOf(wrapperResult.output, wrapperResult.error.orEmpty(), managedResult.output)
                        .filter { it.isNotBlank() }.joinToString("\n"),
                error = managedResult.error,
                exitCode = managedResult.exitCode,
                metadata = managedResult.metadata,
            )
        } finally {
            restoreSdkPath?.invoke()
        }
    }

    private fun isGradleWrapperSetupFailure(result: ToolResult): Boolean {
        if (result.error?.contains("timed out", ignoreCase = true) == true) return false
        val output = (result.output + "\n" + result.error.orEmpty()).lowercase()
        return result.exitCode != 0 && listOf(
            "project gradle wrapper unavailable",
            "gradlewrappermain",
            "could not install gradle distribution",
            "could not download",
            "could not get '",
            "could not head '",
            "unknownhostexception",
            "connection timed out",
            "read timed out",
            "no cached version of",
            "could not unzip",
            "corrupt gradle distribution",
            "distributionurl",
        ).any(output::contains)
    }

    private fun preferredManagedGradlePack(workspace: File): String {
        val wrapperProperties = File(workspace, "gradle/wrapper/gradle-wrapper.properties")
        val wrapperText = runCatching { wrapperProperties.readText() }.getOrDefault("")
        val wrapperMajor = Regex("gradle-(\\d+)(?:\\.[0-9]+)*-(?:bin|all)\\.zip", RegexOption.IGNORE_CASE)
            .find(wrapperText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (wrapperMajor != null) return if (wrapperMajor >= 9) "gradle9" else "gradle"

        val buildFiles = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
            .map { File(workspace, it) }
        val buildText = buildFiles.filter { it.isFile }
            .joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        val agpMajor = Regex(
            "com\\.android\\.(?:application|library)[^\\r\\n]*?version\\s*(?:=\\s*)?[\\\"'](\\d+)",
            RegexOption.IGNORE_CASE,
        ).find(buildText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("com\\.android\\.tools\\.build:gradle:(\\d+)")
                .find(buildText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: runCatching {
                val catalog = File(workspace, "gradle/libs.versions.toml")
                if (!catalog.isFile) return@runCatching null
                val lines = catalog.readLines()
                val pluginLine = lines.firstOrNull { it.contains("com.android.application") || it.contains("com.android.library") }
                val direct = pluginLine?.let { Regex("version\\s*=\\s*[\\\"'](\\d+)").find(it)?.groupValues?.getOrNull(1) }
                val versionRef = pluginLine?.let { Regex("version\\.ref\\s*=\\s*[\\\"']([^\\\"']+)").find(it)?.groupValues?.getOrNull(1) }
                val referenced = versionRef?.let { ref ->
                    lines.firstOrNull { Regex("^\\s*${Regex.escape(ref)}\\s*=").containsMatchIn(it) }
                        ?.let { Regex("=\\s*[\\\"'](\\d+)").find(it)?.groupValues?.getOrNull(1) }
                }
                (direct ?: referenced)?.toIntOrNull()
            }.getOrNull()

        return if (agpMajor != null && agpMajor >= 9) "gradle9" else "gradle"
    }

    private fun selectManagedAapt2(workspace: File, sdkRoot: File): File? {
        val ignoredDirectories = setOf(".git", ".gradle", "build", "node_modules")
        val buildToolsPattern = Regex("buildToolsVersion\\s*(?:=\\s*)?(?:\\(\\s*)?['\"](\\d+\\.\\d+\\.\\d+)['\"]")
        val requestedVersions = workspace.walkTopDown()
            .maxDepth(8)
            .onEnter { directory -> directory == workspace || directory.name !in ignoredDirectories }
            .filter { file ->
                file.isFile && file.name in setOf("build.gradle", "build.gradle.kts")
            }
            .flatMap { file ->
                runCatching { buildToolsPattern.findAll(file.readText()).map { it.groupValues[1] }.toList() }
                    .getOrDefault(emptyList())
                    .asSequence()
            }
            .toList()

        val comparator = Comparator<String> { left, right ->
            val leftParts = left.split('.').mapNotNull { it.toIntOrNull() }
            val rightParts = right.split('.').mapNotNull { it.toIntOrNull() }
            (0..2).firstNotNullOfOrNull { index ->
                (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
                    .takeIf { it != 0 }
            } ?: 0
        }
        val installedVersions = File(sdkRoot, "build-tools").listFiles().orEmpty()
            .filter { it.isDirectory && Regex("\\d+\\.\\d+\\.\\d+").matches(it.name) }
            .map { it.name }
        val candidates = requestedVersions.distinct().sortedWith(comparator.reversed()) +
            installedVersions.sortedWith(comparator.reversed())
        return candidates.asSequence()
            .map { File(sdkRoot, "build-tools/$it/aapt2") }
            .firstOrNull { it.isFile && it.canExecute() }
    }
}

/** Temporarily replace a valid, foreign sdk.dir and restore the exact file after the build. */
private fun temporarilySelectManagedSdk(workspace: File, sdkRoot: File): (() -> Unit)? {
    val localPropertiesFile = File(workspace, "local.properties")
    if (!localPropertiesFile.isFile) return null

    val originalBytes = localPropertiesFile.readBytes()
    val properties = java.util.Properties()
    originalBytes.inputStream().bufferedReader(Charsets.UTF_8).use { properties.load(it) }
    val originalSdkValue = properties.getProperty("sdk.dir")
    val configuredSdk = originalSdkValue?.trim().orEmpty()
    if (configuredSdk.isBlank()) return null

    val configuredPath = File(configuredSdk).let { path ->
        if (path.isAbsolute) path else File(workspace, configuredSdk)
    }.canonicalFile
    val managedPath = sdkRoot.canonicalFile
    if (!configuredPath.isDirectory || configuredPath == managedPath) return null

    properties.setProperty("sdk.dir", managedPath.absolutePath)
    val selectedBytes = java.io.ByteArrayOutputStream().use { output ->
        properties.store(output, "Temporarily using Oreshik's app-managed Android SDK")
        output.toByteArray()
    }
    localPropertiesFile.writeBytes(selectedBytes)
    return {
        // Restore the original bytes when untouched. If another process changed
        // other properties during the build, restore only sdk.dir and retain its edits.
        if (localPropertiesFile.isFile) {
            val currentBytes = localPropertiesFile.readBytes()
            if (currentBytes.contentEquals(selectedBytes)) {
                localPropertiesFile.writeBytes(originalBytes)
            } else {
                val currentProperties = java.util.Properties()
                currentBytes.inputStream().bufferedReader(Charsets.UTF_8).use { currentProperties.load(it) }
                if (currentProperties.getProperty("sdk.dir") == managedPath.absolutePath) {
                    if (originalSdkValue != null) {
                        currentProperties.setProperty("sdk.dir", originalSdkValue)
                    } else {
                        currentProperties.remove("sdk.dir")
                    }
                    java.io.ByteArrayOutputStream().use { output ->
                        currentProperties.store(output, "Restored local Android SDK setting after Oreshik build")
                        localPropertiesFile.writeBytes(output.toByteArray())
                    }
                }
            }
        }
    }
}

private fun java.io.Reader.readTextLimited(max: Int): String {
    val buf = CharArray(8192); val out = StringBuilder(); var total = 0; var truncated = false
    while (true) {
        val n = read(buf); if (n < 0) break
        val take = minOf(n, (max - total).coerceAtLeast(0))
        if (take > 0) out.appendRange(buf, 0, take)
        total += take
        if (take < n) truncated = true
    }
    if (truncated) out.append("\n[output truncated]")
    return out.toString()
}

package com.example.oreshik.agent.tools

import android.annotation.SuppressLint
import com.example.oreshik.agent.core.*
import com.example.oreshik.logging.AppLogger
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Tools in this file intentionally enumerate all installed apps (system.app_list);
// the QueryPermissionsPackages lint advisory is acknowledged there.
@SuppressLint("QueryPermissionsPackages")
class ProcessListTool : AgentTool {
    override val definition = ToolDefinition(
        name = "system.process_list",
        category = ToolCategory.SYSTEM,
        description = "List all running processes on the device with PID, name, UID, and CPU/memory usage. Uses root (when available) so background/other-UID apps are included.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf("filter" to "string", "limit" to "integer"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val filter = o["filter"]?.asString?.lowercase() ?: ""
        val limit = (o["limit"]?.asInt ?: 100).coerceIn(1, 500)

        try {
            val root = com.example.oreshik.agent.security.RootManager(context.app)
            val entries = listProcessesWithRoot(root, filter, limit)
            if (entries.isEmpty()) {
                return@withContext ToolResult(
                    ok = false,
                    error = "No readable processes (/proc hidden and root unavailable).",
                )
            }
            val jsons = entries.map { (pid, name, uid, cmdline, state, ppid, cpu, rss, vsz) ->
                JsonObject().apply {
                    addProperty("pid", pid)
                    addProperty("name", name)
                    addProperty("state", state)
                    addProperty("ppid", ppid)
                    addProperty("uid", uid)
                    addProperty("cpu_time_total", cpu)
                    addProperty("memory_rss_kb", rss)
                    addProperty("memory_vmsize_kb", vsz)
                    addProperty("cmdline", cmdline.take(500))
                }
            }
            // Sort by CPU time (ps TIME when rooted, utime+stime otherwise), then
            // by PID descending so the order is stable.
            val sorted = jsons.sortedWith(
                compareByDescending<JsonObject> { it["cpu_time_total"].asLong }
                    .thenByDescending { it["pid"].asInt },
            )

            val result = JsonObject()
            result.addProperty("count", sorted.size)
            result.add("processes", com.google.gson.Gson().toJsonTree(sorted))

            AppLogger.d("SystemTools", "Listed ${sorted.size} processes (root=${root.canUseRoot()})")
            ToolResult(ok = true, result.toString())
        } catch (e: Exception) {
            AppLogger.e("SystemTools", "Failed to list processes", e)
            ToolResult(ok = false, error = e.message)
        }
    }
}

// Tools in this file intentionally enumerate all installed apps (system.app_list);
// the QueryPermissionsPackages lint advisory is acknowledged there.
@SuppressLint("QueryPermissionsPackages")
class AppListTool : AgentTool {
    override val definition = ToolDefinition(
        name = "system.app_list",
        category = ToolCategory.SYSTEM,
        description = "List all installed applications with package name, version, UID, and install/update times.",
        permissions = setOf(ToolPermission.ACCESSIBILITY),
        parameters = mapOf("filter" to "string", "include_system" to "boolean", "limit" to "integer"),
    )

    @SuppressLint("QueryPermissionsPackages")
    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val filter = o["filter"]?.asString?.lowercase() ?: ""
        val includeSystem = o["include_system"]?.asBoolean ?: false
        val limit = (o["limit"]?.asInt ?: 200).coerceIn(1, 1000)

        try {
            val pm = context.app.packageManager
            val apps = mutableListOf<JsonObject>()

            // Enumerating every installed app is the tool's purpose; the manifest's
            // <queries> declaration keeps this working on API 30+.
            @Suppress("DEPRECATION")
            val packages: List<android.content.pm.PackageInfo> = queryInstalledPackages(pm)
            for (pkgInfo in packages) {
                if (apps.size >= limit) break

                val appInfo = pkgInfo.applicationInfo ?: continue
                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdated = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                if (!includeSystem && isSystem && !isUpdated) continue

                val label = appInfo.loadLabel(pm).toString()
                val pkgName = pkgInfo.packageName

                if (filter.isNotBlank() && !label.lowercase().contains(filter) && !pkgName.lowercase().contains(filter)) continue

                @Suppress("DEPRECATION")
                val legacyVersionCode = pkgInfo.versionCode
                val json = JsonObject()
                json.addProperty("package_name", pkgName)
                json.addProperty("label", label)
                json.addProperty("version_name", pkgInfo.versionName)
                json.addProperty("version_code", legacyVersionCode.toLong())
                json.addProperty("uid", appInfo.uid)
                json.addProperty("is_system", isSystem)
                json.addProperty("is_updated_system", isUpdated)
                json.addProperty("data_dir", appInfo.dataDir)
                json.addProperty("source_dir", appInfo.sourceDir)
                json.addProperty("target_sdk", appInfo.targetSdkVersion.toLong())
                json.addProperty("min_sdk", appInfo.minSdkVersion.toLong())
                json.addProperty("permissions", pkgInfo.requestedPermissions?.joinToString(",") ?: "")
                json.addProperty("first_install_time", pkgInfo.firstInstallTime)
                json.addProperty("last_update_time", pkgInfo.lastUpdateTime)

                apps.add(json)
            }

            val result = JsonObject()
            result.addProperty("count", apps.size)
            result.add("applications", com.google.gson.Gson().toJsonTree(apps))

            AppLogger.d("SystemTools", "Listed ${apps.size} applications")
            ToolResult(ok = true, result.toString())
        } catch (e: Exception) {
            AppLogger.e("SystemTools", "Failed to list applications", e)
            ToolResult(ok = false, error = e.message)
        }
    }
}

class ProcessKillTool : AgentTool {
    override val definition = ToolDefinition(
        name = "system.process_kill",
        category = ToolCategory.SYSTEM,
        description = "Kill a process by PID. Requires root/su on Android.",
        permissions = setOf(ToolPermission.ROOT),
        parameters = mapOf("pid" to "integer", "signal" to "integer"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val o = arguments.asJsonObject
        val pid = o["pid"]?.asInt ?: return@withContext ToolResult(ok = false, error = "Missing pid")
        val signal = o["signal"]?.asInt ?: 9 // SIGKILL

        try {
            val rootManager = com.example.oreshik.agent.security.RootManager(context.app)
            if (!rootManager.canUseRoot()) {
                return@withContext ToolResult(ok = false, error = "Root access required to kill processes")
            }

            val (code, output) = rootManager.execute("kill -$signal $pid", 5)
            if (code == 0) {
                AppLogger.i("SystemTools", "Killed process $pid with signal $signal")
                ToolResult(ok = true, "Process $pid killed")
            } else {
                AppLogger.w("SystemTools", "Failed to kill process $pid: $output")
                ToolResult(ok = false, error = "Failed to kill: $output")
            }
        } catch (e: Exception) {
            AppLogger.e("SystemTools", "Error killing process $pid", e)
            ToolResult(ok = false, error = e.message)
        }
    }
}

/**
 * Queries all installed packages. Uses the modern PackageInfoFlags overload on
 * API 33+ and the legacy Int overload below that; the tool's entire purpose is
 * a full app inventory and the manifest declares <queries> wildcards.
 */
private fun queryInstalledPackages(pm: android.content.pm.PackageManager): List<android.content.pm.PackageInfo> =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        pm.getInstalledPackages(android.content.pm.PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledPackages(0)
    }

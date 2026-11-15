package com.example.oreshik.agent.security

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.example.oreshik.agent.core.AgentConfig
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

/** Restores app ownership when explicitly root-run workspace commands leave files behind. */
internal object WorkspaceOwnershipGuard {
    private const val MAX_SCANNED_ENTRIES = 250_000
    private val lock = Any()

    @Volatile
    private var auditRequired = true

    fun markMayBeRootOwned() {
        synchronized(lock) {
            auditRequired = true
        }
    }

    fun ensureAppOwned(context: Context, requestedWorkspace: File) {
        if (!auditRequired) return
        synchronized(lock) {
            if (!auditRequired) return

            val app = context.applicationContext
            val appFiles = app.filesDir.canonicalFile
            // Audit the canonical directory supplied to tools. AgentConfig refuses
            // to switch to a second workspace if this path is inaccessible.
            val workspacePath = AgentConfig.workspaceRoot(app)

            val workspaceStat = try {
                Os.lstat(workspacePath.absolutePath)
            } catch (error: Exception) {
                throw IOException("Cannot inspect the app workspace at ${workspacePath.absolutePath}: ${error.message}", error)
            }
            if (!OsConstants.S_ISDIR(workspaceStat.st_mode) || OsConstants.S_ISLNK(workspaceStat.st_mode)) {
                throw IOException("The app workspace is not a regular directory: ${workspacePath.absolutePath}")
            }

            val workspace = workspacePath.canonicalFile
            if (workspace.parentFile != appFiles || requestedWorkspace.canonicalFile != workspace) {
                throw IOException("Workspace ownership repair is limited to the private app workspace")
            }

            val initialIssue = findRepairIssue(workspace, Process.myUid())
            if (initialIssue == null) {
                auditRequired = false
                return
            }

            if (!AgentConfig.allowRoot) {
                throw IOException(
                    "Workspace access needs repair ($initialIssue). Enable Allow root in Settings and retry to restore app access.",
                )
            }

            val (exitCode, output) = RootManager(context.applicationContext).repairWorkspaceOwnership(workspace)
            if (exitCode != 0) {
                throw IOException(
                    "Could not restore app ownership of workspace files (root command exited $exitCode): ${output.take(400)}",
                )
            }
            val remainingIssue = findRepairIssue(workspace, Process.myUid())
            if (remainingIssue != null) {
                throw IOException("Workspace repair did not restore app access ($remainingIssue)")
            }
            auditRequired = false
        }
    }

    /** Returns the first path and reason which prevents the app from using the workspace. */
    private fun findRepairIssue(root: File, appUid: Int): String? {
        val pending = ArrayDeque<File>()
        pending.add(root)
        var scanned = 0
        while (pending.isNotEmpty()) {
            val entry = pending.removeLast()
            if (++scanned > MAX_SCANNED_ENTRIES) {
                throw IOException("Workspace contains more than $MAX_SCANNED_ENTRIES entries; refusing an unbounded ownership scan")
            }
            val stat = try {
                Os.lstat(entry.absolutePath)
            } catch (error: Exception) {
                return "cannot inspect ${entry.absolutePath}: ${error.message}"
            }
            if (OsConstants.S_ISLNK(stat.st_mode)) continue
            if (stat.st_uid != appUid) {
                return "${entry.absolutePath} is owned by uid ${stat.st_uid}, expected $appUid"
            }

            val isDirectory = OsConstants.S_ISDIR(stat.st_mode)
            val ownerAccess = OsConstants.S_IRUSR or OsConstants.S_IWUSR or
                if (isDirectory) OsConstants.S_IXUSR else 0
            if (stat.st_mode and ownerAccess != ownerAccess) {
                return "${entry.absolutePath} lacks owner read/write${if (isDirectory) "/execute" else ""} permission"
            }

            if (isDirectory) {
                val children = entry.listFiles()
                    ?: return "cannot enumerate directory ${entry.absolutePath}"
                children.forEach(pending::add)
            }
        }
        return null
    }
}

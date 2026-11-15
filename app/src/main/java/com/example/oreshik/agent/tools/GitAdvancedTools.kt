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

private fun openWorkspaceGit(workspace: File) =
    FileRepositoryBuilder().findGitDir(workspace).build()

private fun validGitPath(raw: String): String {
    val path = raw.replace('\\', '/').trim()
    require(path.isNotBlank() && !path.startsWith("/") &&
        path.split('/').none { it.isBlank() || it == "." || it == ".." }
    ) { "Git path must be workspace-relative and cannot contain traversal segments." }
    return path
}

class GitBranchTool : AgentTool {
    override val definition = ToolDefinition(
        name = "git.branch",
        category = ToolCategory.GIT,
        description = "List local branches or create, switch, and safely delete a local branch. Current branch cannot be deleted.",
        permissions = setOf(ToolPermission.GIT, ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf("action" to "string (list, create, switch, delete)", "name" to "string"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val args = arguments.asJsonObject
        val action = args["action"]?.asString?.lowercase()?.trim() ?: "list"
        val name = args["name"]?.asString?.trim().orEmpty()
        runCatching {
            openWorkspaceGit(context.workspace).use { repo ->
                Git(repo).use { git ->
                    when (action) {
                        "list" -> ToolResult.success(git.branchList().call().joinToString("\n") {
                            (if (it.name == repo.fullBranch) "* " else "  ") + it.name.removePrefix("refs/heads/")
                        }.ifBlank { "No local branches." })
                        "create" -> {
                            require(name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,119}$")) && !name.contains("..")) { "Invalid branch name." }
                            git.branchCreate().setName(name).call()
                            ToolResult.success("Created local branch $name.")
                        }
                        "switch" -> {
                            require(name.isNotBlank()) { "Branch name is required." }
                            git.checkout().setName(name).call()
                            ToolResult.success("Switched to branch $name.")
                        }
                        "delete" -> {
                            require(name.isNotBlank() && name != repo.branch) { "Cannot delete the current branch or an empty branch name." }
                            val deleted = git.branchDelete().setBranchNames(name).setForce(false).call()
                            ToolResult.success("Deleted: ${deleted.joinToString()}")
                        }
                        else -> ToolResult.failure("Supported actions: list, create, switch, delete.")
                    }
                }
            }
        }.getOrElse { ToolResult.failure("Git branch operation failed: ${it.message}") }
    }
}

class GitStageTool : AgentTool {
    override val definition = ToolDefinition(
        name = "git.stage",
        category = ToolCategory.GIT,
        description = "Stage only the explicitly listed workspace-relative paths. No wildcard-all staging is exposed.",
        permissions = setOf(ToolPermission.GIT, ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf("paths" to "array of workspace-relative file or directory paths"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val paths = arguments.asJsonObject.getAsJsonArray("paths")?.map { validGitPath(it.asString) }
                ?: error("paths must be an array.")
            require(paths.isNotEmpty() && paths.size <= 100) { "Provide 1–100 explicit paths." }
            val root = context.workspace.canonicalFile
            paths.forEach { path ->
                val target = File(root, path).canonicalFile
                require(target.path.startsWith(root.path + File.separator) && target.exists()) {
                    "Path does not exist inside the workspace: $path"
                }
            }
            openWorkspaceGit(root).use { repo ->
                Git(repo).use { git ->
                    paths.distinct().forEach { git.add().addFilepattern(it).call() }
                }
            }
            ToolResult.success("Staged ${paths.distinct().size} explicit path(s).")
        }.getOrElse { ToolResult.failure("Git stage failed: ${it.message}") }
    }
}

class GitCommitTool : AgentTool {
    override val definition = ToolDefinition(
        name = "git.commit",
        category = ToolCategory.GIT,
        description = "Commit already-staged changes with an explicit message. Does not stage unstaged or untracked files automatically.",
        permissions = setOf(ToolPermission.GIT, ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf("message" to "string (1–2000 characters)"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val message = arguments.asJsonObject["message"]?.asString?.trim().orEmpty()
            require(message.isNotBlank() && message.length <= 2000) { "Commit message must be 1–2000 characters." }
            openWorkspaceGit(context.workspace).use { repo ->
                Git(repo).use { git ->
                    val status = git.status().call()
                    require(status.hasUncommittedChanges()) { "There are no staged changes to commit." }
                    val commit = git.commit().setMessage(message).setAllowEmpty(false).call()
                    ToolResult.success("Created commit ${commit.name} — ${commit.shortMessage}")
                }
            }
        }.getOrElse { ToolResult.failure("Git commit failed: ${it.message}") }
    }
}

class GitPatchExportTool : AgentTool {
    override val definition = ToolDefinition(
        name = "git.patch.export",
        category = ToolCategory.GIT,
        description = "Export the staged or unstaged Git diff to a new patch file inside the workspace. Existing files are never overwritten.",
        permissions = setOf(ToolPermission.GIT, ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf("path" to "string (workspace-relative .patch file)", "staged" to "boolean"),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val args = arguments.asJsonObject
            val relative = validGitPath(args["path"]?.asString.orEmpty())
            require(relative.endsWith(".patch", ignoreCase = true)) { "Patch export path must end in .patch." }
            val root = context.workspace.canonicalFile
            val target = File(root, relative).canonicalFile
            require(target.path.startsWith(root.path + File.separator) && !target.exists()) {
                "Patch destination must be a new file inside the workspace."
            }
            require(target.parentFile?.isDirectory == true) { "Patch destination parent directory must already exist." }
            val output = ByteArrayOutputStream()
            openWorkspaceGit(root).use { repo ->
                Git(repo).use { git ->
                    DiffFormatter(output).use { formatter ->
                        formatter.setRepository(repo)
                        val diffs = if (args["staged"]?.asBoolean == true) git.diff().setCached(true).call() else git.diff().call()
                        diffs.forEach { formatter.format(it) }
                    }
                }
            }
            val bytes = output.toByteArray()
            require(bytes.size <= 8 * 1024 * 1024) { "Patch exceeds the 8 MiB export limit." }
            require(bytes.isNotEmpty()) { "No diff to export." }
            target.writeBytes(bytes)
            ToolResult.success("Exported ${bytes.size} bytes to ${target.relativeTo(root).invariantSeparatorsPath}.")
        }.getOrElse { ToolResult.failure("Git patch export failed: ${it.message}") }
    }
}

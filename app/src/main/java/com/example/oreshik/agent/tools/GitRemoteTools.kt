package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/** Explicit HTTPS-only Git remote operations. Credentials are supplied per call and never persisted. */
class GitRemoteTool : AgentTool {
    override val definition = ToolDefinition(
        name = "git.remote",
        category = ToolCategory.GIT,
        description = "Clone, fetch, pull or push a Git repository over HTTPS. Pull and push use the selected branch and never force-push. A token is accepted per call for private repositories and is never saved.",
        permissions = setOf(ToolPermission.GIT, ToolPermission.NETWORK, ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf(
            "action" to "string (clone, fetch, pull, push)",
            "url" to "string (HTTPS repository URL; clone only)",
            "destination" to "string (workspace-relative clone folder; clone only)",
            "branch" to "string (optional branch)",
            "token" to "string (optional access token; not persisted)",
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val args = arguments.asJsonObject
        val action = args["action"]?.asString?.lowercase()?.trim().orEmpty()
        val branch = args["branch"]?.asString?.trim().orEmpty()
        val token = args["token"]?.asString?.trim().orEmpty()
        if (branch.isNotBlank() && !branch.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$"))) {
            return@withContext ToolResult.failure("Invalid branch name.")
        }
        val credentials = token.takeIf { it.isNotEmpty() }?.let {
            UsernamePasswordCredentialsProvider("x-access-token", it)
        }
        fun remoteUrl(): String? {
            val value = args["url"]?.asString?.trim().orEmpty()
            if (value.isBlank()) return null
            val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
                uri.query != null || uri.fragment != null
            ) return null
            return value
        }
        runCatching {
            when (action) {
                "clone" -> {
                    val url = remoteUrl() ?: return@runCatching ToolResult.failure("Clone requires a valid HTTPS URL without embedded credentials.")
                    val relative = args["destination"]?.asString?.trim().orEmpty()
                    if (relative.isBlank() || relative.startsWith("/") ||
                        relative.replace('\\', '/').split('/').any { it.isBlank() || it == "." || it == ".." }
                    ) return@runCatching ToolResult.failure("Destination must be a safe workspace-relative folder.")
                    val workspace = context.workspace.canonicalFile
                    val destination = File(workspace, relative).canonicalFile
                    if (!destination.path.startsWith(workspace.path + File.separator) || destination.exists()) {
                        return@runCatching ToolResult.failure("Clone destination must be a new folder inside the workspace.")
                    }
                    destination.parentFile?.mkdirs()
                    Git.cloneRepository().setURI(url).setDirectory(destination).apply {
                        if (branch.isNotBlank()) setBranch(branch)
                        if (credentials != null) setCredentialsProvider(credentials)
                    }.call().use { git ->
                        ToolResult.success("Cloned repository into ${destination.relativeTo(workspace).invariantSeparatorsPath}; branch ${git.repository.branch}.")
                    }
                }
                "fetch", "pull", "push" -> {
                    val repo = org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                        .findGitDir(context.workspace).build()
                    repo.use {
                        Git(repo).use { git ->
                            if (action == "fetch") {
                                val command = git.fetch().setRemote("origin")
                                if (credentials != null) command.setCredentialsProvider(credentials)
                                val result = command.call()
                                ToolResult.success("Fetch completed from origin. Updates: ${result.trackingRefUpdates.size}.")
                            } else {
                                val selectedBranch = branch.ifBlank { repo.branch }
                                if (action == "pull") {
                                    val command = git.pull().setRemote("origin").setRemoteBranchName(selectedBranch)
                                    if (credentials != null) command.setCredentialsProvider(credentials)
                                    val result = command.call()
                                    ToolResult.success("Pull completed: ${result.mergeResult?.mergeStatus ?: "no merge result"}; branch $selectedBranch.")
                                } else {
                                    val command = git.push().setRemote("origin")
                                        .setRefSpecs(org.eclipse.jgit.transport.RefSpec("refs/heads/$selectedBranch:refs/heads/$selectedBranch"))
                                    if (credentials != null) command.setCredentialsProvider(credentials)
                                    val results = command.call()
                                    val statuses = results.joinToString { it.remoteUpdates.joinToString { update -> "${update.remoteName}: ${update.status}" } }
                                    ToolResult.success("Push completed: ${statuses.ifBlank { "no remote updates reported" }}")
                                }
                            }
                        }
                    }
                }
                else -> ToolResult.failure("Supported actions: clone, fetch, pull, push.")
            }
        }.getOrElse { failure ->
            // Do not echo exception text: some transport errors may contain remote or credential details.
            ToolResult.failure("Git remote $action failed (${failure.javaClass.simpleName}). Check HTTPS access, branch and repository permissions.")
        }
    }
}

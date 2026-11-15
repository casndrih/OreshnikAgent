package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Small, offline starter templates; creation is non-destructive and workspace-scoped. */
class ProjectTemplateTool : AgentTool {
    override val definition = ToolDefinition(
        name = "project.template",
        category = ToolCategory.FILESYSTEM,
        description = "List or create a starter project in the workspace. Templates are generated locally and never overwrite existing files.",
        permissions = setOf(ToolPermission.WRITE_WORKSPACE),
        parameters = mapOf("action" to "string (list or create)", "template" to "string", "directory" to "string"),
    )

    private val templates = linkedMapOf(
        "kotlin-cli" to mapOf(
            "settings.gradle.kts" to "rootProject.name = \"KotlinStarter\"\n",
            "build.gradle.kts" to "plugins { kotlin(\"jvm\") version \"2.2.20\"; application }\nrepositories { mavenCentral() }\napplication { mainClass.set(\"MainKt\") }\n",
            "src/main/kotlin/Main.kt" to "fun main() {\n    println(\"Hello from Kotlin\")\n}\n",
            ".gitignore" to ".gradle/\nbuild/\n.idea/\n",
            "README.md" to "# Kotlin CLI\n\nRun with `gradle run`.\n",
        ),
        "android-kotlin" to mapOf(
            "settings.gradle.kts" to "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\nrootProject.name = \"AndroidStarter\"\ninclude(\":app\")\n",
            "build.gradle.kts" to "plugins { id(\"com.android.application\") version \"8.13.0\" apply false; id(\"org.jetbrains.kotlin.android\") version \"2.2.20\" apply false }\n",
            "app/build.gradle.kts" to "plugins { id(\"com.android.application\"); id(\"org.jetbrains.kotlin.android\") }\nandroid { namespace = \"dev.starter.app\"; compileSdk = 36; defaultConfig { applicationId = \"dev.starter.app\"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = \"1.0\" } }\n",
            "app/src/main/AndroidManifest.xml" to "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:theme=\"@android:style/Theme.Material.Light.NoActionBar\" android:label=\"Android Starter\"><activity android:name=\".MainActivity\" android:exported=\"true\"><intent-filter><action android:name=\"android.intent.action.MAIN\"/><category android:name=\"android.intent.category.LAUNCHER\"/></intent-filter></activity></application></manifest>\n",
            "app/src/main/java/dev/starter/app/MainActivity.kt" to "package dev.starter.app\n\nimport android.app.Activity\nimport android.os.Bundle\nimport android.widget.TextView\n\nclass MainActivity : Activity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        setContentView(TextView(this).apply { text = \"Hello Android\"; textSize = 24f })\n    }\n}\n",
            ".gitignore" to ".gradle/\nbuild/\nlocal.properties\n.idea/\n",
            "README.md" to "# Android Kotlin Starter\n\nOpen this folder in Android Studio and sync Gradle.\n",
        ),
        "python" to mapOf(
            "main.py" to "def main():\n    print(\"Hello from Python\")\n\nif __name__ == \"__main__\":\n    main()\n",
            "requirements.txt" to "# Add pinned dependencies here\n",
            ".gitignore" to "__pycache__/\n.venv/\n.env\n",
            "README.md" to "# Python Starter\n\nRun: `python main.py`.\n",
        ),
        "node" to mapOf(
            "package.json" to "{\n  \"name\": \"node-starter\",\n  \"version\": \"1.0.0\",\n  \"private\": true,\n  \"scripts\": { \"start\": \"node src/index.js\" }\n}\n",
            "src/index.js" to "console.log('Hello from Node.js');\n",
            ".gitignore" to "node_modules/\n.env\n",
            "README.md" to "# Node.js Starter\n\nRun `npm start`.\n",
        ),
        "php-api" to mapOf(
            "public/index.php" to "<?php\ndeclare(strict_types=1);\nheader('Content-Type: application/json');\necho json_encode(['ok' => true, 'message' => 'Hello from PHP']);\n",
            "composer.json" to "{\n  \"name\": \"starter/api\",\n  \"description\": \"Minimal PHP API starter\",\n  \"type\": \"project\",\n  \"require\": { \"php\": \"^8.2\" }\n}\n",
            ".gitignore" to "vendor/\n.env\n",
            "README.md" to "# PHP API Starter\n\nServe the `public` directory with PHP's built-in server.\n",
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        val args = arguments.asJsonObject
        when (args["action"]?.asString?.lowercase()?.trim() ?: "list") {
            "list" -> ToolResult.success(templates.keys.joinToString("\n") { "- $it" })
            "create" -> runCatching {
                val id = args["template"]?.asString?.trim().orEmpty()
                val files = templates[id] ?: error("Unknown template. Available: ${templates.keys.joinToString()}")
                val relative = args["directory"]?.asString?.trim().orEmpty()
                require(relative.isNotBlank() && !relative.startsWith("/") &&
                    relative.replace('\\', '/').split('/').none { it.isBlank() || it == "." || it == ".." }
                ) { "Directory must be a safe workspace-relative path." }
                val workspace = context.workspace.canonicalFile
                val target = File(workspace, relative).canonicalFile
                require(target.path.startsWith(workspace.path + File.separator)) { "Directory escaped workspace." }
                require(!target.exists()) { "Target directory already exists; templates never overwrite existing data." }
                val staged = File(context.app.cacheDir, "template-${System.nanoTime()}").apply { mkdirs() }
                try {
                    files.forEach { (path, body) ->
                        val file = File(staged, path).canonicalFile
                        require(file.path.startsWith(staged.canonicalPath + File.separator)) { "Invalid template file path." }
                        file.parentFile?.mkdirs()
                        file.writeText(body, Charsets.UTF_8)
                    }
                    require(target.parentFile?.exists() != false || target.parentFile?.mkdirs() == true) { "Could not create target parent." }
                    require(staged.renameTo(target) || run {
                        staged.copyRecursively(target, overwrite = false)
                        true
                    }) { "Could not install template." }
                } finally {
                    if (staged.exists()) staged.deleteRecursively()
                }
                ToolResult.success("Created $id starter project at ${target.relativeTo(workspace).invariantSeparatorsPath} with ${files.size} files.")
            }.getOrElse { ToolResult.failure("Project template failed: ${it.message}") }
            else -> ToolResult.failure("Supported actions: list, create.")
        }
    }
}

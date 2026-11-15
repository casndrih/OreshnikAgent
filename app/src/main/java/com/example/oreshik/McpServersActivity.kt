package com.example.oreshik

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.oreshik.data.AgentDatabase
import com.example.oreshik.data.McpServerEntity
import com.example.oreshik.logging.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.*

class McpServersActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: LinearLayout
    private val db by lazy { AgentDatabase.get(this) }

    private val presets = listOf(
        McpServerEntity(name = "Composio Integrations", transport = "http", endpoint = "https://mcp.composio.dev/v1", command = "npx", arguments = "-y @composio/mcp", environment = "{\"COMPOSIO_API_KEY\": \"\"}"),
        McpServerEntity(name = "GitHub API", transport = "stdio", endpoint = "https://api.github.com/mcp", command = "npx", arguments = "-y @modelcontextprotocol/server-github", environment = "{\"GITHUB_PERSONAL_ACCESS_TOKEN\": \"\"}"),
        McpServerEntity(name = "Brave Search", transport = "stdio", endpoint = "", command = "npx", arguments = "-y @modelcontextprotocol/server-brave-search", environment = "{\"BRAVE_API_KEY\": \"\"}"),
        McpServerEntity(name = "Knowledge Memory", transport = "stdio", endpoint = "", command = "npx", arguments = "-y @modelcontextprotocol/server-memory", environment = ""),
        McpServerEntity(name = "Puppeteer Browser", transport = "stdio", endpoint = "", command = "npx", arguments = "-y @modelcontextprotocol/server-puppeteer", environment = ""),
        McpServerEntity(name = "Local Filesystem", transport = "stdio", endpoint = "", command = "npx", arguments = "-y @modelcontextprotocol/server-filesystem", environment = ""),
        McpServerEntity(name = "SQLite Database", transport = "stdio", endpoint = "", command = "npx", arguments = "-y @modelcontextprotocol/server-sqlite", environment = ""),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list_settings)
        AppLogger.i(TAG, "MCP server registry opened")
        findViewById<MaterialToolbar>(R.id.toolbar).apply { title = "MCP Hub"; setNavigationOnClickListener { finish() } }
        findViewById<TextView>(R.id.tvSubtitle).apply {
            text = getString(R.string.mcp_subtitle)
            visibility = View.VISIBLE
        }
        container = findViewById(R.id.sectionContainer)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRuntimeManager).apply {
            text = getString(R.string.mcp_add_server)
            setOnClickListener { showEditor(null) }
        }
        load()
    }

    private fun load() = scope.launch(Dispatchers.IO) {
        var servers = db.mcpServers().all()
        if (servers.isEmpty()) {
            AppLogger.i(TAG, "Registry empty — seeding ${presets.size} preset MCP servers")
            presets.forEach { db.mcpServers().put(it) }
            servers = db.mcpServers().all()
        }
        AppLogger.d(TAG, "${servers.size} MCP server(s) loaded")
        render(servers)
    }

    private suspend fun render(servers: List<McpServerEntity>) = withContext(Dispatchers.Main) {
        container.removeAllViews()
        val rows = SettingsUi.addSection(LayoutInflater.from(this@McpServersActivity), container, "Server registry", "${servers.size} configured MCP servers")
        servers.forEach { server ->
            val detail = buildString {
                append(server.transport.uppercase())
                if (server.endpoint.isNotBlank()) append(" • ").append(server.endpoint)
                if (server.command.isNotBlank()) append(" • ").append(server.command).append(" ").append(server.arguments)
            }
            val row = SettingsUi.addRow(LayoutInflater.from(this@McpServersActivity), rows, server.name, detail, R.drawable.ic_mcp)
            SettingsUi.switchOf(row).apply {
                isChecked = server.enabled
                setOnCheckedChangeListener { _, checked ->
                    AppLogger.i(TAG, "MCP '${server.name}' enabled=$checked")
                    scope.launch(Dispatchers.IO) { db.mcpServers().setEnabled(server.id, checked) }
                }
            }
            SettingsUi.actionOf(row).setOnClickListener { showMenu(server) }
        }
    }

    private fun showMenu(server: McpServerEntity) {
        AlertDialog.Builder(this).setTitle(server.name).setItems(arrayOf("Edit", if (server.enabled) "Disable" else "Enable", "Delete")) { _, which ->
            when (which) {
                0 -> showEditor(server)
                1 -> scope.launch(Dispatchers.IO) { db.mcpServers().setEnabled(server.id, !server.enabled); load() }
                2 -> confirmDelete(server)
            }
        }.show()
    }

    private fun showEditor(existing: McpServerEntity?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 16, 40, 16) }
        fun field(hint: String, value: String = "") = EditText(this).apply { this.hint = hint; setText(value); setSingleLine() }

        val presetSpinner = Spinner(this).apply {
            val presetNames = listOf("Custom Server") + presets.map { it.name }
            adapter = ArrayAdapter(this@McpServersActivity, android.R.layout.simple_spinner_dropdown_item, presetNames)
        }

        val name = field("Server name", existing?.name.orEmpty())
        val transport = Spinner(this).apply {
            adapter = ArrayAdapter(this@McpServersActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("http", "stdio", "sse"))
            setSelection(arrayOf("http", "stdio", "sse").indexOf(existing?.transport ?: "http").coerceAtLeast(0))
        }
        val endpoint = field("Endpoint URL (HTTP/SSE)", existing?.endpoint.orEmpty())
        val command = field("Command (stdio)", existing?.command.orEmpty())
        val args = field("Arguments (space separated)", existing?.arguments.orEmpty())
        val env = field("Environment JSON (optional)", existing?.environment.orEmpty())

        if (existing == null) {
            box.addView(TextView(this).apply { text = getString(R.string.mcp_select_preset); setTypeface(null, android.graphics.Typeface.BOLD) })
            box.addView(presetSpinner)
            presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position > 0) {
                        val p = presets[position - 1]
                        name.setText(p.name)
                        transport.setSelection(arrayOf("http", "stdio", "sse").indexOf(p.transport).coerceAtLeast(0))
                        endpoint.setText(p.endpoint)
                        command.setText(p.command)
                        args.setText(p.arguments)
                        env.setText(p.environment)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        box.addView(name); box.addView(transport); box.addView(endpoint); box.addView(command); box.addView(args); box.addView(env)
        AlertDialog.Builder(this).setTitle(if (existing == null) "Add MCP server" else "Edit MCP server").setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isBlank()) {
                    Toast.makeText(this, "Server name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val item = McpServerEntity(
                    existing?.id ?: 0, n, transport.selectedItem.toString(), endpoint.text.toString().trim(),
                    command.text.toString().trim(), args.text.toString().trim(), env.text.toString().trim(),
                    existing?.enabled ?: true, existing?.createdAt ?: System.currentTimeMillis(), System.currentTimeMillis()
                )
                AppLogger.i(TAG, "MCP server saved: '$n' (${item.transport})")
                scope.launch(Dispatchers.IO) { db.mcpServers().put(item); load() }
            }.show()
    }

    private fun confirmDelete(server: McpServerEntity) {
        AlertDialog.Builder(this).setTitle("Delete MCP server?").setMessage("Remove \"${server.name}\" from the local MCP registry?")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                AppLogger.i(TAG, "MCP server deleted: '${server.name}'")
                scope.launch(Dispatchers.IO) { db.mcpServers().delete(server.id); load() }
            }.show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "McpServers"
    }
}

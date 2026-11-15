package com.example.oreshik

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.oreshik.agent.core.AgentEngine
import com.example.oreshik.agent.core.ToolCategory
import com.example.oreshik.agent.core.ToolPermission
import com.example.oreshik.agent.security.PermissionBroker
import com.example.oreshik.agent.security.PermissionManager
import com.example.oreshik.agent.security.ToolApprovalPolicy
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.util.LinkedHashMap
import java.util.ArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ToolsActivity : AppCompatActivity() {
    private lateinit var toolList: RecyclerView
    private lateinit var toolsAdapter: ToolsAdapter
    private lateinit var progressBar: View
    private lateinit var tvEmptyState: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools)
        
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        toolbar.title = "Agent Tools"
        toolbar.setNavigationOnClickListener { finish() }
        
        findViewById<TextView>(R.id.tvSubtitle).apply {
            text = getString(R.string.tools_subtitle)
            visibility = View.VISIBLE
        }
        
        toolList = findViewById(R.id.toolList)
        toolsAdapter = ToolsAdapter()
        toolList.layoutManager = LinearLayoutManager(this)
        toolList.adapter = toolsAdapter
        progressBar = findViewById(R.id.progressBar)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        
        findViewById<MaterialButton>(R.id.btnRuntimeManager)
            .setOnClickListener { startActivity(android.content.Intent(this, RuntimeActivity::class.java)) }
        
        loadTools()
    }
    
    private fun loadTools() {
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            tvEmptyState.visibility = View.GONE
            val result = withContext(Dispatchers.IO) { runCatching { buildToolRows() } }
            if (isFinishing || isDestroyed) return@launch
            progressBar.visibility = View.GONE
            result.onSuccess { rows ->
                toolsAdapter.submitRows(rows)
                tvEmptyState.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                if (rows.isEmpty()) tvEmptyState.text = getString(R.string.no_tools_registered)
            }.onFailure { error ->
                toolsAdapter.submitRows(emptyList())
                tvEmptyState.text = "Unable to load tools: ${error.message ?: error.javaClass.simpleName}"
                tvEmptyState.visibility = View.VISIBLE
            }
        }
    }

    /** Build the registry and group its entries away from the UI thread. */
    private fun buildToolRows(): List<ToolListRow> {
        val appContext = applicationContext
        val engine = AgentEngine(
            appContext,
            permissions = PermissionBroker(PermissionManager(appContext)),
        )
        val toolsByCategory = LinkedHashMap<ToolCategory, MutableList<com.example.oreshik.agent.core.AgentTool>>()
        engine.registry.all().forEach { tool ->
            toolsByCategory.getOrPut(tool.definition.category) { mutableListOf() }.add(tool)
        }

        val fileSystemTools = toolsByCategory[ToolCategory.FILESYSTEM].orEmpty()
        val fileEditTools = fileSystemTools.filter {
            it.definition.permissions.contains(ToolPermission.WRITE_WORKSPACE) &&
                it.definition.name.startsWith("file.") &&
                !it.definition.name.startsWith("file.read") &&
                !it.definition.name.startsWith("file.list") &&
                !it.definition.name.startsWith("file.search")
        }
        val fileReadTools = fileSystemTools.filterNot { it in fileEditTools }
        toolsByCategory[ToolCategory.FILESYSTEM] = ArrayList(fileReadTools)

        val categoryInfo = mapOf(
                ToolCategory.FILESYSTEM to CategoryDisplayInfo(
                    "File System",
                    "Read, list, and search workspace files",
                    R.drawable.ic_file,
                ),
                ToolCategory.DATABASE to CategoryDisplayInfo(
                    "Database",
                    "SQLite, Access, and ODBC database tools",
                    R.drawable.ic_file,
                ),
                ToolCategory.PROCESS to CategoryDisplayInfo(
                    "Process",
                    "Execute shell commands and app-owned runtime tools",
                    R.drawable.ic_terminal,
                ),
                ToolCategory.SYSTEM to CategoryDisplayInfo(
                    "System",
                    "List processes, apps, and kill processes (root)",
                    R.drawable.ic_system,
                ),
                ToolCategory.REVERSE_ENGINEERING to CategoryDisplayInfo(
                    "Reverse Engineering", 
                    "Analyze APKs, DEX, binaries, extract strings",
                    R.drawable.ic_debug,
                ),
                ToolCategory.SECURITY to CategoryDisplayInfo(
                    "Accessibility", 
                    "Screen reading, click, scroll, focus, global actions",
                    R.drawable.ic_accessibility,
                ),
                ToolCategory.MEMORY to CategoryDisplayInfo(
                    "Memory & Skills", 
                    "Search, save, delete persistent memories and list skills",
                    R.drawable.ic_memory,
                ),
                ToolCategory.RUNTIME to CategoryDisplayInfo(
                    "Runtime", 
                    "Python, Node, Java, PHP, Ruby, C/C++, Kotlin, Gradle, Make, Clang",
                    R.drawable.ic_runtime,
                ),
                ToolCategory.GIT to CategoryDisplayInfo(
                    "Git", 
                    "Status, diff, log using app-owned Git engine",
                    R.drawable.ic_git,
                ),
                ToolCategory.BUILD to CategoryDisplayInfo(
                    "Build", 
                    "Android Gradle build using wrapper or managed runtime",
                    R.drawable.ic_build,
                ),
                ToolCategory.ROOT to CategoryDisplayInfo(
                    "Root", 
                    "Execute commands as root via su",
                    R.drawable.ic_root,
                ),
                ToolCategory.WEB to CategoryDisplayInfo(
                    "Web", 
                    "Search and crawl the web",
                    R.drawable.ic_web,
                ),
                ToolCategory.NETWORK to CategoryDisplayInfo(
                    "Network", 
                    "Network utilities",
                    R.drawable.ic_network,
                ),
                ToolCategory.TRAFFIC_CAPTURE to CategoryDisplayInfo(
                    "Traffic Capture", 
                    "HTTP capture, replay, modification, packet capture and log evidence",
                    R.drawable.ic_network,
                ),
                ToolCategory.MEMORY_MANIPULATION to CategoryDisplayInfo(
                    "Memory Manipulation",
                    "Scan, read, write process memory and inject libraries (root required)",
                    R.drawable.ic_debug,
                ),
                ToolCategory.SKILLS to CategoryDisplayInfo(
                    "Skills",
                    "List the skill library and activate full skill instructions on demand",
                    R.drawable.ic_skills,
                ),
        )

        val orderedCategories = listOf(
                ToolCategory.FILESYSTEM,
                ToolCategory.DATABASE,
                ToolCategory.PROCESS,
                ToolCategory.SYSTEM,
                ToolCategory.REVERSE_ENGINEERING,
                ToolCategory.SECURITY,  // Accessibility
                ToolCategory.MEMORY,
                ToolCategory.RUNTIME,
                ToolCategory.GIT,
                ToolCategory.BUILD,
                ToolCategory.ROOT,
                ToolCategory.WEB,
                ToolCategory.NETWORK,
                ToolCategory.TRAFFIC_CAPTURE,
                ToolCategory.MEMORY_MANIPULATION,
                ToolCategory.SKILLS,
        ).asSequence().distinct().filter { toolsByCategory.containsKey(it) }.toList()
        val rows = mutableListOf<ToolListRow>()

        fun appendCategory(info: CategoryDisplayInfo, entries: List<com.example.oreshik.agent.core.AgentTool>) {
            if (entries.isEmpty()) return
            rows += ToolListRow.Category(info)
            entries.forEach { rows += ToolListRow.Tool(it) }
        }

        orderedCategories.forEach { category ->
            val info = categoryInfo[category]
                ?: CategoryDisplayInfo(category.name.replace('_', ' '), "", R.drawable.ic_info)
            appendCategory(info, toolsByCategory[category].orEmpty())
            if (category == ToolCategory.FILESYSTEM) {
                appendCategory(
                    CategoryDisplayInfo("File Edit", "Write, patch, and delete workspace files", R.drawable.ic_file),
                    fileEditTools,
                )
            }
        }
        return rows
    }

    private inner class ToolsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<ToolListRow> = emptyList()

        fun submitRows(items: List<ToolListRow>) {
            rows = items
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is ToolListRow.Category -> TYPE_CATEGORY
            is ToolListRow.Tool -> TYPE_TOOL
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_CATEGORY) {
                val view = inflater.inflate(R.layout.view_settings_section, parent, false)
                view.findViewById<LinearLayout>(R.id.rowsContainer).visibility = View.GONE
                CategoryViewHolder(view)
            } else {
                ToolViewHolder(
                    inflater.inflate(R.layout.view_tool_card, parent, false)
                        as com.google.android.material.card.MaterialCardView,
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is ToolListRow.Category -> (holder as CategoryViewHolder).bind(row.info)
                is ToolListRow.Tool -> (holder as ToolViewHolder).bind(row.tool)
            }
        }

        private inner class CategoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(info: CategoryDisplayInfo) {
                itemView.findViewById<TextView>(R.id.sectionTitle).text = info.title
                itemView.findViewById<TextView>(R.id.sectionSubtitle).apply {
                    text = info.subtitle
                    visibility = if (info.subtitle.isBlank()) View.GONE else View.VISIBLE
                }
            }
        }

        private inner class ToolViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(tool: com.example.oreshik.agent.core.AgentTool) =
                bindToolCard(itemView as com.google.android.material.card.MaterialCardView, tool)
        }

        private val TYPE_CATEGORY = 0
        private val TYPE_TOOL = 1
    }

    private fun bindToolCard(card: com.google.android.material.card.MaterialCardView, tool: com.example.oreshik.agent.core.AgentTool) {
        
        val toolName = card.findViewById<TextView>(R.id.toolName)
        val toolDescription = card.findViewById<TextView>(R.id.toolDescription)
        val toolFullDescription = card.findViewById<TextView>(R.id.toolFullDescription)
        val toolIcon = card.findViewById<ImageView>(R.id.toolIcon)
        val permissionChipsContainer = card.findViewById<LinearLayout>(R.id.permissionChipsContainer)
        val expandButton = card.findViewById<ImageButton>(R.id.expandButton)
        val toolDetails = card.findViewById<LinearLayout>(R.id.toolDetails)
        val toolParametersLabel = card.findViewById<TextView>(R.id.toolParametersLabel)
        val toolParametersContainer = card.findViewById<LinearLayout>(R.id.toolParametersContainer)
        val toolPermissionsContainer = card.findViewById<LinearLayout>(R.id.toolPermissionsContainer)
        val policyButton = card.findViewById<MaterialButton>(R.id.btnToolApprovalPolicy)
        val policyManager = PermissionManager(applicationContext)
        var currentPolicy = ToolApprovalPolicy.DEFAULT

        permissionChipsContainer.removeAllViews()
        toolParametersContainer.removeAllViews()
        toolPermissionsContainer.removeAllViews()
        toolParametersLabel.visibility = View.GONE
        toolParametersContainer.visibility = View.GONE
        toolDetails.visibility = View.GONE
        expandButton.setImageResource(R.drawable.ic_expand_more)
        expandButton.contentDescription = getString(R.string.expand_tool)
        
        toolName.text = tool.definition.name
        toolDescription.text = tool.definition.description
        toolFullDescription.text = tool.definition.description
        
        // Set icon based on category
        val iconRes = when (tool.definition.category) {
            ToolCategory.FILESYSTEM -> R.drawable.ic_file
            ToolCategory.DATABASE -> R.drawable.ic_file
            ToolCategory.PROCESS -> R.drawable.ic_terminal
            ToolCategory.SYSTEM -> R.drawable.ic_system
            ToolCategory.REVERSE_ENGINEERING -> R.drawable.ic_debug
            ToolCategory.SECURITY -> R.drawable.ic_accessibility
            ToolCategory.MEMORY -> R.drawable.ic_memory
            ToolCategory.RUNTIME -> R.drawable.ic_runtime
            ToolCategory.GIT -> R.drawable.ic_git
            ToolCategory.BUILD -> R.drawable.ic_build
            ToolCategory.ROOT -> R.drawable.ic_root
            ToolCategory.WEB -> R.drawable.ic_web
            ToolCategory.NETWORK -> R.drawable.ic_network
            ToolCategory.TRAFFIC_CAPTURE -> R.drawable.ic_network
            ToolCategory.MEMORY_MANIPULATION -> R.drawable.ic_debug
            ToolCategory.SKILLS -> R.drawable.ic_skills
        }
        toolIcon.setImageResource(iconRes)
        toolIcon.visibility = View.VISIBLE
        
        // Add permission chips
        tool.definition.permissions.forEach { perm ->
            val chip = Chip(this@ToolsActivity).apply {
                text = perm.name.replace('_', ' ')
                isClickable = false
                setChipBackgroundColorResource(getChipColor(perm))
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { margins -> (margins as ViewGroup.MarginLayoutParams).setMargins(4, 4, 4, 4) }
            }
            permissionChipsContainer.addView(chip)
        }
        
        // Add parameters if any
        if (tool.definition.parameters.isNotEmpty()) {
            toolParametersLabel.visibility = View.VISIBLE
            toolParametersContainer.visibility = View.VISIBLE
            tool.definition.parameters.forEach { (key, value) ->
                val paramView = TextView(this@ToolsActivity).apply {
                    text = getString(R.string.tool_param_line, key, value.toString())
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).also { margins -> (margins as ViewGroup.MarginLayoutParams).bottomMargin = 4 }
                }
                toolParametersContainer.addView(paramView)
            }
        }
        
        policyButton.tag = tool.definition.name
        policyButton.visibility = if (tool.definition.permissions.isEmpty()) View.GONE else View.VISIBLE
        policyButton.text = approvalPolicyLabel(ToolApprovalPolicy.DEFAULT)
        lifecycleScope.launch {
            val loadedPolicy = withContext(Dispatchers.IO) {
                policyManager.getToolPolicy(tool.definition.name)
            }
            if (policyButton.tag == tool.definition.name) {
                currentPolicy = loadedPolicy
                policyButton.text = approvalPolicyLabel(loadedPolicy)
            }
        }
        policyButton.setOnClickListener {
            val policies = listOf(
                ToolApprovalPolicy.DEFAULT,
                ToolApprovalPolicy.ASK,
                ToolApprovalPolicy.ALLOW,
                ToolApprovalPolicy.DENY,
            )
            val labels = arrayOf("Use global default", "Ask every time", "Always allow", "Always deny")
            AlertDialog.Builder(this@ToolsActivity)
                .setTitle("Approval policy · ${tool.definition.name}")
                .setSingleChoiceItems(labels, policies.indexOf(currentPolicy)) { dialog, which ->
                    val selected = policies[which]
                    currentPolicy = selected
                    policyManager.setToolPolicy(tool.definition.name, selected)
                    policyButton.text = approvalPolicyLabel(selected)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Add permissions detail
        tool.definition.permissions.forEach { perm ->
            val permChip = Chip(this@ToolsActivity).apply {
                text = perm.name.replace('_', ' ')
                isClickable = false
                setChipBackgroundColorResource(getChipColor(perm))
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { (it as ViewGroup.MarginLayoutParams).setMargins(4, 4, 4, 4) }
            }
            toolPermissionsContainer.addView(permChip)
        }
        
        // Expand/collapse logic (explicit visibility compare kept; the KTX
        // isVisible hint restructures the read and is not needed here).
        expandButton.setOnClickListener {
            val wasVisible = toolDetails.isVisible
            toolDetails.visibility = if (wasVisible) View.GONE else View.VISIBLE
            expandButton.setImageResource(if (wasVisible) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
            expandButton.contentDescription = if (wasVisible) {
                getString(R.string.expand_tool)
            } else {
                getString(R.string.collapse_tool)
            }
        }
        
    }
    
    private fun approvalPolicyLabel(policy: ToolApprovalPolicy): String = when (policy) {
        ToolApprovalPolicy.DEFAULT -> "Approval policy: Default"
        ToolApprovalPolicy.ASK -> "Approval policy: Ask every time"
        ToolApprovalPolicy.ALLOW -> "Approval policy: Always allow"
        ToolApprovalPolicy.DENY -> "Approval policy: Always deny"
    }

    private fun getChipColor(perm: ToolPermission): Int {
        return when (perm) {
            ToolPermission.ROOT -> R.color.chip_error
            ToolPermission.ACCESSIBILITY -> R.color.chip_warning
            ToolPermission.MEMORY_WRITE, ToolPermission.MEMORY_SCAN, ToolPermission.PROCESS_MEMORY -> R.color.chip_error
            ToolPermission.TRAFFIC_CAPTURE, ToolPermission.TRAFFIC_MODIFY -> R.color.chip_warning
            ToolPermission.NETWORK -> R.color.chip_warning
            ToolPermission.EXECUTE_PROCESS, ToolPermission.EXECUTE_PYTHON -> R.color.chip_warning
            ToolPermission.WRITE_WORKSPACE, ToolPermission.DELETE -> R.color.chip_warning
            else -> R.color.chip_neutral
        }
    }
    
    private sealed interface ToolListRow {
        data class Category(val info: CategoryDisplayInfo) : ToolListRow
        data class Tool(val tool: com.example.oreshik.agent.core.AgentTool) : ToolListRow
    }

    private data class CategoryDisplayInfo(
        val title: String,
        val subtitle: String,
        val iconRes: Int
    )
}

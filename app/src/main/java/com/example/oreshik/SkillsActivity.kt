package com.example.oreshik

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.oreshik.agent.skills.SkillCatalogSeeder
import com.example.oreshik.agent.skills.SkillRepository
import com.example.oreshik.agent.skills.SkillMarketplaceRepository
import com.example.oreshik.data.AgentDatabase
import com.example.oreshik.data.SkillEntity
import com.example.oreshik.logging.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.io.File

class SkillsActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: LinearLayout
    private val db by lazy { AgentDatabase.get(this) }
    private val gson = Gson()
    private val marketplace by lazy { SkillMarketplaceRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_list_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).apply { title="Skills Hub"; setNavigationOnClickListener { finish() } }
        findViewById<android.widget.TextView>(R.id.tvSubtitle).apply { text=getString(R.string.skills_subtitle); visibility=android.view.View.VISIBLE }
        container=findViewById(R.id.sectionContainer)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRuntimeManager).apply { text=getString(R.string.add_skill); setOnClickListener { showEditor(null) } }
        // Secondary actions live in a programmatic bar (the shared list layout only ships one
        // button): import merges workspace/skills.json into the library, export writes every
        // skill to the (sandboxed) workspace for backup/sharing.
        addSecondaryBar()
        load()
    }

    private fun load() = scope.launch(Dispatchers.IO) {
        val dao = db.skills()
        // Defaults carry real instruction bodies so new installs get useful, actionable playbooks.
        SkillRepository.ensureDefaults(this@SkillsActivity, defaultSkills())
        // Bundled professional catalog (500+ skills). Idempotent — never touches existing rows.
        runCatching { SkillCatalogSeeder.seedAll(this@SkillsActivity) }
            .onFailure { AppLogger.w(TAG, "Catalog seeding failed: ${it.message}") }
        render(dao.all())
    }

    private suspend fun render(skills: List<SkillEntity>) = withContext(Dispatchers.Main) {
        container.removeAllViews(); val rows=SettingsUi.addSection(LayoutInflater.from(this@SkillsActivity),container,"Skill library","${skills.size} reusable skills")
        // With 1000+ catalog skills, rendering every row at once ANRs the main thread.
        // Render a window of rows; "Show more" appends the next page.
        var shown = 0
        fun appendPage() {
            val end = minOf(skills.size, shown + RENDER_PAGE)
            for (i in shown until end) {
                val skill = skills[i]
                val body = skill.instructions.trim()
                val subtitle = buildString {
                    append(skill.description.trim().replace('\n',' ').take(140))
                    append("  •  ")
                    append(if (body.isEmpty()) "no instructions yet" else "${body.length} chars of instructions")
                }
                val row=SettingsUi.addRow(LayoutInflater.from(this@SkillsActivity),rows,"[${skill.category}] ${skill.name}",subtitle,R.drawable.ic_skills)
                SettingsUi.switchOf(row).apply { isChecked=skill.enabled; setOnCheckedChangeListener { _, checked -> scope.launch(Dispatchers.IO){ db.skills().setEnabled(skill.id,checked) } } }
                SettingsUi.actionOf(row).setOnClickListener { showSkillMenu(skill) }
            }
            shown = end
            if (shown < skills.size) {
                val moreRow = SettingsUi.addRow(LayoutInflater.from(this@SkillsActivity),rows,"Show more…","${skills.size - shown} more skills",null)
                moreRow.isClickable = true
                moreRow.setOnClickListener { v ->
                    rows.removeView(v)
                    appendPage()
                }
            }
        }
        appendPage()
    }

    /** Enables or disables every skill in the library (bulk library management). */
    private fun setAllEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            val dao = db.skills()
            val all = dao.all()
            for (skill in all) {
                if (skill.enabled != enabled) dao.setEnabled(skill.id, enabled)
            }
            AppLogger.i(TAG, "Bulk ${if (enabled) "enable" else "disable"}: ${all.size} skills")
            render(dao.all())
        }
    }

    /** Adds a horizontal row with Export / Import buttons under the primary button. */
    private fun addSecondaryBar() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }
        val export = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.skill_export)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
            setOnClickListener { exportSkills() }
        }
        val import = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.skill_import)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
            setOnClickListener { importSkills() }
        }
        // Bulk library management: with 500+ catalog skills, users toggle whole groups.
        val allOn = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.skills_all_on)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
            setOnClickListener { setAllEnabled(true) }
        }
        val allOff = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.skills_all_off)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { setAllEnabled(false) }
        }
        row.addView(export); row.addView(import); row.addView(allOn); row.addView(allOff)
        val parent = findViewById<android.view.View>(R.id.btnRuntimeManager).parent as? LinearLayout
        parent?.addView(row, 1)
        val discover = com.google.android.material.button.MaterialButton(this).apply {
            text = "Discover community skills"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 4
                bottomMargin = 12
            }
            setOnClickListener { showMarketplaceSearch() }
        }
        parent?.addView(discover, 2)
    }

    private fun showSkillMenu(skill: SkillEntity) {
        AlertDialog.Builder(this).setTitle(skill.name).setItems(arrayOf("Edit", if (skill.enabled) "Disable" else "Enable", "Delete")) { _, which ->
            when(which) {
                0 -> showEditor(skill)
                1 -> scope.launch(Dispatchers.IO){ db.skills().setEnabled(skill.id,!skill.enabled); load() }
                2 -> confirmDelete(skill)
            }
        }.show()
    }

    private fun confirmDelete(skill: SkillEntity) {
        AlertDialog.Builder(this).setTitle("Delete skill?").setMessage("Remove \"${skill.name}\" from the local skill library?")
            .setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ ->
                AppLogger.i(TAG, "Skill deleted: '${skill.name}'")
                scope.launch(Dispatchers.IO){ db.skills().delete(skill.id); load() }
            }.show()
    }

    private fun showEditor(existing: SkillEntity?) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(48,8,48,0) }
        val name=EditText(this).apply { hint=getString(R.string.skill_hint_name); setSingleLine(); setText(existing?.name.orEmpty()) }
        val desc=EditText(this).apply { hint=getString(R.string.skill_hint_description); setSingleLine(); setText(existing?.description.orEmpty()) }
        val category=EditText(this).apply { hint=getString(R.string.skill_hint_category); setSingleLine(); setText(existing?.category ?: "general") }
        val instructions=EditText(this).apply {
            hint=getString(R.string.skill_hint_instructions); minLines=6
            setText(existing?.instructions.orEmpty()); gravity=android.view.Gravity.TOP
        }
        box.addView(name); box.addView(desc); box.addView(category); box.addView(instructions)
        AlertDialog.Builder(this).setTitle(if(existing==null) "Create skill" else "Edit skill").setView(box)
            .setNegativeButton("Cancel",null).setPositiveButton("Save") { _,_ ->
                val n=name.text.toString().trim(); val d=desc.text.toString().trim()
                val c=category.text.toString().trim().ifBlank { "general" }
                val i=instructions.text.toString().trim()
                if(n.isBlank()||d.isBlank()){ Toast.makeText(this,"Name and description are required",Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val id = existing?.id ?: ("skill_" + System.currentTimeMillis())
                AppLogger.i(TAG, "Skill saved: '$n' (${i.length} chars of instructions)")
                val now=System.currentTimeMillis()
                scope.launch(Dispatchers.IO){
                    db.skills().put(SkillEntity(id,n,d,existing?.enabled ?: true,i,c,existing?.createdAt ?: now,now))
                    load()
                }
            }.show()
    }


    /**
     * Search the live Skills.sh index instead of bundling thousands of Markdown files
     * in the APK. Chosen community skills are imported disabled for review.
     */
    private fun showMarketplaceSearch() {
        val query = EditText(this).apply {
            hint = "Search Android, Laravel, Kotlin, security…"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Community skills marketplace")
            .setMessage("Search the live Skills.sh ecosystem. Skills are downloaded only when selected and added disabled for review.")
            .setView(query)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Search") { _, _ -> searchMarketplace(query.text.toString()) }
            .show()
    }

    private fun searchMarketplace(term: String) = scope.launch {
        val clean = term.trim()
        if (clean.length < 2) {
            Toast.makeText(this@SkillsActivity, "Enter at least two characters", Toast.LENGTH_SHORT).show()
            return@launch
        }
        Toast.makeText(this@SkillsActivity, "Searching community skills…", Toast.LENGTH_SHORT).show()
        marketplace.search(clean).onFailure {
            AppLogger.w(TAG, "Marketplace search failed: \${it.message}")
            Toast.makeText(this@SkillsActivity, "Search failed: \${it.message}", Toast.LENGTH_LONG).show()
        }.onSuccess { found ->
            if (found.isEmpty()) {
                Toast.makeText(this@SkillsActivity, "No matching community skills found", Toast.LENGTH_SHORT).show()
                return@onSuccess
            }
            val labels = found.map { "\${it.name}  •  \${it.source}  •  \${it.installs} installs" }.toTypedArray()
            AlertDialog.Builder(this@SkillsActivity)
                .setTitle("Found \${found.size} skills")
                .setItems(labels) { _, index -> installMarketplaceSkill(found[index]) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun installMarketplaceSkill(remote: SkillMarketplaceRepository.RemoteSkill) = scope.launch {
        Toast.makeText(this@SkillsActivity, "Downloading \${remote.name}…", Toast.LENGTH_SHORT).show()
        marketplace.download(remote).onFailure {
            AppLogger.w(TAG, "Marketplace install failed for \${remote.id}: \${it.message}")
            Toast.makeText(this@SkillsActivity, "Download failed: \${it.message}", Toast.LENGTH_LONG).show()
        }.onSuccess { skill ->
            val preview = skill.instructions.take(1800)
            AlertDialog.Builder(this@SkillsActivity)
                .setTitle("Review: \${skill.name}")
                .setMessage("\${skill.description}\\n\\nSource: \${remote.source}\\n\\n\${preview}\\n\\nThis skill will be added DISABLED. Review it before enabling.")
                .setNegativeButton("Discard", null)
                .setPositiveButton("Install disabled") { _, _ ->
                    scope.launch(Dispatchers.IO) {
                        val existing = db.skills().all().firstOrNull { it.id == skill.id }
                        if (existing != null) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@SkillsActivity, "This skill is already installed", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            db.skills().put(skill)
                            AppLogger.i(TAG, "Installed community skill \${skill.id} from \${remote.source} (disabled)")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@SkillsActivity, "Installed disabled. Review and enable it in the library.", Toast.LENGTH_LONG).show()
                                load()
                            }
                        }
                    }
                }
                .show()
        }
    }

    // ----- Import / export (skills.json in the sandboxed agent workspace) -----

    private fun exportSkills() = scope.launch(Dispatchers.IO) {
        val skills = db.skills().all()
        if (skills.isEmpty()) {
            withContext(Dispatchers.Main) { Toast.makeText(this@SkillsActivity,"No skills to export",Toast.LENGTH_SHORT).show() }
            return@launch
        }
        val payload = JsonObject().apply {
            addProperty("version", 1)
            add("skills", JsonArray().apply { skills.forEach { s ->
                add(JsonObject().apply {
                    addProperty("id", s.id); addProperty("name", s.name)
                    addProperty("description", s.description); addProperty("instructions", s.instructions)
                    addProperty("category", s.category); addProperty("enabled", s.enabled)
                })
            } })
        }
        val dir = com.example.oreshik.agent.core.AgentConfig.workspaceRoot(this@SkillsActivity)
        val out = File(dir, "skills.json")
        runCatching {
            out.parentFile?.mkdirs(); out.writeText(gson.toJson(payload))
            AppLogger.i(TAG, "Exported ${skills.size} skill(s) to ${out.absolutePath}")
            withContext(Dispatchers.Main) { Toast.makeText(this@SkillsActivity,"Exported ${skills.size} skills to workspace/skills.json",Toast.LENGTH_LONG).show() }
        }.onFailure {
            AppLogger.w(TAG, "Skill export failed: ${it.message}")
            withContext(Dispatchers.Main) { Toast.makeText(this@SkillsActivity,"Export failed: ${it.message}",Toast.LENGTH_SHORT).show() }
        }
    }

    private fun importSkills() = scope.launch(Dispatchers.IO) {
        val file = File(com.example.oreshik.agent.core.AgentConfig.workspaceRoot(this@SkillsActivity), "skills.json")
        if (!file.exists()) {
            withContext(Dispatchers.Main) { Toast.makeText(this@SkillsActivity,"No workspace/skills.json found — export first",Toast.LENGTH_LONG).show() }
            return@launch
        }
        runCatching {
            val root = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
            val arr = root.getAsJsonArray("skills") ?: return@runCatching
            var imported = 0
            for (element in arr) {
                val obj = element.asJsonObject
                val id = obj.get("id")?.asString?.trim().orEmpty()
                val name = obj.get("name")?.asString?.trim().orEmpty()
                val description = obj.get("description")?.asString?.trim().orEmpty()
                if (id.isBlank() || name.isBlank() || description.isBlank()) continue
                val instructions = obj.get("instructions")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val category = (obj.get("category")?.takeIf { it.isJsonPrimitive }?.asString ?: "general").trim().ifBlank { "general" }
                val enabled = obj.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
                val now = System.currentTimeMillis()
                val existing = db.skills().all().firstOrNull { it.id == id }
                val skill = SkillEntity(
                    id, name, description, enabled, instructions, category,
                    existing?.createdAt ?: now, now,
                )
                db.skills().put(skill); imported++
            }
            AppLogger.i(TAG, "Imported $imported skill(s) from ${file.absolutePath}")
            withContext(Dispatchers.Main) { Toast.makeText(this@SkillsActivity,"Imported $imported skills",Toast.LENGTH_SHORT).show(); load() }
        }.onFailure {
            AppLogger.w(TAG, "Skill import failed: ${it.message}")
            withContext(Dispatchers.Main) { Toast.makeText(this@SkillsActivity,"Import failed: ${it.message}",Toast.LENGTH_SHORT).show() }
        }
    }

    /** Default skill library seeded on first launch (only when the id does not exist yet). */
    private fun defaultSkills(): List<SkillEntity> = listOf(
        SkillEntity(
            "coding", "General coding",
            "Inspect, edit, test and verify source code.",
            true,
            "Workflow:\n" +
                "1. Locate relevant files with file.search and read them with file.read before editing.\n" +
                "2. Make the smallest correct change with file.patch; keep the surrounding style.\n" +
                "3. Build or run the relevant test target (gradle.exec, python.exec, process.exec) to verify.\n" +
                "4. Re-read the edited region and summarize what changed and why.\n" +
                "Never leave the build red; if a fix cannot be verified, say so explicitly.",
        ),
        SkillEntity(
            "android", "Android development",
            "Gradle, Android resources, Kotlin/Java and APK verification.",
            true,
            "Workflow:\n" +
                "1. Read build.gradle.kts and AndroidManifest.xml before touching code.\n" +
                "2. Prefer Kotlin and existing project conventions; respect min/target SDK.\n" +
                "3. Verify with android.build (or gradle.exec) after every meaningful change.\n" +
                "4. For APK work (decode, patch, sign, install) use the re.apk.* tools in order.\n" +
                "5. Check logcat after runtime changes to confirm no new crashes.",
        ),
        SkillEntity(
            "python", "Python engineering",
            "Use the app-owned Python runtime for scripts and data processing.",
            true,
            "Workflow:\n" +
                "1. Write scripts into the workspace (file.write) and run them with python.run_file.\n" +
                "2. Keep scripts self-contained; print compact, parseable output.\n" +
                "3. Handle errors explicitly and clean up temp files.\n" +
                "4. For data processing, process in chunks and report progress via onProgress.",
        ),
        SkillEntity(
            "debugging", "Debugging",
            "Reproduce failures, inspect logs and verify fixes.",
            true,
            "Workflow:\n" +
                "1. Reproduce first: run the failing command/flow and capture the exact error.\n" +
                "2. Read logs (AppLogger output, logcat) around the failure time.\n" +
                "3. Form one hypothesis at a time; verify each with the smallest possible experiment.\n" +
                "4. After the fix, re-run the original reproduction to prove it is gone.\n" +
                "5. Record durable root-cause facts with memory.save so future sessions benefit.",
        ),
        SkillEntity(
            "git", "Git workflow",
            "Inspect diffs, history and repository state before changes.",
            true,
            "Workflow:\n" +
                "1. Always run git.status and git.diff BEFORE editing any tracked file.\n" +
                "2. Check git.log to understand recent intent and avoid fighting in-flight work.\n" +
                "3. Keep edits focused; never mix unrelated changes in one pass.\n" +
                "4. Report what you changed relative to the diff you saw, not from memory.",
        ),
        SkillEntity(
            "device", "Device automation",
            "Use accessibility tools to inspect and navigate the Android device.",
            true,
            "Workflow:\n" +
                "1. Read the screen first with androidreadscreen (or accessibility.screen).\n" +
                "2. Prefer tapping by element (androidclickelement) over raw coordinates.\n" +
                "3. Type with androidtypetext after focusing the right field; verify with a fresh read.\n" +
                "4. Use androidpressback/home to reset navigation state when stuck.\n" +
                "5. Announce each step briefly before acting.",
        ),
    )
    override fun onDestroy(){ scope.cancel(); super.onDestroy() }

    private companion object {
        const val TAG = "Skills"

        /** Rows rendered per page in the library list (keeps 1000+ catalogs ANR-free). */
        const val RENDER_PAGE = 60
    }
}

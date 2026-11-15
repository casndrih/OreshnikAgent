package com.example.oreshik

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.oreshik.agent.runtime.RuntimeManager
import com.example.oreshik.logging.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RuntimeActivity : AppCompatActivity() {
    private lateinit var runtime: RuntimeManager
    private lateinit var runtimeList: RecyclerView
    private lateinit var runtimeAdapter: RuntimeAdapter
    private var renderJob: Job? = null
    private val provisionListener: (String, Int) -> Unit = { step, percent ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                findViewById<TextView>(R.id.tvProvisionStatus).text =
                    if (percent >= 0) "$step ($percent%)" else step
                findViewById<MaterialButton>(R.id.btnProvisionPacks).isEnabled = !runtime.provisioning
                val setupFinished = step == "Bundled APK toolpacks are ready" ||
                    step.startsWith("Bundled toolpack setup finished with issues:") ||
                    step == "Bundled toolpack setup stopped"
                if (!runtime.provisioning && (setupFinished || percent == 100)) render()
            }
        }
    }

    // Modern Activity Result API replacement for the deprecated
    // startActivityForResult/onActivityResult pair.
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri: Uri = result.data?.data ?: return@registerForActivityResult
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { runtime.installPack(it) }
                            ?: error("Unable to open archive")
                    }
                    Toast.makeText(this@RuntimeActivity, "Runtime installed", Toast.LENGTH_SHORT).show()
                    render()
                } catch (e: Exception) {
                    Toast.makeText(this@RuntimeActivity, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_runtime)
        AppLogger.i(TAG, "Runtime manager opened")
        runtime = RuntimeManager(this)
        findViewById<MaterialToolbar>(R.id.toolbar).apply { title = "Runtime Manager"; setNavigationOnClickListener { finish() } }
        runtimeList = findViewById(R.id.runtimeList)
        runtimeAdapter = RuntimeAdapter { info ->
            lifecycleScope.launch {
                runtime.removePack(info.id)
                render()
            }
        }
        runtimeList.layoutManager = LinearLayoutManager(this)
        runtimeList.adapter = runtimeAdapter
        findViewById<MaterialButton>(R.id.btnInstallRuntime).setOnClickListener {
            filePicker.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "application/zip"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
            )
        }
        findViewById<MaterialButton>(R.id.btnDownloadRuntime).setOnClickListener {
            downloadAvailableRuntime()
        }
        findViewById<MaterialButton>(R.id.btnProvisionPacks).setOnClickListener {
            provisionPacks()
        }
        runtime.addProvisionListener(provisionListener)
        findViewById<MaterialButton>(R.id.btnProvisionPacks).isEnabled = !runtime.provisioning
        render()
    }

    override fun onDestroy() {
        if (::runtime.isInitialized) runtime.removeProvisionListener(provisionListener)
        renderJob?.cancel()
        super.onDestroy()
    }

    private fun provisionPacks() {
        if (runtime.provisioning) {
            findViewById<MaterialButton>(R.id.btnProvisionPacks).isEnabled = false
            return
        }
        val status = findViewById<TextView>(R.id.tvProvisionStatus)
        status.text = getString(R.string.provisioning_packs)
        findViewById<MaterialButton>(R.id.btnProvisionPacks).isEnabled = false
        lifecycleScope.launch {
            val ok = runtime.ensureProvisioned()
            status.text = if (ok) "All bundled packs installed ✓"
            else "Provisioning issue: ${runtime.lastProvisionError ?: "unknown"}"
            findViewById<MaterialButton>(R.id.btnProvisionPacks).isEnabled = !runtime.provisioning
            render()
            Toast.makeText(
                this@RuntimeActivity,
                if (ok) "Bundled packs installed" else "Provisioning failed — see status",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun downloadAvailableRuntime() {
        val status = findViewById<TextView>(R.id.tvProvisionStatus)
        status.text = "Loading available runtime packs…"
        lifecycleScope.launch {
            try {
                val packs = withContext(Dispatchers.IO) { runtime.fetchRemotePacks() }
                if (packs.isEmpty()) {
                    status.text = "No optional runtime packs are published yet. Add ZIP assets and SHA-256 entries to runtime-catalog.json."
                    return@launch
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@RuntimeActivity)
                    .setTitle("Available runtime packs")
                    .setItems(packs.map { "${it.name} · ${it.version}" }.toTypedArray()) { _, index ->
                        val selected = packs[index]
                        lifecycleScope.launch {
                            try {
                                val installed = runtime.downloadRuntimePack(selected) { step, percent ->
                                    runOnUiThread { status.text = if (percent >= 0) "$step ($percent%)" else step }
                                }
                                status.text = "${installed.id} installed successfully"
                                render()
                            } catch (failure: Exception) {
                                status.text = "Download failed: ${failure.message}"
                            }
                        }
                    }.setNegativeButton("Cancel", null).show()
            } catch (failure: Exception) {
                status.text = "Could not load runtime catalogue: ${failure.message}"
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun render() {
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            // Inventory checks manifests and pack markers in app storage. Keep that
            // filesystem work off the UI thread, especially on first screen entry.
            val rows = withContext(Dispatchers.IO) {
                runtime.inventory().map { info -> RuntimeRow(info, info.root.exists()) }
            }
            if (isFinishing || isDestroyed) return@launch
            runtimeAdapter.submitRows(rows)
        }
    }

    private companion object {
        const val TAG = "Runtime"
    }
}

private data class RuntimeRow(val info: RuntimeManager.RuntimeInfo, val rootExists: Boolean)

private class RuntimeAdapter(
    private val onRemove: (RuntimeManager.RuntimeInfo) -> Unit,
) : RecyclerView.Adapter<RuntimeAdapter.RuntimeViewHolder>() {
    private var rows: List<RuntimeRow> = emptyList()

    fun submitRows(items: List<RuntimeRow>) {
        rows = items
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuntimeViewHolder {
        val row = LayoutInflater.from(parent.context).inflate(R.layout.item_runtime, parent, false)
        return RuntimeViewHolder(row)
    }

    override fun onBindViewHolder(holder: RuntimeViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    inner class RuntimeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.tvRuntimeName)
        private val version = view.findViewById<TextView>(R.id.tvRuntimeVersion)
        private val status = view.findViewById<TextView>(R.id.tvRuntimeStatus)
        private val description = view.findViewById<TextView>(R.id.tvRuntimeDescription)
        private val remove = view.findViewById<MaterialButton>(R.id.btnRemoveRuntime)

        fun bind(row: RuntimeRow) {
            val info = row.info
            name.text = info.id.replace('-', ' ').uppercase()
            version.text = if (info.version == "—") "Not installed" else "Version ${info.version}"
            status.text = if (info.ready) "READY" else "NOT READY"
            description.text = info.description
            remove.visibility = if (row.rootExists) View.VISIBLE else View.GONE
            remove.setOnClickListener { onRemove(info) }
        }
    }
}

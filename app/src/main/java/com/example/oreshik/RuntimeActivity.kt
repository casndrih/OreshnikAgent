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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
        findViewById<TextView>(R.id.tvProvisionStatus).text =
            "Runtime inventory loaded. Download optional packs or repair bundled APK toolpacks."
        render()
    }

    override fun onDestroy() {
        if (::runtime.isInitialized) runtime.removeProvisionListener(provisionListener)
        renderJob?.cancel()
        super.onDestroy()
    }

    private fun provisionPacks() {
        if (runtime.provisioning) return
        val status = findViewById<TextView>(R.id.tvProvisionStatus)
        val button = findViewById<MaterialButton>(R.id.btnProvisionPacks)
        val progress = findViewById<android.widget.ProgressBar>(R.id.runtimeProgress)
        status.text = "Checking bundled APK toolpacks…"
        button.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        lifecycleScope.launch {
            try {
                val ok = runtime.ensureProvisioned()
                status.text = if (ok) "All bundled APK toolpacks are installed and verified ✓"
                else "Bundled setup incomplete: ${runtime.lastProvisionError ?: "see diagnostic log"}"
                Toast.makeText(
                    this@RuntimeActivity,
                    if (ok) "Bundled toolpacks are ready" else "Some toolpacks could not be installed",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLogger.e(TAG, "Bundled runtime repair failed", failure)
                status.text = "Repair failed: ${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                if (!isFinishing && !isDestroyed) {
                    button.isEnabled = !runtime.provisioning
                    progress.visibility = View.GONE
                    render()
                }
            }
        }
    }

    private fun downloadAvailableRuntime() {
        val status = findViewById<TextView>(R.id.tvProvisionStatus)
        val button = findViewById<MaterialButton>(R.id.btnDownloadRuntime)
        val progress = findViewById<android.widget.ProgressBar>(R.id.runtimeProgress)
        if (!button.isEnabled) return
        button.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.text = "Connecting to the Oreshnik runtime catalogue…"
        lifecycleScope.launch {
            try {
                val packs = withTimeout(25_000L) {
                    withContext(Dispatchers.IO) { runtime.fetchRemotePacks() }
                }
                if (packs.isEmpty()) {
                    status.text = "The runtime catalogue is reachable, but it contains no downloadable packs."
                    return@launch
                }
                val installedIds = withContext(Dispatchers.IO) {
                    runtime.inventory().filter { it.ready }.map { it.id }.toSet()
                }
                status.text = "Loaded ${packs.size} optional runtime pack(s). Select a pack to install."
                progress.visibility = View.GONE
                val labels = packs.map { pack ->
                    "${pack.name} · ${pack.version}" +
                        if (pack.id in installedIds) "  ✓ INSTALLED (tap to reinstall)" else "  · NOT INSTALLED"
                }.toTypedArray()
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@RuntimeActivity)
                    .setTitle("Available runtime packs")
                    .setItems(labels) { _, index -> installOptionalRuntimePack(packs[index]) }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLogger.e(TAG, "Runtime catalogue load failed", failure)
                status.text = "Catalogue unavailable: ${failure.message ?: failure.javaClass.simpleName}"
                Toast.makeText(
                    this@RuntimeActivity,
                    "Could not load runtime packs. Check connection and retry.",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                if (!isFinishing && !isDestroyed) {
                    button.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }
    }

    private fun installOptionalRuntimePack(pack: RuntimeManager.RemoteRuntimePack) {
        val status = findViewById<TextView>(R.id.tvProvisionStatus)
        val progress = findViewById<android.widget.ProgressBar>(R.id.runtimeProgress)
        val downloadButton = findViewById<MaterialButton>(R.id.btnDownloadRuntime)
        downloadButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.text = "Preparing ${pack.name}…"
        lifecycleScope.launch {
            try {
                val installed = runtime.downloadRuntimePack(pack) { step, percent ->
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            status.text = if (percent >= 0) "$step ($percent%)" else step
                            progress.isIndeterminate = percent < 0
                            if (percent >= 0) progress.progress = percent
                        }
                    }
                }
                status.text = "${pack.name} installed and verified ✓"
                Toast.makeText(this@RuntimeActivity, "${pack.name} installed", Toast.LENGTH_LONG).show()
                render()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLogger.e(TAG, "Optional runtime install failed: ${pack.id}", failure)
                status.text = "Install failed (${pack.id}): ${failure.message ?: failure.javaClass.simpleName}"
                Toast.makeText(this@RuntimeActivity, "Runtime install failed", Toast.LENGTH_LONG).show()
            } finally {
                if (!isFinishing && !isDestroyed) {
                    downloadButton.isEnabled = true
                    progress.visibility = View.GONE
                }
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
            status.text = when {
                info.ready -> "READY ✓"
                row.rootExists -> "INCOMPLETE — repair required"
                else -> "NOT INSTALLED"
            }
            description.text = info.description
            remove.visibility = if (row.rootExists && info.id != "sqlmap") View.VISIBLE else View.GONE
            remove.setOnClickListener { onRemove(info) }
        }
    }
}

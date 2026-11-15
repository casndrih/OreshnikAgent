package com.example.oreshik

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import com.example.oreshik.ai.AiProvider
import com.example.oreshik.ai.ChatGptPlanAuth
import com.example.oreshik.ai.ChatGptPlanProvider
import com.example.oreshik.ai.KeyValidationResult
import com.example.oreshik.ai.ModelTester
import com.example.oreshik.ai.OpenAiCompatibleProvider
import com.example.oreshik.ai.ProviderManager
import com.example.oreshik.ai.ProviderEndpointPolicy
import com.example.oreshik.ai.ProviderRegistry
import com.example.oreshik.ai.ValidationResult
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class ProvidersActivity : AppCompatActivity() {

    private lateinit var providerContainer: LinearLayout
    private lateinit var activeProviderBadge: Chip
    private lateinit var validationProgress: CircularProgressIndicator
    private lateinit var validateAllBtn: MaterialButton
    private lateinit var refreshModelsBtn: MaterialButton
    private val modelCache = ConcurrentHashMap<String, List<Model>>()

    /** Providers where the user enabled the "only tested-working models" filter. */
    private val workingOnly = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentConfig.init(this)
        setContentView(R.layout.activity_providers)

        ProviderManager.initialize(this)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        providerContainer = findViewById(R.id.providerContainer)
        activeProviderBadge = findViewById(R.id.activeProviderBadge)
        validationProgress = findViewById(R.id.validationProgress)
        validateAllBtn = findViewById(R.id.btnValidateAll)
        refreshModelsBtn = findViewById(R.id.btnRefreshModels)

        validateAllBtn.setOnClickListener { validateAllKeys() }
        refreshModelsBtn.setOnClickListener { refreshActiveProviderModels() }
        val addCompatibleProviderButton = findViewById<MaterialButton>(R.id.btnAddOpenAiCompatible)
        addCompatibleProviderButton.isEnabled = AgentConfig.isReady
        addCompatibleProviderButton.setOnClickListener { showAddOpenAiCompatibleDialog() }

        lifecycleScope.launch {
            AgentConfig.awaitReady()
            withContext(Dispatchers.IO) { ChatGptPlanAuth.warmUp() }
            ProviderManager.refreshOpenAiCompatibleProviders()
            sanitizeLegacyValidationSnapshots()
            renderProviders()
            updateActiveProviderBadge()
            addCompatibleProviderButton.isEnabled = true
        }
    }

    private fun renderProviders() {
        providerContainer.removeAllViews()
        val providers = ProviderRegistry.getAllProviders()
        val inflater = layoutInflater

        for (provider in providers) {
            val section = inflater.inflate(R.layout.provider_section, providerContainer, false)
            val header = section.findViewById<TextView>(R.id.providerHeader)
            val keyContainer = section.findViewById<LinearLayout>(R.id.keyContainer)
            val keyInput = section.findViewById<EditText>(R.id.etNewKey)
            val addKeyBtn = section.findViewById<MaterialButton>(R.id.btnAddKey)
            val baseUrlInput = section.findViewById<EditText>(R.id.etBaseUrl)
            val keysStatus = section.findViewById<TextView>(R.id.keysStatus)
            val chatGptAuthPanel = section.findViewById<LinearLayout>(R.id.chatGptAuthPanel)
            val chatGptAccountStatus = section.findViewById<TextView>(R.id.tvChatGptAccountStatus)
            val chatGptConnectBtn = section.findViewById<MaterialButton>(R.id.btnChatGptConnect)
            val chatGptAddAccountBtn = section.findViewById<MaterialButton>(R.id.btnChatGptAddAccount)
            val chatGptAccountsBtn = section.findViewById<MaterialButton>(R.id.btnChatGptAccounts)
            val chatGptSignOutBtn = section.findViewById<MaterialButton>(R.id.btnChatGptSignOut)
            val modelsStatus = section.findViewById<TextView>(R.id.modelsStatus)
            val modelCount = section.findViewById<TextView>(R.id.modelCount)
            val setActiveBtn = section.findViewById<MaterialButton>(R.id.btnSetActive)
            val validateBtn = section.findViewById<MaterialButton>(R.id.btnValidateProvider)
            val modelSelector = section.findViewById<MaterialButton>(R.id.btnSelectModel)
            val testModelsBtn = section.findViewById<MaterialButton>(R.id.btnTestModels)
            val workingOnlyBtn = section.findViewById<MaterialButton>(R.id.btnShowWorkingOnly)
            val removeCompatibleBtn = section.findViewById<MaterialButton>(R.id.btnRemoveCompatibleProvider)
            val endpointLabel = section.findViewById<TextView>(R.id.tvProviderEndpointLabel)
            val providerActionsRow = section.findViewById<LinearLayout>(R.id.providerActionsRow)
            val providerModelTestRow = section.findViewById<LinearLayout>(R.id.providerModelTestRow)

            header.text = getString(R.string.provider_header_fmt, provider.displayName, provider.id)
            removeCompatibleBtn.isVisible = provider is OpenAiCompatibleProvider
            val isChatGptPlanProvider = provider is ChatGptPlanProvider
            chatGptAuthPanel.isVisible = isChatGptPlanProvider
            if (isChatGptPlanProvider) {
                keyInput.isVisible = false
                addKeyBtn.isVisible = false
                keysStatus.isVisible = false
                keyContainer.isVisible = false
                baseUrlInput.isVisible = false
                endpointLabel.isVisible = false
                providerModelTestRow.isVisible = false
                providerActionsRow.findViewById<View>(R.id.btnValidateProvider).isVisible = false
                val account = ChatGptPlanAuth.activeStatus()
                val connected = account?.connected == true
                val email = account?.email?.takeIf { it.isNotBlank() } ?: "ChatGPT account"
                chatGptAccountStatus.text = if (connected) {
                    getString(R.string.chatgpt_connected_fmt, email)
                } else {
                    getString(R.string.chatgpt_not_connected)
                }
                chatGptConnectBtn.text = if (account == null) getString(R.string.chatgpt_continue) else "Reconnect with ChatGPT"
                chatGptConnectBtn.setOnClickListener { startChatGptSignIn(addAnotherAccount = false) }
                chatGptAddAccountBtn.setOnClickListener { startChatGptSignIn(addAnotherAccount = true) }
                val accounts = ChatGptPlanAuth.accountStatuses()
                chatGptAccountsBtn.isVisible = accounts.size > 1
                chatGptAccountsBtn.setOnClickListener { showChatGptAccountPicker() }
                chatGptSignOutBtn.isVisible = connected
                chatGptSignOutBtn.setOnClickListener { signOutChatGptAccount(chatGptSignOutBtn) }
                setActiveBtn.isEnabled = !isChatGptPlanProvider || connected
            } else {
                chatGptAuthPanel.isVisible = false
                setActiveBtn.isEnabled = true
            }
            if (provider is OpenAiCompatibleProvider) {
                baseUrlInput.hint = "https://api.openai.com/v1 or http://localhost:1234/v1"
                keyInput.hint = "API key (optional for local servers)"
            }
            removeCompatibleBtn.setOnClickListener {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Remove ${provider.displayName}?")
                    .setMessage("This removes the provider profile and its saved API keys from this app.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        if (ProviderManager.removeOpenAiCompatibleProvider(provider.id)) {
                            modelCache.remove(provider.id)
                            renderProviders()
                            updateActiveProviderBadge()
                            Toast.makeText(this, "Provider removed", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            }
            val keys = provider.getApiKeys()
            keysStatus.text = when {
                keys.isEmpty() -> getString(R.string.no_api_keys_configured)
                keys.size == 1 -> getString(R.string.single_api_key_configured)
                else -> getString(R.string.api_keys_configured_with_rotation, keys.size)
            }

            // Editable base URL (API endpoint)
            baseUrlInput.setText(provider.getEffectiveBaseUrl())
            baseUrlInput.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val newUrl = baseUrlInput.text.toString().trim()
                    val effective = provider.getEffectiveBaseUrl()
                    if (newUrl != effective) {
                        val endpointError = if (newUrl.isBlank()) null else ProviderEndpointPolicy.error(newUrl)
                        if (endpointError != null) {
                            baseUrlInput.error = endpointError
                            baseUrlInput.setText(effective)
                            Toast.makeText(this, endpointError, Toast.LENGTH_LONG).show()
                        } else {
                            baseUrlInput.error = null
                            provider.setBaseUrl(newUrl)
                            modelCache.remove(provider.id)
                            Toast.makeText(this, getString(R.string.provider_endpoint_updated_fmt, provider.displayName), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // Add key
            addKeyBtn.setOnClickListener {
                val key = keyInput.text.toString().trim()
                if (key.isBlank()) {
                    Toast.makeText(this, R.string.enter_api_key, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                provider.addApiKey(key)
                keyInput.text?.clear()
                Toast.makeText(this, R.string.api_key_added, Toast.LENGTH_SHORT).show()
                renderProviders()
            }

            // Render existing keys with validation chips
            renderKeys(keyContainer, provider, keys)

            // Set as active provider
            val isActive = ProviderManager.getActiveProviderId() == provider.id
            setActiveBtn.text = if (isActive) getString(R.string.active) else getString(R.string.set_as_active)
            setActiveBtn.isEnabled = !isActive && (!isChatGptPlanProvider || ChatGptPlanAuth.activeStatus()?.connected == true)
            setActiveBtn.setOnClickListener {
                ProviderManager.setActiveProvider(provider.id)
                Toast.makeText(this, getString(R.string.provider_set_as_active_fmt, provider.displayName), Toast.LENGTH_SHORT).show()
                renderProviders()
                updateActiveProviderBadge()
            }

            // Validate this provider's keys
            validateBtn.setOnClickListener {
                validateProviderKeys(provider, keysStatus)
            }

            // Select model from this provider
            modelSelector.setOnClickListener {
                showModelPicker(provider)
            }

            // Live model testing: probe every model with a tiny real request.
            testModelsBtn.setOnClickListener {
                testProviderModels(provider, modelsStatus, modelCount, testModelsBtn)
            }

            // Toggle "show only models that actually responded"
            workingOnlyBtn.text = if (workingOnly.contains(provider.id)) getString(R.string.working_only_on) else getString(R.string.working_only_off)
            workingOnlyBtn.setOnClickListener {
                if (workingOnly.contains(provider.id)) workingOnly.remove(provider.id) else workingOnly.add(provider.id)
                renderProviders()
            }

            // Load and show model count
            loadModelCount(provider, modelCount, modelsStatus)

            providerContainer.addView(section)
        }
    }

    private fun showAddOpenAiCompatibleDialog() {
        val density = resources.displayMetrics.density
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * density).toInt()
            setPadding(padding, padding, padding, 0)
        }
        fun addField(hintText: String, initial: String = "", inputType: Int = android.text.InputType.TYPE_CLASS_TEXT): EditText {
            val field = EditText(this).apply {
                hint = hintText
                setText(initial)
                this.inputType = inputType
                isSingleLine = true
            }
            form.addView(field, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (8 * density).toInt() })
            return field
        }
        val nameInput = addField("Provider name", "My compatible provider")
        val urlInput = addField(
            "API base URL",
            OpenAiCompatibleProvider.DEFAULT_BASE_URL,
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI,
        )
        val keyInput = addField(
            "API key (optional for local servers)",
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        form.addView(TextView(this).apply {
            text = "Use an OpenAI Chat Completions compatible endpoint. Model lists load from /models and chats use /chat/completions. HTTPS is required except for localhost."
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
        })

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Add compatible provider")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add provider", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val endpoint = urlInput.text.toString().trim().trimEnd('/')
                val endpointError = ProviderEndpointPolicy.error(endpoint)
                when {
                    name.isBlank() -> nameInput.error = "Enter a provider name."
                    endpointError != null -> {
                        urlInput.error = endpointError
                        Toast.makeText(this, endpointError, Toast.LENGTH_LONG).show()
                    }
                    else -> runCatching {
                        ProviderManager.addOpenAiCompatibleProvider(name, endpoint, keyInput.text.toString())
                    }.onSuccess {
                        renderProviders()
                        updateActiveProviderBadge()
                        Toast.makeText(this, "Provider added. Load its models, then select one to use it.", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    }.onFailure { failure ->
                        Toast.makeText(this, failure.message ?: "Could not add provider.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun startChatGptSignIn(addAnotherAccount: Boolean) {
        val panel = providerContainer.findViewById<LinearLayout>(R.id.chatGptAuthPanel) ?: return
        val connectButton = panel.findViewById<MaterialButton>(R.id.btnChatGptConnect)
        val addButton = panel.findViewById<MaterialButton>(R.id.btnChatGptAddAccount)
        connectButton.isEnabled = false
        addButton.isEnabled = false
        val originalConnectText = if (ChatGptPlanAuth.activeStatus() == null) getString(R.string.chatgpt_continue) else "Reconnect with ChatGPT"
        connectButton.text = getString(R.string.chatgpt_connecting)
        lifecycleScope.launch {
            try {
                val account = ChatGptPlanAuth.signIn(this@ProvidersActivity, addAnotherAccount)
                modelCache.remove(ChatGptPlanProvider.ID)
                Toast.makeText(
                    this@ProvidersActivity,
                    "Connected to ChatGPT${account.email.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty()}",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (failure: Exception) {
                if (failure !is kotlinx.coroutines.CancellationException) {
                    Toast.makeText(this@ProvidersActivity, failure.message ?: "ChatGPT sign-in failed.", Toast.LENGTH_LONG).show()
                }
            } finally {
                if (!isFinishing && !isDestroyed) {
                    connectButton.isEnabled = true
                    addButton.isEnabled = true
                    connectButton.text = originalConnectText
                    renderProviders()
                    updateActiveProviderBadge()
                }
            }
        }
    }

    private fun showChatGptAccountPicker() {
        val accounts = ChatGptPlanAuth.accountStatuses()
        if (accounts.isEmpty()) return
        val labels = accounts.map { account ->
            val email = account.email.ifBlank { "ChatGPT account" }
            when {
                account.isActive && account.connected -> "$email • active"
                account.connected -> email
                else -> getString(R.string.chatgpt_signed_out_fmt, email)
            }
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.chatgpt_account_picker_title)
            .setItems(labels) { _, which ->
                val selected = accounts[which]
                ChatGptPlanAuth.selectAccount(selected.clientId)
                if (selected.connected) {
                    modelCache.remove(ChatGptPlanProvider.ID)
                    renderProviders()
                    updateActiveProviderBadge()
                } else {
                    startChatGptSignIn(addAnotherAccount = false)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun signOutChatGptAccount(button: MaterialButton) {
        button.isEnabled = false
        button.text = getString(R.string.chatgpt_signing_out)
        lifecycleScope.launch(Dispatchers.IO) {
            val remoteRevoked = ChatGptPlanAuth.signOut()
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    modelCache.remove(ChatGptPlanProvider.ID)
                    Toast.makeText(
                        this@ProvidersActivity,
                        if (remoteRevoked) "Signed out of ChatGPT." else "Signed out locally. OpenAI could not confirm remote revocation; you can disconnect Oreshik in ChatGPT Settings.",
                        Toast.LENGTH_LONG,
                    ).show()
                    renderProviders()
                    updateActiveProviderBadge()
                }
            }
        }
    }

    private fun renderKeys(container: LinearLayout, provider: AiProvider, keys: List<String>) {
        container.removeAllViews()
        val inflater = layoutInflater

        for (key in keys) {
            val row = inflater.inflate(R.layout.item_provider_key, container, false)
            val maskedText = row.findViewById<TextView>(R.id.tvKeyMasked)
            val validationChip = row.findViewById<Chip>(R.id.chipValidationStatus)
            val removeBtn = row.findViewById<View>(R.id.btnRemoveKey)

            maskedText.text = maskKey(key)
            validationChip.text = getString(R.string.not_validated)
            validationChip.setChipBackgroundColorResource(R.color.chip_neutral)

            // Load cached validation result if available
            loadCachedValidation(provider.id, key, validationChip)

            removeBtn.setOnClickListener {
                provider.removeApiKey(key)
                renderProviders()
            }

            container.addView(row)
        }
    }

    private fun maskKey(key: String): String {
        if (key.length <= 12) return "••••"
        return key.take(9) + "••••" + key.takeLast(4)
    }

    private fun validateProviderKeys(provider: AiProvider, statusView: TextView) {
        val keys = provider.getApiKeys()
        if (keys.isEmpty()) {
            Toast.makeText(this, R.string.no_keys_to_validate, Toast.LENGTH_SHORT).show()
            return
        }

        statusView.text = resources.getQuantityString(R.plurals.validating_keys_fmt, keys.size, keys.size)
        validationProgress.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val results = provider.validateAllKeys()

            withContext(Dispatchers.Main) {
                validationProgress.visibility = View.GONE

                val working = results.count { entry -> entry.result.isValid }
                val failed = results.size - working

                val workingText = resources.getQuantityString(R.plurals.working_count_fmt, working, working)
                val failedText = resources.getQuantityString(R.plurals.failed_count_fmt, failed, failed)
                statusView.text = getString(R.string.validated_keys_fmt, workingText, failedText)

                // Save validation results
                saveValidationResults(provider.id, results)

                // Re-render only this provider's key rows so the chips update.
                val section = statusView.parent as? ViewGroup
                val keyContainer = section?.findViewById<LinearLayout>(R.id.keyContainer)
                if (keyContainer != null) {
                    renderKeys(keyContainer, provider, provider.getApiKeys())
                } else {
                    renderProviders()
                }
            }
        }
    }

    private fun validateAllKeys() {
        validationProgress.visibility = View.VISIBLE
        validateAllBtn.isEnabled = false
        validateAllBtn.text = getString(R.string.validating_ellipsis)

        ProviderManager.validateAllProviders(lifecycleScope) { allResults ->
            validationProgress.visibility = View.GONE
            validateAllBtn.isEnabled = true
            validateAllBtn.text = getString(R.string.validate_all_keys)

            var totalWorking = 0
            var totalFailed = 0
            for ((providerId, results) in allResults) {
                totalWorking += results.count { it.result.isValid }
                totalFailed += results.count { !it.result.isValid }
                saveValidationResults(providerId, results)
            }

            val workingText = resources.getQuantityString(R.plurals.working_count_fmt, totalWorking, totalWorking)
            val failedText = resources.getQuantityString(R.plurals.failed_count_fmt, totalFailed, totalFailed)
            Toast.makeText(this, getString(R.string.all_providers_validation_summary_fmt, workingText, failedText), Toast.LENGTH_LONG).show()
            renderProviders()
        }
    }

    /**
     * Live-tests every catalog model of [provider] with a tiny real request
     * (kind-aware: coding/reasoning/vision/general) and caches which models
     * actually respond.
     */
    private fun testProviderModels(
        provider: AiProvider,
        modelsStatus: TextView,
        modelCount: TextView,
        testBtn: MaterialButton,
    ) {
        val keys = provider.getApiKeys()
        if (keys.isEmpty()) {
            Toast.makeText(this, R.string.add_api_key_first, Toast.LENGTH_SHORT).show()
            return
        }

        testBtn.isEnabled = false
        testBtn.text = getString(R.string.testing_ellipsis)
        modelsStatus.text = getString(R.string.loading_catalog)

        lifecycleScope.launch(Dispatchers.IO) {
            val catalog = modelCache[provider.id] ?: provider.fetchModels().getOrDefault(emptyList())
            if (catalog.isEmpty()) {
                withContext(Dispatchers.Main) {
                    testBtn.isEnabled = true
                    testBtn.text = getString(R.string.test_models)
                    modelsStatus.text = getString(R.string.no_models_to_test)
                }
                return@launch
            }

            modelCache[provider.id] = catalog
            val baseUrl = provider.getEffectiveBaseUrl()
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val tested = ModelTester.probeAll(
                providerId = provider.id,
                models = catalog,
                baseUrl = baseUrl,
                keys = keys
            ) { done, total, latest ->
                mainHandler.post {
                    modelsStatus.text = getString(R.string.testing_model_fmt, latest.id, done, total)
                }
            }

            // Persist test results for the picker (shared with the main-screen picker).
            saveModelTestResults(provider.id, tested)

            withContext(Dispatchers.Main) {
                testBtn.isEnabled = true
                testBtn.text = getString(R.string.test_models)
                val working = tested.count { model -> model.tested == true }
                val failed = tested.count { model -> model.tested == false }
                modelCache[provider.id] = tested
                modelCount.text = getString(R.string.tested_count_fmt, working, tested.size)
                val workingText = resources.getQuantityString(R.plurals.working_count_fmt, working, working)
                val failedText = resources.getQuantityString(R.plurals.failed_count_fmt, failed, failed)
                modelsStatus.text = getString(R.string.tested_summary_fmt, workingText, failedText)
                workingOnly.add(provider.id)
                renderProviders()
            }
        }
    }

    private fun loadModelCount(provider: AiProvider, countView: TextView, statusView: TextView) {
        if (provider is ChatGptPlanProvider && ChatGptPlanAuth.activeStatus()?.connected != true) {
            countView.text = getString(R.string.not_applicable)
            statusView.text = getString(R.string.chatgpt_models_need_signin)
            return
        }
        if (!provider.supportsModelCatalog) {
            countView.text = getString(R.string.not_applicable)
            statusView.text = getString(R.string.catalog_not_supported)
            return
        }

        val cached = modelCache[provider.id]
        if (cached != null) {
            countView.text = resources.getQuantityString(R.plurals.models_count_fmt, cached.size, cached.size)
            statusView.text = getString(R.string.cached)
            return
        }

        statusView.text = getString(R.string.loading_model_catalog)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = provider.fetchModels()
            withContext(Dispatchers.Main) {
                result.onSuccess { models ->
                    modelCache[provider.id] = models
                    countView.text = resources.getQuantityString(R.plurals.models_count_fmt, models.size, models.size)
                    statusView.text = getString(R.string.loaded)
                }.onFailure {
                    countView.text = getString(R.string.error)
                    statusView.text = getString(R.string.load_failed_fmt, it.message ?: getString(R.string.unknown_error))
                }
            }
        }
    }

    private fun refreshActiveProviderModels() {
        val provider: AiProvider = ProviderManager.getActiveProvider()

        modelCache.remove(provider.id)
        refreshModelsBtn.isEnabled = false
        refreshModelsBtn.text = getString(R.string.refreshing_ellipsis)

        ProviderManager.fetchActiveProviderModels(lifecycleScope) { result ->
            refreshModelsBtn.isEnabled = true
            refreshModelsBtn.text = getString(R.string.refresh_models)

            result.onSuccess { models ->
                modelCache[provider.id] = models
                Toast.makeText(this, resources.getQuantityString(R.plurals.models_refreshed_from_provider_fmt, models.size, models.size, provider.displayName), Toast.LENGTH_SHORT).show()
                renderProviders()
            }.onFailure {
                Toast.makeText(this, getString(R.string.load_failed_fmt, it.message ?: getString(R.string.unknown_error)), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showModelPicker(provider: AiProvider) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_models, (window.decorView as? ViewGroup), false)
        dialog.setContentView(view)
        var probeJob: kotlinx.coroutines.Job? = null

        val list = view.findViewById<RecyclerView>(R.id.rvModels)
        list.layoutManager = LinearLayoutManager(this)

        val search = view.findViewById<EditText>(R.id.etModelSearch)
        search.hint = getString(R.string.search_provider_models_fmt, provider.displayName)
        val selected = view.findViewById<TextView>(R.id.tvSelectedModel)
        val status = view.findViewById<TextView>(R.id.tvModelsStatus)
        val empty = view.findViewById<TextView>(R.id.tvModelsEmpty)
        val thinking = view.findViewById<LinearLayout>(R.id.thinkingOptions)
        val refreshBtn = view.findViewById<android.widget.ImageButton>(R.id.btnRefreshModels)

        val cachedModels = modelCache[provider.id] ?: emptyList()
        val adapter = ModelAdapter(cachedModels) { model ->
            ProviderManager.setActiveProvider(provider.id)
            AgentConfig.model = model.id
            if (AgentConfig.reasoningEffort !in model.thinkingLevels) {
                AgentConfig.reasoningEffort = model.thinkingLevels.lastOrNull() ?: "off"
            }
            probeJob?.cancel()
            dialog.dismiss()
            updateActiveProviderBadge()
            renderProviders()
                Toast.makeText(this, getString(R.string.selected_model_fmt, model.id), Toast.LENGTH_SHORT).show()
        }
        list.adapter = adapter

        fun renderThinking(model: Model?) {
            thinking.removeAllViews()
            val levels = model?.thinkingLevels ?: listOf("off", "minimal", "low", "medium", "high", "max")
            levels.forEach { level ->
                val chip = TextView(this).apply {
                    text = level.replaceFirstChar { it.uppercase() }
                    setTextColor(resources.getColor(R.color.text_primary, theme))
                    background = androidx.core.content.res.ResourcesCompat.getDrawable(resources, R.drawable.bg_pill_ripple, theme)
                    setPadding(22, 12, 22, 12)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 8, 0) }
                    isAllCaps = false
                }
                chip.alpha = if (level == AgentConfig.reasoningEffort) 1f else 0.65f
                chip.setOnClickListener {
                    AgentConfig.reasoningEffort = level
                    renderThinking(model)
                    selected.text = model?.id?.let { getString(R.string.selected_model_fmt, it) }
                        ?: getString(R.string.thinking_level_fmt, level)
                }
                thinking.addView(chip)
            }
        }

        // Applies the working-only filter and search query to the cached list.
        fun displayModels(): List<Model> {
            val all = modelCache[provider.id] ?: emptyList()
            return if (workingOnly.contains(provider.id)) all.filter { it.tested != false } else all
        }

        fun filter(q: String) {
            val base = displayModels()
            val filtered = base.filter { model -> (model.id + " " + model.description).contains(q, ignoreCase = true) }
            adapter.updateData(filtered)
            empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            val testedCount = base.count { it.tested == true }
            status.text = if (testedCount > 0) {
                getString(
                    R.string.models_filter_status_with_tested_fmt,
                    resources.getQuantityString(R.plurals.models_count_fmt, base.size, base.size),
                    resources.getQuantityString(R.plurals.models_shown_count_fmt, filtered.size, filtered.size, base.size),
                    resources.getQuantityString(R.plurals.tested_count_words_fmt, testedCount, testedCount),
                )
            } else {
                getString(
                    R.string.models_filter_status_fmt,
                    resources.getQuantityString(R.plurals.models_count_fmt, base.size, base.size),
                    resources.getQuantityString(R.plurals.models_shown_count_fmt, filtered.size, filtered.size, base.size),
                )
            }
            val active = base.firstOrNull { it.id == AgentConfig.model }
            selected.text = active?.let { getString(R.string.selected_model_fmt, it.id) }
                ?: getString(R.string.no_model_selected)
            renderThinking(active)
        }

        // Probes untested models while the sheet is open and flips each row's
        // status dot in real time (green/red/orange) as results stream in.
        fun startProbing() {
            probeJob?.cancel()
            if (provider is ChatGptPlanProvider) return
            probeJob = lifecycleScope.launch {
                val base = modelCache[provider.id] ?: emptyList()
                val pending = base.filter { it.tested == null }
                if (pending.isEmpty()) return@launch
                status.text = resources.getQuantityString(R.plurals.models_probing_fmt, pending.size, pending.size, provider.displayName)
                val remaining = java.util.concurrent.atomic.AtomicInteger(pending.size)
                val probed = ModelTester.probeAll(
                    providerId = provider.id,
                    models = pending,
                    baseUrl = provider.getEffectiveBaseUrl(),
                    keys = provider.getApiKeys().filter { it.isNotBlank() },
                    useCache = true,
                ) { _, _, latest ->
                    val left = remaining.decrementAndGet()
                    runOnUiThread {
                        adapter.updateStatus(latest.id, ModelTester.statusFor(latest))
                        status.text = resources.getQuantityString(R.plurals.models_probing_fmt, left, left, provider.displayName)
                    }
                }
                val byId = probed.associateBy { it.id }
                val merged = base.map { byId[it.id] ?: it }
                modelCache[provider.id] = merged
                ModelTester.persistResults(provider.id, merged)
                withContext(Dispatchers.Main) { filter(search.text.toString()) }
            }
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { filter(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Fetch fresh models for this provider
        status.text = getString(R.string.loading_models_for, provider.displayName)
        empty.visibility = View.GONE
        lifecycleScope.launch(Dispatchers.IO) {
            val result = provider.fetchModels()
            withContext(Dispatchers.Main) {
                result.onSuccess { models ->
                    // Overlay saved test results onto the fresh catalog.
                    val merged = applySavedTestResults(provider.id, models)
                    modelCache[provider.id] = merged
                    adapter.updateData(if (workingOnly.contains(provider.id)) merged.filter { it.tested != false } else merged)
                    status.text = resources.getQuantityString(R.plurals.models_loaded_fmt, merged.size, merged.size)
                    empty.visibility = if (merged.isEmpty()) View.VISIBLE else View.GONE
                    val active = merged.firstOrNull { it.id == AgentConfig.model }
                    selected.text = active?.let { getString(R.string.selected_model_fmt, it.id) }
                        ?: getString(R.string.no_model_selected)
                    renderThinking(active)
                    startProbing()
                }.onFailure {
                    status.text = getString(R.string.load_failed_fmt, it.message ?: getString(R.string.unknown_error))
                    if (cachedModels.isEmpty()) {
                        empty.visibility = View.VISIBLE
                        empty.text = getString(R.string.load_models_failed_fmt, it.message ?: getString(R.string.unknown_error))
                    }
                }
            }
        }

        refreshBtn.setOnClickListener {
            status.text = getString(R.string.refreshing_models_for, provider.displayName)
            lifecycleScope.launch(Dispatchers.IO) {
                ModelTester.clearCache(provider.id)
                val result = provider.fetchModels()
                withContext(Dispatchers.Main) {
                    result.onSuccess { models ->
                        // Forget old outcomes and re-probe everything live so every dot updates.
                        val fresh = models.map { it.copy(tested = null, testNote = "") }
                        modelCache[provider.id] = fresh
                        adapter.resetStatuses()
                        adapter.updateData(if (workingOnly.contains(provider.id)) fresh.filter { it.tested != false } else fresh)
                        status.text = resources.getQuantityString(R.plurals.models_loaded_fmt, fresh.size, fresh.size)
                        Toast.makeText(this@ProvidersActivity, resources.getQuantityString(R.plurals.models_refreshed_count_fmt, models.size, models.size), Toast.LENGTH_SHORT).show()
                        startProbing()
                    }.onFailure {
                        status.text = getString(R.string.refresh_failed_fmt, it.message ?: getString(R.string.unknown_error))
                        Toast.makeText(this@ProvidersActivity, getString(R.string.load_failed_fmt, it.message ?: getString(R.string.unknown_error)), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        if (cachedModels.isNotEmpty()) {
            filter("")
            startProbing()
        }

        dialog.setOnDismissListener { probeJob?.cancel() }

        dialog.setOnShowListener {
            @Suppress("DEPRECATION")
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            BottomSheetSurface.clearOuterSurfaces(dialog, view)
            sheet?.let {
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(it).state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    /** Persists per-model test outcomes (tested + note) for a provider. */
    private fun saveModelTestResults(providerId: String, models: List<Model>) =
        ModelTester.persistResults(providerId, models)

    /** Overlays previously saved test results onto a freshly fetched catalog. */
    private fun applySavedTestResults(providerId: String, models: List<Model>): List<Model> =
        ModelTester.overlaySavedResults(providerId, models)

    private fun updateActiveProviderBadge() {
        val provider: AiProvider = ProviderManager.getActiveProvider()
        activeProviderBadge.text = provider.displayName
    }

    private fun saveValidationResults(providerId: String, results: List<KeyValidationResult>) {
        val gson = com.google.gson.Gson()
        val json = gson.toJson(results.map { mapOf(
            "keyFingerprint" to keyFingerprint(it.key),
            "maskedKey" to it.maskedKey,
            "isValid" to it.result.isValid,
            "errorMessage" to (it.result.errorMessage ?: ""),
            "modelsCount" to it.result.modelsCount,
            "latencyMs" to it.result.latencyMs
        ) })
        AgentConfig.putRaw("validation_$providerId", json)
    }

    private fun loadCachedValidation(providerId: String, key: String, chip: Chip) {
        val gson = com.google.gson.Gson()
        val raw = AgentConfig.getRaw("validation_$providerId")
        if (raw.isBlank()) return

        try {
            val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
            val list: List<Map<String, Any>> = gson.fromJson(raw, type) ?: return
            val containsLegacyKeys = list.any { it.containsKey("key") }
            val normalized = list.map { entry ->
                val safeEntry = entry.toMutableMap()
                val legacyKey = safeEntry.remove("key") as? String
                if (legacyKey != null && safeEntry["keyFingerprint"] == null) {
                    safeEntry["keyFingerprint"] = keyFingerprint(legacyKey)
                }
                safeEntry
            }
            if (containsLegacyKeys) {
                AgentConfig.putRaw("validation_$providerId", gson.toJson(normalized))
            }
            val fingerprint = keyFingerprint(key)
            val match = normalized.firstOrNull { it["keyFingerprint"] == fingerprint } ?: return
            val isValid = match["isValid"] as? Boolean ?: return
            val result = ValidationResult(
                isValid = isValid,
                errorMessage = match["errorMessage"] as? String,
                modelsCount = (match["modelsCount"] as? Double)?.toInt() ?: 0,
                latencyMs = ((match["latencyMs"] as? Double) ?: 0.0).toLong()
            )
            updateValidationChip(chip, result)
        } catch (_: Exception) {
            // Ignore parsing errors
        }
    }

    private fun keyFingerprint(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun sanitizeLegacyValidationSnapshots() {
        val gson = com.google.gson.Gson()
        val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val settings = com.example.oreshik.data.AgentDatabase.get(applicationContext).settings()
                ProviderRegistry.getAllProviders().forEach { provider ->
                    val settingKey = "validation_${provider.id}"
                    val raw = runCatching { settings.get(settingKey)?.value.orEmpty() }.getOrDefault("")
                    if (raw.isBlank()) return@forEach
                    val entries = runCatching {
                        gson.fromJson<List<Map<String, Any>>>(raw, type)
                    }.getOrNull()?.takeIf { values -> values.any { it.containsKey("key") } } ?: return@forEach
                    val sanitized = entries.map { entry ->
                        entry.toMutableMap().apply {
                            (remove("key") as? String)?.let { this["keyFingerprint"] = keyFingerprint(it) }
                        }
                    }
                    val sanitizedJson = gson.toJson(sanitized)
                    runCatching {
                        settings.put(com.example.oreshik.data.SettingEntity(settingKey, sanitizedJson))
                        AgentConfig.putRaw(settingKey, sanitizedJson)
                    }.onFailure { error ->
                        AppLogger.w("ProvidersActivity", "could not sanitize validation cache for ${provider.id}: ${error.message}")
                    }
                }
            }.onFailure {
                AppLogger.w("ProvidersActivity", "legacy validation cache migration failed: ${it.message}")
            }
        }
    }

    private fun updateValidationChip(chip: Chip, result: ValidationResult) {
        if (result.isValid) {
            chip.text = resources.getQuantityString(R.plurals.chip_validated_fmt, result.modelsCount, result.modelsCount)
            chip.setChipBackgroundColorResource(R.color.chip_success)
        } else {
            chip.text = getString(R.string.chip_validation_failed_fmt, result.errorMessage?.take(30) ?: getString(R.string.unknown_validation_error))
            chip.setChipBackgroundColorResource(R.color.chip_error)
        }
    }
}

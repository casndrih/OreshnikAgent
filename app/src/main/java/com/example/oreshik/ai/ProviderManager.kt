package com.example.oreshik.ai

import android.content.Context
import com.example.oreshik.Model
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ProviderManager {
    private var initialized = false
    @Volatile private var appContext: Context? = null
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val compatibleProviderLock = Any()
    private const val ACTIVE_PROVIDER_KEY = "active_provider_id"
    private const val DEFAULT_PROVIDER_ID = "nvidia"

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        AgentConfig.init(context)

        // Register all providers
        ProviderRegistry.register(NvidiaProvider())
        ProviderRegistry.register(ChatGptPlanProvider(context))
        ProviderRegistry.register(OpenRouterProvider())
        ProviderRegistry.register(BlueSmindProvider())
        ProviderRegistry.register(NaraRouterProvider())
        ProviderRegistry.register(MistralProvider())
        if (AgentConfig.isReady) {
            refreshOpenAiCompatibleProviders()
        } else {
            initScope.launch {
                AgentConfig.awaitReady()
                refreshOpenAiCompatibleProviders()
            }
        }

        // IMPORTANT: do NOT write a default active provider here. AgentConfig
        // loads persisted settings on a background thread, so at this moment the
        // cache still holds defaults and a synchronous "is blank?" check would
        // always be true — clobbering the user's saved choice with "nvidia" on
        // every process start. A blank value is resolved at READ time instead.
        initialized = true
        AppLogger.i("ProviderManager", "Initialized with ${ProviderRegistry.getAllProviders().size} providers")
    }

    /** The active provider; falls back to NVIDIA when unset or unknown. */
    fun getActiveProvider(): AiProvider {
        refreshOpenAiCompatibleProvidersIfReady()
        val id = AgentConfig.getRaw(ACTIVE_PROVIDER_KEY).ifBlank { DEFAULT_PROVIDER_ID }
        val provider = ProviderRegistry.getProvider(id)
        if (provider == null) {
            AppLogger.w("ProviderManager", "active provider '$id' not found in registry — falling back to '$DEFAULT_PROVIDER_ID'")
        }
        return provider
            ?: ProviderRegistry.getProvider(DEFAULT_PROVIDER_ID)
            ?: ProviderRegistry.getAllProviders().firstOrNull()
            ?: error("No providers registered")
    }

    fun setActiveProvider(id: String) {
        refreshOpenAiCompatibleProvidersIfReady()
        val provider = ProviderRegistry.getProvider(id) ?: run {
            AppLogger.e("ProviderManager", "setActiveProvider: unknown provider id '$id' — ignoring")
            return
        }
        // Sync the provider's key + base URL to the global config so legacy
        // code paths (voice assistant, vision) keep working. If the provider
        // has no explicit active key, promote its first configured key.
        val configuredKeys = provider.getApiKeys().filter { it.isNotBlank() }
        val key = provider.getActiveKey()?.takeIf { it in configuredKeys } ?: configuredKeys.firstOrNull()
        val configuredKey = key?.takeIf { it.isNotBlank() }
        val previousProviderId = AgentConfig.getRaw(ACTIVE_PROVIDER_KEY).ifBlank { DEFAULT_PROVIDER_ID }
        AgentConfig.putRaw(ACTIVE_PROVIDER_KEY, id)
        AgentConfig.modelMode = "online"
        if (previousProviderId != id) AgentConfig.model = ""
        if (configuredKey == null) {
            // Do not carry a previously active provider's credential into a provider with no key.
            AgentConfig.apiKey = ""
        } else {
            provider.setActiveKey(configuredKey)
            AgentConfig.apiKey = configuredKey
        }
        AgentConfig.baseUrl = provider.baseUrl
        AppLogger.i("ProviderManager", "Active provider set to: ${provider.displayName} (id=${provider.id}, keys=${provider.getApiKeys().size}, url=${provider.baseUrl})")
    }

    fun getActiveProviderId(): String = AgentConfig.getRaw(ACTIVE_PROVIDER_KEY).ifBlank { DEFAULT_PROVIDER_ID }

    /** Reconciles saved compatible-provider profiles after AgentConfig's initial DB read. */
    fun refreshOpenAiCompatibleProviders() {
        if (!AgentConfig.isReady) return
        synchronized(compatibleProviderLock) {
            OpenAiCompatibleProvider.ensureProfiles()
            val profiles = OpenAiCompatibleProvider.profiles()
            val desiredIds = profiles.mapTo(mutableSetOf()) { it.id }
            ProviderRegistry.getAllProviders()
                .filterIsInstance<OpenAiCompatibleProvider>()
                .filterNot { it.id in desiredIds }
                .forEach { ProviderRegistry.remove(it.id) }
            profiles.forEach { profile ->
                if (ProviderRegistry.getProvider(profile.id) == null) {
                    ProviderRegistry.register(OpenAiCompatibleProvider(profile))
                }
            }
        }
    }

    private fun refreshOpenAiCompatibleProvidersIfReady() {
        if (AgentConfig.isReady) refreshOpenAiCompatibleProviders()
    }

    fun addOpenAiCompatibleProvider(name: String, baseUrl: String, apiKey: String?): AiProvider {
        val provider = OpenAiCompatibleProvider.createProfile(name, baseUrl)
        apiKey?.takeIf { it.isNotBlank() }?.let(provider::addApiKey)
        synchronized(compatibleProviderLock) { ProviderRegistry.register(provider) }
        AppLogger.i("ProviderManager", "Added OpenAI-compatible profile '${provider.displayName}' (id=${provider.id})")
        return provider
    }

    fun removeOpenAiCompatibleProvider(id: String): Boolean {
        val provider = ProviderRegistry.getProvider(id) as? OpenAiCompatibleProvider ?: return false
        if (!OpenAiCompatibleProvider.removeProfile(id)) return false
        synchronized(compatibleProviderLock) { ProviderRegistry.remove(id) }
        if (getActiveProviderId() == id) setActiveProvider(DEFAULT_PROVIDER_ID)
        AppLogger.i("ProviderManager", "Removed OpenAI-compatible profile '${provider.displayName}' (id=$id)")
        return true
    }

    fun isOfflineMode(): Boolean = AgentConfig.isOfflineMode

    /** Provider used for this agent run. Local mode does not alter the saved online provider. */
    fun getExecutionProvider(modelModeOverride: String? = null): AiProvider {
        val mode = modelModeOverride ?: AgentConfig.modelMode
        if (mode.equals("offline", ignoreCase = true)) {
            val context = appContext ?: error("ProviderManager must be initialized before starting an agent run")
            return OfflineAiProvider(context)
        }
        return getActiveProvider()
    }

    /** Validates all keys for all providers in background */
    fun validateAllProviders(scope: CoroutineScope, onComplete: (Map<String, List<KeyValidationResult>>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val results = mutableMapOf<String, List<KeyValidationResult>>()
            for (provider in ProviderRegistry.getAllProviders()) {
                try {
                    val validationResults = provider.validateAllKeys()
                    results[provider.id] = validationResults
                } catch (e: Exception) {
                    AppLogger.e("ProviderManager", "Validation failed for ${provider.id}: ${e.message}", e)
                    results[provider.id] = emptyList()
                }
            }
            withContext(Dispatchers.Main) {
                onComplete(results)
            }
        }
    }

    /** Fetches models from active provider */
    fun fetchActiveProviderModels(scope: CoroutineScope, onComplete: (Result<List<Model>>) -> Unit) {
        val provider = getActiveProvider()
        scope.launch(Dispatchers.IO) {
            val result = provider.fetchModels()
            result.onSuccess { AppLogger.i("ProviderManager", "models fetched for ${provider.id}: ${it.size} models") }
                .onFailure { AppLogger.e("ProviderManager", "model fetch failed for ${provider.id}: ${it.javaClass.simpleName}: ${it.message?.take(250)}", it) }
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    /** Creates API client for the active provider. */
    fun createActiveApiClient(
        modelModeOverride: String? = null,
        offlineModelPathOverride: String? = null,
    ): AiApiClient? {
        val mode = modelModeOverride ?: AgentConfig.modelMode
        if (mode.equals("offline", ignoreCase = true)) {
            val context = appContext ?: return null
            return runCatching { OfflineModelApiClient(context, offlineModelPathOverride) }
                .onFailure { AppLogger.e("ProviderManager", "failed to create offline GGUF client: ${it.message}", it) }
                .getOrNull()
        }
        val provider = getActiveProvider()
        return runCatching {
            val client = EndpointGuardedApiClient(provider, provider.createApiClient())
            AppLogger.d("ProviderManager", "created API client for provider=${provider.id} model=${AgentConfig.model}")
            client
        }.onFailure {
            AppLogger.e("ProviderManager", "failed to create API client for provider=${provider.id}: ${it.message}", it)
        }.getOrNull()
    }

    private class EndpointGuardedApiClient(
        private val provider: AiProvider,
        private val delegate: AiApiClient,
    ) : AiApiClient {
        override suspend fun complete(
            model: String,
            messages: com.google.gson.JsonObject,
            tools: com.google.gson.JsonObject?,
            reasoningEffort: String,
        ): Result<com.google.gson.JsonObject> {
            val error = ProviderEndpointPolicy.error(provider.baseUrl)
            return if (error == null) delegate.complete(model, messages, tools, reasoningEffort)
            else Result.failure(IllegalArgumentException(error))
        }

        override suspend fun completeText(
            model: String,
            messages: com.google.gson.JsonObject,
            reasoningEffort: String,
        ): Result<com.google.gson.JsonObject> {
            val error = ProviderEndpointPolicy.error(provider.baseUrl)
            return if (error == null) delegate.completeText(model, messages, reasoningEffort)
            else Result.failure(IllegalArgumentException(error))
        }
    }
}

package com.example.oreshik.ai

import com.example.oreshik.Model
import com.google.gson.JsonObject
import androidx.core.net.toUri

/**
 * Abstract interface for AI providers. Each provider implementation handles
 * its own authentication, model catalog, and API communication.
 */
interface AiProvider {
    /** Unique identifier for this provider (e.g., "nvidia", "openrouter") */
    val id: String

    /** Human-readable name (e.g., "NVIDIA NIM", "OpenRouter") */
    val displayName: String

    /** Base URL for the provider's API */
    val baseUrl: String

    /** Whether this provider supports model listing from its catalog */
    val supportsModelCatalog: Boolean

    /**
     * Returns the list of API keys configured for this provider.
     * Empty list means no keys configured.
     */
    fun getApiKeys(): List<String>

    /**
     * Adds a new API key for this provider.
     */
    fun addApiKey(key: String)

    /**
     * Removes an API key for this provider.
     */
    fun removeApiKey(key: String)

    /**
     * Validates a single API key by making a lightweight request.
     * Returns ValidationResult with status and optional error message.
     */
    suspend fun validateKey(key: String): ValidationResult

    /**
     * Validates all configured keys and returns results.
     */
    suspend fun validateAllKeys(): List<KeyValidationResult>

    /**
     * Fetches the model catalog from the provider.
     * Returns list of Model objects or empty list if not supported.
     */
    suspend fun fetchModels(): Result<List<Model>>

    /**
     * Creates an API client for making chat completion requests.
     * The returned client uses the provider's key rotation logic.
     */
    fun createApiClient(): AiApiClient

    /**
     * Returns the currently selected/active API key for this provider.
     * Used when provider is set as active in settings.
     */
    fun getActiveKey(): String?

    /**
     * Sets the active API key for this provider.
     */
    fun setActiveKey(key: String?)

    /**
     * Returns the currently persisted base URL override for this provider,
     * or [baseUrl] when the user has not customized it.
     */
    fun getEffectiveBaseUrl(): String = baseUrl

    /**
     * Persists a user-edited base URL for this provider (editable in the
     * Providers screen). Pass null/blank to reset to the default [baseUrl].
     */
    fun setBaseUrl(url: String?) {}

    /**
     * Icon resource ID for the provider.
     */
    val iconRes: Int
}

/**
 * Prevents provider credentials and conversation data from being sent to a
 * remote endpoint without TLS. Loopback HTTP remains available for local
 * inference servers that never leave the device.
 */
object ProviderEndpointPolicy {
    fun error(baseUrl: String): String? {
        val uri = baseUrl.trim().toUri()
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase()
        if (host.isNullOrBlank()) return "Provider endpoint must include a host."
        if (scheme == "https") return null
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if ((scheme == "http") && isLoopback) return null
        return "Provider endpoints must use HTTPS. HTTP is allowed only for localhost."
    }
}

/**
 * Result of validating a single API key.
 */
data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val modelsCount: Int = 0,
    val latencyMs: Long = 0,
)

/**
 * Key validation result with key reference.
 */
data class KeyValidationResult(
    val key: String,
    val maskedKey: String,
    val result: ValidationResult,
)

/**
 * Generic API client interface for chat completions.
 * Provider-specific implementations handle the actual HTTP communication.
 */
interface AiApiClient {
    suspend fun complete(
        model: String,
        messages: JsonObject,
        tools: JsonObject?,
        reasoningEffort: String,
    ): Result<JsonObject>

    suspend fun completeText(
        model: String,
        messages: JsonObject,
        reasoningEffort: String,
    ): Result<JsonObject>
}

/**
 * Provider registry - manages all available providers.
 */
object ProviderRegistry {
    private val providers = mutableMapOf<String, AiProvider>()

    @Synchronized
    fun register(provider: AiProvider) {
        providers[provider.id] = provider
    }

    @Synchronized
    fun getProvider(id: String): AiProvider? = providers[id]

    @Synchronized
    fun getAllProviders(): List<AiProvider> = providers.values.toList()

    @Synchronized
    fun remove(id: String): AiProvider? = providers.remove(id)

    // Public API kept for future use (e.g., settings screens filtering by configured keys)
    @Suppress("unused")
    @Synchronized
    fun getEnabledProviders(): List<AiProvider> = providers.values.filter { it.getApiKeys().isNotEmpty() }.toList()

    @Synchronized
    fun clear() { providers.clear() }
}

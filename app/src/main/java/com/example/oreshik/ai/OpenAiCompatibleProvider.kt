package com.example.oreshik.ai

import com.example.oreshik.Model
import com.example.oreshik.ModelCapabilities
import com.example.oreshik.R
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Provider for OpenAI Chat Completions compatible services (cloud and local). */
class OpenAiCompatibleProvider(
    val profile: Profile,
    private val config: AgentConfig = AgentConfig,
) : AiProvider {
    override val id: String = profile.id
    override val displayName: String = profile.name
    override val supportsModelCatalog: Boolean = true
    override val iconRes: Int = R.drawable.ic_auto_awesome

    override val baseUrl: String
        get() = config.getRaw(baseUrlKey).trim().trimEnd('/').ifBlank { profile.baseUrl.ifBlank { DEFAULT_BASE_URL } }

    private val baseUrlKey get() = "${PROFILE_PREFIX}${id}_base_url"
    private val keysKey get() = "${PROFILE_PREFIX}${id}_keys"
    private val activeKeyKey get() = "${PROFILE_PREFIX}${id}_active_key"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val gson = Gson()

    override fun getApiKeys(): List<String> = readKeys()

    override fun addApiKey(key: String) {
        val normalized = key.trim()
        if (normalized.isBlank()) return
        val keys = (readKeys() + normalized).distinct()
        writeKeys(keys)
        if (getActiveKey().isNullOrBlank()) setActiveKey(normalized)
    }

    override fun removeApiKey(key: String) {
        val keys = readKeys().filterNot { it == key }
        writeKeys(keys)
        if (getActiveKey() == key) setActiveKey(keys.firstOrNull())
    }

    override fun getEffectiveBaseUrl(): String = baseUrl

    override fun setBaseUrl(url: String?) {
        val cleaned = url?.trim()?.trimEnd('/').orEmpty()
        config.putRaw(baseUrlKey, if (cleaned.equals(profile.baseUrl, ignoreCase = true)) "" else cleaned)
        if (ProviderManager.getActiveProviderId() == id) AgentConfig.baseUrl = baseUrl
    }

    override fun getActiveKey(): String? = config.getRaw(activeKeyKey).takeIf { it.isNotBlank() }

    override fun setActiveKey(key: String?) {
        val chosen = key?.takeIf { candidate -> readKeys().contains(candidate) }.orEmpty()
        config.putRaw(activeKeyKey, chosen)
    }

    override suspend fun validateKey(key: String): ValidationResult = withContext(Dispatchers.IO) {
        ProviderEndpointPolicy.error(baseUrl)?.let { return@withContext ValidationResult(false, it) }
        val started = System.currentTimeMillis()
        try {
            val request = Request.Builder().url("$baseUrl/models")
                .header("Authorization", "Bearer $key")
                .get().build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val latency = System.currentTimeMillis() - started
                if (!response.isSuccessful) {
                    ValidationResult(false, "HTTP ${response.code}: ${body.take(250)}", latencyMs = latency)
                } else {
                    val count = runCatching { JsonParser.parseString(body).asJsonObject.getAsJsonArray("data")?.size() ?: 0 }.getOrDefault(0)
                    ValidationResult(true, modelsCount = count, latencyMs = latency)
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            ValidationResult(false, failure.message ?: "Unable to reach the provider", latencyMs = System.currentTimeMillis() - started)
        }
    }

    override suspend fun validateAllKeys(): List<KeyValidationResult> = withContext(Dispatchers.IO) {
        getApiKeys().map { key ->
            KeyValidationResult(key, ProviderKeyRotator.mask(key), validateKey(key))
        }
    }

    override suspend fun fetchModels(): Result<List<Model>> = withContext(Dispatchers.IO) {
        ProviderEndpointPolicy.error(baseUrl)?.let {
            return@withContext Result.failure(IllegalArgumentException(it))
        }
        try {
            val request = Request.Builder().url("$baseUrl/models").apply {
                activeRequestKey()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }.get().build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext Result.failure(
                    IllegalStateException("HTTP ${response.code}: ${body.take(500)}"),
                )
                val data = JsonParser.parseString(body).asJsonObject.getAsJsonArray("data")
                    ?: return@withContext Result.success(emptyList())
                val models = LinkedHashMap<String, Model>()
                data.forEach { element ->
                    if (!element.isJsonObject) return@forEach
                    val item = element.asJsonObject
                    val modelId = item.get("id")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
                    if (modelId.isBlank()) return@forEach
                    val lower = modelId.lowercase()
                    val vision = listOf("vision", "-vl", "_vl", "4o", "gpt-4.1").any { lower.contains(it) }
                    val audio = ModelCapabilities.supportsChatAudio(modelId, item.get("description")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
                    val reasoningCapability = ModelCapabilities.reasoningCapability(item, modelId, item.get("description")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
                    val reasoning = reasoningCapability.supported
                    models.putIfAbsent(modelId, Model(
                        id = modelId,
                        description = "${item.get("owned_by")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: displayName} • $modelId",
                        ownedBy = item.get("owned_by")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                        reasoning = reasoning,
                        vision = vision,
                        audio = audio,
                        thinkingLevels = reasoningCapability.levels,
                        reasoningVerified = reasoningCapability.verified,
                    ))
                }
                Result.success(models.values.sortedBy { it.id.lowercase() })
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            AppLogger.e(TAG, "model catalog request failed: ${failure.message}", failure)
            Result.failure(failure)
        }
    }

    override fun createApiClient(): AiApiClient = OpenAiCompatibleApiClient()

    private fun readKeys(): List<String> = runCatching {
        val raw = config.getRaw(keysKey)
        if (raw.isBlank()) emptyList() else gson.fromJson(raw, Array<String>::class.java).orEmpty().filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun writeKeys(keys: List<String>) {
        val clean = keys.map(String::trim).filter(String::isNotBlank).distinct()
        config.putRaw(keysKey, if (clean.isEmpty()) "" else gson.toJson(clean))
    }

    private fun activeRequestKey(): String? = getActiveKey()?.takeIf { it in readKeys() } ?: readKeys().firstOrNull()

    private inner class OpenAiCompatibleApiClient : AiApiClient {
        override suspend fun complete(
            model: String,
            messages: JsonObject,
            tools: JsonObject?,
            reasoningEffort: String,
        ): Result<JsonObject> = send(model, messages, tools)

        override suspend fun completeText(
            model: String,
            messages: JsonObject,
            reasoningEffort: String,
        ): Result<JsonObject> = send(model, messages, null)

        private suspend fun send(model: String, messages: JsonObject, tools: JsonObject?): Result<JsonObject> = withContext(Dispatchers.IO) {
            ProviderEndpointPolicy.error(baseUrl)?.let {
                return@withContext Result.failure(IllegalArgumentException(it))
            }
            if (model.isBlank()) return@withContext Result.failure(IllegalStateException("Choose a model from this provider's model list first."))
            try {
                val body = JsonObject().apply {
                    addProperty("model", model)
                    add("messages", messages.getAsJsonArray("messages") ?: JsonArray())
                    val toolArray = tools?.getAsJsonArray("tools")
                    if (toolArray != null && toolArray.size() > 0) {
                        add("tools", toolArray)
                        addProperty("tool_choice", "auto")
                    }
                }
                val request = Request.Builder().url("$baseUrl/chat/completions").apply {
                    activeRequestKey()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                }.post(gson.toJson(body).toRequestBody("application/json".toMediaType())).build()
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        AppLogger.w(TAG, "chat $model failed with HTTP ${response.code}: ${responseBody.take(300)}")
                        Result.failure(IllegalStateException("HTTP ${response.code}: ${responseBody.take(1500)}"))
                    } else {
                        val parsed = gson.fromJson(responseBody, JsonObject::class.java)
                        if (parsed == null) Result.failure(IllegalStateException("Provider returned an empty response."))
                        else Result.success(parsed)
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                AppLogger.e(TAG, "chat request failed: ${failure.javaClass.simpleName}: ${failure.message}", failure)
                Result.failure(failure)
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val PROFILE_PREFIX = "openai_compatible_profile_"
        private const val PROFILES_KEY = "openai_compatible_profiles"
        private const val INITIALIZED_KEY = "openai_compatible_profiles_initialized"
        private const val LEGACY_BASE_URL_KEY = "openai_compatible_base_url"
        private const val LEGACY_KEYS_KEY = "openai_compatible_keys"
        private const val LEGACY_ACTIVE_KEY_KEY = "openai_compatible_active_key"
        private const val TAG = "Provider/OpenAICompatible"

        data class Profile(val id: String, val name: String, val baseUrl: String)

        fun ensureProfiles() {
            // AgentConfig reads the settings database asynchronously. Never treat
            // its temporary defaults as saved values during process startup.
            if (!AgentConfig.isReady) return
            if (AgentConfig.getRaw(INITIALIZED_KEY) == "true") return
            val legacyKeys = AgentConfig.getRaw(LEGACY_KEYS_KEY)
            val legacyBaseUrl = AgentConfig.getRaw(LEGACY_BASE_URL_KEY).trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
            val default = Profile("openai_compatible", "OpenAI Compatible", legacyBaseUrl)
            saveProfiles(listOf(default))
            if (legacyKeys.isNotBlank()) AgentConfig.putRaw("${PROFILE_PREFIX}${default.id}_keys", legacyKeys)
            AgentConfig.getRaw(LEGACY_ACTIVE_KEY_KEY).takeIf { it.isNotBlank() }?.let {
                AgentConfig.putRaw("${PROFILE_PREFIX}${default.id}_active_key", it)
            }
            AgentConfig.putRaw(LEGACY_KEYS_KEY, "")
            AgentConfig.putRaw(LEGACY_ACTIVE_KEY_KEY, "")
            AgentConfig.putRaw(LEGACY_BASE_URL_KEY, "")
            AgentConfig.putRaw(INITIALIZED_KEY, "true")
        }

        fun profiles(): List<Profile> {
            if (!AgentConfig.isReady) return emptyList()
            ensureProfiles()
            return runCatching {
                val raw = AgentConfig.getRaw(PROFILES_KEY)
                if (raw.isBlank()) emptyList() else Gson().fromJson(raw, Array<Profile>::class.java).orEmpty().toList()
            }.getOrDefault(emptyList())
        }

        fun createProfile(name: String, baseUrl: String): OpenAiCompatibleProvider {
            check(AgentConfig.isReady) { "Provider settings are still loading. Try again in a moment." }
            val normalizedName = name.trim()
            require(normalizedName.isNotBlank()) { "Enter a provider name." }
            val normalizedUrl = baseUrl.trim().trimEnd('/')
            require(ProviderEndpointPolicy.error(normalizedUrl) == null) {
                ProviderEndpointPolicy.error(normalizedUrl) ?: "Enter a valid HTTPS or localhost endpoint."
            }
            val id = "openai_compatible_${UUID.randomUUID().toString().replace("-", "")}" 
            val profile = Profile(id, normalizedName, normalizedUrl)
            saveProfiles(profiles() + profile)
            return OpenAiCompatibleProvider(profile)
        }

        fun removeProfile(id: String): Boolean {
            val current = profiles()
            if (current.none { it.id == id }) return false
            saveProfiles(current.filterNot { it.id == id })
            AgentConfig.putRaw("${PROFILE_PREFIX}${id}_keys", "")
            AgentConfig.putRaw("${PROFILE_PREFIX}${id}_active_key", "")
            AgentConfig.putRaw("${PROFILE_PREFIX}${id}_base_url", "")
            return true
        }

        private fun saveProfiles(profiles: List<Profile>) {
            AgentConfig.putRaw(PROFILES_KEY, Gson().toJson(profiles))
        }
    }
}

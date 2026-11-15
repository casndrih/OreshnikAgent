package com.example.oreshik.ai

import android.content.Context
import com.example.oreshik.Model
import com.example.oreshik.R
import com.example.oreshik.agent.core.AgentConfig
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** OpenAI Responses API provider authenticated with the user's ChatGPT plan. */
class ChatGptPlanProvider(context: Context) : AiProvider {
    init { ChatGptPlanAuth.initialize(context) }

    override val id: String = ID
    override val displayName: String = "ChatGPT plan"
    override val baseUrl: String = BASE_URL
    override val supportsModelCatalog: Boolean = true
    override val iconRes: Int = R.drawable.ic_auto_awesome

    override fun getApiKeys(): List<String> = emptyList()
    override fun addApiKey(key: String) = Unit
    override fun removeApiKey(key: String) = Unit
    override fun getActiveKey(): String? = null
    override fun setActiveKey(key: String?) = Unit
    override fun getEffectiveBaseUrl(): String = BASE_URL
    override fun setBaseUrl(url: String?) = Unit

    override suspend fun validateKey(key: String): ValidationResult =
        ValidationResult(false, "Use Continue with ChatGPT. A ChatGPT plan token is not an API key.")

    override suspend fun validateAllKeys(): List<KeyValidationResult> = emptyList()

    override suspend fun fetchModels(): Result<List<Model>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = ChatGptPlanAuth.accessToken()
            val request = Request.Builder()
                .url("$BASE_URL/models")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ChatGptResponsesApi.httpFailure(response.code, body)
                val models = JsonParser.parseString(body).asJsonObject.getAsJsonArray("models")
                    ?: return@use emptyList<Model>()
                models.mapNotNull { entry ->
                    if (!entry.isJsonObject) return@mapNotNull null
                    val item = entry.asJsonObject
                    val visible = item.get("visibility")?.takeUnless { it.isJsonNull }?.asString
                    if (visible != null && visible != "list") return@mapNotNull null
                    val slug = item.get("slug")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
                    if (slug.isBlank()) return@mapNotNull null
                    val displayName = item.get("display_name")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
                    val lower = slug.lowercase()
                    val thinkingLevels = thinkingLevelsFor(lower)
                    val supportsReasoning = thinkingLevels != listOf("off")
                    val supportsVision = listOf("vision", "4o", "gpt-4.1", "gpt-6").any { lower.contains(it) }
                    Model(
                        id = slug,
                        description = displayName.ifBlank { slug },
                        ownedBy = "OpenAI",
                        reasoning = supportsReasoning,
                        vision = supportsVision,
                        thinkingLevels = thinkingLevels,
                    )
                }.distinctBy { it.id }.sortedBy { it.id.lowercase() }
            }
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            AppLogger.w("Provider/ChatGPT", "could not load ChatGPT model catalog: ${failure.message}")
        }
    }

    override fun createApiClient(): AiApiClient = ChatGptPlanApiClient()

    companion object {
        const val ID = "chatgpt_plan"
        const val BASE_URL = "https://api.openai.com/v1"
        private val http = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Maps the app's Chat Completions-shaped agent history and tools onto Responses. */
private class ChatGptPlanApiClient : AiApiClient {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun complete(
        model: String,
        messages: JsonObject,
        tools: JsonObject?,
        reasoningEffort: String,
    ): Result<JsonObject> = request(model, messages, tools, reasoningEffort)

    override suspend fun completeText(
        model: String,
        messages: JsonObject,
        reasoningEffort: String,
    ): Result<JsonObject> = request(model, messages, null, reasoningEffort)

    private suspend fun request(
        model: String,
        messages: JsonObject,
        tools: JsonObject?,
        reasoningEffort: String,
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        if (model.isBlank()) return@withContext Result.failure(IllegalStateException("Choose a ChatGPT plan model in Providers first."))
        try {
            val token = ChatGptPlanAuth.accessToken()
            val body = buildRequest(model, messages.getAsJsonArray("messages") ?: JsonArray(), tools, reasoningEffort)
            val request = Request.Builder()
                .url("${ChatGptPlanProvider.BASE_URL}/responses")
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    AppLogger.w("Provider/ChatGPT", "Responses API request failed (HTTP ${response.code})")
                    return@withContext Result.failure(ChatGptResponsesApi.httpFailure(response.code, errorBody))
                }
                val source = response.body?.source()
                    ?: return@withContext Result.failure(IllegalStateException("ChatGPT returned an empty response stream."))
                var completedResponse: JsonObject? = null
                var failureMessage: String? = null
                val streamedText = StringBuilder()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") continue
                    val event = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                    when (event.get("type")?.asString.orEmpty()) {
                        "response.output_text.delta" -> event.get("delta")?.takeUnless { it.isJsonNull }?.asString?.let(streamedText::append)
                        "response.completed" -> completedResponse = event.getAsJsonObject("response")
                        "response.failed" -> failureMessage = ChatGptResponsesApi.responseError(event.getAsJsonObject("response"))
                        "response.incomplete" -> failureMessage = "ChatGPT stopped before completing this response. Try a shorter prompt or a lower reasoning effort."
                    }
                }
                if (failureMessage != null) return@withContext Result.failure(IllegalStateException(failureMessage))
                val completed = completedResponse
                    ?: return@withContext Result.failure(IllegalStateException("ChatGPT ended the stream without a completed response."))
                val compatible = ChatGptResponsesApi.toChatCompletionsShape(completed, streamedText.toString())
                if (compatible == null) Result.failure(IllegalStateException("ChatGPT completed the request without text or a tool call."))
                else Result.success(compatible)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            AppLogger.e("Provider/ChatGPT", "Responses API request failed: ${failure.javaClass.simpleName}: ${failure.message?.take(250)}")
            Result.failure(failure)
        }
    }

    private fun buildRequest(model: String, history: JsonArray, tools: JsonObject?, reasoningEffort: String): JsonObject {
        val instructions = ArrayList<String>()
        val input = JsonArray()
        history.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val message = element.asJsonObject
            val role = message.get("role")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            when (role) {
                "system" -> message.get("content")?.takeUnless { it.isJsonNull }?.let { instructions += it.asString }
                "developer" -> {
                    val developerMessage = responseMessage(role, message.get("content"))
                    if (developerMessage != null) input.add(developerMessage)
                }
                "tool" -> {
                    val output = JsonObject().apply {
                        addProperty("type", "function_call_output")
                        addProperty("call_id", message.get("tool_call_id")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
                        addProperty("output", message.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
                    }
                    input.add(output)
                }
                "assistant" -> {
                    val assistantMessage = responseMessage(role, message.get("content"))
                    if (assistantMessage != null) input.add(assistantMessage)
                    message.getAsJsonArray("tool_calls")?.forEach { toolCallElement ->
                        if (!toolCallElement.isJsonObject) return@forEach
                        val call = toolCallElement.asJsonObject
                        val function = call.getAsJsonObject("function") ?: return@forEach
                        val functionCall = JsonObject().apply {
                            addProperty("type", "function_call")
                            addProperty("call_id", call.get("id")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
                            addProperty("name", function.get("name")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
                            val arguments = function.get("arguments")
                            addProperty("arguments", when {
                                arguments == null || arguments.isJsonNull -> "{}"
                                arguments.isJsonPrimitive && arguments.asJsonPrimitive.isString -> arguments.asString
                                else -> arguments.toString()
                            })
                        }
                        input.add(functionCall)
                    }
                }
                "user" -> responseMessage(role, message.get("content"))?.let(input::add)
            }
        }

        return JsonObject().apply {
            addProperty("model", model)
            if (instructions.isNotEmpty()) addProperty("instructions", instructions.joinToString("\n\n"))
            add("input", input)
            addProperty("store", false)
            addProperty("stream", true)
            val effort = normalizeEffort(model, reasoningEffort)
            if (effort != null) add("reasoning", JsonObject().apply { addProperty("effort", effort) })
            val functionTools = tools?.getAsJsonArray("tools")?.mapNotNull(::responseFunctionTool).orEmpty()
            if (functionTools.isNotEmpty()) {
                add("tools", JsonArray().also { array -> functionTools.forEach(array::add) })
                addProperty("tool_choice", "auto")
            }
        }
    }

    private fun responseMessage(role: String, content: JsonElement?): JsonObject? {
        if (content == null || content.isJsonNull) return null
        val wireContent: JsonElement = if (content.isJsonPrimitive) {
            if (content.asString.isBlank()) return null
            content.deepCopy()
        } else if (content.isJsonArray) {
            JsonArray().apply {
                content.asJsonArray.forEach { partElement ->
                    if (!partElement.isJsonObject) return@forEach
                    val part = partElement.asJsonObject
                    when (part.get("type")?.takeUnless { it.isJsonNull }?.asString.orEmpty()) {
                        "text", "input_text", "output_text" -> {
                            val text = part.get("text")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                            if (text.isNotBlank()) add(JsonObject().apply {
                                addProperty("type", if (role == "assistant") "output_text" else "input_text")
                                addProperty("text", text)
                            })
                        }
                        "image_url", "input_image" -> {
                            val imageUrl = part.get("image_url")?.let { image ->
                                if (image.isJsonObject) image.asJsonObject.get("url")?.takeUnless { it.isJsonNull }?.asString
                                else if (image.isJsonPrimitive) image.asString else null
                            } ?: part.get("url")?.takeUnless { it.isJsonNull }?.asString
                            if (!imageUrl.isNullOrBlank() && role != "assistant") add(JsonObject().apply {
                                addProperty("type", "input_image")
                                addProperty("image_url", imageUrl)
                            })
                        }
                        "input_file" -> {
                            if (role != "assistant") {
                                val fileData = part.get("file_data")?.takeUnless { it.isJsonNull }?.asString
                                val fileId = part.get("file_id")?.takeUnless { it.isJsonNull }?.asString
                                if (!fileData.isNullOrBlank() || !fileId.isNullOrBlank()) add(JsonObject().apply {
                                    addProperty("type", "input_file")
                                    addProperty("filename", part.get("filename")?.takeUnless { it.isJsonNull }?.asString ?: "attachment")
                                    if (!fileData.isNullOrBlank()) addProperty("file_data", fileData) else addProperty("file_id", fileId)
                                    part.get("detail")?.takeUnless { it.isJsonNull }?.let { add("detail", it.deepCopy()) }
                                })
                            }
                        }
                    }
                }
            }.also { if (it.size() == 0) return null }
        } else {
            JsonArray().apply { add(JsonObject().apply {
                addProperty("type", if (role == "assistant") "output_text" else "input_text")
                addProperty("text", content.toString())
            }) }
        }
        return JsonObject().apply {
            addProperty("type", "message")
            addProperty("role", role)
            add("content", wireContent)
        }
    }

    private fun responseFunctionTool(element: JsonElement): JsonObject? {
        if (!element.isJsonObject) return null
        val source = element.asJsonObject
        val fn = source.getAsJsonObject("function") ?: return null
        val name = fn.get("name")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        if (name.isBlank()) return null
        return JsonObject().apply {
            addProperty("type", "function")
            addProperty("name", name)
            fn.get("description")?.takeUnless { it.isJsonNull }?.let { add("description", it.deepCopy()) }
            fn.get("parameters")?.takeUnless { it.isJsonNull }?.let { add("parameters", it.deepCopy()) }
        }
    }

    private fun normalizeEffort(model: String, value: String): String? {
        val supported = thinkingLevelsFor(model.lowercase())
        if (supported == listOf("off")) return null
        val normalized = if (value.trim().equals("ultra", ignoreCase = true)) "max" else value.trim().lowercase()
        return normalized.takeIf { it in supported } ?: supported.lastOrNull()
    }

}

private fun thinkingLevelsFor(modelSlug: String): List<String> = when {
    modelSlug.contains("gpt-6") || modelSlug.contains("gpt-5.6") -> listOf("low", "medium", "high", "xhigh", "max")
    modelSlug.contains("gpt-5") -> listOf("low", "medium", "high", "xhigh")
    listOf("o1", "o3", "o4").any { modelSlug.contains(it) } -> listOf("low", "medium", "high", "xhigh")
    else -> listOf("off")
}

private object ChatGptResponsesApi {
    fun httpFailure(code: Int, body: String): IllegalStateException {
        val error = runCatching { JsonParser.parseString(body).asJsonObject.getAsJsonObject("error") }.getOrNull()
        val apiCode = error?.get("code")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val message = error?.get("message")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val readable = when (apiCode) {
            "subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable" ->
                "ChatGPT plan usage is unavailable or its shared usage limit has been reached. Check ChatGPT Settings → Usage."
            "invalid_api_key", "invalid_token" -> "ChatGPT sign-in expired. Reconnect the ChatGPT account in Providers."
            else -> message.takeIf { it.isNotBlank() } ?: "The ChatGPT Responses API request failed (HTTP $code)."
        }
        return IllegalStateException(readable)
    }

    fun responseError(response: JsonObject?): String {
        val error = response?.getAsJsonObject("error")
        val code = error?.get("code")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val message = error?.get("message")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        return when (code) {
            "subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable" ->
                "ChatGPT plan usage is unavailable or its shared usage limit has been reached. Check ChatGPT Settings → Usage."
            else -> message.ifBlank { "ChatGPT could not complete this response." }
        }
    }

    fun toChatCompletionsShape(response: JsonObject, streamedText: String): JsonObject? {
        val content = StringBuilder()
        val calls = JsonArray()
        val output = response.getAsJsonArray("output")
        output?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val item = element.asJsonObject
            when (item.get("type")?.asString.orEmpty()) {
                "message" -> item.getAsJsonArray("content")?.forEach { contentElement ->
                    if (!contentElement.isJsonObject) return@forEach
                    val part = contentElement.asJsonObject
                    when (part.get("type")?.asString.orEmpty()) {
                        "output_text", "refusal" -> part.get("text")?.takeUnless { it.isJsonNull }?.asString?.let(content::append)
                    }
                }
                "function_call" -> {
                    val callId = item.get("call_id")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    val name = item.get("name")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    if (callId.isNotBlank() && name.isNotBlank()) {
                        calls.add(JsonObject().apply {
                            addProperty("id", callId)
                            addProperty("type", "function")
                            add("function", JsonObject().apply {
                                addProperty("name", name)
                                addProperty("arguments", item.get("arguments")?.takeUnless { it.isJsonNull }?.asString ?: "{}")
                            })
                        })
                    }
                }
            }
        }
        if (content.isEmpty()) content.append(response.get("output_text")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
        if (content.isEmpty()) content.append(streamedText)
        if (content.isEmpty() && calls.size() == 0) return null
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            if (content.isNotEmpty()) addProperty("content", content.toString()) else add("content", com.google.gson.JsonNull.INSTANCE)
            if (calls.size() > 0) add("tool_calls", calls)
        }
        return JsonObject().apply {
            add("choices", JsonArray().also { choices ->
                choices.add(JsonObject().apply {
                    addProperty("index", 0)
                    add("message", message)
                    addProperty("finish_reason", if (calls.size() > 0) "tool_calls" else "stop")
                })
            })
        }
    }
}

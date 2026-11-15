package com.example.oreshik.agent.tools

import com.example.oreshik.agent.core.*
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shows real observed token/latency counts and estimates cost only with user-supplied rates. */
class ProviderMetricsTool : AgentTool {
    override val definition = ToolDefinition(
        name = "provider.metrics",
        category = ToolCategory.SYSTEM,
        description = "View provider request counts, observed token usage and latency. Configure input/output USD per million tokens to calculate cost estimates; no vendor prices are guessed.",
        permissions = emptySet(),
        parameters = mapOf(
            "action" to "string (summary, set_pricing, reset)",
            "host" to "string (provider host for pricing)",
            "model" to "string",
            "input_usd_per_million" to "number",
            "output_usd_per_million" to "number",
        ),
    )

    override suspend fun execute(context: ToolContext, arguments: JsonElement): ToolResult = withContext(Dispatchers.IO) {
        ProviderMetrics.initialize(context.app)
        val args = arguments.asJsonObject
        when (args["action"]?.asString?.lowercase()?.trim() ?: "summary") {
            "summary" -> {
                val rows = ProviderMetrics.snapshot()
                if (rows.isEmpty()) return@withContext ToolResult.success("No provider requests recorded in this app installation yet.")
                var knownCost = 0.0
                var priced = 0
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val output = rows.joinToString("\n\n") { row ->
                    val cost = ProviderMetrics.estimatedCostUsd(context.app, row)
                    if (cost != null) { knownCost += cost; priced++ }
                    buildString {
                        append(row.host).append(" · ").append(row.model).append('\n')
                        append("Requests: ").append(row.requests)
                            .append(" (").append(row.successes).append(" successful, ").append(row.failures).append(" failed)").append('\n')
                        append("Tokens: ").append(row.promptTokens).append(" input + ").append(row.completionTokens).append(" output").append('\n')
                        append("Latency: avg ").append(row.averageLatencyMs).append(" ms, last ").append(row.lastLatencyMs).append(" ms").append('\n')
                        append("Estimated cost: ").append(cost?.let { "USD %.6f".format(Locale.US, it) } ?: "not configured")
                            .append('\n').append("Updated: ").append(formatter.format(Date(row.updatedAt)))
                    }
                }
                ToolResult.success(output + "\n\nEstimated total for priced models: USD %.6f (%d/%d models priced).".format(Locale.US, knownCost, priced, rows.size))
            }
            "set_pricing" -> runCatching {
                val host = args["host"]?.asString?.trim().orEmpty()
                val model = args["model"]?.asString?.trim().orEmpty()
                require(host.isNotBlank() && model.isNotBlank()) { "host and model are required." }
                val input = args["input_usd_per_million"]?.asDouble ?: error("input_usd_per_million is required.")
                val output = args["output_usd_per_million"]?.asDouble ?: error("output_usd_per_million is required.")
                ProviderMetrics.setPricing(context.app, host, model, input, output)
                ToolResult.success("Saved user-supplied pricing for $host / $model.")
            }.getOrElse { ToolResult.failure("Could not save pricing: ${it.message}") }
            "reset" -> {
                ProviderMetrics.reset()
                ToolResult.success("Provider usage counters reset. Configured pricing rates were retained.")
            }
            else -> ToolResult.failure("Supported actions: summary, set_pricing, reset.")
        }
    }
}

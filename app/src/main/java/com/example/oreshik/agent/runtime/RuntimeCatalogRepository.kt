package com.example.oreshik.agent.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RemoteRuntimePack(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val url: String,
    val sha256: String,
)

class RuntimeCatalogRepository {
    companion object {
        const val CATALOG_URL =
            "https://raw.githubusercontent.com/casndrih/OreshnikAgent/main/runtime-catalog.json"
    }

    suspend fun fetch(): List<RemoteRuntimePack> = withContext(Dispatchers.IO) {
        val connection = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OreshikAgent-RuntimeManager/1")
        }
        try {
            check(connection.responseCode in 200..299) {
                "Runtime catalog request failed: HTTP ${connection.responseCode}"
            }
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            require(root.optInt("schemaVersion") == 1) { "Unsupported runtime catalog schema" }
            val packs = root.optJSONArray("packs") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until packs.length()) {
                    val item = packs.optJSONObject(index) ?: continue
                    val pack = RemoteRuntimePack(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        version = item.optString("version"),
                        description = item.optString("description"),
                        url = item.optString("url"),
                        sha256 = item.optString("sha256"),
                    )
                    if (pack.id.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")) &&
                        pack.name.isNotBlank() && pack.version.isNotBlank() &&
                        pack.url.startsWith("https://") &&
                        pack.sha256.matches(Regex("(?i)^[a-f0-9]{64}$"))
                    ) add(pack)
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

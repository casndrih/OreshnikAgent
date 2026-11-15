package com.example.oreshik.agent.core

/**
 * Human-readable descriptions for NVIDIA NIM auth failures.
 *
 * NVIDIA's gateway validates API keys as JWTs. When the credential is empty, a
 * placeholder, a key from another provider (sk-or-v1-…, sk-…), or a "Bearer "
 * prefix pasted into the key field, the gateway answers HTTP 401 with the raw
 * message of its JWT parsing library (jose4j), e.g.
 * `{"detail":"invalid serialization: missing dot delimiter"}`. That string is
 * meaningless to users — what they need to know is "your API key is invalid".
 *
 * The `HTTP <code>: ` prefix is deliberately preserved in every message so the
 * existing key-rotation and cooldown paths ([NvidiaKeyManager.httpCodeFromMessage]
 * and the `HTTP (\d{3})` regexes elsewhere) keep parsing the status unchanged.
 */
object NvidiaAuthErrors {

    /**
     * Formats a failed HTTP response for [Result] failures and validation
     * messages. Auth failures get an explicit, actionable hint appended on a
     * new line instead of only the raw gateway body.
     *
     * @param bodyLimit max characters of the raw response body to keep.
     */
    fun describe(httpCode: Int, body: String, bodyLimit: Int = 1500): String {
        val raw = "HTTP $httpCode: ${body.take(bodyLimit)}"
        return when (httpCode) {
            401 -> "$raw\n" +
                "The API key was rejected as invalid or malformed. " +
                "Open Providers → NVIDIA NIM and re-enter a valid nvapi-… key " +
                "(paste the key itself, without the word \"Bearer\")."
            403 -> "$raw\n" +
                "The API key is valid but not allowed for this model or account."
            429 -> "$raw\n" +
                "NVIDIA rate limited this API key. The app will try other saved NVIDIA keys; " +
                "if they are also limited, wait for the quota window to recover and try again."
            else -> raw
        }
    }

    /**
     * True when a failure message is an authentication/authorization failure
     * (401/403). Used to skip capability fallbacks (no-tools / no-reasoning
     * retries) that can never fix an auth problem and only burn requests.
     */
    fun isAuthFailure(message: String): Boolean =
        message.contains("HTTP 401") || message.contains("HTTP 403")
}

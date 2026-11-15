package com.example.oreshik.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.oreshik.logging.AppLogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Sign in with ChatGPT credentials for the documented open-source client flow. */
object ChatGptPlanAuth {
    private const val TAG = "ChatGPTAuth"
    private const val PREFS_NAME = "chatgpt_plan_credentials"
    private const val ACCOUNTS_KEY = "accounts_json"
    private const val ACTIVE_ACCOUNT_KEY = "active_client_id"
    private const val HOST_ID_KEY = "ext_agent_host_id"
    private const val ISSUER = "https://auth.openai.com"
    private const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
    private const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
    private const val RESOURCE = "https://api.openai.com/v1"
    private const val REQUIRED_SCOPE = "chatgpt.tokens.use.direct"
    private const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    private const val INITIAL_CLIENT_ID = "dynamic_agent_client"
    private const val CALLBACK_PATH = "/auth/callback"

    private val gson = Gson()
    private val authMutex = Mutex()
    private val refreshMutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var appContext: Context? = null
    @Volatile private var accountsCache: List<AccountCredential>? = null
    @Volatile private var activeClientIdCache: String? = null
    @Volatile private var activeClientIdLoaded = false
    @Volatile private var hostIdCache: String? = null
    @Volatile private var preferencesCache: android.content.SharedPreferences? = null

    data class AccountStatus(
        val clientId: String,
        val email: String,
        val connected: Boolean,
        val isActive: Boolean,
    )

    private data class AccountCredential(
        val clientId: String,
        val subject: String,
        val email: String,
        val hostId: String,
        val idToken: String,
        val accessToken: String,
        val refreshToken: String,
        val scopes: List<String>,
        val expiresAtMillis: Long,
    ) {
        fun status(activeId: String?) = AccountStatus(
            clientId = clientId,
            email = email,
            connected = accessToken.isNotBlank() && refreshToken.isNotBlank() && REQUIRED_SCOPE in scopes,
            isActive = activeId == clientId,
        )
    }

    private data class PendingAuthorization(
        val clientId: String,
        val redirectUri: String,
        val state: String,
        val nonce: String,
        val verifier: String,
        val idTokenHint: String?,
        val loginHint: String?,
    )

    private data class Callback(val code: String, val state: String, val clientId: String?, val error: String?)
    private data class TokenResponse(
        val access_token: String = "",
        val refresh_token: String = "",
        val id_token: String = "",
        val token_type: String = "",
        val expires_in: Long = 0,
        val scope: String = "",
    )

    private class TokenRequestFailure(val errorCode: String, statusCode: Int, detail: String?) : IllegalStateException(
        "ChatGPT token request failed (HTTP $statusCode)${detail?.takeIf { it.isNotBlank() }?.let { ": ${it.take(300)}" }.orEmpty()}"
    )

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /** Loads/creates the encrypted credential store away from the UI thread. */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        readAccounts()
        activeClientId()
        pendingHostId()
    }

    fun activeStatus(): AccountStatus? {
        val activeId = activeClientId() ?: return null
        return readAccounts().firstOrNull { it.clientId == activeId }?.status(activeId)
    }

    fun accountStatuses(): List<AccountStatus> {
        val activeId = activeClientId()
        return readAccounts().map { it.status(activeId) }
    }

    fun selectAccount(clientId: String) {
        require(readAccounts().any { it.clientId == clientId }) { "That ChatGPT account is not saved in this app." }
        prefs().edit().putString(ACTIVE_ACCOUNT_KEY, clientId).apply()
        activeClientIdCache = clientId
        activeClientIdLoaded = true
    }

    suspend fun signIn(activity: Activity, addAnotherAccount: Boolean = false): AccountStatus = authMutex.withLock {
        val accounts = readAccounts().toMutableList()
        val activeId = activeClientId()
        val prior = if (addAnotherAccount) null else accounts.firstOrNull { it.clientId == activeId }
        fun issuedClientId(pending: PendingAuthorization, callback: Callback): String {
            if (callback.state != pending.state) throw IllegalStateException("ChatGPT sign-in state did not match. Please try again.")
            callback.error?.let { error ->
                throw IllegalStateException(if (error == "access_denied") "ChatGPT sign-in was cancelled." else "ChatGPT sign-in failed: $error")
            }
            if (callback.code.isBlank()) throw IllegalStateException("ChatGPT did not return an authorization code.")
            if (pending.clientId == INITIAL_CLIENT_ID) {
                return callback.clientId?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("ChatGPT did not finish registering this app. Please try sign-in again.")
            }
            if (!callback.clientId.isNullOrBlank() && callback.clientId != pending.clientId) {
                throw IllegalStateException("ChatGPT returned a different account registration. No credentials were changed.")
            }
            return pending.clientId
        }

        var authorization = awaitBrowserAuthorization(activity, prior)
        val issuedClientId = issuedClientId(authorization.first, authorization.second)
        val tokens = try {
            exchangeAuthorizationCode(authorization.second.code, issuedClientId, authorization.first)
        } catch (failure: TokenRequestFailure) {
            if (failure.errorCode != "invalid_grant") throw failure
            // Authorization codes are one-use. Open a fresh authorization attempt with the
            // issued ID from dynamic registration, as required by the sign-in recovery flow.
            authorization = awaitBrowserAuthorization(activity, prior, clientIdOverride = issuedClientId)
            val retryClientId = issuedClientId(authorization.first, authorization.second)
            if (retryClientId != issuedClientId) {
                throw IllegalStateException("ChatGPT returned a different account registration. No credentials were changed.")
            }
            exchangeAuthorizationCode(authorization.second.code, issuedClientId, authorization.first)
        }
        val grantedScopes = tokens.scope.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        if (REQUIRED_SCOPE !in grantedScopes) {
            throw IllegalStateException("ChatGPT sign-in did not grant Responses API access. Reconnect and approve the ChatGPT plan access request.")
        }
        if (tokens.access_token.isBlank() || tokens.id_token.isBlank()) {
            throw IllegalStateException("ChatGPT returned an incomplete sign-in response.")
        }

        val identity = validateIdToken(tokens.id_token, issuedClientId, authorization.first.nonce)
        if (prior != null && identity.subject != prior.subject) {
            throw IllegalStateException("The signed-in ChatGPT account does not match the selected saved account.")
        }
        val hostId = pendingHostId()
        val credential = AccountCredential(
            clientId = issuedClientId,
            subject = identity.subject,
            email = identity.email.ifBlank { prior?.email.orEmpty() },
            hostId = hostId,
            idToken = tokens.id_token,
            accessToken = tokens.access_token,
            refreshToken = tokens.refresh_token,
            scopes = grantedScopes.toList().sorted(),
            expiresAtMillis = System.currentTimeMillis() + tokens.expires_in.coerceAtLeast(60) * 1000L,
        )
        accounts.removeAll { it.clientId == issuedClientId }
        accounts.add(credential)
        withContext(Dispatchers.IO) {
            writeAccounts(accounts)
            prefs().edit().putString(ACTIVE_ACCOUNT_KEY, issuedClientId).commit()
        }
        activeClientIdCache = issuedClientId
        activeClientIdLoaded = true
        AppLogger.i(TAG, "ChatGPT plan account connected (email present=${credential.email.isNotBlank()})")
        credential.status(issuedClientId)
    }

    suspend fun accessToken(): String = refreshMutex.withLock {
        val activeId = activeClientId()
            ?: throw IllegalStateException("Sign in with ChatGPT in Providers before selecting a ChatGPT model.")
        val accounts = readAccounts().toMutableList()
        val index = accounts.indexOfFirst { it.clientId == activeId }
        if (index < 0) throw IllegalStateException("The active ChatGPT account is no longer available. Sign in again.")
        val current = accounts[index]
        if (REQUIRED_SCOPE !in current.scopes) {
            throw IllegalStateException("This ChatGPT account has no Responses API permission. Reconnect it in Providers.")
        }
        if (current.accessToken.isNotBlank() && current.expiresAtMillis > System.currentTimeMillis() + 120_000L) {
            return@withLock current.accessToken
        }
        if (current.refreshToken.isBlank()) throw IllegalStateException("ChatGPT sign-in expired. Reconnect the account in Providers.")

        val response = postTokenForm(FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", current.clientId)
            .add("refresh_token", current.refreshToken)
            .add("resource", RESOURCE)
            .build())
        if (response.access_token.isBlank() || response.refresh_token.isBlank()) {
            throw IllegalStateException("ChatGPT token refresh returned an incomplete response. Reconnect the account in Providers.")
        }
        val refreshed = current.copy(
            accessToken = response.access_token,
            refreshToken = response.refresh_token,
            idToken = response.id_token.ifBlank { current.idToken },
            scopes = response.scope.split(Regex("\\s+")).filter { it.isNotBlank() }.ifEmpty { current.scopes },
            expiresAtMillis = System.currentTimeMillis() + response.expires_in.coerceAtLeast(60) * 1000L,
        )
        if (REQUIRED_SCOPE !in refreshed.scopes) throw IllegalStateException("ChatGPT token refresh no longer grants Responses API access. Reconnect the account in Providers.")
        accounts[index] = refreshed
        writeAccounts(accounts)
        refreshed.accessToken
    }

    /** Revoke the renewable session when possible and always clear local tokens. */
    suspend fun signOut(): Boolean = refreshMutex.withLock {
        val activeId = activeClientId() ?: return@withLock true
        val accounts = readAccounts().toMutableList()
        val index = accounts.indexOfFirst { it.clientId == activeId }
        if (index < 0) return@withLock true
        val current = accounts[index]
        var revoked = current.refreshToken.isBlank()
        if (current.refreshToken.isNotBlank()) {
            revoked = try {
                withContext(Dispatchers.IO) {
                val configuration = oidcConfiguration()
                val endpoint = configuration.get("revocation_endpoint")?.asString
                    ?: throw IllegalStateException("OpenAI did not publish a token revocation endpoint.")
                val form = FormBody.Builder()
                    .add("token", current.refreshToken)
                    .add("token_type_hint", "refresh_token")
                    .add("client_id", current.clientId)
                    .build()
                postFormExpectNoBody(endpoint, form)
                }
                true
            } catch (failure: Exception) {
                AppLogger.w(TAG, "remote ChatGPT sign-out was not confirmed: ${failure.javaClass.simpleName}")
                false
            }
        }
        accounts[index] = current.copy(idToken = "", accessToken = "", refreshToken = "", scopes = emptyList(), expiresAtMillis = 0)
        writeAccounts(accounts)
        revoked
    }

    private suspend fun awaitBrowserAuthorization(
        activity: Activity,
        prior: AccountCredential?,
        clientIdOverride: String? = null,
    ): Pair<PendingAuthorization, Callback> {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 240_000
        val redirect = "http://127.0.0.1:${server.localPort}$CALLBACK_PATH"
        val pending = PendingAuthorization(
            clientId = prior?.clientId ?: clientIdOverride ?: INITIAL_CLIENT_ID,
            redirectUri = redirect,
            state = randomUrlSafe(32),
            nonce = randomUrlSafe(32),
            verifier = randomUrlSafe(48),
            idTokenHint = prior?.idToken?.takeIf { it.isNotBlank() },
            loginHint = prior?.email?.takeIf { it.isNotBlank() },
        )
        val result = CompletableDeferred<Callback>()
        val thread = Thread({
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                        val requestLine = reader.readLine().orEmpty()
                        if (!requestLine.startsWith("GET ")) {
                            writeBrowserReply(socket, 405, "Sign-in callback received an unsupported request.")
                            return@use
                        }
                        val target = requestLine.split(' ').getOrNull(1).orEmpty()
                        val callbackUri = Uri.parse("http://127.0.0.1$target")
                        if (callbackUri.path != CALLBACK_PATH) {
                            writeBrowserReply(socket, 404, "No sign-in callback was found at this path.")
                            return@use
                        }
                        val callback = Callback(
                            code = callbackUri.getQueryParameter("code").orEmpty(),
                            state = callbackUri.getQueryParameter("state").orEmpty(),
                            clientId = callbackUri.getQueryParameter("client_id"),
                            error = callbackUri.getQueryParameter("error"),
                        )
                        result.complete(callback)
                        AppLogger.i(
                            TAG,
                            "loopback callback received (code present=${callback.code.isNotBlank()}, state present=${callback.state.isNotBlank()}, client id present=${!callback.clientId.isNullOrBlank()}, oauth error present=${!callback.error.isNullOrBlank()})",
                        )
                        writeBrowserReply(socket, 200, "Sign-in callback received by Oreshik. Returning to the app…")
                        server.close()
                        return@Thread
                    }
                }
            } catch (failure: Exception) {
                if (!server.isClosed) {
                    AppLogger.e(TAG, "loopback callback listener failed (${failure.javaClass.simpleName})")
                    result.completeExceptionally(failure)
                }
            }
        }, "chatgpt-oauth-callback").apply { isDaemon = true }
        thread.start()
        try {
            val authorizationUrl = authorizationUrl(pending, pending.clientId == INITIAL_CLIENT_ID)
            withContext(Dispatchers.Main.immediate) {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)))
            }
            val callback = withTimeout(240_000) { result.await() }
            return pending to callback
        } finally {
            runCatching { server.close() }
        }
    }

    private fun authorizationUrl(pending: PendingAuthorization, firstRegistration: Boolean): String {
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pending.verifier.toByteArray(StandardCharsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val builder = HttpUrl.Builder()
            .scheme("https").host("auth.openai.com").addPathSegments("api/accounts/authorize")
            .addQueryParameter("client_id", pending.clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", pending.redirectUri)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", pending.state)
            .addQueryParameter("nonce", pending.nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("ext_agent_host_id", pendingHostId())
        if (firstRegistration) builder.addQueryParameter("agent_name_hint", "Oreshik")
        pending.idTokenHint?.let { builder.addQueryParameter("id_token_hint", it) }
        pending.loginHint?.let { builder.addQueryParameter("login_hint", it) }
        return builder.build().toString()
    }

    private suspend fun exchangeAuthorizationCode(code: String, clientId: String, pending: PendingAuthorization): TokenResponse = withContext(Dispatchers.IO) {
        postTokenForm(FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("code", code)
            .add("code_verifier", pending.verifier)
            .add("redirect_uri", pending.redirectUri)
            .add("resource", RESOURCE)
            .build())
    }

    private suspend fun postTokenForm(form: FormBody): TokenResponse = withContext(Dispatchers.IO) {
        postForm(TOKEN_URL, form)
    }

    private fun postForm(url: String, form: FormBody): TokenResponse {
        val request = Request.Builder().url(url).post(form).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val payload = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                val nestedError = payload?.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
                val errorCode = payload?.get("error")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    ?: nestedError?.get("code")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: nestedError?.get("type")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: ""
                val description = payload?.get("error_description")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: nestedError?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: nestedError?.get("description")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: payload?.get("error")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                throw TokenRequestFailure(errorCode, response.code, description)
            }
            return gson.fromJson(body, TokenResponse::class.java)
                ?: throw IllegalStateException("OpenAI returned an empty token response.")
        }
    }

    private fun postFormExpectNoBody(url: String, form: FormBody) {
        val request = Request.Builder().url(url).post(form).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw IllegalStateException("ChatGPT sign-out could not be confirmed (HTTP ${response.code}: ${body.take(200)}).")
            }
        }
    }

    private data class Identity(val subject: String, val email: String)

    private suspend fun validateIdToken(token: String, expectedClientId: String, expectedNonce: String): Identity = withContext(Dispatchers.IO) {
        val pieces = token.split('.')
        if (pieces.size != 3) throw IllegalStateException("ChatGPT returned an invalid identity token.")
        val header = parseJwtPart(pieces[0])
        val claims = parseJwtPart(pieces[1])
        if (header.get("alg")?.asString != "RS256") throw IllegalStateException("ChatGPT used an unsupported identity-token signature.")
        val keyId = header.get("kid")?.asString.orEmpty()
        if (keyId.isBlank()) throw IllegalStateException("ChatGPT identity token has no signing-key identifier.")
        val configuration = oidcConfiguration()
        val issuer = configuration.get("issuer")?.asString.orEmpty().trimEnd('/')
        if (issuer != ISSUER) throw IllegalStateException("OpenAI identity issuer did not match the expected issuer.")
        val jwksUrl = configuration.get("jwks_uri")?.asString
            ?: throw IllegalStateException("OpenAI did not publish identity signing keys.")
        val jwks = getJson(jwksUrl).getAsJsonArray("keys")
            ?: throw IllegalStateException("OpenAI identity signing keys were empty.")
        val key = jwks.firstOrNull { it.isJsonObject && it.asJsonObject.get("kid")?.asString == keyId }?.asJsonObject
            ?: throw IllegalStateException("ChatGPT identity token used an unknown signing key.")
        val modulus = BigInteger(1, decodeUrlSafe(key.get("n")?.asString.orEmpty()))
        val exponent = BigInteger(1, decodeUrlSafe(key.get("e")?.asString.orEmpty()))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${pieces[0]}.${pieces[1]}".toByteArray(StandardCharsets.US_ASCII))
        if (!verifier.verify(decodeUrlSafe(pieces[2]))) throw IllegalStateException("ChatGPT identity-token signature validation failed.")

        val audience = claims.get("aud")?.let { claim ->
            if (claim.isJsonArray) claim.asJsonArray.mapNotNull { if (it.isJsonNull) null else it.asString }
            else listOf(claim.asString)
        }.orEmpty()
        val nowSeconds = System.currentTimeMillis() / 1000
        val expiry = claims.get("exp")?.asLong ?: 0L
        val notBefore = claims.get("nbf")?.asLong ?: 0L
        val subject = claims.get("sub")?.asString.orEmpty()
        if (audience.none { it == expectedClientId }) throw IllegalStateException("ChatGPT identity token was issued for a different client.")
        if ((claims.get("iss")?.asString.orEmpty().trimEnd('/') != ISSUER) || expiry <= nowSeconds || (notBefore > nowSeconds)) {
            throw IllegalStateException("ChatGPT identity token issuer or validity period is invalid.")
        }
        if (claims.get("nonce")?.asString != expectedNonce || subject.isBlank()) {
            throw IllegalStateException("ChatGPT identity token did not match this sign-in attempt.")
        }
        Identity(subject, claims.get("email")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
    }

    private suspend fun oidcConfiguration(): JsonObject = getJson("https://auth.openai.com/.well-known/openid-configuration")

    private fun getJson(url: String): JsonObject {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Could not validate ChatGPT sign-in (HTTP ${response.code}).")
            return JsonParser.parseString(body).asJsonObject
        }
    }

    private fun parseJwtPart(value: String): JsonObject =
        JsonParser.parseString(String(decodeUrlSafe(value), StandardCharsets.UTF_8)).asJsonObject

    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes)
        java.security.SecureRandom().nextBytes(data)
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun decodeUrlSafe(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun writeBrowserReply(socket: Socket, status: Int, message: String) {
        val appPackage = appContext?.packageName.orEmpty()
        val returnIntent = "intent://oauth-complete#Intent;scheme=oreshik;package=$appPackage;end"
        val returnLink = if (status == 200 && appPackage.isNotBlank()) {
            "<p><a href=\"$returnIntent\">Return to Oreshik</a></p><script>window.location.href='$returnIntent';</script>"
        } else {
            ""
        }
        val body = "<!doctype html><meta charset=\"utf-8\"><title>Oreshik sign-in</title><p>$message</p>$returnLink".toByteArray(StandardCharsets.UTF_8)
        val reason = if (status == 200) "OK" else if (status == 405) "Method Not Allowed" else "Not Found"
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun pendingHostId(): String {
        hostIdCache?.let { return it }
        val preferences = prefs()
        preferences.getString(HOST_ID_KEY, null)?.let { hostIdCache = it; return it }
        val created = "urn:uuid:${UUID.randomUUID()}"
        check(preferences.edit().putString(HOST_ID_KEY, created).commit()) { "Could not initialize ChatGPT host identity." }
        hostIdCache = created
        return created
    }

    private fun activeClientId(): String? {
        if (activeClientIdLoaded) return activeClientIdCache
        synchronized(this) {
            if (!activeClientIdLoaded) {
                activeClientIdCache = prefs().getString(ACTIVE_ACCOUNT_KEY, null)
                activeClientIdLoaded = true
            }
        }
        return activeClientIdCache
    }

    private fun prefs(): android.content.SharedPreferences {
        preferencesCache?.let { return it }
        synchronized(this) {
            preferencesCache?.let { return it }
            val context = appContext ?: throw IllegalStateException("ChatGPT provider has not been initialized.")
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { preferencesCache = it }
        }
    }

    @Synchronized
    private fun readAccounts(): List<AccountCredential> {
        accountsCache?.let { return it }
        val stored = runCatching {
            val raw = prefs().getString(ACCOUNTS_KEY, "").orEmpty()
            if (raw.isBlank()) emptyList() else gson.fromJson(raw, Array<AccountCredential>::class.java).orEmpty().toList()
        }.getOrElse { failure ->
        AppLogger.e(TAG, "could not read encrypted ChatGPT account store", failure)
        throw IllegalStateException("Saved ChatGPT credentials could not be read. Reinstalling or restoring app data may require signing in again.")
        }
        accountsCache = stored
        return stored
    }

    @Synchronized
    private fun writeAccounts(accounts: List<AccountCredential>) {
        check(prefs().edit().putString(ACCOUNTS_KEY, gson.toJson(accounts)).commit()) { "Could not securely save ChatGPT credentials." }
        accountsCache = accounts.toList()
    }
}

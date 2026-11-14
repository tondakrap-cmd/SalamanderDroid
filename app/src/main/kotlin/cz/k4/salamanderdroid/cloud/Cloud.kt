package cz.k4.salamanderdroid.cloud

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class CloudKind(val key: String, val label: String, val scheme: String) {
    OneDrive("onedrive", "OneDrive", "onedrive://"),
    GoogleDrive("gdrive", "Google Drive", "gdrive://");

    companion object {
        fun parse(key: String) = entries.firstOrNull { it.key == key } ?: OneDrive
    }
}

class Tokens(val access: String, val expiresAt: Long, val refresh: String?)

/**
 * A cloud drive account. The application (client) ID comes from the user's own registration
 * with Microsoft or Google; tokens are stored sealed (see [CloudStore]).
 */
data class CloudAccount(
    val id: String,
    val kind: CloudKind,
    val name: String,
    val clientId: String,
    /** Google "desktop app" clients have a secret; Microsoft public clients do not. */
    val clientSecret: String = "",
    /** Microsoft tenant: `common` for any account, or a tenant id for single-tenant apps. */
    val tenant: String = "common",
    /** Signed-in user (e-mail), shown in lists. */
    val user: String = "",
) {
    fun displayName(): String = name.ifEmpty { user.ifEmpty { kind.label } }

    /** Name used in panel paths, e.g. `onedrive://Práce/Dokumenty`. */
    fun pathName(): String = displayName().replace('/', '_')

    fun validate(): String? = when {
        clientId.isBlank() -> "Zadejte ID aplikace (client ID)"
        kind == CloudKind.GoogleDrive && clientSecret.isBlank() -> "Zadejte client secret z Google Cloud Console"
        listOf(name, clientId, clientSecret, tenant).any { it.contains('\n') } -> "Hodnoty nesmí obsahovat konec řádku"
        else -> null
    }
}

/** Service addresses; replaced by local fakes in tests. */
data class CloudEndpoints(
    val microsoftLogin: String = "https://login.microsoftonline.com",
    val graph: String = "https://graph.microsoft.com/v1.0",
    val googleAuth: String = "https://accounts.google.com/o/oauth2/v2/auth",
    val googleToken: String = "https://oauth2.googleapis.com/token",
    val drive: String = "https://www.googleapis.com/drive/v3",
    val driveUpload: String = "https://www.googleapis.com/upload/drive/v3",
)

class OAuthConfig(
    val authUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String,
    val scopes: String,
    /** Host of the loopback redirect; Microsoft registers `localhost`, Google prefers the address. */
    val redirectHost: String,
    val extraAuthParams: Map<String, String> = emptyMap(),
) {
    companion object {
        fun of(account: CloudAccount, endpoints: CloudEndpoints): OAuthConfig = when (account.kind) {
            CloudKind.OneDrive -> OAuthConfig(
                "${endpoints.microsoftLogin}/${account.tenant.ifBlank { "common" }}/oauth2/v2.0/authorize",
                "${endpoints.microsoftLogin}/${account.tenant.ifBlank { "common" }}/oauth2/v2.0/token",
                account.clientId, "", "Files.ReadWrite.All offline_access User.Read", "localhost",
                mapOf("prompt" to "select_account"),
            )
            CloudKind.GoogleDrive -> OAuthConfig(
                endpoints.googleAuth, endpoints.googleToken, account.clientId, account.clientSecret,
                "https://www.googleapis.com/auth/drive", "127.0.0.1",
                mapOf("access_type" to "offline", "prompt" to "consent"),
            )
        }
    }
}

class CloudException(val code: Int, message: String) : IOException(message)

fun urlEncode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/**
 * OAuth 2 authorization-code flow with PKCE for an installed app: the system browser signs
 * the user in and redirects to a one-shot listener on the loopback interface.
 */
object OAuth {
    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    fun challengeOf(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    private fun parseTokens(json: JSONObject, previousRefresh: String?): Tokens {
        val access = json.optString("access_token")
        if (access.isEmpty()) throw IOException("Server nevrátil přístupový token")
        val expires = json.optLong("expires_in", 3600)
        val refresh = json.optString("refresh_token").ifEmpty { previousRefresh }
        return Tokens(access, System.currentTimeMillis() + expires * 1000, refresh)
    }

    private fun tokenRequest(http: OkHttpClient, config: OAuthConfig, params: Map<String, String>, previousRefresh: String?): Tokens {
        val form = FormBody.Builder().add("client_id", config.clientId)
        if (config.clientSecret.isNotEmpty()) form.add("client_secret", config.clientSecret)
        for ((key, value) in params) form.add(key, value)
        http.newCall(Request.Builder().url(config.tokenUrl).post(form.build()).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                JSONObject()
            }
            if (!response.isSuccessful) {
                val detail = json.optString("error_description").ifEmpty { json.optString("error").ifEmpty { "HTTP ${response.code}" } }
                if (json.optString("error") == "invalid_grant") {
                    throw CloudException(401, "Přihlášení vypršelo nebo bylo odvoláno; přihlaste účet znovu (Vzdálené → Cloudové účty). $detail")
                }
                throw CloudException(response.code, "Přihlášení selhalo: ${detail.lineSequence().first()}")
            }
            return parseTokens(json, previousRefresh)
        }
    }

    fun refresh(http: OkHttpClient, config: OAuthConfig, refreshToken: String): Tokens = tokenRequest(
        http, config, mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken, "scope" to config.scopes), refreshToken,
    )

    private const val DONE_PAGE = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
        "<title>Salamander Droid</title></head><body style=\"font-family:sans-serif;margin:3em\"><h2>%s</h2><p>%s</p></body></html>"

    private fun respond(socket: Socket, title: String, text: String) {
        val body = DONE_PAGE.format(title, text).toByteArray()
        socket.getOutputStream().use { out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
        }
    }

    /**
     * Runs the sign-in. [openBrowser] gets the authorization URL; the call blocks until the
     * browser comes back, [cancel] is set, or the timeout passes.
     */
    fun authorize(
        http: OkHttpClient,
        config: OAuthConfig,
        openBrowser: (String) -> Unit,
        cancel: AtomicBoolean,
        timeoutSeconds: Long = 300,
    ): Tokens {
        val verifier = randomUrlSafe(48)
        val state = randomUrlSafe(16)
        val v4 = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port = v4.localPort
        // "localhost" may resolve to the IPv6 loopback in the browser; listen there too if possible.
        val v6 = try {
            ServerSocket(port, 4, InetAddress.getByName("::1"))
        } catch (_: Exception) {
            null
        }
        val servers = listOfNotNull(v4, v6)
        val redirect = "http://${config.redirectHost}:$port"
        val results = LinkedBlockingQueue<Result<String>>()
        for (server in servers) {
            Thread {
                try {
                    while (true) {
                        val socket = server.accept()
                        socket.soTimeout = 5000
                        val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                        val target = line.split(' ').getOrNull(1).orEmpty()
                        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
                            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                        when {
                            query["state"] != state -> respond(socket, "Salamander Droid", "Tato stránka slouží jen k dokončení přihlášení.")
                            query["code"] != null -> {
                                respond(socket, "Přihlášení dokončeno", "Můžete se vrátit do aplikace Salamander Droid.")
                                results += Result.success(query.getValue("code"))
                                return@Thread
                            }
                            else -> {
                                val error = query["error_description"] ?: query["error"] ?: "neznámá chyba"
                                respond(socket, "Přihlášení se nezdařilo", error)
                                results += Result.failure(IOException("Přihlášení se nezdařilo: $error"))
                                return@Thread
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Listener closed.
                }
            }.apply { isDaemon = true }.start()
        }
        try {
            val url = StringBuilder(config.authUrl)
                .append("?response_type=code&client_id=").append(urlEncode(config.clientId))
                .append("&redirect_uri=").append(urlEncode(redirect))
                .append("&scope=").append(urlEncode(config.scopes))
                .append("&state=").append(state)
                .append("&code_challenge=").append(challengeOf(verifier))
                .append("&code_challenge_method=S256")
            for ((key, value) in config.extraAuthParams) url.append('&').append(key).append('=').append(urlEncode(value))
            openBrowser(url.toString())
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            var code: String? = null
            while (code == null) {
                if (cancel.get()) throw IOException("Přihlášení zrušeno")
                if (System.currentTimeMillis() > deadline) throw IOException("Přihlášení nebylo dokončeno včas")
                code = results.poll(250, TimeUnit.MILLISECONDS)?.getOrThrow()
            }
            return tokenRequest(
                http, config,
                mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirect, "code_verifier" to verifier),
                null,
            )
        } finally {
            for (server in servers) {
                try {
                    server.close()
                } catch (_: IOException) {
                }
            }
        }
    }
}

/** Authorized HTTP for one account: refreshes the token and retries throttled requests. */
class CloudSession(
    val http: OkHttpClient,
    private val config: OAuthConfig,
    private var tokens: Tokens,
    private val onTokens: (Tokens) -> Unit,
) {
    @Synchronized
    private fun accessToken(force: Boolean): String {
        if (force || System.currentTimeMillis() > tokens.expiresAt - 60_000) {
            val refresh = tokens.refresh ?: throw CloudException(401, "Přihlášení vypršelo; přihlaste účet znovu (Vzdálené → Cloudové účty)")
            tokens = OAuth.refresh(http, config, refresh)
            onTokens(tokens)
        }
        return tokens.access
    }

    /** Executes the request; the caller closes the response. [build] is called again on retries. */
    fun execute(auth: Boolean = true, build: () -> Request.Builder): Response {
        var refreshed = false
        var attempt = 0
        while (true) {
            val builder = build()
            if (auth) builder.header("Authorization", "Bearer ${accessToken(false)}")
            val response = http.newCall(builder.build()).execute()
            when {
                response.code == 401 && auth && !refreshed -> {
                    response.close()
                    refreshed = true
                    accessToken(true)
                }
                (response.code == 429 || response.code == 503) && attempt < 3 -> {
                    val wait = response.header("Retry-After")?.toLongOrNull() ?: (1L shl attempt)
                    response.close()
                    attempt++
                    Thread.sleep(wait.coerceIn(1, 15) * 1000)
                }
                else -> return response
            }
        }
    }

    /** Throws a readable error for a failed response (which it closes). */
    fun fail(response: Response, what: String): Nothing {
        val text = try {
            response.body?.string().orEmpty()
        } catch (_: IOException) {
            ""
        } finally {
            response.close()
        }
        val detail = try {
            val error = JSONObject(text).opt("error")
            (error as? JSONObject)?.optString("message") ?: error?.toString()
        } catch (_: Exception) {
            null
        }
        val message = when (response.code) {
            401 -> "Přihlášení vypršelo; přihlaste účet znovu (Vzdálené → Cloudové účty)"
            403 -> "Přístup odepřen: $what" + (detail?.let { " ($it)" } ?: "")
            404 -> "Soubor nebo složka neexistuje: $what"
            507 -> "V cloudovém úložišti není dost místa"
            else -> "${detail ?: "HTTP ${response.code}"}: $what"
        }
        throw CloudException(response.code, message)
    }

    /** JSON of a successful response; null for 404 when [missingOk]. */
    fun json(what: String, missingOk: Boolean = false, build: () -> Request.Builder): JSONObject? {
        val response = execute(build = build)
        if (response.code == 404 && missingOk) {
            response.close()
            return null
        }
        if (!response.isSuccessful) fail(response, what)
        return response.use { JSONObject(it.body?.string().orEmpty().ifEmpty { "{}" }) }
    }
}

/** Protects stored tokens; the app uses a hardware-backed key, tests a plain pass-through. */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray

    object Plain : Sealer {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }
}

/** Accounts and their tokens in one JSON file; tokens are sealed. */
class CloudStore(private val file: File, private val sealer: Sealer) {
    private class Entry(val account: CloudAccount, var tokens: Tokens?)

    private val entries = ArrayList<Entry>()

    init {
        try {
            if (file.exists()) {
                val array = JSONObject(file.readText()).optJSONArray("accounts") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val account = CloudAccount(
                        id = item.getString("id"), kind = CloudKind.parse(item.optString("kind")), name = item.optString("name"),
                        clientId = item.optString("client_id"), clientSecret = item.optString("client_secret"),
                        tenant = item.optString("tenant", "common"), user = item.optString("user"),
                    )
                    val tokens = item.optString("tokens").takeIf { it.isNotEmpty() }?.let { sealedText ->
                        try {
                            val json = JSONObject(String(sealer.open(Base64.getDecoder().decode(sealedText)), Charsets.UTF_8))
                            Tokens(json.getString("access"), json.getLong("expires"), json.optString("refresh").ifEmpty { null })
                        } catch (_: Exception) {
                            null
                        }
                    }
                    entries += Entry(account, tokens)
                }
            }
        } catch (_: Exception) {
            // A damaged file means signing in again, not a crash.
        }
    }

    @Synchronized
    fun accounts(): List<CloudAccount> = entries.map { it.account }

    @Synchronized
    fun tokens(id: String): Tokens? = entries.firstOrNull { it.account.id == id }?.tokens

    @Synchronized
    fun put(account: CloudAccount, tokens: Tokens?) {
        val existing = entries.indexOfFirst { it.account.id == account.id }
        if (existing >= 0) entries[existing] = Entry(account, tokens) else entries += Entry(account, tokens)
        save()
    }

    @Synchronized
    fun setTokens(id: String, tokens: Tokens?) {
        entries.firstOrNull { it.account.id == id }?.tokens = tokens
        save()
    }

    @Synchronized
    fun remove(id: String) {
        entries.removeAll { it.account.id == id }
        save()
    }

    private fun save() {
        val array = JSONArray()
        for (entry in entries) {
            val account = entry.account
            val item = JSONObject()
                .put("id", account.id).put("kind", account.kind.key).put("name", account.name)
                .put("client_id", account.clientId).put("client_secret", account.clientSecret)
                .put("tenant", account.tenant).put("user", account.user)
            entry.tokens?.let { tokens ->
                val json = JSONObject().put("access", tokens.access).put("expires", tokens.expiresAt).put("refresh", tokens.refresh ?: "")
                item.put("tokens", Base64.getEncoder().encodeToString(sealer.seal(json.toString().toByteArray())))
            }
            array.put(item)
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeText(JSONObject().put("accounts", array).toString(1))
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }
}

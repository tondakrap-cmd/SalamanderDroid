package cz.k4.salamanderdroid

import cz.k4.salamanderdroid.cloud.CloudAccount
import cz.k4.salamanderdroid.cloud.CloudEndpoints
import cz.k4.salamanderdroid.cloud.CloudFs
import cz.k4.salamanderdroid.cloud.CloudKind
import cz.k4.salamanderdroid.cloud.CloudSession
import cz.k4.salamanderdroid.cloud.CloudStore
import cz.k4.salamanderdroid.cloud.DriveFs
import cz.k4.salamanderdroid.cloud.GraphFs
import cz.k4.salamanderdroid.cloud.OAuth
import cz.k4.salamanderdroid.cloud.OAuthConfig
import cz.k4.salamanderdroid.cloud.Sealer
import cz.k4.salamanderdroid.cloud.Tokens
import cz.k4.salamanderdroid.core.Conflict
import cz.k4.salamanderdroid.core.ConflictAction
import cz.k4.salamanderdroid.core.EntryKind
import cz.k4.salamanderdroid.core.LocalFs
import cz.k4.salamanderdroid.core.OpListener
import cz.k4.salamanderdroid.core.Transfer
import cz.k4.salamanderdroid.core.deleteTrees
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory stand-in for the Microsoft and Google services: OAuth token endpoints, the part
 * of Microsoft Graph that OneDrive uses, and the part of the Google Drive API.
 */
class FakeCloud {
    class GNode(var name: String, val dir: Boolean) {
        var data = ByteArray(0)
        var modified = "2024-01-01T00:00:00Z"
        val children = LinkedHashMap<String, GNode>()
    }

    class DFile(val id: String, var name: String, val mime: String, var parent: String) {
        var data = ByteArray(0)
        var modified = "2024-01-01T00:00:00Z"
        var trashed = false
    }

    private class Upload(val commit: (ByteArray) -> JSONObject) {
        val buffer = java.io.ByteArrayOutputStream()
    }

    val graphRoot = GNode("root", true)
    val driveFiles = LinkedHashMap<String, DFile>()
    val validTokens = HashSet<String>()
    var issued = 0
    var refreshes = 0
    var pendingChallenge: String? = null
    var requests = ArrayList<String>()
    private val uploads = HashMap<String, Upload>()
    private var counter = 0
    /** One request and the response being built for it. */
    class HttpExchange(request: RecordedRequest) {
        val method: String = request.method.orEmpty()
        val path: String = request.requestUrl!!.encodedPath.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        val rawQuery: String = request.requestUrl!!.encodedQuery.orEmpty()
        val body: ByteArray = request.body.readByteArray()
        private val headers = request.headers
        var response = MockResponse().setResponseCode(500)
        fun header(name: String): String? = headers[name]
    }

    private val server = MockWebServer()
    val url: String get() = "http://127.0.0.1:${server.port}"
    val endpoints get() = CloudEndpoints("$url/login", "$url/graph", "$url/gauth", "$url/gtoken", "$url/drive/v3", "$url/upload/drive/v3")

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val exchange = HttpExchange(request)
                try {
                    handle(exchange)
                } catch (error: Exception) {
                    send(exchange, 500, JSONObject().put("error", JSONObject().put("message", error.toString())))
                }
                return exchange.response
            }
        }
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
    }

    fun stop() = server.shutdown()

    private fun send(exchange: HttpExchange, code: Int, json: JSONObject? = null, bytes: ByteArray? = null, headers: Map<String, String> = emptyMap()) {
        val body = bytes ?: json?.toString()?.toByteArray() ?: ByteArray(0)
        val response = MockResponse().setResponseCode(code)
        for ((key, value) in headers) response.addHeader(key, value)
        if (json != null) response.addHeader("Content-Type", "application/json")
        response.setBody(okio.Buffer().write(body))
        exchange.response = response
    }

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.rawQuery.split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun error(exchange: HttpExchange, code: Int, message: String) =
        send(exchange, code, JSONObject().put("error", JSONObject().put("message", message)))

    @Synchronized
    private fun handle(exchange: HttpExchange) {
        val path = exchange.path
        val method = exchange.method
        requests += "$method $path"
        val body = exchange.body
        if (path.endsWith("/oauth2/v2.0/token") || path == "/gtoken") return token(exchange, String(body))
        if (path.startsWith("/upload/session/")) return uploadChunk(exchange, body)
        val auth = exchange.header("Authorization").orEmpty().removePrefix("Bearer ")
        if (auth !in validTokens) return error(exchange, 401, "invalid token")
        when {
            path.startsWith("/graph/") -> graph(exchange, method, path.removePrefix("/graph"), body)
            path.startsWith("/drive/v3/") -> drive(exchange, method, path.removePrefix("/drive/v3"), body)
            path.startsWith("/upload/drive/v3/") -> driveUploadStart(exchange, method, path.removePrefix("/upload/drive/v3"), body)
            else -> error(exchange, 404, "no route")
        }
    }

    private fun token(exchange: HttpExchange, form: String) {
        val params = form.split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val ok = when (params["grant_type"]) {
            "authorization_code" -> params["code"] == "the-code" && OAuth.challengeOf(params["code_verifier"].orEmpty()) == pendingChallenge
            "refresh_token" -> (params["refresh_token"] == "refresh-1").also { if (it) refreshes++ }
            else -> false
        }
        if (!ok) return send(exchange, 400, JSONObject().put("error", "invalid_grant").put("error_description", "bad grant"))
        val access = "access-${++issued}"
        validTokens += access
        val json = JSONObject().put("access_token", access).put("expires_in", 3600)
        if (params["grant_type"] == "authorization_code") json.put("refresh_token", "refresh-1")
        send(exchange, 200, json)
    }

    private fun uploadChunk(exchange: HttpExchange, body: ByteArray) {
        val id = exchange.path.substringAfterLast('/')
        val upload = uploads[id] ?: return error(exchange, 404, "no session")
        if (exchange.method == "DELETE") {
            uploads.remove(id)
            return send(exchange, 204)
        }
        // Graph upload URLs must be used without a bearer token.
        if (id.startsWith("g") && exchange.header("Authorization") != null) return error(exchange, 401, "no auth on upload url")
        val range = exchange.header("Content-Range").orEmpty().removePrefix("bytes ")
        val total = range.substringAfter('/')
        val span = range.substringBefore('/')
        if (span != "*") {
            val start = span.substringBefore('-').toLong()
            if (start != upload.buffer.size().toLong()) return error(exchange, 416, "wrong offset $start")
            if (body.size.toLong() != span.substringAfter('-').toLong() - start + 1) return error(exchange, 400, "length mismatch")
            upload.buffer.write(body)
        }
        if (total != "*" && total.toLong() == upload.buffer.size().toLong()) {
            uploads.remove(id)
            send(exchange, 201, upload.commit(upload.buffer.toByteArray()))
        } else {
            send(exchange, if (id.startsWith("g")) 202 else 308, if (id.startsWith("g")) JSONObject() else null)
        }
    }

    // ---- Microsoft Graph ----

    private fun graphFind(path: String): GNode? {
        var node: GNode? = graphRoot
        for (part in path.split('/').filter { it.isNotEmpty() }) node = node?.children?.get(part)
        return node
    }

    private fun graphJson(node: GNode): JSONObject {
        val json = JSONObject().put("name", node.name).put("size", node.data.size)
            .put("lastModifiedDateTime", node.modified)
            .put("fileSystemInfo", JSONObject().put("lastModifiedDateTime", node.modified))
        if (node.dir) json.put("folder", JSONObject().put("childCount", node.children.size)) else json.put("file", JSONObject())
        return json
    }

    private fun graph(exchange: HttpExchange, method: String, path: String, body: ByteArray) {
        if (path == "/me") return send(exchange, 200, JSONObject().put("userPrincipalName", "user@example.com"))
        if (path == "/me/drive") return send(exchange, 200, JSONObject().put("quota", JSONObject().put("total", 1000).put("remaining", 400)))
        val rest = path.removePrefix("/me/drive/root")
        val (itemPath, suffix) = when {
            rest.startsWith(":/") -> {
                // "root:/a/b" and "root:/a/b:" address the item, "root:/a/b:/children" a facet of it.
                val inner = rest.removePrefix(":")
                val facet = listOf("children", "content", "createUploadSession").firstOrNull { inner.endsWith(":/$it") }
                if (facet != null) inner.removeSuffix(":/$facet") to facet else inner.removeSuffix(":") to ""
            }
            else -> "" to rest.removePrefix("/")
        }
        val node = graphFind(itemPath)
        val parent = graphFind(itemPath.substringBeforeLast('/', ""))
        val name = itemPath.substringAfterLast('/')
        when {
            suffix == "children" && method == "GET" -> {
                node ?: return error(exchange, 404, "not found")
                val all = node.children.values.toList()
                val skip = query(exchange)["skip"]?.toInt() ?: 0
                val page = all.drop(skip).take(2)
                val json = JSONObject().put("value", JSONArray(page.map(::graphJson)))
                if (skip + 2 < all.size) json.put("@odata.nextLink", "$url/graph$path?skip=${skip + 2}")
                send(exchange, 200, json)
            }
            suffix == "children" && method == "POST" -> {
                node ?: return error(exchange, 404, "not found")
                val newName = JSONObject(String(body)).getString("name")
                if (node.children.containsKey(newName)) return error(exchange, 409, "nameAlreadyExists")
                node.children[newName] = GNode(newName, true)
                send(exchange, 201, graphJson(node.children.getValue(newName)))
            }
            suffix == "content" && method == "GET" -> {
                node ?: return error(exchange, 404, "not found")
                send(exchange, 200, bytes = node.data)
            }
            suffix == "content" && method == "PUT" -> {
                parent ?: return error(exchange, 404, "no parent")
                val file = parent.children.getOrPut(name) { GNode(name, false) }
                file.data = body
                send(exchange, 201, graphJson(file))
            }
            suffix == "createUploadSession" -> {
                parent ?: return error(exchange, 404, "no parent")
                val id = "g${++counter}"
                uploads[id] = Upload { data ->
                    val file = parent.children.getOrPut(name) { GNode(name, false) }
                    file.data = data
                    graphJson(file)
                }
                send(exchange, 200, JSONObject().put("uploadUrl", "$url/upload/session/$id"))
            }
            suffix.isEmpty() && method == "GET" -> if (node == null) error(exchange, 404, "itemNotFound") else send(exchange, 200, graphJson(node))
            suffix.isEmpty() && method == "DELETE" -> {
                if (node == null || parent == null) return error(exchange, 404, "itemNotFound")
                parent.children.remove(name)
                send(exchange, 204)
            }
            suffix.isEmpty() && method == "PATCH" -> {
                if (node == null || parent == null) return error(exchange, 404, "itemNotFound")
                val patch = JSONObject(String(body))
                patch.optJSONObject("fileSystemInfo")?.let { node.modified = it.getString("lastModifiedDateTime") }
                if (patch.has("name")) {
                    val target = patch.optJSONObject("parentReference")?.let { graphFind(it.getString("path").removePrefix("/drive/root:")) } ?: parent
                    parent.children.remove(name)
                    node.name = patch.getString("name")
                    target.children[node.name] = node
                }
                send(exchange, 200, graphJson(node))
            }
            else -> error(exchange, 404, "no route $method $path")
        }
    }

    // ---- Google Drive ----

    private fun driveJson(file: DFile): JSONObject {
        val json = JSONObject().put("id", file.id).put("name", file.name).put("mimeType", file.mime).put("modifiedTime", file.modified)
        if (!file.mime.startsWith("application/vnd.google-apps.")) json.put("size", file.data.size.toString())
        return json
    }

    fun driveAdd(name: String, mime: String, parent: String, data: ByteArray = ByteArray(0)): DFile {
        val file = DFile("id${++counter}", name, mime, parent)
        file.data = data
        driveFiles[file.id] = file
        return file
    }

    private fun drive(exchange: HttpExchange, method: String, path: String, body: ByteArray) {
        val params = query(exchange)
        if (path == "/about") {
            return send(
                exchange, 200,
                JSONObject().put("user", JSONObject().put("emailAddress", "user@gmail.com"))
                    .put("storageQuota", JSONObject().put("limit", "1000").put("usage", "250")),
            )
        }
        if (path == "/files" && method == "GET") {
            val q = params.getValue("q")
            val parent = Regex("^'((?:[^'\\\\]|\\\\.)*)' in parents and trashed=false$").matchEntire(q)?.groupValues?.get(1)
                ?: return error(exchange, 400, "unsupported query $q")
            val all = driveFiles.values.filter { it.parent == parent && !it.trashed }
            val skip = params["pageToken"]?.toInt() ?: 0
            val json = JSONObject().put("files", JSONArray(all.drop(skip).take(2).map(::driveJson)))
            if (skip + 2 < all.size) json.put("nextPageToken", (skip + 2).toString())
            return send(exchange, 200, json)
        }
        if (path == "/files" && method == "POST") {
            val meta = JSONObject(String(body))
            val file = driveAdd(meta.getString("name"), meta.optString("mimeType", "application/octet-stream"), meta.getJSONArray("parents").getString(0))
            return send(exchange, 200, driveJson(file))
        }
        val id = path.removePrefix("/files/").substringBefore('/')
        val file = driveFiles[id] ?: return error(exchange, 404, "File not found: $id")
        when {
            path.endsWith("/export") -> send(exchange, 200, bytes = "exported:${params["mimeType"]}".toByteArray())
            method == "GET" && params["alt"] == "media" -> send(exchange, 200, bytes = file.data)
            method == "PATCH" -> {
                val patch = JSONObject(String(body))
                if (patch.has("name")) file.name = patch.getString("name")
                if (patch.has("trashed")) file.trashed = patch.getBoolean("trashed")
                if (patch.has("modifiedTime")) file.modified = patch.getString("modifiedTime")
                params["addParents"]?.let {
                    if (params["removeParents"] != file.parent) return error(exchange, 400, "wrong removeParents")
                    file.parent = it
                }
                send(exchange, 200, driveJson(file))
            }
            else -> error(exchange, 404, "no route $method $path")
        }
    }

    private fun driveUploadStart(exchange: HttpExchange, method: String, path: String, body: ByteArray) {
        if (query(exchange)["uploadType"] != "resumable") return error(exchange, 400, "uploadType")
        val id = "d${++counter}"
        if (method == "POST" && path == "/files") {
            val meta = JSONObject(String(body))
            uploads[id] = Upload { data ->
                driveJson(driveAdd(meta.getString("name"), "application/octet-stream", meta.getJSONArray("parents").getString(0), data))
            }
        } else if (method == "PATCH") {
            val file = driveFiles[path.removePrefix("/files/")] ?: return error(exchange, 404, "File not found")
            uploads[id] = Upload { data ->
                file.data = data
                driveJson(file)
            }
        } else {
            return error(exchange, 404, "no route")
        }
        send(exchange, 200, headers = mapOf("Location" to "$url/upload/session/$id"))
    }
}

class CloudTest {
    @get:Rule
    val temp = TemporaryFolder()
    private lateinit var fake: FakeCloud
    private val http = OkHttpClient()

    @Before
    fun start() {
        fake = FakeCloud()
    }

    @After
    fun stop() = fake.stop()

    private fun account(kind: CloudKind) = CloudAccount("acc-${kind.key}", kind, "Test", "client-id", if (kind == CloudKind.GoogleDrive) "secret" else "")

    /** Stands in for the browser: follows the authorization URL straight to the loopback redirect. */
    private fun browser(expectHost: String): (String) -> Unit = { authUrl ->
        val params = authUrl.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("S256", params["code_challenge_method"])
        assertEquals("client-id", params["client_id"])
        fake.pendingChallenge = params["code_challenge"]
        val redirect = params.getValue("redirect_uri")
        assertTrue(redirect, redirect.startsWith("http://$expectHost:"))
        // A request with the wrong state must not complete the sign-in.
        http.newCall(Request.Builder().url("${redirect.replace("localhost", "127.0.0.1")}/?code=evil&state=wrong").build()).execute().close()
        Thread {
            Thread.sleep(100)
            http.newCall(Request.Builder().url("${redirect.replace("localhost", "127.0.0.1")}/?code=the-code&state=${params["state"]}").build()).execute().use {
                assertTrue(it.body!!.string().contains("Přihlášení dokončeno"))
            }
        }.start()
    }

    private fun signIn(kind: CloudKind, store: CloudStore? = null): CloudFs {
        val account = account(kind)
        val config = OAuthConfig.of(account, fake.endpoints)
        val tokens = OAuth.authorize(http, config, browser(if (kind == CloudKind.OneDrive) "localhost" else "127.0.0.1"), AtomicBoolean(false), 20)
        assertEquals("refresh-1", tokens.refresh)
        store?.put(account, tokens)
        val session = CloudSession(http, config, tokens) { store?.setTokens(account.id, it) }
        return when (kind) {
            CloudKind.OneDrive -> GraphFs(account, session, temp.newFolder(), fake.endpoints.graph)
            CloudKind.GoogleDrive -> DriveFs(account, session, temp.newFolder(), fake.endpoints.drive, fake.endpoints.driveUpload)
        }
    }

    private fun quiet(action: ConflictAction = ConflictAction.Overwrite) = object : OpListener {
        override fun conflict(conflict: Conflict) = action
    }

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    /** The same scenario must work on both services. */
    private fun scenario(fs: CloudFs, bigSize: Int) {
        assertEquals(emptyList<String>(), fs.list("/").map { it.name })
        assertNull(fs.stat("/missing"))

        val local = temp.newFolder()
        File(local, "tree/sub/deep").mkdirs()
        File(local, "tree/a.txt").writeText("alpha")
        File(local, "tree/b.txt").writeText("beta")
        File(local, "tree/Příliš žluťoučký & #1.txt").writeText("diakritika")
        File(local, "tree/empty.bin").writeBytes(ByteArray(0))
        File(local, "tree/sub/deep/big.bin").writeBytes(bytes(bigSize, 7))
        File(local, "tree/a.txt").setLastModified(1_600_000_000_000)

        val up = Transfer(fs, quiet(), AtomicBoolean(false)).copyTree(LocalFs, listOf(File(local, "tree").path), "/", false)
        assertTrue(up.errors.toString(), up.errors.isEmpty())
        // Five entries with a page size of two: the listing must follow the paging links.
        val listed = fs.list("/tree").associateBy { it.name }
        assertEquals(setOf("a.txt", "b.txt", "Příliš žluťoučký & #1.txt", "empty.bin", "sub"), listed.keys)
        assertEquals(EntryKind.Directory, listed.getValue("sub").kind)
        assertEquals(5L, listed.getValue("a.txt").size)
        assertEquals(1_600_000_000_000, listed.getValue("a.txt").modified)
        assertEquals(bigSize.toLong(), fs.stat("/tree/sub/deep/big.bin")!!.size)

        val back = temp.newFolder()
        val down = Transfer(LocalFs, quiet(), AtomicBoolean(false)).copyTree(fs, listOf("/tree"), back.path, false)
        assertTrue(down.errors.toString(), down.errors.isEmpty())
        assertArrayEquals(bytes(bigSize, 7), File(back, "tree/sub/deep/big.bin").readBytes())
        assertEquals("diakritika", File(back, "tree/Příliš žluťoučký & #1.txt").readText())
        assertEquals(0, File(back, "tree/empty.bin").length())

        // Overwrite replaces the content; a cancelled upload leaves the old file alone.
        val newer = File(local, "a.txt").apply { writeText("ALPHA-NEW") }
        assertTrue(Transfer(fs, quiet(), AtomicBoolean(false)).copyTree(LocalFs, listOf(newer.path), "/tree", false).errors.isEmpty())
        assertEquals("ALPHA-NEW", fs.openRead("/tree/a.txt").use { String(it.readBytes()) })
        val cancel = AtomicBoolean(false)
        val transfer = Transfer(fs, quiet(), cancel)
        val ok = transfer.putFile("src", "/tree/a.txt", 100, null, null) {
            object : java.io.InputStream() {
                override fun read(): Int {
                    cancel.set(true)
                    return 1
                }
            }
        }
        assertFalse(ok)
        assertEquals("ALPHA-NEW", fs.openRead("/tree/a.txt").use { String(it.readBytes()) })

        // Unknown size (new empty file, editor saves).
        fs.openWrite("/tree/note.txt").use { it.write("poznámka".toByteArray()) }
        assertEquals("poznámka", fs.openRead("/tree/note.txt").use { String(it.readBytes()) })

        // Rename in place, move to another folder, set time, create folders.
        fs.rename("/tree/b.txt", "/tree/c.txt")
        assertNull(fs.stat("/tree/b.txt"))
        fs.mkdir("/moved")
        assertTrue(Transfer(fs, quiet(), AtomicBoolean(false)).copyTree(fs, listOf("/tree/c.txt", "/tree/sub"), "/moved", true).errors.isEmpty())
        assertEquals(setOf("c.txt", "sub"), fs.list("/moved").map { it.name }.toSet())
        assertEquals("beta", fs.openRead("/moved/c.txt").use { String(it.readBytes()) })
        assertNull(fs.stat("/tree/sub"))
        assertEquals(bigSize.toLong(), fs.stat("/moved/sub/deep/big.bin")!!.size)
        fs.setModified("/moved/c.txt", 1_700_000_000_000)
        assertEquals(1_700_000_000_000, fs.stat("/moved/c.txt")!!.modified)

        assertTrue(deleteTrees(fs, listOf("/tree", "/moved"), AtomicBoolean(false)).isEmpty())
        assertEquals(emptyList<String>(), fs.list("/").map { it.name })
        try {
            fs.list("/nowhere")
            fail("listed a missing folder")
        } catch (_: IOException) {
        }
    }

    @Test
    fun oneDriveScenario() {
        val fs = signIn(CloudKind.OneDrive)
        assertEquals("user@example.com", fs.userName())
        assertEquals(400L to 1000L, fs.space())
        // Larger than the simple-upload limit and one chunk, so the upload session is used.
        scenario(fs, 6 * 1024 * 1024)
        assertTrue(fake.requests.any { it.contains("createUploadSession") })
    }

    @Test
    fun googleDriveScenario() {
        val fs = signIn(CloudKind.GoogleDrive)
        assertEquals("user@gmail.com", fs.userName())
        assertEquals(750L to 1000L, fs.space())
        scenario(fs, 9 * 1024 * 1024)
        // Deleting moves to the trash instead of destroying.
        assertTrue(fake.driveFiles.values.any { it.trashed })
    }

    @Test
    fun googleDocsDuplicatesAndSpecialItems() {
        val fs = signIn(CloudKind.GoogleDrive)
        fake.driveAdd("Report", "application/vnd.google-apps.document", "root")
        fake.driveAdd("same.txt", "text/plain", "root", "one".toByteArray())
        fake.driveAdd("same.txt", "text/plain", "root", "two".toByteArray())
        fake.driveAdd("Form", "application/vnd.google-apps.form", "root")
        fake.driveAdd("it's", "application/vnd.google-apps.folder", "root")
        val listed = fs.list("/").associateBy { it.name }
        assertEquals(setOf("Report.docx", "same.txt", "same.txt (2)", "Form", "it's"), listed.keys)
        assertEquals(EntryKind.Other, listed.getValue("Form").kind)
        assertTrue(fs.openRead("/Report.docx").use { String(it.readBytes()) }.contains("wordprocessingml"))
        assertEquals("two", fs.openRead("/same.txt (2)").use { String(it.readBytes()) })
        fs.openWrite("/it's/x.txt", 1).use { it.write(65) }
        assertEquals(listOf("x.txt"), fs.list("/it's").map { it.name })
        fs.rename("/Report.docx", "/Renamed.docx")
        assertEquals("Renamed", fake.driveFiles.values.first { it.mime.endsWith("document") }.name)
    }

    @Test
    fun refreshesExpiredTokensAndPersistsThem() {
        val file = File(temp.root, "cloud.json")
        val store = CloudStore(file, Sealer.Plain)
        val fs = signIn(CloudKind.OneDrive, store)
        fs.list("/")
        val first = store.tokens("acc-onedrive")!!.access
        fake.validTokens.clear()
        fs.list("/")
        assertEquals(1, fake.refreshes)
        val second = CloudStore(file, Sealer.Plain)
        assertTrue(second.tokens("acc-onedrive")!!.access != first)
        assertEquals("refresh-1", second.tokens("acc-onedrive")!!.refresh)
        assertEquals(account(CloudKind.OneDrive), second.accounts().single())
        second.remove("acc-onedrive")
        assertTrue(CloudStore(file, Sealer.Plain).accounts().isEmpty())
    }

    @Test
    fun signInCanBeCancelledAndValidatesAccounts() {
        val cancel = AtomicBoolean(false)
        try {
            OAuth.authorize(http, OAuthConfig.of(account(CloudKind.OneDrive), fake.endpoints), { cancel.set(true) }, cancel, 20)
            fail("sign-in finished without a browser")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("zrušeno"))
        }
        assertNotNull(CloudAccount("x", CloudKind.GoogleDrive, "n", "id").validate())
        assertNotNull(CloudAccount("x", CloudKind.OneDrive, "n", "").validate())
        assertNull(CloudAccount("x", CloudKind.OneDrive, "n", "id").validate())
        assertEquals(Instant.parse("2024-01-01T00:00:00Z").toEpochMilli(), 1704067200000)
    }
}

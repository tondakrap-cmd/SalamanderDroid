package cz.k4.salamanderdroid.cloud

import cz.k4.salamanderdroid.core.EntryKind
import cz.k4.salamanderdroid.core.FileEntry
import cz.k4.salamanderdroid.core.Fs
import cz.k4.salamanderdroid.core.normalizePath
import cz.k4.salamanderdroid.core.parentPath
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val BINARY = "application/octet-stream".toMediaType()

private fun JSONObject.body(): RequestBody = toString().toRequestBody(JSON_TYPE)

private fun parseTime(text: String?): Long? = try {
    if (text.isNullOrEmpty()) null else Instant.parse(text).toEpochMilli()
} catch (_: Exception) {
    null
}

private fun formatInstant(millis: Long): String = Instant.ofEpochMilli(millis / 1000 * 1000).toString()

/** A cloud drive seen as a file system; [space] is (free, total) when the service reports it. */
abstract class CloudFs(val account: CloudAccount, protected val session: CloudSession, protected val tempDir: File) : Fs {
    override val directWrites: Boolean get() = true
    override fun sameFs(other: Fs): Boolean = other is CloudFs && other.account.id == account.id
    override fun openWrite(path: String): OutputStream = openWrite(path, -1)
    abstract fun space(): Pair<Long, Long>?

    /** E-mail or name of the signed-in user. */
    abstract fun userName(): String

    /**
     * Collects written bytes into chunks of [chunkSize] and hands each full chunk to [send]
     * (data, length, offset, isLast). With a declared [size], closing early aborts instead
     * of committing a truncated file.
     */
    protected inner class ChunkedOutput(
        private val size: Long,
        private val chunkSize: Int,
        private val send: (ByteArray, Int, Long, Boolean) -> Unit,
        private val abort: () -> Unit,
    ) : OutputStream() {
        private val buffer = ByteArray(chunkSize)
        private var filled = 0
        private var offset = 0L
        private var closed = false

        override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

        override fun write(data: ByteArray, start: Int, length: Int) {
            // More data than announced must never be committed as if it were the whole file.
            if (size >= 0 && offset + filled + length > size) {
                closed = true
                abort()
                throw IOException("Soubor se během nahrávání změnil")
            }
            var position = start
            var left = length
            while (left > 0) {
                if (filled == chunkSize) flushChunk(false)
                val count = minOf(left, chunkSize - filled)
                System.arraycopy(data, position, buffer, filled, count)
                filled += count
                position += count
                left -= count
            }
        }

        private fun flushChunk(last: Boolean) {
            send(buffer, filled, offset, last)
            offset += filled
            filled = 0
        }

        override fun close() {
            if (closed) return
            closed = true
            if (size >= 0 && offset + filled != size) {
                abort()
                throw IOException("Nahrávání bylo přerušeno")
            }
            flushChunk(true)
        }
    }

    /** For uploads that need the total size up front: spools to a temporary file first. */
    protected fun spooled(upload: (File) -> Unit): OutputStream {
        tempDir.mkdirs()
        val file = File.createTempFile("upload", ".tmp", tempDir)
        return object : FileOutputStream(file) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                super.close()
                try {
                    upload(file)
                } finally {
                    file.delete()
                }
            }
        }
    }
}

// ---- OneDrive (Microsoft Graph) ----

class GraphFs(account: CloudAccount, session: CloudSession, tempDir: File, private val base: String) : CloudFs(account, session, tempDir) {
    private companion object {
        const val SELECT = "name,size,folder,file,lastModifiedDateTime,fileSystemInfo"
        const val SIMPLE_LIMIT = 4L * 1024 * 1024
        const val CHUNK = 16 * 320 * 1024
    }

    /** Address of an item by path; the root has its own form. */
    private fun item(path: String, suffix: String = ""): String {
        val clean = normalizePath(path).trim('/')
        return if (clean.isEmpty()) {
            "$base/me/drive/root" + (if (suffix.isEmpty()) "" else "/$suffix")
        } else {
            "$base/me/drive/root:/" + clean.split('/').joinToString("/") { urlEncode(it) } + (if (suffix.isEmpty()) "" else ":/$suffix")
        }
    }

    private fun entryOf(json: JSONObject): FileEntry {
        val folder = json.has("folder")
        return FileEntry(
            name = json.getString("name"),
            kind = if (folder) EntryKind.Directory else if (json.has("file")) EntryKind.File else EntryKind.Other,
            size = if (folder) 0 else json.optLong("size"),
            modified = parseTime(json.optJSONObject("fileSystemInfo")?.optString("lastModifiedDateTime"))
                ?: parseTime(json.optString("lastModifiedDateTime")),
        )
    }

    override fun list(dir: String): List<FileEntry> {
        val entries = ArrayList<FileEntry>()
        var url: String? = item(dir, "children") + "?\$top=200&\$select=$SELECT"
        while (url != null) {
            val page = session.json(dir) { Request.Builder().url(url!!) }!!
            val values = page.optJSONArray("value") ?: JSONArray()
            for (index in 0 until values.length()) entries += entryOf(values.getJSONObject(index))
            url = page.optString("@odata.nextLink").ifEmpty { null }
        }
        return entries
    }

    override fun stat(path: String): FileEntry? =
        session.json(path, missingOk = true) { Request.Builder().url(item(path) + "?\$select=$SELECT") }?.let(::entryOf)

    override fun openRead(path: String): InputStream {
        val response = session.execute { Request.Builder().url(item(path, "content")) }
        if (!response.isSuccessful) session.fail(response, path)
        return response.body!!.byteStream()
    }

    override fun openWrite(path: String, size: Long): OutputStream = when {
        size < 0 -> spooled { file -> openWrite(path, file.length()).use { out -> FileInputStream(file).use { it.copyTo(out, 1 shl 16) } } }
        size <= SIMPLE_LIMIT -> ChunkedOutput(size, maxOf(size.toInt(), 1), { data, length, _, _ ->
            val response = session.execute { Request.Builder().url(item(path, "content")).put(data.toRequestBody(BINARY, 0, length)) }
            if (!response.isSuccessful) session.fail(response, path)
            response.close()
        }, {})
        else -> {
            val created = session.json(path) {
                Request.Builder().url(item(path, "createUploadSession"))
                    .post(JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace")).body())
            }!!
            val uploadUrl = created.getString("uploadUrl")
            ChunkedOutput(size, CHUNK, { data, length, offset, _ ->
                // The upload URL carries its own authorization; a bearer token must not be sent.
                val response = session.execute(auth = false) {
                    Request.Builder().url(uploadUrl)
                        .header("Content-Range", "bytes $offset-${offset + length - 1}/$size")
                        .put(data.toRequestBody(BINARY, 0, length))
                }
                if (!response.isSuccessful) session.fail(response, path)
                response.close()
            }, {
                try {
                    session.execute(auth = false) { Request.Builder().url(uploadUrl).delete() }.close()
                } catch (_: IOException) {
                }
            })
        }
    }

    override fun mkdir(path: String) {
        val parent = parentPath(normalizePath(path)) ?: throw IOException("Kořen nelze vytvořit")
        val body = JSONObject().put("name", normalizePath(path).substringAfterLast('/')).put("folder", JSONObject())
            .put("@microsoft.graph.conflictBehavior", "fail")
        session.json(path) { Request.Builder().url(item(parent, "children")).post(body.body()) }
    }

    private fun delete(path: String) {
        val response = session.execute { Request.Builder().url(item(path)).delete() }
        if (!response.isSuccessful) session.fail(response, path)
        response.close()
    }

    override fun deleteFile(path: String) = delete(path)
    override fun deleteDir(path: String) = delete(path)

    override fun rename(from: String, to: String) {
        val target = normalizePath(to)
        val body = JSONObject().put("name", target.substringAfterLast('/'))
        val newParent = parentPath(target) ?: "/"
        if (newParent != (parentPath(normalizePath(from)) ?: "/")) {
            body.put("parentReference", JSONObject().put("path", "/drive/root:" + newParent.trimEnd('/')))
        }
        session.json("$from → $to") { Request.Builder().url(item(from)).patch(body.body()) }
    }

    override fun setModified(path: String, millis: Long) {
        val body = JSONObject().put("fileSystemInfo", JSONObject().put("lastModifiedDateTime", formatInstant(millis)))
        session.json(path) { Request.Builder().url(item(path)).patch(body.body()) }
    }

    override fun space(): Pair<Long, Long>? = try {
        val quota = session.json("drive") { Request.Builder().url("$base/me/drive?\$select=quota") }!!.optJSONObject("quota")
        quota?.takeIf { it.optLong("total") > 0 }?.let { it.optLong("remaining") to it.optLong("total") }
    } catch (_: IOException) {
        null
    }

    override fun userName(): String {
        val me = session.json("me") { Request.Builder().url("$base/me?\$select=userPrincipalName,mail,displayName") }!!
        return me.optString("mail").ifEmpty { me.optString("userPrincipalName").ifEmpty { me.optString("displayName") } }
    }
}

// ---- Google Drive ----

class DriveFs(
    account: CloudAccount,
    session: CloudSession,
    tempDir: File,
    private val base: String,
    private val uploadBase: String,
) : CloudFs(account, session, tempDir) {
    private companion object {
        const val FOLDER = "application/vnd.google-apps.folder"
        const val FIELDS = "id,name,mimeType,size,modifiedTime"
        const val CHUNK = 8 * 1024 * 1024
        const val CACHE_MS = 30_000L

        /** Google's own document types are downloaded converted: type → (extension, export type). */
        val EXPORTS = mapOf(
            "application/vnd.google-apps.document" to ("docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "application/vnd.google-apps.spreadsheet" to ("xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            "application/vnd.google-apps.presentation" to ("pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            "application/vnd.google-apps.drawing" to ("pdf" to "application/pdf"),
        )
    }

    private class Node(val id: String, val mime: String, val realName: String, val entry: FileEntry)

    private class Cached(val at: Long, val nodes: Map<String, Node>)

    /** Directory path → children by displayed name. Drive addresses files by id, not path. */
    private val cache = HashMap<String, Cached>()
    private val root = Node("root", FOLDER, "", FileEntry("", EntryKind.Directory))

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("'", "\\'")

    private fun load(dir: String, node: Node): Map<String, Node> {
        val nodes = LinkedHashMap<String, Node>()
        var token: String? = null
        do {
            val url = "$base/files?q=" + urlEncode("'${escape(node.id)}' in parents and trashed=false") +
                "&pageSize=1000&fields=" + urlEncode("nextPageToken,files($FIELDS)") + (token?.let { "&pageToken=" + urlEncode(it) } ?: "")
            val page = session.json(dir) { Request.Builder().url(url) }!!
            val files = page.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val mime = file.optString("mimeType")
                val realName = file.getString("name").replace('/', '_')
                val export = EXPORTS[mime]
                var name = if (export != null) "$realName.${export.first}" else realName
                // Drive allows equal names in one folder; panels need them distinct.
                var counter = 2
                val wanted = name
                while (nodes.containsKey(name)) name = "$wanted ($counter)".also { counter++ }
                val kind = when {
                    mime == FOLDER -> EntryKind.Directory
                    export != null || !mime.startsWith("application/vnd.google-apps.") -> EntryKind.File
                    else -> EntryKind.Other
                }
                nodes[name] = Node(
                    file.getString("id"), mime, realName,
                    FileEntry(name, kind, if (kind == EntryKind.File) file.optString("size").toLongOrNull() ?: 0 else 0, parseTime(file.optString("modifiedTime"))),
                )
            }
            token = page.optString("nextPageToken").ifEmpty { null }
        } while (token != null)
        synchronized(cache) { cache[dir] = Cached(System.currentTimeMillis(), nodes) }
        return nodes
    }

    private fun childrenOf(dir: String, fresh: Boolean = false): Map<String, Node>? {
        val path = normalizePath(dir)
        if (!fresh) {
            synchronized(cache) { cache[path] }?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }?.let { return it.nodes }
        }
        val node = nodeOf(path) ?: return null
        if (node.mime != FOLDER) throw IOException("Není složka: $dir")
        return load(path, node)
    }

    private fun nodeOf(path: String): Node? {
        val clean = normalizePath(path)
        if (clean == "/") return root
        return childrenOf(parentPath(clean) ?: "/")?.get(clean.substringAfterLast('/'))
    }

    private fun need(path: String): Node = nodeOf(path) ?: throw CloudException(404, "Soubor nebo složka neexistuje: $path")

    private fun invalidate(vararg paths: String) = synchronized(cache) {
        for (path in paths) cache.remove(parentPath(normalizePath(path)) ?: "/")
    }

    override fun list(dir: String): List<FileEntry> =
        (childrenOf(dir, fresh = true) ?: throw CloudException(404, "Složka neexistuje: $dir")).values.map { it.entry }

    override fun stat(path: String): FileEntry? = nodeOf(path)?.entry

    override fun openRead(path: String): InputStream {
        val node = need(path)
        val export = EXPORTS[node.mime]
        val url = if (export != null) "$base/files/${node.id}/export?mimeType=${urlEncode(export.second)}" else "$base/files/${node.id}?alt=media"
        val response = session.execute { Request.Builder().url(url) }
        if (!response.isSuccessful) session.fail(response, path)
        return response.body!!.byteStream()
    }

    override fun openWrite(path: String, size: Long): OutputStream {
        val clean = normalizePath(path)
        val existing = nodeOf(clean)
        val parent = need(parentPath(clean) ?: "/")
        val start = session.execute {
            val builder = Request.Builder().header("X-Upload-Content-Type", "application/octet-stream")
            if (existing != null) {
                builder.url("$uploadBase/files/${existing.id}?uploadType=resumable").patch(JSONObject().body())
            } else {
                val meta = JSONObject().put("name", clean.substringAfterLast('/')).put("parents", JSONArray().put(parent.id))
                builder.url("$uploadBase/files?uploadType=resumable").post(meta.body())
            }
        }
        if (!start.isSuccessful) session.fail(start, path)
        val uploadUrl = start.header("Location") ?: run {
            start.close()
            throw IOException("Server nevrátil adresu pro nahrávání")
        }
        start.close()
        return ChunkedOutput(size, CHUNK, { data, length, offset, last ->
            val total = if (last) (offset + length).toString() else "*"
            val range = if (length == 0) "bytes */$total" else "bytes $offset-${offset + length - 1}/$total"
            if (length > 0 || last) {
                val response = session.execute {
                    Request.Builder().url(uploadUrl).header("Content-Range", range).put(data.toRequestBody(BINARY, 0, length))
                }
                // 308 means "chunk stored, continue"; the final chunk answers 200 or 201.
                if (!response.isSuccessful && response.code != 308) session.fail(response, path)
                response.close()
            }
            if (last) invalidate(clean)
        }, {
            try {
                session.execute { Request.Builder().url(uploadUrl).delete() }.close()
            } catch (_: IOException) {
            }
        })
    }

    override fun mkdir(path: String) {
        val clean = normalizePath(path)
        val parent = need(parentPath(clean) ?: "/")
        val meta = JSONObject().put("name", clean.substringAfterLast('/')).put("mimeType", FOLDER).put("parents", JSONArray().put(parent.id))
        session.json(path) { Request.Builder().url("$base/files?fields=id").post(meta.body()) }
        invalidate(clean)
    }

    /** Moves the item to the Drive trash rather than deleting it for good. */
    private fun trash(path: String) {
        val node = need(path)
        session.json(path) { Request.Builder().url("$base/files/${node.id}?fields=id").patch(JSONObject().put("trashed", true).body()) }
        invalidate(path)
        synchronized(cache) { cache.remove(normalizePath(path)) }
    }

    override fun deleteFile(path: String) = trash(path)
    override fun deleteDir(path: String) = trash(path)

    override fun rename(from: String, to: String) {
        val source = normalizePath(from)
        val target = normalizePath(to)
        val node = need(source)
        var name = target.substringAfterLast('/')
        EXPORTS[node.mime]?.let { name = name.removeSuffix(".${it.first}") }
        var url = "$base/files/${node.id}?fields=id"
        val oldParent = parentPath(source) ?: "/"
        val newParent = parentPath(target) ?: "/"
        if (oldParent != newParent) url += "&addParents=${need(newParent).id}&removeParents=${need(oldParent).id}"
        session.json("$from → $to") { Request.Builder().url(url).patch(JSONObject().put("name", name).body()) }
        invalidate(source, target)
        synchronized(cache) { cache.keys.removeAll { it == source || it.startsWith("$source/") } }
    }

    override fun setModified(path: String, millis: Long) {
        val node = need(path)
        session.json(path) {
            Request.Builder().url("$base/files/${node.id}?fields=id").patch(JSONObject().put("modifiedTime", formatInstant(millis)).body())
        }
        invalidate(path)
    }

    override fun space(): Pair<Long, Long>? = try {
        val quota = session.json("about") { Request.Builder().url("$base/about?fields=" + urlEncode("storageQuota(limit,usage)")) }!!
            .optJSONObject("storageQuota")
        val limit = quota?.optString("limit")?.toLongOrNull()
        val usage = quota?.optString("usage")?.toLongOrNull() ?: 0
        if (limit != null && limit > 0) maxOf(limit - usage, 0) to limit else null
    } catch (_: IOException) {
        null
    }

    override fun userName(): String =
        session.json("about") { Request.Builder().url("$base/about?fields=" + urlEncode("user(emailAddress,displayName)")) }!!
            .optJSONObject("user")?.let { it.optString("emailAddress").ifEmpty { it.optString("displayName") } }.orEmpty()
}

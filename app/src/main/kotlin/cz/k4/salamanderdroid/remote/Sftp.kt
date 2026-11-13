package cz.k4.salamanderdroid.remote

import cz.k4.salamanderdroid.core.EntryKind
import cz.k4.salamanderdroid.core.FileEntry
import cz.k4.salamanderdroid.core.Fs
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet

class PasswordAnswer(val password: CharArray, val save: Boolean)

/** Questions the connection layer asks the user; called on a worker thread and may block. */
interface RemotePrompts {
    /** Unknown host key: true to trust and remember it. */
    fun confirmHostKey(server: Server, keyType: String, fingerprint: String): Boolean

    /** Null cancels the connection. [failed] is true after a rejected attempt. */
    fun password(server: Server, failed: Boolean): PasswordAnswer?
    fun passphrase(server: Server, keyFile: String): CharArray?

    /** Password kept in the vault, if any (may ask to unlock the vault). */
    fun storedPassword(server: Server): CharArray?
    fun storePassword(server: Server, password: CharArray)
}

class ConnectCancelled : IOException("Připojení zrušeno")

/** `SHA256:…` fingerprint as printed by OpenSSH. */
fun sha256Fingerprint(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/** Known host keys: one `host:port keytype fingerprint` line per key. */
class KnownHosts(private val file: File) {
    private fun entries(): List<List<String>> = try {
        file.readLines().map { it.trim().split(' ') }.filter { it.size == 3 }
    } catch (_: IOException) {
        emptyList()
    }

    /** Key type → fingerprint for the host. */
    @Synchronized
    fun keysOf(host: String, port: Int): Map<String, String> =
        entries().filter { it[0] == "$host:$port" }.associate { it[1] to it[2] }

    @Synchronized
    fun add(host: String, port: Int, type: String, fingerprint: String) {
        file.parentFile?.mkdirs()
        file.appendText("$host:$port $type $fingerprint\n")
    }

    @Synchronized
    fun forget(host: String, port: Int) {
        val kept = entries().filter { it[0] != "$host:$port" }
        file.writeText(kept.joinToString("") { it.joinToString(" ") + "\n" })
    }
}

class Connections(
    private val knownHosts: KnownHosts,
    private val log: RemoteLog,
    private val prompts: RemotePrompts,
) {
    private class Live(val ssh: SSHClient, val sftp: SFTPClient, val home: String)

    private val live = HashMap<String, Live>()
    private val locks = HashMap<String, Any>()

    private fun lockOf(id: String): Any = synchronized(locks) { locks.getOrPut(id) { Any() } }

    fun isConnected(id: String): Boolean = synchronized(live) { live[id]?.ssh?.isConnected == true }

    fun connectedIds(): Set<String> = synchronized(live) { live.filterValues { it.ssh.isConnected }.keys.toSet() }

    fun disconnect(server: Server) {
        val old = synchronized(live) { live.remove(server.id) } ?: return
        log.log(server.label(), "odpojení")
        try {
            old.sftp.close()
            old.ssh.disconnect()
        } catch (_: Exception) {
        }
    }

    fun disconnectAll() {
        val all = synchronized(live) { live.values.toList().also { live.clear() } }
        for (item in all) {
            try {
                item.ssh.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun fs(server: Server) = SftpFs(this, server, log)

    /** Home directory reported by the server; connects when needed. */
    fun home(server: Server): String = connection(server).home

    internal fun sftp(server: Server): SFTPClient = connection(server).sftp

    private fun connection(server: Server): Live = synchronized(lockOf(server.id)) {
        synchronized(live) { live[server.id] }?.let { existing ->
            if (existing.ssh.isConnected && existing.ssh.isAuthenticated) return existing
            synchronized(live) { live.remove(server.id) }
            try {
                existing.ssh.disconnect()
            } catch (_: Exception) {
            }
        }
        val created = connect(server)
        synchronized(live) { live[server.id] = created }
        created
    }

    private fun connect(server: Server): Live {
        val label = server.label()
        log.log(label, "připojování")
        val config = DefaultConfig()
        config.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        val ssh = SSHClient(config)
        var hostKeyError: String? = null
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val type = KeyType.fromKey(key).toString()
                val fingerprint = sha256Fingerprint(key)
                val known = knownHosts.keysOf(server.host, server.port)
                val stored = known[type]
                return when {
                    stored == fingerprint -> true
                    stored != null -> {
                        hostKeyError = "KLÍČ SERVERU SE ZMĚNIL ($type $fingerprint). Může jít o útok. " +
                            "Pokud je změna v pořádku, smažte starý klíč ve správci serverů (Zapomenout klíč serveru)."
                        log.log(label, "ZMĚNĚNÝ KLÍČ SERVERU odmítnut", "$type $fingerprint")
                        false
                    }
                    prompts.confirmHostKey(server, type, fingerprint) -> {
                        knownHosts.add(server.host, server.port, type, fingerprint)
                        log.log(label, "klíč serveru přijat", "$type $fingerprint")
                        true
                    }
                    else -> {
                        hostKeyError = "Klíč serveru nebyl potvrzen"
                        log.log(label, "klíč serveru odmítnut", "$type $fingerprint")
                        false
                    }
                }
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                knownHosts.keysOf(server.host, server.port).keys.toList()
        })
        try {
            ssh.connectTimeout = 15_000
            ssh.timeout = 60_000
            try {
                ssh.connect(server.host, server.port)
            } catch (error: IOException) {
                throw IOException(hostKeyError ?: "Nelze se připojit k $label: ${error.message}", error)
            }
            authenticate(ssh, server)
            ssh.connection.keepAlive.keepAliveInterval = 30
            val sftp = ssh.newSFTPClient()
            val home = try {
                sftp.canonicalize(".")
            } catch (_: IOException) {
                "/"
            }
            log.log(label, "připojeno", home)
            return Live(ssh, sftp, home)
        } catch (error: Exception) {
            try {
                ssh.disconnect()
            } catch (_: Exception) {
            }
            log.log(label, "připojení selhalo", error.message.orEmpty())
            throw if (error is IOException) error else IOException(error.message ?: error.javaClass.simpleName, error)
        }
    }

    private fun authenticate(ssh: SSHClient, server: Server) {
        val label = server.label()
        val identity = server.identity?.takeIf { it.isNotEmpty() }
        if (server.auth != AuthMethod.Password && identity != null) {
            log.log(label, "ověření klíčem", identity)
            if (!File(identity).isFile) throw IOException("Soubor s klíčem neexistuje: $identity")
            val finder = object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?): CharArray =
                    prompts.passphrase(server, identity) ?: throw ConnectCancelled()

                override fun shouldRetry(resource: Resource<*>?): Boolean = false
            }
            try {
                ssh.auth(server.user, AuthPublickey(ssh.loadKeys(identity, finder)))
                return
            } catch (error: UserAuthException) {
                if (server.auth == AuthMethod.Key) throw IOException("Server klíč odmítl", error)
            } catch (error: IOException) {
                if (error is ConnectCancelled) throw error
                if (error.cause is ConnectCancelled) throw ConnectCancelled()
                if (server.auth == AuthMethod.Key) throw IOException("Klíč nelze použít: ${error.message}", error)
            }
        }
        var attempt = 0
        var stored = if (server.savePassword) prompts.storedPassword(server) else null
        while (true) {
            val usedStored = stored != null
            val answer = stored?.let { PasswordAnswer(it, false) }
                ?: prompts.password(server, attempt > 0)
                ?: throw ConnectCancelled()
            stored = null
            log.log(label, if (usedStored) "ověření uloženým heslem" else "ověření heslem")
            val finder = object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?): CharArray = answer.password.copyOf()
                override fun shouldRetry(resource: Resource<*>?): Boolean = false
            }
            try {
                ssh.auth(server.user, AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(finder)))
                if (answer.save) prompts.storePassword(server, answer.password)
                return
            } catch (error: UserAuthException) {
                attempt++
                log.log(label, "heslo odmítnuto")
                if (!ssh.isConnected || attempt >= 3) throw IOException("Přihlášení selhalo: server heslo odmítl", error)
            }
        }
    }
}

/** SFTP view of one server. Reconnects transparently when the connection was lost. */
class SftpFs internal constructor(
    private val connections: Connections,
    val server: Server,
    private val log: RemoteLog,
) : Fs {
    private val label = server.label()
    private val sftp: SFTPClient get() = connections.sftp(server)

    private fun <T> run(action: String, path: String, block: (SFTPClient) -> T): T {
        try {
            val result = block(sftp)
            if (action.isNotEmpty()) log.log(label, action, path)
            return result
        } catch (error: SFTPException) {
            if (action.isNotEmpty()) log.log(label, "CHYBA $action", "$path: ${error.message}")
            throw IOException(translate(error, path), error)
        } catch (error: IOException) {
            if (action.isNotEmpty()) log.log(label, "CHYBA $action", "$path: ${error.message}")
            throw error
        }
    }

    private fun translate(error: SFTPException, path: String): String = when (error.statusCode) {
        Response.StatusCode.NO_SUCH_FILE -> "Soubor nebo adresář neexistuje: $path"
        Response.StatusCode.PERMISSION_DENIED -> "Přístup odepřen: $path"
        else -> "${error.message ?: "Chyba SFTP"}: $path"
    }

    private fun entryOf(name: String, path: String, attributes: FileAttributes, client: SFTPClient): FileEntry {
        val kind = when (attributes.type) {
            FileMode.Type.DIRECTORY -> EntryKind.Directory
            FileMode.Type.REGULAR -> EntryKind.File
            FileMode.Type.SYMLINK -> EntryKind.Symlink
            else -> EntryKind.Other
        }
        var size = if (kind == EntryKind.File) attributes.size else 0
        var targetIsDir = false
        var linkTarget: String? = null
        if (kind == EntryKind.Symlink) {
            try {
                linkTarget = client.readlink(path)
                val target = client.stat(path)
                targetIsDir = target.type == FileMode.Type.DIRECTORY
                if (!targetIsDir) size = target.size
            } catch (_: IOException) {
            }
        }
        return FileEntry(
            name = name,
            kind = kind,
            size = size,
            modified = attributes.mtime * 1000,
            mode = attributes.mode.permissionsMask,
            linkTarget = linkTarget,
            targetIsDir = targetIsDir,
            owner = attributes.uid.toString(),
            group = attributes.gid.toString(),
        )
    }

    override fun list(dir: String): List<FileEntry> = run("výpis", dir) { client ->
        client.ls(dir).filter { it.name != "." && it.name != ".." }
            .map { entryOf(it.name, it.path, it.attributes, client) }
    }

    override fun stat(path: String): FileEntry? {
        val client = sftp
        return try {
            entryOf(path.trimEnd('/').substringAfterLast('/'), path, client.lstat(path), client)
        } catch (error: SFTPException) {
            if (error.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw IOException(translate(error, path), error)
        }
    }

    override fun openRead(path: String): InputStream = run("stažení", path) { client ->
        val file = client.open(path)
        val stream = file.ReadAheadRemoteFileInputStream(16)
        object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream.read(buffer, offset, length)
            override fun close() {
                try {
                    stream.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    override fun openWrite(path: String): OutputStream = run("nahrání", path) { client ->
        val file = client.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
        val stream = file.RemoteFileOutputStream(0, 16)
        object : BufferedOutputStream(stream, 32 * 1024) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    override fun mkdir(path: String) = run("nový adresář", path) { it.mkdir(path) }
    override fun deleteFile(path: String) = run("smazání", path) { it.rm(path) }
    override fun deleteDir(path: String) = run("smazání adresáře", path) { it.rmdir(path) }
    override fun rename(from: String, to: String) = run("přejmenování", "$from → $to") { it.rename(from, to) }

    override fun setModified(path: String, millis: Long) = run("", path) {
        val seconds = millis / 1000
        it.setattr(path, FileAttributes.Builder().withAtimeMtime(seconds, seconds).build())
    }

    override fun setMode(path: String, mode: Int) = run("chmod ${Integer.toOctalString(mode)}", path) { it.chmod(path, mode) }

    override fun sameFs(other: Fs): Boolean = other is SftpFs && other.server.id == server.id

    fun home(): String = connections.home(server)

    /** Absolute normalized path on the server. */
    fun canonicalize(path: String): String = run("", path) { it.canonicalize(path) }
}

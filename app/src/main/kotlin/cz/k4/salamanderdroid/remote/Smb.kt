package cz.k4.salamanderdroid.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import cz.k4.salamanderdroid.core.EntryKind
import cz.k4.salamanderdroid.core.FileEntry
import cz.k4.salamanderdroid.core.Fs
import cz.k4.salamanderdroid.core.normalizePath
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

private const val ATTRIBUTE_DIRECTORY = 0x10L

/** Connections to SMB shares (Windows, NAS); one per server entry, reused by both panels. */
class SmbConnections(private val log: RemoteLog, private val prompts: RemotePrompts) {
    private class Live(val client: SMBClient, val connection: Connection, val session: Session, val share: DiskShare)

    private val live = HashMap<String, Live>()
    private val locks = HashMap<String, Any>()

    private fun lockOf(id: String): Any = synchronized(locks) { locks.getOrPut(id) { Any() } }

    fun isConnected(id: String): Boolean = synchronized(live) { live[id]?.connection?.isConnected == true }

    fun fs(server: Server) = SmbFs(this, server, log)

    private fun close(item: Live) {
        try {
            item.share.close()
            item.session.close()
            item.connection.close()
            item.client.close()
        } catch (_: Exception) {
        }
    }

    fun disconnect(server: Server) {
        val old = synchronized(live) { live.remove(server.id) } ?: return
        log.log(server.label(), "odpojení")
        close(old)
    }

    fun disconnectAll() {
        val all = synchronized(live) { live.values.toList().also { live.clear() } }
        all.forEach(::close)
    }

    internal fun share(server: Server): DiskShare = synchronized(lockOf(server.id)) {
        synchronized(live) { live[server.id] }?.let { existing ->
            if (existing.connection.isConnected && existing.share.isConnected) return existing.share
            synchronized(live) { live.remove(server.id) }
            close(existing)
        }
        val created = connect(server)
        synchronized(live) { live[server.id] = created }
        created.share
    }

    private fun connect(server: Server): Live {
        val label = server.label()
        log.log(label, "připojování (SMB)", server.share)
        val client = SMBClient(
            SmbConfig.builder().withTimeout(60, TimeUnit.SECONDS).withSoTimeout(120, TimeUnit.SECONDS).build()
        )
        var connection: Connection? = null
        try {
            connection = try {
                client.connect(server.host, server.port)
            } catch (error: Exception) {
                throw IOException("Nelze se připojit k $label: ${error.message}", error)
            }
            val session = authenticate(connection, server)
            val share = try {
                session.connectShare(server.share) as? DiskShare ?: throw IOException("„${server.share}“ není sdílená složka se soubory")
            } catch (error: SMBApiException) {
                throw IOException(
                    when (error.status) {
                        NtStatus.STATUS_BAD_NETWORK_NAME -> "Sdílená složka „${server.share}“ na serveru neexistuje"
                        NtStatus.STATUS_ACCESS_DENIED -> "Přístup ke sdílené složce „${server.share}“ byl odepřen"
                        else -> "Sdílenou složku nelze otevřít: ${error.message}"
                    },
                    error,
                )
            }
            log.log(label, "připojeno (SMB)", server.share)
            return Live(client, connection, session, share)
        } catch (error: Exception) {
            try {
                connection?.close()
                client.close()
            } catch (_: Exception) {
            }
            log.log(label, "připojení selhalo", error.message.orEmpty())
            throw if (error is IOException) error else IOException(error.message ?: error.javaClass.simpleName, error)
        }
    }

    private fun authenticate(connection: Connection, server: Server): Session {
        val label = server.label()
        if (server.user.isEmpty()) {
            log.log(label, "přihlášení bez účtu (host)")
            return try {
                connection.authenticate(AuthenticationContext.guest())
            } catch (error: SMBApiException) {
                throw IOException("Server nedovoluje přístup bez účtu; vyplňte uživatele", error)
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
            try {
                val session = connection.authenticate(AuthenticationContext(server.user, answer.password.copyOf(), server.domain.ifEmpty { null }))
                if (answer.save) prompts.storePassword(server, answer.password)
                return session
            } catch (error: SMBApiException) {
                attempt++
                log.log(label, "heslo odmítnuto")
                if (attempt >= 3 || !connection.isConnected) throw IOException("Přihlášení selhalo: server jméno nebo heslo odmítl", error)
            }
        }
    }
}

/** One SMB share as a file system; `/` is the root of the share. */
class SmbFs internal constructor(
    private val connections: SmbConnections,
    val server: Server,
    private val log: RemoteLog,
) : Fs {
    private val label = server.label()

    private fun smbPath(path: String): String = normalizePath(path).trim('/').replace('/', '\\')

    private fun <T> run(action: String, path: String, block: (DiskShare) -> T): T {
        try {
            val result = block(connections.share(server))
            if (action.isNotEmpty()) log.log(label, action, path)
            return result
        } catch (error: IOException) {
            if (action.isNotEmpty()) log.log(label, "CHYBA $action", "$path: ${error.message}")
            throw error
        } catch (error: Exception) {
            if (action.isNotEmpty()) log.log(label, "CHYBA $action", "$path: ${error.message}")
            val status = (error as? SMBApiException)?.status
            throw IOException(
                when (status) {
                    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NO_SUCH_FILE ->
                        "Soubor nebo složka neexistuje: $path"
                    NtStatus.STATUS_ACCESS_DENIED -> "Přístup odepřen: $path"
                    NtStatus.STATUS_OBJECT_NAME_COLLISION -> "Položka již existuje: $path"
                    NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> "Složka není prázdná: $path"
                    NtStatus.STATUS_SHARING_VIOLATION -> "Soubor používá jiný program: $path"
                    NtStatus.STATUS_DISK_FULL -> "Na disku není místo"
                    else -> "${error.message ?: error.javaClass.simpleName}: $path"
                },
                error,
            )
        }
    }

    private fun missing(error: Throwable): Boolean {
        val status = ((error.cause ?: error) as? SMBApiException)?.status
        return status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ||
            status == NtStatus.STATUS_NO_SUCH_FILE
    }

    override fun list(dir: String): List<FileEntry> = run("výpis", dir) { share ->
        share.list(smbPath(dir)).filter { it.fileName != "." && it.fileName != ".." }.map {
            val directory = it.fileAttributes and ATTRIBUTE_DIRECTORY != 0L
            FileEntry(
                name = it.fileName,
                kind = if (directory) EntryKind.Directory else EntryKind.File,
                size = if (directory) 0 else it.endOfFile,
                modified = it.lastWriteTime.toEpochMillis(),
            )
        }
    }

    override fun stat(path: String): FileEntry? {
        val clean = smbPath(path)
        if (clean.isEmpty()) return FileEntry("", EntryKind.Directory)
        return try {
            run("", path) { share ->
                val info = share.getFileInformation(clean)
                val directory = info.standardInformation.isDirectory
                FileEntry(
                    name = clean.substringAfterLast('\\'),
                    kind = if (directory) EntryKind.Directory else EntryKind.File,
                    size = if (directory) 0 else info.standardInformation.endOfFile,
                    modified = info.basicInformation.lastWriteTime.toEpochMillis(),
                )
            }
        } catch (error: IOException) {
            if (missing(error)) null else throw error
        }
    }

    override fun openRead(path: String): InputStream = run("stažení", path) { share ->
        val file = share.openFile(
            smbPath(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
        val stream = file.inputStream
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

    override fun openWrite(path: String): OutputStream = run("nahrání", path) { share ->
        val file = share.openFile(
            smbPath(path), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
        )
        object : BufferedOutputStream(file.outputStream, 256 * 1024) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    override fun mkdir(path: String) = run("nový adresář", path) { it.mkdir(smbPath(path)) }
    override fun deleteFile(path: String) = run("smazání", path) { it.rm(smbPath(path)) }
    override fun deleteDir(path: String) = run("smazání adresáře", path) { it.rmdir(smbPath(path), false) }

    override fun rename(from: String, to: String) = run("přejmenování", "$from → $to") { share ->
        share.open(
            smbPath(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        ).use { it.rename(smbPath(to), false) }
    }

    override fun setModified(path: String, millis: Long) = run("", path) { share ->
        val keep = FileBasicInformation.DONT_SET
        share.open(
            smbPath(path), EnumSet.of(AccessMask.FILE_WRITE_ATTRIBUTES), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        ).use { it.setFileInformation(FileBasicInformation(keep, keep, FileTime.ofEpochMillis(millis), keep, 0)) }
    }

    override fun sameFs(other: Fs): Boolean = other is SmbFs && other.server.id == server.id

    /** Free and total bytes of the share, when the server tells. */
    fun space(): Pair<Long, Long>? = try {
        run("", "/") { share -> share.shareInformation.let { it.callerFreeSpace to it.totalSpace } }.takeIf { it.second > 0 }
    } catch (_: IOException) {
        null
    }
}

package cz.k4.salamanderdroid.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.k4.salamanderdroid.OpService
import cz.k4.salamanderdroid.cloud.CloudAccount
import cz.k4.salamanderdroid.cloud.CloudEndpoints
import cz.k4.salamanderdroid.cloud.CloudFs
import cz.k4.salamanderdroid.cloud.CloudKind
import cz.k4.salamanderdroid.cloud.CloudSession
import cz.k4.salamanderdroid.cloud.CloudStore
import cz.k4.salamanderdroid.cloud.DriveFs
import cz.k4.salamanderdroid.cloud.GraphFs
import cz.k4.salamanderdroid.cloud.KeystoreSealer
import cz.k4.salamanderdroid.cloud.OAuth
import cz.k4.salamanderdroid.cloud.OAuthConfig
import cz.k4.salamanderdroid.cloud.Tokens
import cz.k4.salamanderdroid.core.ArchiveFormat
import cz.k4.salamanderdroid.core.ArchiveIndex
import cz.k4.salamanderdroid.core.Archives
import cz.k4.salamanderdroid.core.Config
import cz.k4.salamanderdroid.core.Conflict
import cz.k4.salamanderdroid.core.ConflictAction
import cz.k4.salamanderdroid.core.EntryKind
import cz.k4.salamanderdroid.core.FileEntry
import cz.k4.salamanderdroid.core.Fs
import cz.k4.salamanderdroid.core.LocalFs
import cz.k4.salamanderdroid.core.Mask
import cz.k4.salamanderdroid.core.Measure
import cz.k4.salamanderdroid.core.OpError
import cz.k4.salamanderdroid.core.OpKind
import cz.k4.salamanderdroid.core.OpListener
import cz.k4.salamanderdroid.core.OpProgress
import cz.k4.salamanderdroid.core.Rendered
import cz.k4.salamanderdroid.core.SortKey
import cz.k4.salamanderdroid.core.Transfer
import cz.k4.salamanderdroid.core.Trash
import cz.k4.salamanderdroid.core.compareListings
import cz.k4.salamanderdroid.core.deleteTrees
import cz.k4.salamanderdroid.core.extensionOf
import cz.k4.salamanderdroid.core.formatBytes
import cz.k4.salamanderdroid.core.joinPath
import cz.k4.salamanderdroid.core.measure
import cz.k4.salamanderdroid.core.normalizePath
import cz.k4.salamanderdroid.core.parentPath
import cz.k4.salamanderdroid.core.readLimited
import cz.k4.salamanderdroid.core.renderContent
import cz.k4.salamanderdroid.core.validateName
import cz.k4.salamanderdroid.remote.Connections
import cz.k4.salamanderdroid.remote.KnownHosts
import cz.k4.salamanderdroid.remote.PasswordAnswer
import cz.k4.salamanderdroid.remote.RemoteLog
import cz.k4.salamanderdroid.remote.RemotePrompts
import cz.k4.salamanderdroid.remote.Server
import cz.k4.salamanderdroid.remote.Servers
import cz.k4.salamanderdroid.remote.Vault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

/** Dialogs currently shown, as a stack. Compared by identity. */
sealed class Dlg {
    class Message(val title: String, val text: String, val mono: Boolean = false) : Dlg()
    class Confirm(val title: String, val text: String, val confirmLabel: String, val onConfirm: () -> Unit) : Dlg()
    class Prompt(
        val title: String,
        val label: String,
        val initial: String,
        val confirmLabel: String,
        /** Select only the part of the name before the extension. */
        val selectStem: Boolean = false,
        val onConfirm: (String) -> Unit,
    ) : Dlg()

    class Errors(val errors: List<OpError>) : Dlg()
    class ConflictAsk(val conflict: Conflict, val answer: CompletableDeferred<ConflictAction>) : Dlg()
    class HostKey(val server: Server, val keyType: String, val fingerprint: String, val answer: CompletableDeferred<Boolean>) : Dlg()
    class PasswordAsk(val server: Server, val failed: Boolean, val answer: CompletableDeferred<PasswordAnswer?>) : Dlg()
    class Secret(val title: String, val text: String, val error: String?, val confirmLabel: String, val answer: CompletableDeferred<CharArray?>) : Dlg()
    class NewMaster(val title: String, val answer: CompletableDeferred<CharArray?>) : Dlg()
    class Places(val target: PanelState) : Dlg()
    class Find(val root: String) : Dlg()
    class Properties(val loc: Loc, val entries: List<FileEntry>) : Dlg()
    class Attributes(val loc: Loc, val entries: List<FileEntry>) : Dlg()
    class Pack(val count: Int, val destDir: String, val name: String, val onConfirm: (String) -> Unit) : Dlg()
    class Unpack(val count: Int, val destDir: String, val onConfirm: (String, Boolean) -> Unit) : Dlg()
    class ServerManager : Dlg()
    class ServerEdit(val server: Server, val creating: Boolean, val hasStoredPassword: Boolean, val onSave: (Server, String, Boolean) -> Unit) : Dlg()
    class CloudAccounts : Dlg()
    class CloudAccountEdit(val account: CloudAccount, val creating: Boolean, val onSave: (CloudAccount) -> Unit) : Dlg()
    class Settings : Dlg()
    class Viewer(val title: String, val content: Rendered) : Dlg()
    class Editor(val title: String, val text: String, val save: suspend (String) -> Unit) : Dlg()
}

class ProgressUi(val title: String, val text: String, val detail: String, val fraction: Float?)

class Volume(val label: String, val path: String, val primary: Boolean)

private const val EDIT_LIMIT = 2 * 1024 * 1024
private const val VAULT_IDLE_MS = 15 * 60 * 1000L
private const val REMOTE_UNSUPPORTED = "Na vzdáleném serveru tato operace není podporována."
private const val CLOUD_UNSUPPORTED = "V cloudovém úložišti tato operace není podporována; soubory nejprve stáhněte (F5)."
private const val ARCHIVE_UNSUPPORTED =
    "V archivu tato operace není podporována. Archiv lze procházet, rozbalovat (F5), " +
        "doplňovat (F5 z druhého panelu) a mazat z něj (F8)."

class AppState(application: Application) : AndroidViewModel(application) {
    companion object {
        /** Replaceable in tests, where the platform check is not available. */
        var storageAccessCheck: () -> Boolean = { Environment.isExternalStorageManager() }
    }

    private val context: Context = application
    val scope: CoroutineScope = viewModelScope

    private val configFile = File(context.filesDir, "config")
    private val serversFile = File(context.filesDir, "servers")
    private val vaultFile = File(context.filesDir, "vault")
    private val viewDir = File(context.cacheDir, "view")

    var config by mutableStateOf(Config.load(configFile))
        private set
    var servers by mutableStateOf(Servers.load(serversFile))
        private set

    val home: String = Environment.getExternalStorageDirectory().path
    val left = PanelState(this, Loc.Local(home))
    val right = PanelState(this, Loc.Local(home))
    var leftActive by mutableStateOf(true)
    val active: PanelState get() = if (leftActive) left else right
    val other: PanelState get() = if (leftActive) right else left

    val dialogs = mutableStateListOf<Dlg>()
    var progress by mutableStateOf<ProgressUi?>(null)
        private set
    var flash by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var hasStorageAccess by mutableStateOf(false)
        private set
    var pathEditing by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)

    /** Index of a menu to open from the keyboard (F9), or -1. */
    var menuRequest by mutableStateOf(-1)
    var onExit: () -> Unit = {}

    val remoteLog = RemoteLog(File(context.filesDir, "remote.log"))
    val knownHosts = KnownHosts(File(context.filesDir, "known_hosts"))
    private var started = false
    private var flashJob: Job? = null
    private var jobCancel: AtomicBoolean? = null
    private var clip: Triple<String, List<String>, Boolean>? = null

    // ---- Start-up and storage access ----

    fun checkStorageAccess() {
        hasStorageAccess = storageAccessCheck()
        if (hasStorageAccess && !started) {
            started = true
            viewDir.deleteRecursively()
            fun startLoc(saved: String?): Loc =
                saved?.takeIf { !it.contains("://") }?.let { parseLoc(it, Loc.Local(home)) } ?: Loc.Local(home)
            left.navigate(startLoc(config.leftDir), fallback = Loc.Local(home))
            right.navigate(startLoc(config.rightDir), fallback = Loc.Local(home))
            scope.launch {
                while (true) {
                    delay(30_000)
                    if (vaultKey != null && System.currentTimeMillis() - vaultLastUse > VAULT_IDLE_MS) lockVault(silent = true)
                }
            }
        } else if (hasStorageAccess) {
            if (!busy) {
                left.reload()
                right.reload()
            }
        }
    }

    fun savePanelDirs() {
        if (!started) return
        updateConfig(refresh = false) { it.copy(leftDir = left.loc.display(), rightDir = right.loc.display()) }
    }

    fun updateConfig(refresh: Boolean = true, change: (Config) -> Config) {
        config = change(config)
        try {
            config.save(configFile)
        } catch (_: IOException) {
        }
        if (refresh) {
            left.refreshView()
            right.refreshView()
        }
    }

    override fun onCleared() {
        left.dispose()
        right.dispose()
        Thread { connections.disconnectAll() }.start()
        vaultKey?.wipe()
    }

    // ---- Messages ----

    fun showError(message: String) {
        dialogs += Dlg.Message("Chyba", message)
    }

    fun showShortcuts() {
        dialogs += Dlg.Message("Klávesové zkratky", SHORTCUTS, mono = true)
    }

    fun flash(text: String) {
        flash = text
        flashJob?.cancel()
        flashJob = scope.launch {
            delay(4000)
            flash = null
        }
    }

    fun close(dialog: Dlg) {
        dialogs.remove(dialog)
    }

    /** Shows a dialog from a worker thread and blocks until it is answered. */
    private fun <T> ask(make: (CompletableDeferred<T>) -> Dlg): T = runBlocking {
        val answer = CompletableDeferred<T>()
        val dialog = make(answer)
        withContext(Dispatchers.Main) { dialogs += dialog }
        try {
            answer.await()
        } finally {
            withContext(Dispatchers.Main) { dialogs.remove(dialog) }
        }
    }

    // ---- Storage ----

    fun volumes(): List<Volume> {
        val manager = context.getSystemService(StorageManager::class.java)
        return manager.storageVolumes.mapNotNull { volume ->
            volume.directory?.let { Volume(volume.getDescription(context), it.path, volume.isPrimary) }
        }
    }

    private fun volumeRootOf(path: String): String? =
        volumes().map { it.path }.filter { (normalizePath(path) + "/").startsWith("$it/") }.maxByOrNull { it.length }

    fun trashDirs(): List<String> = volumes().map { Trash.trashDir(it.path).path + "/files" }.filter { File(it).isDirectory }

    private val indexCache = HashMap<String, Triple<Long, Long, ArchiveIndex>>()

    private fun archiveIndex(file: File): ArchiveIndex = synchronized(indexCache) {
        val cached = indexCache[file.path]
        if (cached != null && cached.first == file.lastModified() && cached.second == file.length()) return cached.third
        if (!file.isFile) throw IOException("Archiv neexistuje: ${file.path}")
        val index = try {
            Archives.index(file)
        } catch (error: Exception) {
            throw IOException("Archiv nelze otevřít: ${error.message ?: error.javaClass.simpleName}", error)
        }
        indexCache[file.path] = Triple(file.lastModified(), file.length(), index)
        index
    }

    /** Lists a location; called on a worker thread. */
    fun list(target: Loc): Listing = when (target) {
        is Loc.Local -> {
            val entries = LocalFs.list(target.path)
            val dir = File(target.path)
            val total = dir.totalSpace
            Listing(target, entries, if (total > 0) dir.usableSpace to total else null)
        }
        is Loc.Archive -> {
            val entries = archiveIndex(File(target.archive)).list(target.inner)
                ?: throw IOException("Adresář v archivu neexistuje: ${target.inner}")
            Listing(target, entries, null)
        }
        is Loc.Remote -> {
            val fs = connections.fs(target.server)
            val wanted = target.path.ifEmpty { target.server.initialDir.ifEmpty { fs.home() } }
            val path = if (wanted.startsWith("/") && !wanted.contains("/../") && !wanted.endsWith("/..")) wanted else fs.canonicalize(wanted)
            Listing(Loc.Remote(target.server, path), fs.list(path), null)
        }
        is Loc.Cloud -> {
            val fs = cloudFs(target.account)
            val path = normalizePath(target.path)
            Listing(Loc.Cloud(target.account, path), fs.list(path), cloudSpace(fs))
        }
    }

    private fun fsOf(loc: Loc): Fs? = when (loc) {
        is Loc.Local -> LocalFs
        is Loc.Remote -> connections.fs(loc.server)
        is Loc.Cloud -> cloudFs(loc.account)
        is Loc.Archive -> null
    }

    /**
     * Turns typed text into a location: `sftp://user@host/path`, an absolute path, or a path
     * relative to [base]. A path leading into an archive file opens the archive.
     */
    fun parseLoc(text: String, base: Loc): Loc? {
        val typed = text.trim()
        if (typed.isEmpty()) return null
        if (typed.startsWith("sftp://")) {
            val rest = typed.removePrefix("sftp://")
            val server = servers.filter { rest == it.label() || rest.startsWith(it.label() + "/") }
                .maxByOrNull { it.label().length } ?: return null
            return Loc.Remote(server, rest.removePrefix(server.label()).let { if (it.isEmpty()) "" else normalizePath(it) })
        }
        for (kind in CloudKind.entries) {
            if (!typed.startsWith(kind.scheme)) continue
            val rest = typed.removePrefix(kind.scheme)
            val account = cloudAccounts.filter { it.kind == kind && (rest == it.pathName() || rest.startsWith(it.pathName() + "/")) }
                .maxByOrNull { it.pathName().length } ?: return null
            return Loc.Cloud(account, normalizePath("/" + rest.removePrefix(account.pathName())))
        }
        val expanded = when {
            typed == "~" -> home
            typed.startsWith("~/") -> joinPath(home, typed.removePrefix("~/"))
            else -> typed
        }
        if (!expanded.startsWith("/")) {
            return when (base) {
                is Loc.Remote -> Loc.Remote(base.server, normalizePath(joinPath(base.path, expanded)))
                is Loc.Cloud -> Loc.Cloud(base.account, normalizePath(joinPath(base.path, expanded)))
                is Loc.Archive -> Loc.Archive(base.archive, normalizePath("/" + joinPath(base.inner, expanded)).removePrefix("/"))
                is Loc.Local -> localLoc(normalizePath(joinPath(base.path, expanded)))
            }
        }
        return localLoc(normalizePath(expanded))
    }

    private fun localLoc(path: String): Loc {
        var candidate: String? = path
        while (candidate != null && candidate != "/") {
            val file = File(candidate)
            if (file.isDirectory) break
            if (file.isFile) {
                if (ArchiveFormat.of(file.name) != null) return Loc.Archive(candidate, path.removePrefix(candidate).trim('/'))
                break
            }
            candidate = parentPath(candidate)
        }
        return Loc.Local(path)
    }

    // ---- Background jobs ----

    inner class JobCtx(val cancel: AtomicBoolean, private val title: String) : OpListener, Archives.PackListener {
        private var lastPost = 0L

        private fun post(text: String, detail: String, fraction: Float?, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPost < 80) return
            lastPost = now
            scope.launch { if (busy) progress = ProgressUi(title, text, detail, fraction) }
        }

        fun status(text: String) = post(text, "", null, force = true)

        override fun preparing(current: String) = post("Připravuji: $current", "", null)

        override fun progress(progress: OpProgress) {
            val fraction = when {
                progress.totalBytes > 0 -> progress.doneBytes.toFloat() / progress.totalBytes
                progress.totalFiles > 0 -> progress.doneFiles.toFloat() / progress.totalFiles
                else -> null
            }
            post(
                progress.current,
                "${progress.doneFiles} z ${progress.totalFiles} souborů, ${formatBytes(progress.doneBytes)} z ${formatBytes(progress.totalBytes)}",
                fraction?.coerceIn(0f, 1f),
            )
        }

        override fun conflict(conflict: Conflict): ConflictAction = ask { Dlg.ConflictAsk(conflict, it) }

        override fun progress(current: String, doneBytes: Long, totalBytes: Long) = post(
            current,
            "${formatBytes(doneBytes)} z ${formatBytes(totalBytes)}",
            if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null,
        )

        fun transfer(dst: Fs) = Transfer(dst, this, cancel)
    }

    fun cancelJob() {
        jobCancel?.set(true)
    }

    /**
     * Runs [block] on a worker thread with a progress dialog. Afterwards both panels reload,
     * errors are shown and [onDone] gets true when the job finished without errors or cancel.
     */
    private fun runJob(
        title: String,
        reload: Boolean = true,
        focus: String? = null,
        onDone: (Boolean) -> Unit = {},
        block: (JobCtx) -> List<OpError>,
    ) {
        if (busy) return
        busy = true
        val cancel = AtomicBoolean(false)
        jobCancel = cancel
        progress = ProgressUi(title, "", "", null)
        // Only longer jobs get the foreground service; starting and stopping it within a few
        // milliseconds is not allowed by the system.
        var serviceStarted = false
        val serviceJob = scope.launch {
            delay(1500)
            try {
                context.startForegroundService(Intent(context, OpService::class.java))
                serviceStarted = true
            } catch (_: Exception) {
                // Without the service the job still runs, only less protected in the background.
            }
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(block(JobCtx(cancel, title)))
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
            busy = false
            jobCancel = null
            progress = null
            serviceJob.cancel()
            if (serviceStarted) {
                // The service stops itself, so it always gets to enter the foreground first.
                try {
                    context.startService(Intent(context, OpService::class.java).setAction(OpService.ACTION_STOP))
                } catch (_: Exception) {
                    context.stopService(Intent(context, OpService::class.java))
                }
            }
            if (reload) {
                active.reload(focus = focus)
                other.reload()
            }
            result.onSuccess { errors ->
                if (errors.isNotEmpty()) dialogs += Dlg.Errors(errors)
                onDone(errors.isEmpty() && !cancel.get())
            }.onFailure { error ->
                showError(error.message ?: error.javaClass.simpleName)
                onDone(false)
            }
        }
    }

    /** Short blocking call (rename, mkdir, …) off the main thread. */
    private fun io(block: () -> Unit, then: () -> Unit = {}) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            result.onSuccess { then() }.onFailure { showError(it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun ensureDirs(fs: Fs, dir: String) {
        if (dir.isEmpty() || dir == "/") return
        val existing = fs.stat(dir)
        if (existing != null) {
            if (!existing.isDirLike) throw IOException("Cíl není adresář: $dir")
            return
        }
        parentPath(dir)?.let { ensureDirs(fs, it) }
        fs.mkdir(dir)
    }

    private fun what(entries: List<FileEntry>): String =
        if (entries.size == 1) "„${entries[0].name}“" else "${entries.size} položek"

    private fun unsupported(loc: Loc): Boolean {
        when (loc) {
            is Loc.Remote -> showError(REMOTE_UNSUPPORTED)
            is Loc.Cloud -> showError(CLOUD_UNSUPPORTED)
            is Loc.Archive -> showError(ARCHIVE_UNSUPPORTED)
            is Loc.Local -> return false
        }
        return true
    }

    // ---- Navigation actions ----

    fun switchPanel() {
        active.quickSearchEnd()
        leftActive = !leftActive
    }

    fun activateEntry(panel: PanelState = active) {
        val entry = panel.cursorEntry() ?: return
        val loc = panel.loc
        when {
            entry.isParent -> panel.navigateParent()
            entry.isDirLike -> panel.navigate(loc.child(entry.name))
            loc is Loc.Local && ArchiveFormat.of(entry.name) != null -> panel.navigate(Loc.Archive(loc.pathOf(entry.name), ""))
            else -> withLocalCopy(loc, entry) { openFile(it, chooser = false) }
        }
    }

    fun openExternal() {
        val entry = active.cursorEntry()?.takeIf { !it.isParent && !it.isDirLike } ?: return
        withLocalCopy(active.loc, entry) { openFile(it, chooser = true) }
    }

    fun openInPanel(target: PanelState) {
        val source = active
        val entry = source.cursorEntry()
        val loc = when {
            entry == null -> source.loc
            entry.isParent -> source.loc.parent()?.first ?: source.loc
            entry.isDirLike -> source.loc.child(entry.name)
            else -> source.loc
        }
        target.navigate(loc)
    }

    fun swapPanels() {
        val (a, b) = left.loc to right.loc
        left.navigate(b)
        right.navigate(a)
    }

    fun goTyped(text: String, panel: PanelState = active) {
        val loc = parseLoc(text, panel.loc)
        if (loc == null) showError("Neplatná cesta: $text") else panel.navigate(loc)
    }

    fun setHotpath(index: Int) {
        val path = active.loc.display()
        updateConfig(refresh = false) { it.copy(hotpaths = it.hotpaths.toMutableList().also { list -> list[index] = path }) }
        flash("Klávesová cesta $index: $path")
    }

    fun goHotpath(index: Int) {
        val path = config.hotpaths[index]
        if (path == null) flash("Klávesová cesta $index není nastavena (Ctrl+Shift+$index)") else goTyped(path)
    }

    // ---- Opening files ----

    private fun openFile(file: File, chooser: Boolean) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val mime = mimeOf(file.name) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(
                if (chooser) Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent
            )
        } catch (_: ActivityNotFoundException) {
            showError("Žádná aplikace neumí otevřít „${file.name}“. Zkuste F3 (prohlížeč) nebo Shift+Enter.")
        } catch (error: Exception) {
            showError("Soubor nelze otevřít: ${error.message}")
        }
    }

    fun mimeOf(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(name).lowercase())

    private var viewCounter = 0

    /** Gives [then] a local file: the file itself, or a private temporary copy from an archive or server. */
    private fun withLocalCopy(loc: Loc, entry: FileEntry, then: (File) -> Unit) {
        if (loc is Loc.Local) {
            then(File(loc.pathOf(entry.name)))
            return
        }
        val dir = File(viewDir, (++viewCounter).toString())
        val target = File(dir, entry.name)
        val title = if (loc is Loc.Archive) "Rozbaluji ${entry.name}" else "Stahuji ${entry.name}"
        runJob(title, reload = false, onDone = { ok -> if (ok) then(target) }) { job ->
            dir.mkdirs()
            if (loc is Loc.Archive) {
                Archives.extractFile(File(loc.archive), loc.pathOf(entry.name), target)
                emptyList()
            } else {
                val fs = fsOf(loc)!!
                val transfer = job.transfer(LocalFs)
                transfer.totalFiles = 1
                transfer.totalBytes = entry.size
                val source = loc.pathOf(entry.name)
                transfer.putFile(source, target.path, entry.size, entry.modified, null) { fs.openRead(source) }
                transfer.errors
            }
        }
    }

    fun view() {
        val entry = active.cursorEntry()?.takeIf { !it.isParent && !it.isDirLike } ?: return
        withLocalCopy(active.loc, entry) { viewFile(it) }
    }

    fun viewFile(file: File) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { file.inputStream().use { renderContent(readLimited(it)) } } }
            result.onSuccess { dialogs += Dlg.Viewer(file.name, it) }
                .onFailure { showError("Soubor nelze číst: ${it.message}") }
        }
    }

    fun edit() {
        val panel = active
        val loc = panel.loc
        val entry = panel.cursorEntry()?.takeIf { !it.isParent } ?: return
        if (loc is Loc.Archive) {
            showError(ARCHIVE_UNSUPPORTED)
            return
        }
        if (entry.isDirLike) {
            showError("Adresář nelze upravit; použijte Enter")
            return
        }
        if (entry.size > EDIT_LIMIT) {
            showError("Soubor je pro vestavěný editor příliš velký (limit ${formatBytes(EDIT_LIMIT.toLong())}). Použijte Shift+Enter.")
            return
        }
        withLocalCopy(loc, entry) { file ->
            scope.launch {
                val content = withContext(Dispatchers.IO) { runCatching { renderContent(file.readBytes()) } }.getOrNull()
                val text = content?.text
                if (text == null) {
                    showError("„${entry.name}“ není textový soubor (UTF-8). Použijte F3 nebo Shift+Enter.")
                    return@launch
                }
                dialogs += Dlg.Editor(entry.name, text) { edited ->
                    withContext(Dispatchers.IO) {
                        file.writeText(edited)
                        if (loc !is Loc.Local) {
                            val fs = fsOf(loc)!!
                            val transfer = Transfer(fs, object : OpListener {
                                override fun conflict(conflict: Conflict) = ConflictAction.Overwrite
                            }, AtomicBoolean(false))
                            val target = loc.pathOf(entry.name)
                            if (!transfer.putFile(file.path, target, file.length(), null, entry.mode) { file.inputStream() }) {
                                throw IOException(transfer.errors.firstOrNull()?.message ?: "Nahrání selhalo")
                            }
                        }
                    }
                    flash(if (loc !is Loc.Local) "Uloženo a nahráno: ${entry.name}" else "Uloženo: ${entry.name}")
                    panel.reload()
                }
            }
        }
    }

    // ---- Create, rename, delete ----

    fun rename() {
        val panel = active
        val loc = panel.loc
        if (loc is Loc.Archive) return showError(ARCHIVE_UNSUPPORTED)
        val entry = panel.cursorEntry()?.takeIf { !it.isParent } ?: return
        dialogs += Dlg.Prompt("Přejmenovat", "Nový název pro „${entry.name}“:", entry.name, "Přejmenovat", selectStem = !entry.isDirLike) { name ->
            validateName(name)?.let { return@Prompt showError(it) }
            if (name == entry.name) return@Prompt
            io({
                val fs = fsOf(loc)!!
                if (fs.stat(loc.pathOf(name)) != null && !name.equals(entry.name, ignoreCase = true)) {
                    throw IOException("„$name“ již existuje")
                }
                fs.rename(loc.pathOf(entry.name), loc.pathOf(name))
            }) { panel.reload(focus = name) }
        }
    }

    fun create(file: Boolean) {
        val panel = active
        val loc = panel.loc
        if (loc is Loc.Archive) return showError(ARCHIVE_UNSUPPORTED)
        val (title, label) = if (file) "Nový soubor" to "Název nového souboru:" else "Nová složka" to "Název nové složky (lze i a/b/c):"
        dialogs += Dlg.Prompt(title, label, "", "Vytvořit") { typed ->
            val parts = typed.trim().trim('/').split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@Prompt showError("Zadejte název")
            parts.firstNotNullOfOrNull { validateName(it) }?.let { return@Prompt showError(it) }
            io({
                val fs = fsOf(loc)!!
                val path = joinPath(loc.dir, parts.joinToString("/"))
                if (fs.stat(path) != null) throw IOException("„${parts.last()}“ již existuje")
                if (file) {
                    parentPath(path)?.let { ensureDirs(fs, it) }
                    fs.openWrite(path).close()
                } else {
                    ensureDirs(fs, path)
                }
            }) { panel.reload(focus = parts.first()) }
        }
    }

    fun remove(permanent: Boolean) {
        if (busy) return
        val panel = active
        val loc = panel.loc
        val entries = panel.selectedEntries()
        if (entries.isEmpty()) return
        val paths = entries.map { loc.pathOf(it.name) }
        val what = what(entries)
        when (loc) {
            is Loc.Remote -> dialogs += Dlg.Confirm("Smazat na serveru", "Trvale smazat $what ze serveru ${loc.server.displayName()}? Tuto akci nelze vrátit.", "Smazat") {
                runJob("Mažu na serveru") { job ->
                    deleteTrees(connections.fs(loc.server), paths, job.cancel) { job.status(it) }
                }
            }
            is Loc.Cloud -> dialogs += Dlg.Confirm("Smazat v cloudu", "Smazat $what z úložiště ${loc.account.displayName()}? Položky se přesunou do koše služby.", "Smazat") {
                runJob("Mažu v cloudu") { job ->
                    deleteTrees(cloudFs(loc.account), paths, job.cancel) { job.status(it) }
                }
            }
            is Loc.Archive -> dialogs += Dlg.Confirm("Odstranit z archivu", "Odstranit $what z archivu? Archiv se přepíše.", "Odstranit") {
                runJob("Odstraňuji z archivu") { job ->
                    Archives.update(File(loc.archive), emptyList(), "", paths, job, job.cancel)
                }
            }
            is Loc.Local -> if (permanent) {
                dialogs += Dlg.Confirm("Trvalé smazání", "Trvale smazat $what? Tuto akci nelze vrátit.", "Smazat") {
                    runJob("Mažu") { job -> deleteTrees(LocalFs, paths, job.cancel) { job.status(it) } }
                }
            } else {
                dialogs += Dlg.Confirm("Přesunout do koše", "Přesunout $what do koše?", "Přesunout do koše") {
                    runJob("Přesouvám do koše") { Trash.trash(paths, ::volumeRootOf) }
                }
            }
        }
    }

    fun emptyTrash() {
        val dirs = volumes().map { Trash.trashDir(it.path) }.filter { it.isDirectory }
        if (dirs.isEmpty()) return flash("Koš je prázdný")
        dialogs += Dlg.Confirm("Vysypat koš", "Trvale smazat celý obsah koše? Tuto akci nelze vrátit.", "Vysypat") {
            runJob("Vysypávám koš") { job -> deleteTrees(LocalFs, dirs.map { it.path }, job.cancel) { job.status(it) } }
        }
    }

    // ---- Copy and move ----

    fun copyMove(kind: OpKind) {
        if (busy) return
        val source = active
        val entries = source.selectedEntries()
        if (entries.isEmpty()) return
        val srcLoc = source.loc
        val verb = if (kind == OpKind.Copy) "Kopírovat" else "Přesunout"
        val title = when {
            srcLoc is Loc.Archive -> "Rozbalit"
            other.loc is Loc.Archive -> "Přidat do archivu"
            else -> verb
        }
        dialogs += Dlg.Prompt(title, "$title ${what(entries)} do:", other.loc.display(), title) { typed ->
            val target = parseLoc(typed, srcLoc) ?: return@Prompt showError("Neplatná cílová cesta")
            startCopyMove(kind, source, srcLoc, entries, target)
        }
    }

    private fun startCopyMove(kind: OpKind, source: PanelState, srcLoc: Loc, entries: List<FileEntry>, target: Loc) {
        val paths = entries.map { srcLoc.pathOf(it.name) }
        val done: (Boolean) -> Unit = { ok -> if (ok) source.clearMarks() }
        when {
            srcLoc is Loc.Archive && target is Loc.Archive -> showError("Kopírování mezi dvěma archivy není podporováno")
            (srcLoc is Loc.Archive || target is Loc.Archive) && kind == OpKind.Move ->
                showError("Přesun z archivu nebo do archivu není podporován; použijte F5 a potom F8")
            srcLoc is Loc.Archive -> runJob("Rozbaluji", onDone = done) { job ->
                val fs = fsOf(target)!!
                ensureDirs(fs, target.dir)
                val transfer = job.transfer(fs)
                Archives.extract(File(srcLoc.archive), paths, transfer, target.dir)
                transfer.errors
            }
            target is Loc.Archive -> {
                if (srcLoc !is Loc.Local) return showError("Do archivu lze přidávat jen místní soubory; ze serveru je nejprve stáhněte")
                runJob("Přidávám do archivu", onDone = done) { job ->
                    Archives.update(File(target.archive), paths.map { File(it) }, target.inner, emptyList(), job, job.cancel)
                }
            }
            else -> {
                val title = when {
                    srcLoc !is Loc.Local && target is Loc.Local -> "Stahuji"
                    srcLoc is Loc.Local && target !is Loc.Local -> "Nahrávám"
                    kind == OpKind.Copy -> "Kopíruji"
                    else -> "Přesouvám"
                }
                runJob(title, onDone = done) { job ->
                    val dst = fsOf(target)!!
                    ensureDirs(dst, target.dir)
                    job.transfer(dst).copyTree(fsOf(srcLoc)!!, paths, target.dir, kind == OpKind.Move).errors
                }
            }
        }
    }

    // ---- Clipboard ----

    fun clipboardCopy(cut: Boolean) {
        val loc = active.loc
        if (unsupported(loc)) return
        val paths = active.selectedPaths()
        if (paths.isEmpty()) return
        clip = Triple(loc.dir, paths, cut)
        flash(if (cut) "Vyjmuto: ${paths.size}" else "Zkopírováno do schránky: ${paths.size}")
    }

    fun clipboardPaste() {
        val loc = active.loc
        if (unsupported(loc)) return
        val (_, paths, cut) = clip ?: return flash("Schránka je prázdná")
        runJob(if (cut) "Přesouvám" else "Kopíruji") { job ->
            job.transfer(LocalFs).copyTree(LocalFs, paths, loc.dir, cut).errors
        }
        if (cut) clip = null
    }

    fun copyPathText() {
        val entries = active.selectedEntries()
        if (entries.isEmpty()) return
        val base = active.loc
        val text = entries.joinToString("\n") { base.child(it.name).display() }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("cesta", text))
        flash("Cesta zkopírována: ${entries.size}")
    }

    // ---- Selection tools ----

    fun markGroup(mark: Boolean) {
        val panel = active
        val title = if (mark) "Vybrat skupinu" else "Odznačit skupinu"
        dialogs += Dlg.Prompt(title, "Maska (např. *.jpg;*.png|tmp*):", "*", if (mark) "Vybrat" else "Odznačit") {
            panel.markMatching(Mask.parse(it), mark)
        }
    }

    fun compareDirectories() {
        val (leftNames, rightNames) = compareListings(left.compareItems(), right.compareItems())
        left.markNames(leftNames)
        right.markNames(rightNames)
        flash(
            if (leftNames.isEmpty() && rightNames.isEmpty()) "Adresáře obsahují stejné soubory"
            else "Odlišné soubory: ${leftNames.size} vlevo, ${rightNames.size} vpravo"
        )
    }

    fun calculateSize() {
        val panel = active
        val loc = panel.loc
        if (loc is Loc.Archive) return showError(ARCHIVE_UNSUPPORTED)
        val entries = panel.selectedEntries()
        if (entries.isEmpty()) return
        val sizes = HashMap<String, Long>()
        val total = Measure()
        runJob("Počítám obsazené místo", reload = false, onDone = {
            panel.addDirSizes(sizes)
            flash("${total.files} souborů, ${total.dirs} adresářů — ${formatBytes(total.bytes)}")
        }) { job ->
            val fs = fsOf(loc)!!
            for (entry in entries) {
                if (job.cancel.get()) break
                job.status(entry.name)
                val measured = measure(fs, loc.pathOf(entry.name), { job.cancel.get() }, entry)
                if (entry.kind == EntryKind.Directory) sizes[entry.name] = measured.bytes
                total.add(measured)
            }
            emptyList()
        }
    }

    // ---- Properties and attributes ----

    fun properties() {
        val entries = active.selectedEntries()
        if (entries.isNotEmpty()) dialogs += Dlg.Properties(active.loc, entries)
    }

    fun attributes() {
        val loc = active.loc
        if (loc is Loc.Archive) return showError(ARCHIVE_UNSUPPORTED)
        val entries = active.selectedEntries()
        if (entries.isNotEmpty()) dialogs += Dlg.Attributes(loc, entries)
    }

    /** Recursive totals for the properties dialog; runs on a worker thread. */
    suspend fun measureEntries(loc: Loc, entries: List<FileEntry>, cancel: AtomicBoolean): Measure? = withContext(Dispatchers.IO) {
        val fs = try {
            fsOf(loc)
        } catch (_: IOException) {
            null
        } ?: return@withContext null
        val total = Measure()
        for (entry in entries) total.add(measure(fs, loc.pathOf(entry.name), { cancel.get() }, entry))
        total
    }

    fun applyAttributes(loc: Loc, entries: List<FileEntry>, mode: Int?, modified: Long?, recursive: Boolean) {
        if (mode == null && modified == null) return
        runJob("Měním atributy") { job ->
            val fs = fsOf(loc)!!
            val errors = ArrayList<OpError>()
            fun apply(path: String, entry: FileEntry) {
                if (job.cancel.get()) return
                job.status(path)
                try {
                    if (entry.kind != EntryKind.Symlink) {
                        if (mode != null) {
                            // Like `chmod X`: execute bits stay off for plain files that had none.
                            val effective = if (recursive && entry.kind == EntryKind.File && (entry.mode ?: 0) and 0x49 == 0) mode and 0x49.inv() else mode
                            fs.setMode(path, effective)
                        }
                        if (modified != null) fs.setModified(path, modified)
                    }
                    if (recursive && entry.kind == EntryKind.Directory) {
                        for (child in fs.list(path)) apply(joinPath(path, child.name), child)
                    }
                } catch (error: IOException) {
                    errors += OpError(path, error.message ?: "Chyba")
                }
            }
            for (entry in entries) apply(loc.pathOf(entry.name), entry)
            errors
        }
    }

    // ---- Archives ----

    fun pack() {
        if (busy) return
        val panel = active
        val loc = panel.loc
        if (unsupported(loc)) return
        val entries = panel.selectedEntries()
        if (entries.isEmpty()) return
        val destDir = (other.loc as? Loc.Local)?.path ?: loc.dir
        val name = if (entries.size == 1) ArchiveFormat.stem(entries[0].name) else File(loc.dir).name.ifEmpty { "archiv" }
        dialogs += Dlg.Pack(entries.size, destDir, name) { typed ->
            val target = File(if (typed.startsWith("/")) normalizePath(typed) else normalizePath(joinPath(loc.dir, typed)))
            if (ArchiveFormat.of(target.name)?.writable != true) return@Pack showError("Neznámý formát archivu: ${target.name}")
            if (target.exists()) return@Pack showError("${target.path} již existuje")
            val sources = entries.map { File(loc.pathOf(it.name)) }
            runJob("Balím do ${target.name}", onDone = { ok -> if (ok) panel.clearMarks() }) { job ->
                target.parentFile?.mkdirs()
                Archives.pack(target, sources, job, job.cancel)
            }
        }
    }

    fun unpack() {
        if (busy) return
        val panel = active
        val loc = panel.loc
        when (loc) {
            is Loc.Remote -> return showError(REMOTE_UNSUPPORTED)
            is Loc.Cloud -> return showError(CLOUD_UNSUPPORTED)
            // Inside an archive Alt+F9 extracts the selection, like F5.
            is Loc.Archive -> return copyMove(OpKind.Copy)
            is Loc.Local -> {}
        }
        val archives = panel.selectedEntries().filter { !it.isDirLike && ArchiveFormat.of(it.name) != null }
        if (archives.isEmpty()) return showError("Výběr neobsahuje žádný podporovaný archiv")
        val dest = other.loc.takeIf { it !is Loc.Archive }?.display() ?: loc.dir
        dialogs += Dlg.Unpack(archives.size, dest) { typed, subfolders ->
            val target = parseLoc(typed, loc) ?: return@Unpack showError("Neplatná cílová cesta")
            if (target is Loc.Archive) return@Unpack showError("Do archivu rozbalovat nelze")
            runJob("Rozbaluji", onDone = { ok -> if (ok) panel.clearMarks() }) { job ->
                val fs = fsOf(target)!!
                val transfer = job.transfer(fs)
                for (archive in archives) {
                    if (job.cancel.get()) break
                    val dir = if (subfolders) joinPath(target.dir, ArchiveFormat.stem(archive.name)) else target.dir
                    try {
                        ensureDirs(fs, dir)
                        Archives.extract(File(loc.pathOf(archive.name)), listOf(""), transfer, dir)
                    } catch (error: Exception) {
                        transfer.fail(archive.name, error)
                    }
                }
                transfer.errors
            }
        }
    }

    // ---- Tools ----

    fun find() {
        val loc = active.loc
        if (unsupported(loc)) return
        dialogs += Dlg.Find(loc.dir)
    }

    fun goToFound(path: String) {
        val parent = parentPath(path) ?: return
        active.navigate(Loc.Local(parent), focus = File(path).name)
    }

    fun places(panel: PanelState) {
        dialogs += Dlg.Places(panel)
    }

    // ---- Remote servers ----

    private var vault: Vault? = null
    private var vaultKey: Vault.Key? = null
    private var vaultLastUse = 0L
    private val vaultLock = Any()

    val vaultUnlocked: Boolean get() = vaultKey != null

    /** Unlocks (or creates) the vault, asking for the master password. Worker threads only. */
    private fun unlockedVault(): Pair<Vault, Vault.Key>? = synchronized(vaultLock) {
        vaultKey?.let { key ->
            vaultLastUse = System.currentTimeMillis()
            return vault!! to key
        }
        val existing = Vault.load(vaultFile)
        if (existing == null) {
            val master = ask<CharArray?> { Dlg.NewMaster("Nový trezor hesel", it) } ?: return null
            val (created, key) = Vault.create(vaultFile, master)
            master.fill(' ')
            vault = created
            vaultKey = key
        } else {
            var error: String? = null
            while (true) {
                val master = ask<CharArray?> {
                    Dlg.Secret("Odemknout trezor hesel", "Zadejte hlavní heslo trezoru:", error, "Odemknout", it)
                } ?: return null
                val key = existing.unlock(master)
                master.fill(' ')
                if (key != null) {
                    vault = existing
                    vaultKey = key
                    break
                }
                error = "Nesprávné hlavní heslo."
            }
        }
        vaultLastUse = System.currentTimeMillis()
        vault!! to vaultKey!!
    }

    fun lockVault(silent: Boolean = false) {
        synchronized(vaultLock) {
            vaultKey?.wipe()
            vaultKey = null
            vault = null
        }
        if (!silent) flash("Trezor hesel je zamčený")
    }

    fun changeMaster() {
        if (!vaultFile.exists()) return showError("Trezor hesel zatím neexistuje; vytvoří se při prvním uložení hesla.")
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val (store, key) = unlockedVault() ?: return@withContext null
                    val master = ask<CharArray?> { Dlg.NewMaster("Změnit hlavní heslo trezoru", it) } ?: return@withContext null
                    synchronized(vaultLock) { vaultKey = store.changeMaster(key, master) }
                    key.wipe()
                    master.fill(' ')
                    "Hlavní heslo trezoru bylo změněno"
                } catch (error: Exception) {
                    "Chyba: ${error.message}"
                }
            }
            message?.let { flash(it) }
        }
    }

    /** The unlocked vault when there is one, otherwise a fresh read of the file. */
    private fun vaultView(): Vault? = synchronized(vaultLock) { vault ?: Vault.load(vaultFile) }

    fun hasStoredPassword(server: Server): Boolean = try {
        vaultView()?.contains(server.id) == true
    } catch (_: IOException) {
        false
    }

    private fun removeStoredPassword(server: Server) {
        try {
            vaultView()?.remove(server.id)
        } catch (error: IOException) {
            remoteLog.log(server.label(), "CHYBA trezoru", error.message.orEmpty())
        }
    }

    private val prompts = object : RemotePrompts {
        override fun confirmHostKey(server: Server, keyType: String, fingerprint: String): Boolean =
            ask { Dlg.HostKey(server, keyType, fingerprint, it) }

        override fun password(server: Server, failed: Boolean): PasswordAnswer? = ask { Dlg.PasswordAsk(server, failed, it) }

        override fun passphrase(server: Server, keyFile: String): CharArray? =
            ask { Dlg.Secret("Heslo ke klíči", "Heslo k soukromému klíči\n$keyFile:", null, "Použít", it) }

        override fun storedPassword(server: Server): CharArray? {
            if (!hasStoredPassword(server)) return null
            return try {
                val (store, key) = unlockedVault() ?: return null
                store.get(key, server.id)
            } catch (error: IOException) {
                remoteLog.log(server.label(), "CHYBA trezoru", error.message.orEmpty())
                null
            }
        }

        override fun storePassword(server: Server, password: CharArray) {
            try {
                val (store, key) = unlockedVault() ?: return
                store.set(key, server.id, password)
                remoteLog.log(server.label(), "heslo uloženo do trezoru")
            } catch (error: IOException) {
                remoteLog.log(server.label(), "CHYBA trezoru", error.message.orEmpty())
            }
        }
    }

    val connections = Connections(knownHosts, remoteLog, prompts)

    fun serverManager() {
        dialogs += Dlg.ServerManager()
    }

    fun connect(server: Server, panel: PanelState = active) {
        panel.navigate(Loc.Remote(server, ""))
    }

    fun disconnectActive() {
        val loc = active.loc as? Loc.Remote ?: return flash("Aktivní panel není připojen k serveru")
        for (panel in listOf(left, right)) {
            if ((panel.loc as? Loc.Remote)?.server?.id == loc.server.id) panel.navigate(Loc.Local(home))
        }
        io({ connections.disconnect(loc.server) }) { flash("Odpojeno: ${loc.server.displayName()}") }
    }

    private fun saveServers(list: List<Server>) {
        servers = list
        try {
            Servers.save(serversFile, list)
        } catch (error: IOException) {
            showError(error.message ?: "Nelze uložit seznam serverů")
        }
    }

    fun editServer(server: Server, creating: Boolean) {
        dialogs += Dlg.ServerEdit(server, creating, !creating && hasStoredPassword(server)) { edited, password, forget ->
            saveServers(if (creating) servers + edited else servers.map { if (it.id == edited.id) edited else it })
            if (!creating) {
                for (panel in listOf(left, right)) {
                    if ((panel.loc as? Loc.Remote)?.server?.id == edited.id) panel.navigate(Loc.Local(home))
                }
            }
            io({
                if (!creating) connections.disconnect(server)
                if (forget || !edited.savePassword) {
                    removeStoredPassword(edited)
                } else if (password.isNotEmpty()) {
                    prompts.storePassword(edited, password.toCharArray())
                }
            })
        }
    }

    fun duplicateServer(server: Server) {
        saveServers(servers + server.copy(id = cz.k4.salamanderdroid.remote.randomHex(8), name = "${server.displayName()} (kopie)"))
    }

    fun deleteServer(server: Server) {
        dialogs += Dlg.Confirm("Smazat server", "Smazat server ${server.displayName()}? Smaže se i jeho uložené heslo v trezoru.", "Smazat") {
            saveServers(servers.filter { it.id != server.id })
            for (panel in listOf(left, right)) {
                if ((panel.loc as? Loc.Remote)?.server?.id == server.id) panel.navigate(Loc.Local(home))
            }
            io({
                connections.disconnect(server)
                removeStoredPassword(server)
            })
        }
    }

    fun forgetHostKey(server: Server) {
        dialogs += Dlg.Confirm(
            "Zapomenout klíč serveru",
            "Zapomenout uložený klíč serveru ${server.label()}? Při příštím připojení se znovu zobrazí otisk k potvrzení.",
            "Zapomenout",
        ) {
            io({
                knownHosts.forget(server.host, server.port)
                remoteLog.log(server.label(), "klíč serveru zapomenut")
            }) { flash("Klíč serveru zapomenut") }
        }
    }

    fun showRemoteLog() {
        scope.launch {
            val text = withContext(Dispatchers.IO) { remoteLog.tail() }
            dialogs += Dlg.Message("Log vzdálených operací", text.ifEmpty { "Log je prázdný." }, mono = true)
        }
    }

    // ---- Cloud drives (OneDrive, Google Drive) ----

    private val cloudStore = CloudStore(File(context.filesDir, "cloud.json"), KeystoreSealer())
    var cloudAccounts by mutableStateOf(cloudStore.accounts())
        private set
    private val cloudHttp by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS).build()
    }
    private val cloudEndpoints = CloudEndpoints()
    private val cloudSessions = HashMap<String, CloudFs>()
    private val cloudSpaces = HashMap<String, Pair<Long, Pair<Long, Long>?>>()

    fun cloudSignedIn(account: CloudAccount): Boolean = cloudStore.tokens(account.id) != null

    private fun newCloudFs(account: CloudAccount, tokens: Tokens): CloudFs {
        val session = CloudSession(cloudHttp, OAuthConfig.of(account, cloudEndpoints), tokens) { cloudStore.setTokens(account.id, it) }
        val temp = File(context.cacheDir, "upload")
        return when (account.kind) {
            CloudKind.OneDrive -> GraphFs(account, session, temp, cloudEndpoints.graph)
            CloudKind.GoogleDrive -> DriveFs(account, session, temp, cloudEndpoints.drive, cloudEndpoints.driveUpload)
        }
    }

    private fun cloudFs(account: CloudAccount): CloudFs = synchronized(cloudSessions) {
        cloudSessions.getOrPut(account.id) {
            val tokens = cloudStore.tokens(account.id)
                ?: throw IOException("Účet ${account.displayName()} není přihlášen (Vzdálené → Cloudové účty → Přihlásit)")
            newCloudFs(account, tokens)
        }
    }

    /** Free and total space, asked at most once a minute per account. */
    private fun cloudSpace(fs: CloudFs): Pair<Long, Long>? = synchronized(cloudSpaces) {
        val cached = cloudSpaces[fs.account.id]
        if (cached != null && System.currentTimeMillis() - cached.first < 60_000) return cached.second
        val space = fs.space()
        cloudSpaces[fs.account.id] = System.currentTimeMillis() to space
        space
    }

    fun cloudAccountsDialog() {
        dialogs += Dlg.CloudAccounts()
    }

    fun openCloud(account: CloudAccount, panel: PanelState = active) {
        panel.navigate(Loc.Cloud(account, "/"))
    }

    private fun leaveCloud(id: String) {
        synchronized(cloudSessions) { cloudSessions.remove(id) }
        for (panel in listOf(left, right)) {
            if ((panel.loc as? Loc.Cloud)?.account?.id == id) panel.navigate(Loc.Local(home))
        }
    }

    fun editCloudAccount(account: CloudAccount, creating: Boolean) {
        dialogs += Dlg.CloudAccountEdit(account, creating) { edited ->
            leaveCloud(edited.id)
            // A changed application registration invalidates the stored sign-in.
            val keep = !creating && edited.clientId == account.clientId && edited.clientSecret == account.clientSecret && edited.tenant == account.tenant
            cloudStore.put(edited, if (keep) cloudStore.tokens(account.id) else null)
            cloudAccounts = cloudStore.accounts()
            if (!keep) signInCloud(edited)
        }
    }

    /** Opens the system browser for sign-in and waits for it to come back. */
    fun signInCloud(account: CloudAccount) {
        runJob("Přihlášení: ${account.displayName()}", reload = false, onDone = { ok ->
            cloudAccounts = cloudStore.accounts()
            if (ok) flash("Přihlášeno: ${cloudAccounts.firstOrNull { it.id == account.id }?.user.orEmpty()}")
        }) { job ->
            job.status("Dokončete přihlášení v prohlížeči a vraťte se do aplikace…")
            val tokens = OAuth.authorize(cloudHttp, OAuthConfig.of(account, cloudEndpoints), { url ->
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, job.cancel)
            cloudStore.put(account, tokens)
            synchronized(cloudSessions) { cloudSessions.remove(account.id) }
            val user = try {
                newCloudFs(account, tokens).userName()
            } catch (_: IOException) {
                ""
            }
            cloudStore.put(account.copy(user = user), cloudStore.tokens(account.id))
            emptyList()
        }
    }

    fun removeCloudAccount(account: CloudAccount) {
        dialogs += Dlg.Confirm("Odebrat účet", "Odebrat účet ${account.displayName()} z aplikace? Soubory v cloudu zůstanou.", "Odebrat") {
            leaveCloud(account.id)
            cloudStore.remove(account.id)
            cloudAccounts = cloudStore.accounts()
        }
    }

    fun sortBy(key: SortKey) = active.sortBy(key)
}

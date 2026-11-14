package cz.k4.salamanderdroid.ui

import android.os.FileObserver
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.k4.salamanderdroid.cloud.CloudAccount
import cz.k4.salamanderdroid.core.CompareItem
import cz.k4.salamanderdroid.core.EntryKind
import cz.k4.salamanderdroid.core.FileEntry
import cz.k4.salamanderdroid.core.Mask
import cz.k4.salamanderdroid.core.SortKey
import cz.k4.salamanderdroid.core.entryComparator
import cz.k4.salamanderdroid.core.joinPath
import cz.k4.salamanderdroid.core.parentPath
import cz.k4.salamanderdroid.core.wildcardMatch
import cz.k4.salamanderdroid.remote.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where a panel is: a local directory, a directory inside an archive, or on a server. */
sealed interface Loc {
    data class Local(val path: String) : Loc
    data class Archive(val archive: String, val inner: String) : Loc
    data class Remote(val server: Server, val path: String) : Loc
    data class Cloud(val account: CloudAccount, val path: String) : Loc

    /** Directory path inside the location's own file system. */
    val dir: String
        get() = when (this) {
            is Local -> path
            is Archive -> inner
            is Remote -> path
            is Cloud -> path
        }

    fun display(): String = when (this) {
        is Local -> path
        is Archive -> if (inner.isEmpty()) archive else "$archive/$inner"
        is Remote -> server.url() + path
        is Cloud -> "${account.kind.scheme}${account.pathName()}$path"
    }

    fun pathOf(name: String): String = joinPath(dir, name)

    fun child(name: String): Loc = when (this) {
        is Local -> Local(joinPath(path, name))
        is Archive -> Archive(archive, joinPath(inner, name))
        is Remote -> Remote(server, joinPath(path, name))
        is Cloud -> Cloud(account, joinPath(path, name))
    }

    /** Parent location and the name to put the cursor on there. */
    fun parent(): Pair<Loc, String>? = when (this) {
        is Local -> parentPath(path)?.let { Local(it) to File(path).name }
        is Archive ->
            if (inner.isEmpty()) Local(parentPath(archive) ?: "/") to File(archive).name
            else Archive(archive, inner.substringBeforeLast('/', "")) to inner.substringAfterLast('/')
        is Remote -> parentPath(path)?.let { Remote(server, it) to path.trimEnd('/').substringAfterLast('/') }
        is Cloud -> parentPath(path)?.let { Cloud(account, it) to path.trimEnd('/').substringAfterLast('/') }
    }
}

class Listing(val loc: Loc, val entries: List<FileEntry>, val space: Pair<Long, Long>?)

class PanelState(private val app: AppState, start: Loc) {
    var loc by mutableStateOf(start)
        private set
    var rows by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var cursor by mutableStateOf(0)
        private set
    var marked by mutableStateOf<Set<String>>(emptySet())
        private set
    var dirSizes by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    var sortKey by mutableStateOf(SortKey.Name)
        private set
    var sortDescending by mutableStateOf(false)
        private set
    var quickSearch by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var space by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    var note by mutableStateOf<String?>(null)

    val listState = LazyListState()

    /** Number of fully visible rows, reported by the UI; used for PageUp/PageDown. */
    var pageRows = 20

    private var raw: List<FileEntry> = emptyList()
    private val history = ArrayList<Loc>()
    private var historyIndex = -1
    private var generation = 0
    private var observer: FileObserver? = null
    private var refreshJob: Job? = null

    // ---- Navigation ----

    fun navigate(target: Loc, focus: String? = null, record: Boolean = true, quiet: Boolean = false, fallback: Loc? = null) {
        val mine = ++generation
        loading = true
        app.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.list(target) } }
            if (mine != generation) return@launch
            loading = false
            result.onSuccess { listing ->
                val changed = listing.loc != loc || !loaded
                val keepName = if (changed) null else cursorEntry()?.name
                loc = listing.loc
                raw = listing.entries
                space = listing.space
                loaded = true
                if (changed) {
                    marked = emptySet()
                    dirSizes = emptyMap()
                    quickSearch = ""
                    note = null
                    if (record) pushHistory(listing.loc)
                    watch(listing.loc)
                } else {
                    val names = raw.mapTo(HashSet()) { it.name }
                    marked = marked.filterTo(HashSet()) { it in names }
                    dirSizes = dirSizes.filterKeys { it in names }
                }
                rebuild(focus ?: keepName, resetCursor = changed)
            }.onFailure { error ->
                if (fallback != null) {
                    navigate(fallback, record = record)
                } else if (!quiet) {
                    app.showError(error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    private fun pushHistory(target: Loc) {
        if (historyIndex >= 0 && history[historyIndex] == target) return
        while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
        history += target
        if (history.size > 50) history.removeAt(0)
        historyIndex = history.size - 1
    }

    fun history(): List<Loc> = history.asReversed().distinct()

    fun historyStep(delta: Int) {
        val index = historyIndex + delta
        if (index !in history.indices) return
        historyIndex = index
        navigate(history[index], record = false)
    }

    fun reload(quiet: Boolean = true, focus: String? = null) = navigate(loc, focus = focus, record = false, quiet = quiet)

    fun navigateParent() {
        val (parent, focus) = loc.parent() ?: return
        navigate(parent, focus)
    }

    private fun rebuild(focusName: String?, resetCursor: Boolean = false) {
        val config = app.config
        val visible = if (config.showHidden) raw else raw.filter { !it.isHidden }
        val sorted = visible.sortedWith(entryComparator(sortKey, sortDescending) { dirSizes[it.name] ?: 0 })
        rows = if (config.showParentEntry && loc.parent() != null) listOf(FileEntry.parentLink()) + sorted else sorted
        val index = focusName?.let { name -> rows.indexOfFirst { it.name == name && !it.isParent } } ?: -1
        cursor = when {
            index >= 0 -> index
            resetCursor -> 0
            else -> cursor.coerceIn(0, maxOf(rows.size - 1, 0))
        }
    }

    /** Re-applies hidden filter, sorting and the parent row after a settings change. */
    fun refreshView() = rebuild(cursorEntry()?.name)

    fun sortBy(key: SortKey) {
        if (sortKey == key) sortDescending = !sortDescending else {
            sortKey = key
            // Newest and largest first is what one usually wants.
            sortDescending = key == SortKey.Time || key == SortKey.Size
        }
        rebuild(cursorEntry()?.name)
    }

    // ---- Directory watching (local directories only) ----

    private fun watch(target: Loc) {
        observer?.stopWatching()
        observer = null
        if (target !is Loc.Local) return
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.ATTRIB
        observer = object : FileObserver(File(target.path), mask) {
            override fun onEvent(event: Int, path: String?) {
                app.scope.launch { scheduleRefresh() }
            }
        }.also {
            try {
                it.startWatching()
            } catch (_: Exception) {
            }
        }
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = app.scope.launch {
            delay(500)
            // A running operation reloads the panels itself when it finishes.
            if (!app.busy) reload()
        }
    }

    fun dispose() {
        observer?.stopWatching()
        observer = null
    }

    // ---- Cursor and marks ----

    fun cursorEntry(): FileEntry? = rows.getOrNull(cursor)

    fun moveCursor(index: Int) {
        if (rows.isEmpty()) return
        cursor = index.coerceIn(0, rows.size - 1)
    }

    fun moveCursorBy(delta: Int) = moveCursor(cursor + delta)

    /** Marked entries in display order, or the entry under the cursor. */
    fun selectedEntries(): List<FileEntry> {
        val chosen = rows.filter { !it.isParent && it.name in marked }
        if (chosen.isNotEmpty()) return chosen
        return listOfNotNull(cursorEntry()?.takeIf { !it.isParent })
    }

    fun selectedPaths(): List<String> = selectedEntries().map { loc.pathOf(it.name) }

    fun toggleMark(index: Int) {
        val entry = rows.getOrNull(index) ?: return
        if (entry.isParent) return
        marked = if (entry.name in marked) marked - entry.name else marked + entry.name
    }

    /** Marks every row between two positions (Shift+click). */
    fun markRange(from: Int, to: Int) {
        val names = (minOf(from, to)..maxOf(from, to)).mapNotNull { rows.getOrNull(it) }.filter { !it.isParent }.map { it.name }
        marked = marked + names
    }

    fun toggleMarkAndAdvance() {
        toggleMark(cursor)
        moveCursorBy(1)
    }

    fun markAndMove(delta: Int) {
        toggleMark(cursor)
        moveCursorBy(delta)
    }

    fun markAll() {
        marked = rows.filter { !it.isParent }.mapTo(HashSet()) { it.name }
    }

    fun clearMarks() {
        marked = emptySet()
    }

    fun invertMarks() {
        marked = rows.filter { !it.isParent && it.name !in marked }.mapTo(HashSet()) { it.name }
    }

    fun markMatching(mask: Mask, mark: Boolean) {
        val names = rows.filter { !it.isParent && mask.matches(it.name) }.map { it.name }
        marked = if (mark) marked + names else marked - names.toSet()
    }

    fun markNames(names: Set<String>) {
        marked = rows.filter { !it.isParent && it.name in names }.mapTo(HashSet()) { it.name }
    }

    fun compareItems(): List<CompareItem> =
        rows.filter { !it.isParent && !it.isDirLike }.map { CompareItem(it.name, it.size, it.modified) }

    fun addDirSizes(sizes: Map<String, Long>) {
        dirSizes = dirSizes + sizes
        if (sortKey == SortKey.Size) rebuild(cursorEntry()?.name)
    }

    fun effectiveSize(entry: FileEntry): Long = if (entry.isDirLike) dirSizes[entry.name] ?: 0 else entry.size

    // ---- Quick search ----

    private fun findQuickMatch(query: String, start: Int, forward: Boolean): Int? {
        val folded = query.lowercase()
        val wildcard = query.contains('*') || query.contains('?')
        fun matches(index: Int): Boolean {
            val entry = rows[index]
            return !entry.isParent &&
                if (wildcard) wildcardMatch("$folded*", entry.foldedName) else entry.foldedName.startsWith(folded)
        }
        val range = if (forward) start until rows.size else minOf(start, rows.size - 1) downTo 0
        return range.firstOrNull { matches(it) }
    }

    /** Extends the search text; keeps the old text when nothing matches the longer one. */
    fun quickSearchPush(char: Char): Boolean {
        val query = quickSearch + char
        val index = findQuickMatch(query, 0, true) ?: return false
        quickSearch = query
        moveCursor(index)
        return true
    }

    fun quickSearchPop() {
        quickSearch = quickSearch.dropLast(1)
        if (quickSearch.isNotEmpty()) findQuickMatch(quickSearch, 0, true)?.let { moveCursor(it) }
    }

    fun quickSearchNext(forward: Boolean) {
        if (quickSearch.isEmpty()) return
        val start = if (forward) cursor + 1 else cursor - 1
        if (start < 0) return
        findQuickMatch(quickSearch, start, forward)?.let { moveCursor(it) }
    }

    fun quickSearchEnd() {
        quickSearch = ""
    }

    // ---- Status texts ----

    fun infoText(): String {
        if (quickSearch.isNotEmpty()) return "Rychlé hledání: $quickSearch"
        val entry = cursorEntry() ?: return ""
        if (entry.isParent) return "..   (nadřazená složka)"
        val text = StringBuilder(entry.name)
        entry.linkTarget?.let { text.append(" → ").append(it) }
        text.append("   ").append(displaySize(entry))
        text.append("   ").append(cz.k4.salamanderdroid.core.formatTime(entry.modified))
        val mode = entry.modeText()
        if (mode.isNotEmpty()) text.append("   ").append(mode)
        if (entry.owner.isNotEmpty()) text.append("   ").append(entry.owner).append(':').append(entry.group)
        return text.toString()
    }

    fun displaySize(entry: FileEntry): String {
        if (entry.kind == EntryKind.Directory) {
            dirSizes[entry.name]?.let { return cz.k4.salamanderdroid.core.formatBytes(it) }
        }
        return cz.k4.salamanderdroid.core.formatSize(entry)
    }

    fun statusText(): String {
        var files = 0
        var dirs = 0
        var bytes = 0L
        var markedFiles = 0
        var markedDirs = 0
        var markedBytes = 0L
        for (entry in rows) {
            if (entry.isParent) continue
            val size = effectiveSize(entry)
            if (entry.isDirLike) dirs++ else files++
            bytes += size
            if (entry.name in marked) {
                if (entry.isDirLike) markedDirs++ else markedFiles++
                markedBytes += size
            }
        }
        val format: (Long) -> String = { cz.k4.salamanderdroid.core.formatBytes(it) }
        val text = StringBuilder()
        note?.let { text.append(it).append("   |   ") }
        if (markedFiles + markedDirs > 0) {
            text.append("Vybráno $markedFiles z $files souborů, $markedDirs z $dirs adresářů — ${format(markedBytes)} z ${format(bytes)}")
        } else {
            text.append("$files souborů, $dirs adresářů — ${format(bytes)}")
        }
        space?.let { (available, total) -> text.append("   |   Volno ${format(available)} z ${format(total)}") }
        return text.toString()
    }
}

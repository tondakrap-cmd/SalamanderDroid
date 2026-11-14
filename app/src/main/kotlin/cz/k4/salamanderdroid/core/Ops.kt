package cz.k4.salamanderdroid.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class OpKind { Copy, Move }

enum class ConflictAction { Overwrite, Skip, OverwriteAll, SkipAll, Abort }

data class Conflict(
    val source: String,
    val target: String,
    val sourceSize: Long,
    val sourceModified: Long?,
    val targetEntry: FileEntry,
)

data class OpProgress(
    val doneFiles: Long,
    val doneBytes: Long,
    val totalFiles: Long,
    val totalBytes: Long,
    val current: String,
)

class OpResult(val cancelled: Boolean, val errors: List<OpError>)

interface OpListener {
    fun preparing(current: String) {}
    fun progress(progress: OpProgress) {}

    /** Called on the worker thread; blocks until the user decides. */
    fun conflict(conflict: Conflict): ConflictAction = ConflictAction.Skip
}

const val PART_SUFFIX = ".srs-part"
private const val BUFFER_SIZE = 256 * 1024
private const val PROGRESS_INTERVAL_MS = 60L

private fun describe(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

/**
 * Writes files into a destination file system with conflict handling, progress and
 * cancellation. Tree copies, moves, archive extraction and uploads all go through it.
 */
class Transfer(
    val dst: Fs,
    private val listener: OpListener,
    private val cancel: AtomicBoolean,
) {
    val errors = ArrayList<OpError>()
    private var overwriteAll = false
    private var skipAll = false
    private var doneFiles = 0L
    private var doneBytes = 0L
    var totalFiles = 0L
    var totalBytes = 0L
    private var lastEmit = 0L

    val cancelled: Boolean get() = cancel.get()

    fun result() = OpResult(cancelled, errors)

    fun fail(path: String, error: Throwable) {
        errors += OpError(path, describe(error))
    }

    fun fail(path: String, message: String) {
        errors += OpError(path, message)
    }

    private fun emit(current: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmit < PROGRESS_INTERVAL_MS) return
        lastEmit = now
        listener.progress(OpProgress(doneFiles, doneBytes, totalFiles, totalBytes, current))
    }

    /** Accounts for a file that was skipped or moved without copying. */
    fun skipped(current: String, files: Long, bytes: Long) {
        doneFiles += files
        doneBytes += bytes
        emit(current)
    }

    /** Ensures [target] is a directory (existing directories are merged into). */
    fun putDir(target: String): Boolean {
        val existing = try {
            dst.stat(target)
        } catch (error: IOException) {
            fail(target, error)
            return false
        }
        if (existing != null) {
            if (existing.isDirLike) return true
            fail(target, "Cíl existuje a není adresář")
            return false
        }
        return try {
            dst.mkdir(target)
            true
        } catch (error: IOException) {
            fail(target, error)
            false
        }
    }

    /**
     * Writes one file. The data goes to a temporary name first and replaces the target only
     * when complete, so a failed or cancelled copy never damages an existing file.
     * Returns true when the target now holds the source content.
     */
    fun putFile(
        source: String,
        target: String,
        size: Long,
        modified: Long?,
        mode: Int?,
        open: () -> InputStream,
    ): Boolean {
        if (cancelled) return false
        val existing = try {
            dst.stat(target)
        } catch (error: IOException) {
            fail(target, error)
            skipped(source, 1, maxOf(size, 0))
            return false
        }
        if (existing != null) {
            if (existing.kind == EntryKind.Directory) {
                fail(target, "Cíl je adresář")
                skipped(source, 1, maxOf(size, 0))
                return false
            }
            val action = when {
                overwriteAll -> ConflictAction.Overwrite
                skipAll -> ConflictAction.Skip
                else -> listener.conflict(Conflict(source, target, size, modified, existing))
            }
            when (action) {
                ConflictAction.OverwriteAll -> overwriteAll = true
                ConflictAction.SkipAll -> skipAll = true
                ConflictAction.Abort -> cancel.set(true)
                else -> {}
            }
            if (action == ConflictAction.Abort) return false
            if (action == ConflictAction.Skip || action == ConflictAction.SkipAll) {
                skipped(source, 1, maxOf(size, 0))
                return false
            }
        }
        val direct = dst.directWrites
        val part = if (direct) target else target + PART_SUFFIX
        val startBytes = doneBytes
        try {
            open().use { input ->
                dst.openWrite(part, size).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancelled) throw CancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        doneBytes += read
                        emit(source)
                    }
                }
            }
            if (!direct) {
                if (existing != null) dst.deleteFile(target)
                dst.rename(part, target)
            }
        } catch (error: Exception) {
            if (!direct) {
                try {
                    dst.deleteFile(part)
                } catch (_: Exception) {
                }
            }
            if (size >= 0) doneBytes = startBytes + size
            doneFiles++
            if (error !is CancelledException) fail(source, error)
            return false
        }
        try {
            if (modified != null) dst.setModified(target, modified)
            if (mode != null) dst.setMode(target, mode)
        } catch (_: Exception) {
            // Timestamps and permissions are best effort (e.g. FAT or Android shared storage).
        }
        if (size >= 0) doneBytes = startBytes + size
        doneFiles++
        emit(source)
        return true
    }

    private class CancelledException : RuntimeException()

    // ---- Tree copy / move ----

    private fun scan(src: Fs, path: String, entry: FileEntry) {
        if (cancelled) return
        listener.preparing(path)
        if (entry.kind == EntryKind.Directory) {
            val children = try {
                src.list(path)
            } catch (_: IOException) {
                return
            }
            for (child in children) scan(src, joinPath(path, child.name), child)
        } else {
            totalFiles++
            totalBytes += entry.size
        }
    }

    fun copyTree(src: Fs, sources: List<String>, destDir: String, move: Boolean): OpResult {
        val same = src.sameFs(dst)
        val items = ArrayList<Pair<String, FileEntry>>()
        for (source in sources) {
            val entry = try {
                src.stat(source)
            } catch (error: IOException) {
                fail(source, error)
                continue
            }
            if (entry == null) {
                fail(source, "Zdroj neexistuje")
                continue
            }
            val target = joinPath(destDir, baseName(source))
            if (same) {
                if (normalizePath(target) == normalizePath(source)) {
                    fail(source, "Zdroj a cíl jsou tentýž soubor")
                    continue
                }
                if (entry.kind == EntryKind.Directory &&
                    (normalizePath(destDir) + "/").startsWith(normalizePath(source) + "/")
                ) {
                    fail(source, "Adresář nelze kopírovat ani přesouvat do sebe sama")
                    continue
                }
            }
            items += source to entry
        }
        for ((source, entry) in items) scan(src, source, entry)
        for ((source, entry) in items) {
            if (cancelled) break
            val target = joinPath(destDir, baseName(source))
            if (move && same) {
                val exists = try {
                    dst.stat(target) != null
                } catch (_: IOException) {
                    true
                }
                if (!exists) {
                    val renamed = try {
                        src.rename(source, target)
                        true
                    } catch (_: IOException) {
                        false
                    }
                    if (renamed) {
                        val moved = Measure()
                        if (entry.kind == EntryKind.Directory) {
                            moved.add(measure(dst, target, { false }))
                        } else {
                            moved.files = 1
                            moved.bytes = entry.size
                        }
                        skipped(source, moved.files, moved.bytes)
                        continue
                    }
                }
            }
            copyItem(src, source, entry, target, move)
        }
        listener.progress(OpProgress(doneFiles, doneBytes, totalFiles, totalBytes, ""))
        return result()
    }

    /** Returns true when the whole item was transferred (and, for a move, removed). */
    private fun copyItem(src: Fs, source: String, entry: FileEntry, target: String, move: Boolean): Boolean {
        if (cancelled) return false
        when (entry.kind) {
            EntryKind.Directory -> {
                if (!putDir(target)) return false
                val children = try {
                    src.list(source)
                } catch (error: IOException) {
                    fail(source, error)
                    return false
                }
                var complete = true
                for (child in children) {
                    if (cancelled) return false
                    if (!copyItem(src, joinPath(source, child.name), child, joinPath(target, child.name), move)) {
                        complete = false
                    }
                }
                if (complete) {
                    try {
                        if (entry.modified != null) dst.setModified(target, entry.modified)
                    } catch (_: Exception) {
                    }
                    if (move) {
                        try {
                            src.deleteDir(source)
                        } catch (error: IOException) {
                            fail(source, error)
                            return false
                        }
                    }
                }
                return complete
            }
            EntryKind.File, EntryKind.Symlink -> {
                if (entry.kind == EntryKind.Symlink && entry.targetIsDir) {
                    fail(source, "Symbolický odkaz na adresář se nekopíruje")
                    skipped(source, 1, entry.size)
                    return false
                }
                val ok = putFile(source, target, entry.size, entry.modified, entry.mode) { src.openRead(source) }
                if (ok && move) {
                    try {
                        src.deleteFile(source)
                    } catch (error: IOException) {
                        fail(source, error)
                        return false
                    }
                }
                return ok
            }
            else -> {
                fail(source, "Speciální soubor nelze kopírovat")
                skipped(source, 1, 0)
                return false
            }
        }
    }
}

/** Deletes trees permanently; symlinks are removed, never followed. */
fun deleteTrees(fs: Fs, paths: List<String>, cancel: AtomicBoolean, onItem: (String) -> Unit = {}): List<OpError> {
    val errors = ArrayList<OpError>()
    fun remove(path: String, entry: FileEntry): Boolean {
        if (cancel.get()) return false
        onItem(path)
        return try {
            if (entry.kind == EntryKind.Directory) {
                var ok = true
                for (child in fs.list(path)) {
                    if (!remove(joinPath(path, child.name), child)) ok = false
                }
                if (ok) fs.deleteDir(path)
                ok
            } else {
                fs.deleteFile(path)
                true
            }
        } catch (error: IOException) {
            errors += OpError(path, describe(error))
            false
        }
    }
    for (path in paths) {
        if (cancel.get()) break
        val entry = try {
            fs.stat(path)
        } catch (error: IOException) {
            errors += OpError(path, describe(error))
            continue
        }
        if (entry == null) errors += OpError(path, "Položka neexistuje") else remove(path, entry)
    }
    return errors
}

/**
 * App-managed trash: one hidden directory per storage volume, laid out like the FreeDesktop
 * trash (`files/` with the data, `info/` with the original path and deletion time).
 */
object Trash {
    const val DIR_NAME = ".SalamanderDroid-Trash"

    fun trashDir(volumeRoot: String): File = File(volumeRoot, DIR_NAME)

    /** [volumeRootOf] returns the root of the volume holding the path, or null when unknown. */
    fun trash(paths: List<String>, volumeRootOf: (String) -> String?): List<OpError> {
        val errors = ArrayList<OpError>()
        for (path in paths) {
            val root = volumeRootOf(path)
            if (root == null) {
                errors += OpError(path, "Na tomto úložišti koš není; použijte trvalé smazání (Shift+F8)")
                continue
            }
            val trash = trashDir(root)
            if ((normalizePath(path) + "/").startsWith(normalizePath(trash.path) + "/")) {
                errors += OpError(path, "Položka už je v koši; použijte trvalé smazání (Shift+F8)")
                continue
            }
            val files = File(trash, "files")
            val info = File(trash, "info")
            if ((!files.isDirectory && !files.mkdirs()) || (!info.isDirectory && !info.mkdirs())) {
                errors += OpError(path, "Koš nelze vytvořit")
                continue
            }
            val name = baseName(path)
            var target = File(files, name)
            var counter = 2
            while (target.exists()) {
                target = File(files, "$name.$counter")
                counter++
            }
            if (!File(path).renameTo(target)) {
                errors += OpError(path, "Nelze přesunout do koše")
                continue
            }
            try {
                File(info, target.name + ".trashinfo").writeText(
                    "[Trash Info]\nPath=$path\nDeletionDate=${formatTime(System.currentTimeMillis(), "yyyy-MM-dd'T'HH:mm:ss")}\n"
                )
            } catch (_: IOException) {
            }
        }
        return errors
    }

    /** Original location recorded for an item in `files/`, if any. */
    fun originalPath(volumeRoot: String, name: String): String? = try {
        File(File(trashDir(volumeRoot), "info"), "$name.trashinfo").readLines()
            .firstOrNull { it.startsWith("Path=") }?.removePrefix("Path=")
    } catch (_: IOException) {
        null
    }

    fun forgetInfo(volumeRoot: String, name: String) {
        File(File(trashDir(volumeRoot), "info"), "$name.trashinfo").delete()
    }
}

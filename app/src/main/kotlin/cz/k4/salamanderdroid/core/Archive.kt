package cz.k4.salamanderdroid.core

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

enum class ArchiveFormat(val label: String, val suffixes: List<String>, val writable: Boolean) {
    Zip("zip", listOf(".zip", ".jar"), true),
    TarGz("tar.gz", listOf(".tar.gz", ".tgz"), true),
    TarXz("tar.xz", listOf(".tar.xz", ".txz"), true),
    TarZst("tar.zst", listOf(".tar.zst", ".tzst"), true),
    TarBz2("tar.bz2", listOf(".tar.bz2", ".tbz2", ".tbz"), true),
    Tar("tar", listOf(".tar"), true),
    SevenZ("7z", listOf(".7z"), true),
    Cpio("cpio", listOf(".cpio"), false),
    Ar("ar", listOf(".ar", ".deb", ".a"), false);

    companion object {
        /** Formats offered when packing, in menu order. */
        val PACKABLE = listOf(Zip, TarGz, TarXz, TarZst, TarBz2, Tar, SevenZ)

        fun of(name: String): ArchiveFormat? {
            val folded = name.lowercase()
            return entries.firstOrNull { format -> format.suffixes.any { folded.endsWith(it) && folded.length > it.length } }
        }

        /** File name without the archive suffix, e.g. `data.tar.gz` → `data`. */
        fun stem(name: String): String {
            val format = of(name) ?: return name.substringBeforeLast('.', name)
            val suffix = format.suffixes.first { name.lowercase().endsWith(it) }
            return name.substring(0, name.length - suffix.length)
        }
    }
}

/** Archive member with a normalized inner path (no leading or trailing slash). */
class ArchiveMember(
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long?,
    val mode: Int?,
    val isSymlink: Boolean,
)

/** Directory tree of an archive: inner directory path ("" is the root) → entries. */
class ArchiveIndex(private val dirs: Map<String, List<FileEntry>>, val members: Map<String, ArchiveMember>) {
    fun list(inner: String): List<FileEntry>? = dirs[inner]
    fun isDir(inner: String): Boolean = dirs.containsKey(inner)
}

object Archives {
    /**
     * Normalizes a member name. Returns null for names that would escape the extraction
     * directory ("zip slip"): absolute tricks are stripped, any `..` component rejects it.
     */
    fun safeInnerPath(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun decompress(format: ArchiveFormat, input: InputStream): InputStream = when (format) {
        ArchiveFormat.TarGz -> GzipCompressorInputStream(input, true)
        ArchiveFormat.TarXz -> XZCompressorInputStream(input, true)
        ArchiveFormat.TarBz2 -> BZip2CompressorInputStream(input, true)
        ArchiveFormat.TarZst -> ZstdCompressorInputStream(input)
        else -> input
    }

    private fun member(name: String, isDir: Boolean, size: Long, modified: Long?, mode: Int?, symlink: Boolean): ArchiveMember? {
        val path = safeInnerPath(name) ?: return null
        return ArchiveMember(path, isDir, if (isDir) 0 else maxOf(size, 0), modified, mode?.and(0x1FF), symlink)
    }

    /**
     * Visits every member in archive order. The stream passed to [visit] is valid only
     * during the call and must not be closed. Returning false stops the walk.
     */
    fun read(file: File, visit: (ArchiveMember, InputStream) -> Boolean) {
        val format = ArchiveFormat.of(file.name) ?: throw IOException("Nepodporovaný formát archivu")
        when (format) {
            ArchiveFormat.Zip -> ZipFile.builder().setFile(file).get().use { zip ->
                for (entry in zip.entriesInPhysicalOrder) {
                    val mode = entry.unixMode.takeIf { it != 0 }
                    val item = member(
                        entry.name, entry.isDirectory, entry.size,
                        entry.time.takeIf { it >= 0 }, mode, entry.isUnixSymlink,
                    ) ?: continue
                    if (item.isDir || item.isSymlink) {
                        if (!visit(item, EMPTY)) return
                    } else {
                        if (!zip.canReadEntryData(entry)) throw IOException("Šifrované nebo nepodporované položky zip nelze číst")
                        val more = zip.getInputStream(entry).use { visit(item, it) }
                        if (!more) return
                    }
                }
            }
            ArchiveFormat.SevenZ -> SevenZFile.builder().setFile(file).get().use { seven ->
                while (true) {
                    val entry = seven.nextEntry ?: break
                    val item = member(
                        entry.name ?: continue, entry.isDirectory, entry.size,
                        if (entry.hasLastModifiedDate) entry.lastModifiedDate.time else null, null, false,
                    ) ?: continue
                    val stream = if (item.isDir) EMPTY else seven.getInputStream(entry)
                    if (!visit(item, stream)) return
                }
            }
            else -> {
                BufferedInputStream(FileInputStream(file), 1 shl 16).use { raw ->
                    val archive: ArchiveInputStream<out ArchiveEntry> = when (format) {
                        ArchiveFormat.Cpio -> CpioArchiveInputStream(raw)
                        ArchiveFormat.Ar -> ArArchiveInputStream(raw)
                        else -> TarArchiveInputStream(decompress(format, raw))
                    }
                    archive.use {
                        while (true) {
                            val entry = archive.nextEntry ?: break
                            val tar = entry as? TarArchiveEntry
                            val cpio = entry as? CpioArchiveEntry
                            if (tar != null && !(tar.isFile || tar.isDirectory || tar.isSymbolicLink)) continue
                            val item = member(
                                entry.name, entry.isDirectory, entry.size,
                                entry.lastModifiedDate?.time,
                                tar?.mode ?: cpio?.mode?.toInt(),
                                tar?.isSymbolicLink == true || cpio?.isSymbolicLink == true,
                            ) ?: continue
                            if (!visit(item, archive)) return
                        }
                    }
                }
            }
        }
    }

    private val EMPTY: InputStream = object : InputStream() {
        override fun read(): Int = -1
    }

    fun index(file: File): ArchiveIndex {
        val members = LinkedHashMap<String, ArchiveMember>()
        read(file) { item, _ ->
            members[item.path] = item
            true
        }
        val dirs = HashMap<String, LinkedHashMap<String, FileEntry>>()
        dirs[""] = LinkedHashMap()
        fun ensureDir(path: String) {
            if (path.isEmpty() || dirs.containsKey(path)) return
            val parent = path.substringBeforeLast('/', "")
            ensureDir(parent)
            dirs[path] = LinkedHashMap()
            val name = path.substringAfterLast('/')
            dirs.getValue(parent).putIfAbsent(name, FileEntry(name, EntryKind.Directory))
        }
        for (item in members.values) {
            val parent = item.path.substringBeforeLast('/', "")
            val name = item.path.substringAfterLast('/')
            ensureDir(parent)
            if (item.isDir) {
                ensureDir(item.path)
                dirs.getValue(parent)[name] = FileEntry(name, EntryKind.Directory, modified = item.modified, mode = item.mode)
            } else {
                dirs.getValue(parent)[name] = FileEntry(
                    name, if (item.isSymlink) EntryKind.Symlink else EntryKind.File,
                    size = item.size, modified = item.modified, mode = item.mode,
                )
            }
        }
        return ArchiveIndex(dirs.mapValues { it.value.values.toList() }, members)
    }

    private fun selected(path: String, selection: List<String>): String? =
        selection.firstOrNull { path == it || path.startsWith("$it/") }

    /**
     * Extracts the selected inner paths (files or whole directories) into [destDir] of the
     * transfer's destination, in a single pass over the archive. An empty selection path ""
     * extracts everything.
     */
    fun extract(file: File, selection: List<String>, transfer: Transfer, destDir: String) {
        val everything = selection.any { it.isEmpty() }
        var files = 0L
        var bytes = 0L
        read(file) { item, _ ->
            if (!item.isDir && (everything || selected(item.path, selection) != null)) {
                files++
                bytes += item.size
            }
            !transfer.cancelled
        }
        transfer.totalFiles += files
        transfer.totalBytes += bytes
        val madeDirs = HashSet<String>()
        fun ensureDirs(relative: String): Boolean {
            if (relative.isEmpty()) return true
            if (relative in madeDirs) return true
            if (!ensureDirs(relative.substringBeforeLast('/', ""))) return false
            if (!transfer.putDir(joinPath(destDir, relative))) return false
            madeDirs += relative
            return true
        }
        read(file) { item, stream ->
            if (transfer.cancelled) return@read false
            val root = if (everything) "" else selected(item.path, selection) ?: return@read true
            // Keep the selected item's own name: strip only its parent directory.
            val strip = root.substringBeforeLast('/', "")
            val relative = if (strip.isEmpty()) item.path else item.path.substring(strip.length + 1)
            val shown = "${file.name}/${item.path}"
            when {
                item.isDir -> ensureDirs(relative)
                item.isSymlink -> {
                    transfer.fail(shown, "Symbolický odkaz se nerozbaluje")
                    transfer.skipped(shown, 1, item.size)
                }
                else -> {
                    if (ensureDirs(relative.substringBeforeLast('/', ""))) {
                        transfer.putFile(shown, joinPath(destDir, relative), item.size, item.modified, item.mode?.and(0x1FF)) {
                            NonClosing(stream)
                        }
                    } else {
                        transfer.skipped(shown, 1, item.size)
                    }
                }
            }
            true
        }
    }

    /** Extracts a single file to [target] (used for viewing and opening). */
    fun extractFile(file: File, inner: String, target: File) {
        var found = false
        read(file) { item, stream ->
            if (item.path == inner && !item.isDir) {
                FileOutputStream(target).use { stream.copyTo(it, 1 shl 16) }
                item.modified?.let { target.setLastModified(it) }
                found = true
                false
            } else {
                true
            }
        }
        if (!found) throw IOException("Položka v archivu nenalezena: $inner")
    }

    private class NonClosing(private val inner: InputStream) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)
        override fun close() {}
    }

    // ---- Writing ----

    private interface Writer : Closeable {
        fun dir(path: String, modified: Long?, mode: Int?)
        fun file(path: String, size: Long, modified: Long?, mode: Int?, input: InputStream)
    }

    private fun writer(format: ArchiveFormat, file: File): Writer = when (format) {
        ArchiveFormat.Zip -> object : Writer {
            val out = ZipArchiveOutputStream(file)
            fun entry(path: String, modified: Long?, mode: Int?, dir: Boolean) = ZipArchiveEntry(if (dir) "$path/" else path).apply {
                if (modified != null) time = modified
                unixMode = (mode ?: if (dir) 0x1ED else 0x1A4) or (if (dir) 0x4000 else 0x8000)
            }

            override fun dir(path: String, modified: Long?, mode: Int?) {
                out.putArchiveEntry(entry(path, modified, mode, true))
                out.closeArchiveEntry()
            }

            override fun file(path: String, size: Long, modified: Long?, mode: Int?, input: InputStream) {
                out.putArchiveEntry(entry(path, modified, mode, false))
                input.copyTo(out, 1 shl 16)
                out.closeArchiveEntry()
            }

            override fun close() = out.close()
        }
        ArchiveFormat.SevenZ -> object : Writer {
            val out = SevenZOutputFile(file)
            fun entry(path: String, modified: Long?, dir: Boolean) = SevenZArchiveEntry().apply {
                name = path
                isDirectory = dir
                if (modified != null) lastModifiedDate = Date(modified)
            }

            override fun dir(path: String, modified: Long?, mode: Int?) {
                out.putArchiveEntry(entry(path, modified, true))
                out.closeArchiveEntry()
            }

            override fun file(path: String, size: Long, modified: Long?, mode: Int?, input: InputStream) {
                out.putArchiveEntry(entry(path, modified, false))
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.closeArchiveEntry()
            }

            override fun close() = out.close()
        }
        ArchiveFormat.Tar, ArchiveFormat.TarGz, ArchiveFormat.TarXz, ArchiveFormat.TarBz2, ArchiveFormat.TarZst -> object : Writer {
            val raw: OutputStream = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
            val out = TarArchiveOutputStream(
                when (format) {
                    ArchiveFormat.TarGz -> GzipCompressorOutputStream(raw)
                    ArchiveFormat.TarXz -> XZCompressorOutputStream(raw)
                    ArchiveFormat.TarBz2 -> BZip2CompressorOutputStream(raw)
                    ArchiveFormat.TarZst -> ZstdCompressorOutputStream(raw)
                    else -> raw
                }
            ).apply {
                setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            }

            override fun dir(path: String, modified: Long?, mode: Int?) {
                val entry = TarArchiveEntry("$path/")
                if (modified != null) entry.setModTime(modified)
                entry.mode = mode ?: 0x1ED
                out.putArchiveEntry(entry)
                out.closeArchiveEntry()
            }

            override fun file(path: String, size: Long, modified: Long?, mode: Int?, input: InputStream) {
                val entry = TarArchiveEntry(path)
                entry.size = size
                if (modified != null) entry.setModTime(modified)
                entry.mode = mode ?: 0x1A4
                out.putArchiveEntry(entry)
                var left = size
                val buffer = ByteArray(1 shl 16)
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) throw IOException("Soubor se během balení zkrátil: $path")
                    out.write(buffer, 0, read)
                    left -= read
                }
                out.closeArchiveEntry()
            }

            override fun close() = out.close()
        }
        else -> throw IOException("Do formátu ${format.label} nelze zapisovat")
    }

    interface PackListener {
        fun progress(current: String, doneBytes: Long, totalBytes: Long) {}
    }

    private class Packer(
        val writer: Writer,
        val listener: PackListener,
        val cancel: AtomicBoolean,
        val errors: MutableList<OpError>,
        val totalBytes: Long,
    ) {
        var doneBytes = 0L

        fun add(source: File, inner: String) {
            if (cancel.get()) return
            val entry = LocalFs.stat(source.path)
            if (entry == null) {
                errors += OpError(source.path, "Zdroj neexistuje")
                return
            }
            listener.progress(source.path, doneBytes, totalBytes)
            when {
                entry.kind == EntryKind.Directory -> {
                    writer.dir(inner, entry.modified, entry.mode)
                    val children = source.list()
                    if (children == null) {
                        errors += OpError(source.path, "Adresář nelze číst")
                        return
                    }
                    for (child in children.sorted()) add(File(source, child), "$inner/$child")
                }
                entry.kind == EntryKind.File || (entry.kind == EntryKind.Symlink && !entry.targetIsDir) -> {
                    val input = try {
                        FileInputStream(source)
                    } catch (error: IOException) {
                        errors += OpError(source.path, error.message ?: "Soubor nelze číst")
                        return
                    }
                    input.use { writer.file(inner, source.length(), entry.modified, entry.mode, it) }
                    doneBytes += entry.size
                }
                else -> errors += OpError(source.path, "Speciální soubory a odkazy na adresáře se nebalí")
            }
        }
    }

    private fun totalOf(sources: List<File>, cancel: AtomicBoolean): Long =
        sources.sumOf { measure(LocalFs, it.path, { cancel.get() }).bytes }

    /** Creates a new archive from local files and directories. */
    fun pack(target: File, sources: List<File>, listener: PackListener, cancel: AtomicBoolean): List<OpError> {
        val format = ArchiveFormat.of(target.name) ?: throw IOException("Neznámý formát archivu: ${target.name}")
        val errors = ArrayList<OpError>()
        val part = File(target.path + PART_SUFFIX)
        try {
            writer(format, part).use { writer ->
                val packer = Packer(writer, listener, cancel, errors, totalOf(sources, cancel))
                for (source in sources) packer.add(source, source.name)
            }
            if (cancel.get()) {
                part.delete()
            } else {
                if (target.exists() && !target.delete()) throw IOException("Nelze nahradit ${target.path}")
                if (!part.renameTo(target)) throw IOException("Nelze vytvořit ${target.path}")
            }
        } catch (error: Exception) {
            part.delete()
            throw error
        }
        return errors
    }

    /**
     * Rewrites an archive: drops members under [remove] and adds local [add] sources into the
     * inner directory [addInto] (same-named members are replaced). The original is replaced
     * only after the new archive is complete.
     */
    fun update(
        archive: File,
        add: List<File>,
        addInto: String,
        remove: List<String>,
        listener: PackListener,
        cancel: AtomicBoolean,
    ): List<OpError> {
        val format = ArchiveFormat.of(archive.name) ?: throw IOException("Nepodporovaný formát archivu")
        if (!format.writable) throw IOException("Archivy ${format.label} lze jen číst")
        val errors = ArrayList<OpError>()
        val replaced = add.map { joinPath(addInto, it.name) }
        val dropped = remove + replaced
        val part = File(archive.path + PART_SUFFIX)
        try {
            writer(format, part).use { writer ->
                read(archive) { item, stream ->
                    if (selected(item.path, dropped) == null) {
                        if (item.isDir) writer.dir(item.path, item.modified, item.mode)
                        else if (!item.isSymlink) writer.file(item.path, item.size, item.modified, item.mode, stream)
                    }
                    !cancel.get()
                }
                val packer = Packer(writer, listener, cancel, errors, totalOf(add, cancel))
                for (source in add) packer.add(source, joinPath(addInto, source.name))
            }
            if (cancel.get()) {
                part.delete()
            } else {
                val backup = File(archive.path + ".srs-old")
                if (!archive.renameTo(backup)) throw IOException("Archiv nelze nahradit")
                if (!part.renameTo(archive)) {
                    backup.renameTo(archive)
                    throw IOException("Archiv nelze nahradit")
                }
                backup.delete()
            }
        } catch (error: Exception) {
            part.delete()
            throw error
        }
        return errors
    }
}

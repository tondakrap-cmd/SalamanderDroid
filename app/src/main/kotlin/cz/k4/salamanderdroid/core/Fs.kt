package cz.k4.salamanderdroid.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission

/** Minimal file system abstraction shared by local storage and SFTP servers. */
interface Fs {
    fun list(dir: String): List<FileEntry>

    /** Entry for the path without following a final symlink; null when it does not exist. */
    fun stat(path: String): FileEntry?
    fun openRead(path: String): InputStream

    /** Creates or truncates the file. */
    fun openWrite(path: String): OutputStream
    fun mkdir(path: String)
    fun deleteFile(path: String)

    /** Removes an empty directory. */
    fun deleteDir(path: String)
    fun rename(from: String, to: String)
    fun setModified(path: String, millis: Long)
    fun setMode(path: String, mode: Int) {}

    /** True when [other] is the same storage, so that rename can move between paths. */
    fun sameFs(other: Fs): Boolean
}

object LocalFs : Fs {
    private fun modeOf(attributes: PosixFileAttributes): Int {
        var mode = 0
        for (permission in attributes.permissions()) {
            mode = mode or when (permission) {
                PosixFilePermission.OWNER_READ -> 0x100
                PosixFilePermission.OWNER_WRITE -> 0x80
                PosixFilePermission.OWNER_EXECUTE -> 0x40
                PosixFilePermission.GROUP_READ -> 0x20
                PosixFilePermission.GROUP_WRITE -> 0x10
                PosixFilePermission.GROUP_EXECUTE -> 0x8
                PosixFilePermission.OTHERS_READ -> 0x4
                PosixFilePermission.OTHERS_WRITE -> 0x2
                PosixFilePermission.OTHERS_EXECUTE -> 0x1
                else -> 0
            }
        }
        return mode
    }

    private fun entryOf(file: File): FileEntry? {
        val path = file.toPath()
        val attributes: BasicFileAttributes = try {
            Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: UnsupportedOperationException) {
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                return null
            }
        } catch (_: IOException) {
            return null
        }
        val posix = attributes as? PosixFileAttributes
        val kind = when {
            attributes.isSymbolicLink -> EntryKind.Symlink
            attributes.isDirectory -> EntryKind.Directory
            attributes.isRegularFile -> EntryKind.File
            else -> EntryKind.Other
        }
        var size = if (kind == EntryKind.File) attributes.size() else 0
        var linkTarget: String? = null
        var targetIsDir = false
        if (kind == EntryKind.Symlink) {
            linkTarget = try {
                Files.readSymbolicLink(path).toString()
            } catch (_: Exception) {
                null
            }
            targetIsDir = file.isDirectory
            if (!targetIsDir) size = file.length()
        }
        return FileEntry(
            name = file.name,
            kind = kind,
            size = size,
            modified = attributes.lastModifiedTime().toMillis(),
            mode = posix?.let(::modeOf),
            linkTarget = linkTarget,
            targetIsDir = targetIsDir,
            owner = try { posix?.owner()?.name ?: "" } catch (_: Exception) { "" },
            group = try { posix?.group()?.name ?: "" } catch (_: Exception) { "" },
        )
    }

    override fun list(dir: String): List<FileEntry> {
        val directory = File(dir)
        val names = directory.list()
            ?: throw IOException(
                if (!directory.exists()) "Adresář neexistuje: $dir"
                else if (!directory.isDirectory) "Není adresář: $dir"
                else "Adresář nelze číst (chybí oprávnění): $dir"
            )
        return names.mapNotNull { entryOf(File(directory, it)) }
    }

    override fun stat(path: String): FileEntry? = entryOf(File(path))
    override fun openRead(path: String): InputStream = FileInputStream(path)
    override fun openWrite(path: String): OutputStream = FileOutputStream(path)

    override fun mkdir(path: String) {
        val file = File(path)
        if (!file.mkdir() && !file.isDirectory) throw IOException("Adresář nelze vytvořit: $path")
    }

    override fun deleteFile(path: String) {
        Files.delete(File(path).toPath())
    }

    override fun deleteDir(path: String) {
        Files.delete(File(path).toPath())
    }

    override fun rename(from: String, to: String) {
        if (!File(from).renameTo(File(to))) throw IOException("Nelze přejmenovat $from na $to")
    }

    override fun setModified(path: String, millis: Long) {
        File(path).setLastModified(millis)
    }

    override fun sameFs(other: Fs): Boolean = other === this
}

/** Recursive totals of a tree; symlinks are counted, not followed. */
data class Measure(var files: Long = 0, var dirs: Long = 0, var bytes: Long = 0) {
    fun add(other: Measure) {
        files += other.files
        dirs += other.dirs
        bytes += other.bytes
    }
}

fun measure(fs: Fs, path: String, cancel: () -> Boolean, entry: FileEntry? = fs.stat(path)): Measure {
    val total = Measure()
    if (entry == null || cancel()) return total
    if (entry.kind == EntryKind.Directory) {
        total.dirs++
        val children = try {
            fs.list(path)
        } catch (_: IOException) {
            emptyList()
        }
        for (child in children) {
            if (cancel()) break
            total.add(measure(fs, joinPath(path, child.name), cancel, child))
        }
    } else {
        total.files++
        total.bytes += entry.size
    }
    return total
}

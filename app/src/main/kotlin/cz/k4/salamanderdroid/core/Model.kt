package cz.k4.salamanderdroid.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class EntryKind { Parent, Directory, File, Symlink, Other }

/** One row of a panel. Paths are not stored; the panel knows its directory. */
data class FileEntry(
    val name: String,
    val kind: EntryKind,
    val size: Long = 0,
    /** Modification time in epoch milliseconds. */
    val modified: Long? = null,
    /** Unix permission bits (0o7777) when known. */
    val mode: Int? = null,
    val linkTarget: String? = null,
    val targetIsDir: Boolean = false,
    val owner: String = "",
    val group: String = "",
) {
    val foldedName: String = name.lowercase()

    val isParent: Boolean get() = kind == EntryKind.Parent
    val isDirLike: Boolean
        get() = kind == EntryKind.Directory || kind == EntryKind.Parent ||
            (kind == EntryKind.Symlink && targetIsDir)
    val isHidden: Boolean get() = name.startsWith(".")
    val extension: String get() = if (isDirLike) "" else extensionOf(name)

    fun modeText(): String = mode?.let { formatMode(it, kind) } ?: ""

    companion object {
        fun parentLink() = FileEntry("..", EntryKind.Parent)
    }
}

class DirectoryListing(val entries: List<FileEntry>, val hiddenCount: Int)

data class OpError(val path: String, val message: String)

/** Extension without the dot; a leading dot (hidden file) does not start an extension. */
fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1)
}

fun formatSize(entry: FileEntry): String = when (entry.kind) {
    EntryKind.Directory -> "<DIR>"
    EntryKind.Symlink -> if (entry.targetIsDir) "<LINK→DIR>" else "<LINK>"
    EntryKind.Other -> "<SPECIAL>"
    EntryKind.File -> formatBytes(entry.size)
    EntryKind.Parent -> "<UP-DIR>"
}

fun formatBytes(size: Long): String {
    if (size < 1024) return "$size B"
    var value = size.toDouble()
    var unit = "B"
    for (candidate in listOf("KiB", "MiB", "GiB", "TiB", "PiB")) {
        value /= 1024.0
        unit = candidate
        if (value < 1024.0) break
    }
    return String.format(Locale.ROOT, "%.1f %s", value, unit)
}

/** Exact byte count with thin-space thousands separators, e.g. "1 234 567". */
fun formatBytesExact(size: Long): String {
    val digits = size.toString()
    val out = StringBuilder()
    digits.forEachIndexed { index, digit ->
        if (index > 0 && (digits.length - index) % 3 == 0) out.append(' ')
        out.append(digit)
    }
    return out.toString()
}

fun formatTime(millis: Long?, pattern: String = "yyyy-MM-dd HH:mm"): String =
    if (millis == null) "" else SimpleDateFormat(pattern, Locale.ROOT).format(Date(millis))

/** Parses "yyyy-MM-dd HH:mm[:ss]" in the local time zone. */
fun parseLocalTime(text: String): Long? {
    for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
        val format = SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }
        val position = java.text.ParsePosition(0)
        val trimmed = text.trim()
        val date = format.parse(trimmed, position)
        if (date != null && position.index == trimmed.length) return date.time
    }
    return null
}

/** `drwxr-sr-t` style text. */
fun formatMode(mode: Int, kind: EntryKind): String {
    val type = when (kind) {
        EntryKind.Directory, EntryKind.Parent -> 'd'
        EntryKind.Symlink -> 'l'
        EntryKind.File -> '-'
        EntryKind.Other -> '?'
    }
    val out = StringBuilder().append(type)
    fun triple(shift: Int, special: Int, specialChar: Char) {
        out.append(if (mode shr shift and 4 != 0) 'r' else '-')
        out.append(if (mode shr shift and 2 != 0) 'w' else '-')
        val exec = mode shr shift and 1 != 0
        out.append(
            when {
                mode and special != 0 && exec -> specialChar
                mode and special != 0 -> specialChar.uppercaseChar()
                exec -> 'x'
                else -> '-'
            }
        )
    }
    triple(6, 0x800, 's')
    triple(3, 0x400, 's')
    triple(0, 0x200, 't')
    return out.toString()
}

fun formatOctal(mode: Int): String = String.format(Locale.ROOT, "%04o", mode and 0xFFF)

fun parseOctal(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.length > 4 || trimmed.any { it !in '0'..'7' }) return null
    return trimmed.toInt(8)
}

/**
 * File mask such as `*.rs;*.toml|target*`: patterns before `|` include, after it exclude.
 * Patterns are case-insensitive, support `*` and `?`, and `*.*` matches every name.
 */
class Mask private constructor(private val include: List<String>, private val exclude: List<String>) {
    fun matches(name: String): Boolean {
        val folded = name.lowercase()
        return include.any { wildcardMatch(it, folded) } && exclude.none { wildcardMatch(it, folded) }
    }

    companion object {
        fun parse(text: String): Mask {
            val bar = text.indexOf('|')
            val includeText = if (bar < 0) text else text.substring(0, bar)
            val excludeText = if (bar < 0) "" else text.substring(bar + 1)
            fun split(part: String) = part.split(';', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { if (it == "*.*") "*" else it.lowercase() }
            val include = split(includeText).ifEmpty { listOf("*") }
            return Mask(include, split(excludeText))
        }
    }
}

fun wildcardMatch(pattern: String, text: String): Boolean {
    var p = 0
    var t = 0
    var star = -1
    var resume = 0
    while (t < text.length) {
        if (p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t])) {
            p++
            t++
        } else if (p < pattern.length && pattern[p] == '*') {
            star = p
            resume = t
            p++
        } else if (star >= 0) {
            p = star + 1
            resume++
            t = resume
        } else {
            return false
        }
    }
    while (p < pattern.length && pattern[p] == '*') p++
    return p == pattern.length
}

data class CompareItem(val name: String, val size: Long, val modified: Long?)

/**
 * Names to mark on the left and right side: files missing on the other side, newer files,
 * and files with the same time but a different size. Times within two seconds are equal
 * (FAT/exFAT resolution).
 */
fun compareListings(left: List<CompareItem>, right: List<CompareItem>): Pair<Set<String>, Set<String>> {
    val tolerance = 2000L
    val rightByName = right.associateBy { it.name }
    val leftNames = left.mapTo(HashSet()) { it.name }
    val leftMarks = HashSet<String>()
    val rightMarks = HashSet<String>()
    for (item in left) {
        val other = rightByName[item.name]
        if (other == null) {
            leftMarks += item.name
            continue
        }
        val a = item.modified
        val b = other.modified
        when {
            a != null && b != null && a > b + tolerance -> leftMarks += item.name
            a != null && b != null && b > a + tolerance -> rightMarks += item.name
            item.size != other.size -> {
                leftMarks += item.name
                rightMarks += item.name
            }
        }
    }
    for (item in right) if (item.name !in leftNames) rightMarks += item.name
    return leftMarks to rightMarks
}

enum class SortKey { Name, Extension, Time, Size }

private val byName: Comparator<FileEntry> = compareBy<FileEntry> { it.foldedName }.thenBy { it.name }

/** Parent first, directories before files, then the chosen key; name breaks ties. */
fun entryComparator(key: SortKey, descending: Boolean, dirSize: (FileEntry) -> Long = { it.size }): Comparator<FileEntry> {
    val keyed: Comparator<FileEntry> = when (key) {
        SortKey.Name -> byName
        SortKey.Extension -> compareBy<FileEntry> { it.extension.lowercase() }.then(byName)
        SortKey.Time -> compareBy<FileEntry> { it.modified ?: Long.MIN_VALUE }.then(byName)
        SortKey.Size -> compareBy<FileEntry> { if (it.isDirLike) dirSize(it) else it.size }.then(byName)
    }
    val ordered = if (descending) keyed.reversed() else keyed
    return compareBy<FileEntry> { if (it.isParent) 0 else if (it.isDirLike) 1 else 2 }.then(ordered)
}

// ---- Path helpers ('/'-separated, used for local, archive and remote paths) ----

fun joinPath(dir: String, name: String): String = when {
    dir.isEmpty() -> name
    dir.endsWith("/") -> dir + name
    else -> "$dir/$name"
}

fun baseName(path: String): String = path.trimEnd('/').substringAfterLast('/')

/** Parent of an absolute path; null for the root. */
fun parentPath(path: String): String? {
    val trimmed = if (path.length > 1) path.trimEnd('/') else path
    if (trimmed == "/" || trimmed.isEmpty()) return null
    val slash = trimmed.lastIndexOf('/')
    return when {
        slash < 0 -> ""
        slash == 0 -> "/"
        else -> trimmed.substring(0, slash)
    }
}

/** Resolves `.` and `..` and duplicate slashes in an absolute path. */
fun normalizePath(path: String): String {
    val parts = ArrayList<String>()
    for (part in path.split('/')) {
        when (part) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += part
        }
    }
    return "/" + parts.joinToString("/")
}

fun validateName(name: String): String? = when {
    name.isEmpty() -> "Zadejte název"
    name == "." || name == ".." -> "Název „$name“ není povolen"
    name.contains('/') -> "Název nesmí obsahovat lomítko"
    name.contains('\u0000') -> "Název obsahuje nepovolený znak"
    else -> null
}

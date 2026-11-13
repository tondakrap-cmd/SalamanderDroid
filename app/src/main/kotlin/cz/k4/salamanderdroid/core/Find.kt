package cz.k4.salamanderdroid.core

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class FindQuery(
    val root: String,
    val mask: Mask,
    /** Content to search for; empty means name-only search. */
    val content: ByteArray = ByteArray(0),
    val caseSensitive: Boolean = false,
    val includeHidden: Boolean = false,
    val includeDirs: Boolean = true,
)

sealed interface FindEvent {
    data class Directory(val path: String) : FindEvent
    data class Found(val path: String) : FindEvent
    data class Finished(val found: Long, val errors: Long, val cancelled: Boolean) : FindEvent
}

private fun fold(byte: Byte): Byte = if (byte in 'A'.code.toByte()..'Z'.code.toByte()) (byte + 32).toByte() else byte

/** Streams the file looking for [needle]; matches across chunk boundaries are found. */
fun fileContains(file: File, needle: ByteArray, caseSensitive: Boolean, cancel: AtomicBoolean): Boolean {
    if (needle.isEmpty()) return true
    val pattern = if (caseSensitive) needle else ByteArray(needle.size) { fold(needle[it]) }
    // Knuth–Morris–Pratt failure table.
    val failure = IntArray(pattern.size)
    var k = 0
    for (i in 1 until pattern.size) {
        while (k > 0 && pattern[i] != pattern[k]) k = failure[k - 1]
        if (pattern[i] == pattern[k]) k++
        failure[i] = k
    }
    FileInputStream(file).use { input ->
        val buffer = ByteArray(1 shl 16)
        var matched = 0
        while (true) {
            if (cancel.get()) return false
            val read = input.read(buffer)
            if (read < 0) return false
            for (i in 0 until read) {
                val byte = if (caseSensitive) buffer[i] else fold(buffer[i])
                while (matched > 0 && byte != pattern[matched]) matched = failure[matched - 1]
                if (byte == pattern[matched]) matched++
                if (matched == pattern.size) return true
            }
        }
    }
}

/** Walks the tree without following symlinks. [onEvent] returning false stops the search. */
fun findFiles(query: FindQuery, cancel: AtomicBoolean, onEvent: (FindEvent) -> Boolean) {
    var found = 0L
    var errors = 0L
    var stopped = false
    val pending = ArrayDeque<File>()
    pending += File(query.root)
    while (pending.isNotEmpty() && !stopped && !cancel.get()) {
        val dir = pending.removeLast()
        if (!onEvent(FindEvent.Directory(dir.path))) {
            stopped = true
            break
        }
        val children = dir.listFiles()
        if (children == null) {
            errors++
            continue
        }
        children.sortBy { it.name.lowercase() }
        val subdirs = ArrayList<File>()
        for (child in children) {
            if (cancel.get()) break
            if (!query.includeHidden && child.name.startsWith(".")) continue
            val symlink = Files.isSymbolicLink(child.toPath())
            val isDir = !symlink && child.isDirectory
            if (isDir) subdirs += child
            if (!query.mask.matches(child.name)) continue
            val hit = if (query.content.isEmpty()) {
                !isDir || query.includeDirs
            } else if (isDir || !child.isFile) {
                false
            } else {
                try {
                    fileContains(child, query.content, query.caseSensitive, cancel)
                } catch (_: IOException) {
                    errors++
                    false
                }
            }
            if (hit) {
                found++
                if (!onEvent(FindEvent.Found(child.path))) {
                    stopped = true
                    break
                }
            }
        }
        // Depth-first in name order.
        for (sub in subdirs.asReversed()) pending += sub
    }
    onEvent(FindEvent.Finished(found, errors, stopped || cancel.get()))
}

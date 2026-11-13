package cz.k4.salamanderdroid.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

const val VIEW_LIMIT = 10 * 1024 * 1024

/** Reads at most [VIEW_LIMIT] + 1 bytes; the extra byte tells that the file was truncated. */
fun readLimited(input: InputStream, limit: Int = VIEW_LIMIT): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(1 shl 16)
    while (out.size() <= limit) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

class Rendered(val data: ByteArray, val text: String?, val truncated: Boolean) {
    val isText: Boolean get() = text != null
}

/**
 * Decides between text and hex: valid UTF-8 without NUL bytes is text. A multi-byte
 * sequence cut by the size limit does not turn the whole file into binary.
 */
fun renderContent(raw: ByteArray, limit: Int = VIEW_LIMIT): Rendered {
    val truncated = raw.size > limit
    val data = if (truncated) raw.copyOf(limit) else raw
    if (data.any { it == 0.toByte() }) return Rendered(data, null, truncated)
    var end = data.size
    if (truncated) {
        // Drop an incomplete trailing sequence (at most 3 bytes).
        var back = 0
        while (back < 3 && end - 1 - back >= 0 && (data[end - 1 - back].toInt() and 0xC0) == 0x80) back++
        val lead = end - 1 - back
        if (lead >= 0) {
            val byte = data[lead].toInt() and 0xFF
            val need = when {
                byte >= 0xF0 -> 4
                byte >= 0xE0 -> 3
                byte >= 0xC0 -> 2
                else -> 1
            }
            if (need > back + 1) end = lead
        }
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
        decoder.decode(ByteBuffer.wrap(data, 0, end)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }
    return Rendered(data, text, truncated)
}

const val HEX_BYTES_PER_LINE = 16

fun hexLineCount(size: Int): Int = (size + HEX_BYTES_PER_LINE - 1) / HEX_BYTES_PER_LINE

/** `00000010  68 65 6c 6c 6f …  |hello…|` */
fun hexLine(data: ByteArray, line: Int): String {
    val start = line * HEX_BYTES_PER_LINE
    val out = StringBuilder(80)
    out.append(String.format(Locale.ROOT, "%08x ", start))
    for (i in 0 until HEX_BYTES_PER_LINE) {
        if (i == 8) out.append(' ')
        val index = start + i
        if (index < data.size) out.append(String.format(Locale.ROOT, " %02x", data[index].toInt() and 0xFF)) else out.append("   ")
    }
    out.append("  |")
    for (index in start until minOf(start + HEX_BYTES_PER_LINE, data.size)) {
        val byte = data[index].toInt() and 0xFF
        out.append(if (byte in 0x20..0x7E) byte.toChar() else '.')
    }
    out.append('|')
    return out.toString()
}

fun hexDump(data: ByteArray): String = (0 until hexLineCount(data.size)).joinToString("\n") { hexLine(data, it) }

/** Index of [needle] in [data] at or after [from] (ASCII case-insensitive when asked), or -1. */
fun indexOfBytes(data: ByteArray, needle: ByteArray, from: Int, ignoreCase: Boolean): Int {
    if (needle.isEmpty()) return -1
    fun fold(byte: Byte): Byte = if (ignoreCase && byte in 65..90) (byte + 32).toByte() else byte
    var start = maxOf(from, 0)
    while (start + needle.size <= data.size) {
        var i = 0
        while (i < needle.size && fold(data[start + i]) == fold(needle[i])) i++
        if (i == needle.size) return start
        start++
    }
    return -1
}

package cz.k4.salamanderdroid.remote

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Append-only log of remote operations, rotated at 5 MiB. Never contains passwords. */
class RemoteLog(val file: File) {
    @Synchronized
    fun log(server: String, action: String, detail: String = "") {
        try {
            file.parentFile?.mkdirs()
            if (file.length() > MAX_SIZE) {
                val old = File(file.path + ".1")
                old.delete()
                file.renameTo(old)
            }
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            val clean = detail.replace('\n', ' ').replace('\r', ' ')
            FileWriter(file, true).use { it.write("$time  $server  $action  $clean\n") }
        } catch (_: Exception) {
            // Logging must never break the operation itself.
        }
    }

    /** The newest part of the log. */
    fun tail(maxBytes: Int = 256 * 1024): String = try {
        val length = file.length()
        file.inputStream().use { input ->
            if (length > maxBytes) input.skip(length - maxBytes)
            val text = input.readBytes().toString(Charsets.UTF_8)
            if (length > maxBytes) text.substringAfter('\n') else text
        }
    } catch (_: Exception) {
        ""
    }

    companion object {
        private const val MAX_SIZE = 5L * 1024 * 1024
    }
}

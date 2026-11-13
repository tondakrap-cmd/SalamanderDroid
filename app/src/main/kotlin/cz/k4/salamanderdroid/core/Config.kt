package cz.k4.salamanderdroid.core

import java.io.File

const val HOTPATH_COUNT = 10

/** Persistent settings as simple `key=value` lines (same format as Salamander RS). */
data class Config(
    val leftDir: String? = null,
    val rightDir: String? = null,
    val showHidden: Boolean = false,
    val showExtColumn: Boolean = false,
    val showPermissionsColumn: Boolean = false,
    val showOwnerColumn: Boolean = false,
    /** Shows the `..` row leading to the parent directory. */
    val showParentEntry: Boolean = true,
    /** Bottom bar with F-key buttons (useful without a hardware keyboard). */
    val showButtonBar: Boolean = true,
    /** Font size of the panels in sp. */
    val fontSize: Int = 14,
    val hotpaths: List<String?> = List(HOTPATH_COUNT) { null },
) {
    fun serialize(): String {
        val out = StringBuilder("# Salamander Droid settings\n")
        out.append("left_dir=${leftDir.orEmpty()}\n")
        out.append("right_dir=${rightDir.orEmpty()}\n")
        out.append("show_hidden=$showHidden\n")
        out.append("column_ext=$showExtColumn\n")
        out.append("column_permissions=$showPermissionsColumn\n")
        out.append("column_owner=$showOwnerColumn\n")
        out.append("parent_entry=$showParentEntry\n")
        out.append("button_bar=$showButtonBar\n")
        out.append("font_size=$fontSize\n")
        hotpaths.forEachIndexed { index, path -> if (path != null) out.append("hotpath$index=$path\n") }
        return out.toString()
    }

    fun save(file: File) {
        file.parentFile?.mkdirs()
        // Write to a temporary file first so a crash never leaves a truncated config.
        val temporary = File(file.path + ".tmp")
        temporary.writeText(serialize())
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    companion object {
        fun load(file: File): Config = try {
            parse(file.readText())
        } catch (_: Exception) {
            Config()
        }

        fun parse(text: String): Config {
            var config = Config()
            val hotpaths = arrayOfNulls<String>(HOTPATH_COUNT)
            for (rawLine in text.lines()) {
                val line = rawLine.trimEnd('\r')
                if (line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1)
                val path = value.takeIf { it.isNotEmpty() }
                val flag = value.trim() == "true"
                config = when (key) {
                    "left_dir" -> config.copy(leftDir = path)
                    "right_dir" -> config.copy(rightDir = path)
                    "show_hidden" -> config.copy(showHidden = flag)
                    "column_ext" -> config.copy(showExtColumn = flag)
                    "column_permissions" -> config.copy(showPermissionsColumn = flag)
                    "column_owner" -> config.copy(showOwnerColumn = flag)
                    "parent_entry" -> config.copy(showParentEntry = flag)
                    "button_bar" -> config.copy(showButtonBar = flag)
                    "font_size" -> config.copy(fontSize = (value.trim().toIntOrNull() ?: config.fontSize).coerceIn(9, 28))
                    else -> {
                        val index = key.removePrefix("hotpath").toIntOrNull()
                        if (key.startsWith("hotpath") && index != null && index in 0 until HOTPATH_COUNT) hotpaths[index] = path
                        config
                    }
                }
            }
            return config.copy(hotpaths = hotpaths.toList())
        }
    }
}

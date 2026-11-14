package cz.k4.salamanderdroid.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.k4.salamanderdroid.core.EntryKind
import cz.k4.salamanderdroid.core.FileEntry
import cz.k4.salamanderdroid.core.extensionOf
import cz.k4.salamanderdroid.core.formatBytes
import cz.k4.salamanderdroid.core.formatTime
import cz.k4.salamanderdroid.core.hexLine
import cz.k4.salamanderdroid.core.hexLineCount
import cz.k4.salamanderdroid.core.readLimited
import cz.k4.salamanderdroid.core.renderContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Preview {
    class Picture(val bitmap: Bitmap, val note: String) : Preview
    class Lines(val lines: List<String>, val note: String) : Preview
    class Info(val text: String) : Preview
}

private val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private const val TEXT_PREVIEW = 256 * 1024
private const val MAX_SIDE = 2048

private fun loadImage(file: File): Preview {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return Preview.Info("Obrázek nelze načíst.")
    var sample = 1
    while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return Preview.Info("Obrázek nelze načíst.")
    return Preview.Picture(bitmap, "${bounds.outWidth} × ${bounds.outHeight} px")
}

private fun loadPdf(file: File): Preview =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount == 0) return Preview.Info("PDF nemá žádné stránky.")
            renderer.openPage(0).use { page ->
                val scale = minOf(1600f / page.width, 3f)
                val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                Preview.Picture(bitmap, "PDF, strana 1 z ${renderer.pageCount}")
            }
        }
    }

private fun load(loc: Loc, entry: FileEntry): Preview {
    val summary = "${entry.name}\n${formatBytes(entry.size)}   ${formatTime(entry.modified)}"
    if (entry.isParent) return Preview.Info("Nadřazená složka")
    if (entry.isDirLike) return Preview.Info("${entry.name}\nSložka   ${formatTime(entry.modified)}")
    if (loc !is Loc.Local) return Preview.Info("$summary\n\nNáhled je k dispozici jen pro místní soubory; F3 soubor stáhne a zobrazí.")
    if (entry.kind == EntryKind.Other) return Preview.Info(summary)
    val file = File(loc.pathOf(entry.name))
    return try {
        val extension = extensionOf(entry.name).lowercase()
        when {
            extension in IMAGE_TYPES -> loadImage(file)
            extension == "pdf" -> loadPdf(file)
            else -> {
                val rendered = file.inputStream().use { renderContent(readLimited(it, TEXT_PREVIEW), TEXT_PREVIEW) }
                val text = rendered.text
                val note = if (rendered.truncated) "prvních ${formatBytes(TEXT_PREVIEW.toLong())}" else ""
                if (text != null) {
                    Preview.Lines(splitLines(text).take(5000), note)
                } else {
                    val data = rendered.data.copyOf(minOf(rendered.data.size, 16 * 1024))
                    Preview.Lines((0 until hexLineCount(data.size)).map { hexLine(data, it) }, "binární soubor, prvních ${formatBytes(data.size.toLong())}")
                }
            }
        }
    } catch (error: Exception) {
        Preview.Info("$summary\n\nNáhled nelze zobrazit: ${error.message}")
    }
}

/** Preview of the entry under the cursor of the active panel, shown in place of the other panel. */
@Composable
fun QuickViewPane(app: AppState, modifier: Modifier) {
    val source = app.active
    val entry = source.cursorEntry()
    val loc = source.loc
    val preview by produceState<Preview?>(null, loc, entry?.name, entry?.size, entry?.modified) {
        value = null
        if (entry == null) return@produceState
        // Moving the cursor quickly should not decode every file on the way.
        delay(120)
        value = withContext(Dispatchers.IO) { load(loc, entry) }
    }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize()) {
        Text(
            "Rychlé zobrazení: ${entry?.name.orEmpty()}", fontSize = app.config.fontSize.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.StartEllipsis, color = colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().background(colors.surfaceVariant).padding(horizontal = 8.dp, vertical = 5.dp),
        )
        val note = when (val shown = preview) {
            is Preview.Picture -> shown.note
            is Preview.Lines -> shown.note
            else -> ""
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.TopStart) {
            when (val shown = preview) {
                null -> Text(if (entry == null) "Složka je prázdná." else "Načítám…", color = colors.onSurfaceVariant)
                is Preview.Info -> Text(shown.text)
                is Preview.Picture -> Image(
                    shown.bitmap.asImageBitmap(), contentDescription = entry?.name,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                )
                is Preview.Lines -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown.lines.size) { index ->
                        Text(shown.lines[index].ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = (app.config.fontSize - 1).sp)
                    }
                }
            }
        }
        Text(
            (if (note.isEmpty()) "" else "$note   |   ") + "Ctrl+P zavře náhled, F3 otevře prohlížeč",
            fontSize = (app.config.fontSize - 2).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

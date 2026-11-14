package cz.k4.salamanderdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.k4.salamanderdroid.core.VIEW_LIMIT
import cz.k4.salamanderdroid.core.formatBytes
import cz.k4.salamanderdroid.core.hexLine
import cz.k4.salamanderdroid.core.hexLineCount
import cz.k4.salamanderdroid.core.indexOfBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_LINE = 4000

/** Splits text into display lines; very long lines are cut into chunks so they stay fast. */
fun splitLines(text: String): List<String> {
    val lines = ArrayList<String>()
    for (line in text.split('\n')) {
        val clean = line.trimEnd('\r')
        if (clean.length <= MAX_LINE) lines += clean else clean.chunked(MAX_LINE).forEach { lines += it }
    }
    return lines
}

@Composable
private fun FullScreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
        }
    }
}

@Composable
fun ViewerDialog(app: AppState, dialog: Dlg.Viewer) {
    val content = dialog.content
    val close = { app.close(dialog) }
    var hex by remember { mutableStateOf(!content.isText) }
    var wrap by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val query = remember { fieldOf("") }
    var match by remember { mutableIntStateOf(-1) }
    var message by remember { mutableStateOf("") }
    val textLines by produceState<List<String>?>(null) {
        value = content.text?.let { withContext(Dispatchers.Default) { splitLines(it) } } ?: emptyList()
    }
    val foldedLines = remember(textLines) { lazy { textLines.orEmpty().map { it.lowercase() } } }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rootFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val lineCount = if (hex) hexLineCount(content.data.size) else textLines?.size ?: 0

    fun scrollBy(lines: Int) {
        scope.launch { state.scrollToItem((state.firstVisibleItemIndex + lines).coerceIn(0, maxOf(lineCount - 1, 0))) }
    }

    fun find(forward: Boolean) {
        val needle = query.value.text
        if (needle.isEmpty()) return
        val from = if (match >= 0) match + (if (forward) 1 else -1) else state.firstVisibleItemIndex
        val found: Int = if (hex) {
            val bytes = needle.toByteArray()
            if (forward) {
                indexOfBytes(content.data, bytes, from * 16, true).let { if (it < 0) -1 else it / 16 }
            } else {
                var line = -1
                var index = indexOfBytes(content.data, bytes, 0, true)
                while (index >= 0 && index / 16 <= from) {
                    line = index / 16
                    index = indexOfBytes(content.data, bytes, index + 1, true)
                }
                line
            }
        } else {
            val lines = foldedLines.value
            val folded = needle.lowercase()
            val range = if (forward) maxOf(from, 0) until lines.size else minOf(from, lines.size - 1) downTo 0
            range.firstOrNull { lines[it].contains(folded) } ?: -1
        }
        if (found < 0) {
            message = "Nenalezeno"
        } else {
            message = ""
            match = found
            scope.launch { state.scrollToItem(maxOf(found - 3, 0)) }
        }
    }

    FullScreen(close) {
        Column(
            Modifier
                .fillMaxSize()
                .focusRequester(rootFocus)
                .onPreviewKeyEvent { event ->
                    val page = maxOf(state.layoutInfo.visibleItemsInfo.size - 2, 1)
                    when {
                        event.isDown(Key.Escape) -> {
                            if (searching) {
                                searching = false
                                rootFocus.requestFocus()
                            } else close()
                            true
                        }
                        event.isDown(Key.F3) -> { find(!event.isShiftPressed); true }
                        event.isDown(Key.F7) || (event.isDown(Key.F) && event.isCtrlPressed) -> {
                            searching = true
                            true
                        }
                        searchFocused -> if (event.isEnterDown) { find(true); true } else false
                        event.isDown(Key.H) -> { if (content.isText) { hex = !hex; match = -1 }; true }
                        event.isDown(Key.W) -> { wrap = !wrap; true }
                        event.isDown(Key.DirectionDown) -> { scrollBy(1); true }
                        event.isDown(Key.DirectionUp) -> { scrollBy(-1); true }
                        event.isDown(Key.PageDown, Key.Spacebar) -> { scrollBy(page); true }
                        event.isDown(Key.PageUp) -> { scrollBy(-page); true }
                        event.isDown(Key.MoveHome) -> { scrollBy(-lineCount); true }
                        event.isDown(Key.MoveEnd) -> { scrollBy(lineCount); true }
                        event.isDown(Key.Q) -> { close(); true }
                        else -> false
                    }
                }
                .focusable()
        ) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    dialog.title + "  (${formatBytes(content.data.size.toLong())}" + (if (content.truncated) ", zobrazeno prvních ${formatBytes(VIEW_LIMIT.toLong())}" else "") + ")",
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (content.isText) TextButton(onClick = { hex = !hex; match = -1 }) { Text(if (hex) "Text (H)" else "Hex (H)") }
                TextButton(onClick = { wrap = !wrap }) { Text(if (wrap) "Nezalamovat (W)" else "Zalamovat (W)") }
                TextButton(onClick = { searching = true }) { Text("Hledat (Ctrl+F)") }
                TextButton(onClick = close) { Text("Zavřít (Esc)") }
            }
            if (searching) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(query, "Hledat", Modifier.width(360.dp).focusRequester(searchFocus).onFocusChanged { searchFocused = it.hasFocus }, onDone = { find(true) })
                    TextButton(onClick = { find(true) }) { Text("Další (F3)") }
                    TextButton(onClick = { find(false) }) { Text("Předchozí (Shift+F3)") }
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
                LaunchedEffect(Unit) { searchFocus.requestFocus() }
            }
            val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = app.config.fontSize.sp, color = MaterialTheme.colorScheme.onSurface)
            val highlight = MaterialTheme.colorScheme.tertiaryContainer
            val softWrap = wrap && !hex
            if (textLines == null && !hex) {
                Text("Načítám…", Modifier.padding(12.dp))
            } else {
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)
                        .then(if (softWrap) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                ) {
                    items(lineCount) { index ->
                        val line = if (hex) hexLine(content.data, index) else textLines!![index]
                        Text(
                            line.ifEmpty { " " }, style = style, softWrap = softWrap, maxLines = if (softWrap) Int.MAX_VALUE else 1,
                            modifier = (if (softWrap) Modifier.fillMaxWidth() else Modifier)
                                .then(if (index == match) Modifier.background(highlight) else Modifier),
                        )
                    }
                }
            }
        }
        LaunchedEffect(Unit) { rootFocus.requestFocus() }
    }
}

@Composable
fun EditorDialog(app: AppState, dialog: Dlg.Editor) {
    var value by remember { mutableStateOf(TextFieldValue(dialog.text)) }
    var saved by remember { mutableStateOf(dialog.text) }
    var status by remember { mutableStateOf("") }
    var confirmClose by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val modified = value.text != saved

    fun save(then: () -> Unit = {}) {
        if (saving) return
        saving = true
        val text = value.text
        scope.launch {
            try {
                dialog.save(text)
                saved = text
                status = "Uloženo"
                then()
            } catch (error: Exception) {
                status = "Uložení selhalo: ${error.message}"
            } finally {
                saving = false
            }
        }
    }

    val close = { if (modified) confirmClose = true else app.close(dialog) }
    FullScreen(close) {
        Column(
            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                when {
                    event.isDown(Key.Escape) -> { close(); true }
                    event.isDown(Key.S) && event.isCtrlPressed -> { save(); true }
                    event.isDown(Key.F2) -> { save(); true }
                    else -> false
                }
            }
        ) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text((if (modified) "● " else "") + dialog.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(status, fontSize = 13.sp, color = if (status.startsWith("Uložení selhalo")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled = modified && !saving, onClick = { save() }) { Text("Uložit (Ctrl+S)") }
                TextButton(onClick = close) { Text("Zavřít (Esc)") }
            }
            BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    if (status == "Uloženo") status = ""
                },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = app.config.fontSize.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxSize().padding(8.dp).focusRequester(focus),
            )
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
    if (confirmClose) {
        DialogFrame(
            "Neuložené změny", { confirmClose = false }, focusRoot = true,
            buttons = {
                TextButton(onClick = { confirmClose = false }) { Text("Zpět k úpravám") }
                TextButton(onClick = { app.close(dialog) }) { Text("Zahodit") }
                androidx.compose.material3.Button(onClick = { confirmClose = false; save { app.close(dialog) } }) { Text("Uložit") }
            },
        ) { Text("Soubor „${dialog.title}“ má neuložené změny.") }
    }
}

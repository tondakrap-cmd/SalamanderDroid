package cz.k4.salamanderdroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.k4.salamanderdroid.BuildConfig
import cz.k4.salamanderdroid.core.ArchiveFormat
import cz.k4.salamanderdroid.core.EntryKind
import cz.k4.salamanderdroid.core.FileEntry
import cz.k4.salamanderdroid.core.OpKind
import cz.k4.salamanderdroid.core.SortKey
import cz.k4.salamanderdroid.core.formatTime

private val MARKED = Color(0xFFE01B24)

@Composable
fun MainScreen(app: AppState) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        MenuBar(app)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // On a phone-sized window only the active panel is shown; Tab (or ⇄) switches.
            val narrow = maxWidth < 600.dp
            Row(Modifier.fillMaxSize()) {
                if (!narrow || app.leftActive) PanelView(app, app.left, Modifier.weight(1f))
                if (!narrow) Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                if (!narrow || !app.leftActive) PanelView(app, app.right, Modifier.weight(1f))
            }
        }
        if (app.config.showButtonBar) ButtonBar(app)
        app.flash?.let {
            Text(
                it, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

// ---- Menu ----

private class MenuItem(val label: String, val shortcut: String = "", val checked: Boolean? = null, val action: () -> Unit)

private fun menus(app: AppState): List<Pair<String, List<MenuItem?>>> {
    val panel = app.active
    val config = app.config
    return listOf(
        "Soubor" to listOf(
            MenuItem("Přejmenovat…", "F2") { app.rename() },
            MenuItem("Zobrazit", "F3") { app.view() },
            MenuItem("Upravit", "F4") { app.edit() },
            MenuItem("Nový soubor…", "Shift+F4") { app.create(file = true) },
            MenuItem("Kopírovat…", "F5") { app.copyMove(OpKind.Copy) },
            MenuItem("Přesunout…", "F6") { app.copyMove(OpKind.Move) },
            MenuItem("Nová složka…", "F7") { app.create(file = false) },
            null,
            MenuItem("Přesunout do koše", "F8") { app.remove(permanent = false) },
            MenuItem("Trvale smazat", "Shift+F8") { app.remove(permanent = true) },
            MenuItem("Vysypat koš…") { app.emptyTrash() },
            null,
            MenuItem("Vlastnosti…", "Alt+Enter") { app.properties() },
            MenuItem("Oprávnění a atributy…", "Ctrl+F2") { app.attributes() },
            MenuItem("Spočítat obsazené místo", "Alt+Shift+Enter") { app.calculateSize() },
            null,
            MenuItem("Zabalit…", "Alt+F5") { app.pack() },
            MenuItem("Rozbalit…", "Alt+F9") { app.unpack() },
            MenuItem("Otevřít v aplikaci…", "Shift+Enter") { app.openExternal() },
            null,
            MenuItem("Ukončit", "Ctrl+Q") { app.onExit() },
        ),
        "Úpravy" to listOf(
            MenuItem("Kopírovat do schránky", "Ctrl+C") { app.clipboardCopy(cut = false) },
            MenuItem("Vyjmout do schránky", "Ctrl+X") { app.clipboardCopy(cut = true) },
            MenuItem("Vložit ze schránky", "Ctrl+V") { app.clipboardPaste() },
            MenuItem("Kopírovat cestu jako text", "Ctrl+Shift+C") { app.copyPathText() },
            null,
            MenuItem("Označit / odznačit", "Mezerník") { panel.toggleMarkAndAdvance() },
            MenuItem("Vybrat vše", "Ctrl+A") { panel.markAll() },
            MenuItem("Zrušit výběr", "Ctrl+D") { panel.clearMarks() },
            MenuItem("Vybrat skupinu…", "Num +") { app.markGroup(true) },
            MenuItem("Odznačit skupinu…", "Num −") { app.markGroup(false) },
            MenuItem("Invertovat výběr", "Num *") { panel.invertMarks() },
            MenuItem("Porovnat adresáře", "Shift+F2") { app.compareDirectories() },
        ),
        "Přejít" to listOf(
            MenuItem("Levý panel: místa a svazky…", "Alt+F1") { app.places(app.left) },
            MenuItem("Pravý panel: místa a svazky…", "Alt+F2") { app.places(app.right) },
            MenuItem("Upravit cestu", "Ctrl+L") { app.pathEditing = true },
            null,
            MenuItem("Zpět", "Alt+←") { panel.historyStep(-1) },
            MenuItem("Vpřed", "Alt+→") { panel.historyStep(1) },
            MenuItem("Nadřazený adresář", "Backspace") { panel.navigateParent() },
            MenuItem("Hlavní úložiště", "Alt+Home") { panel.navigate(Loc.Local(app.home)) },
            null,
            MenuItem("Prohodit panely", "Ctrl+U") { app.swapPanels() },
            MenuItem("Stejný adresář v druhém panelu") { app.other.navigate(panel.loc) },
            MenuItem("Uložit klávesovou cestu…", "Ctrl+Shift+1") {
                app.dialogs += Dlg.Prompt("Uložit klávesovou cestu", "Číslo 0–9 (pak skok Ctrl+číslo):", "1", "Uložit") { text ->
                    val index = text.trim().toIntOrNull()
                    if (index != null && index in 0..9) app.setHotpath(index) else app.showError("Zadejte číslo 0–9")
                }
            },
        ),
        "Zobrazit" to listOf(
            MenuItem("Přepnout panel", "Tab") { app.switchPanel() },
            MenuItem("Zobrazit skryté položky", "Ctrl+H", config.showHidden) { app.updateConfig { it.copy(showHidden = !it.showHidden) } },
            MenuItem("Obnovit panel", "Ctrl+R") { panel.reload(quiet = false) },
            null,
            MenuItem("Řadit podle jména", "Ctrl+F3", panel.sortKey == SortKey.Name) { app.sortBy(SortKey.Name) },
            MenuItem("Řadit podle přípony", "Ctrl+F4", panel.sortKey == SortKey.Extension) { app.sortBy(SortKey.Extension) },
            MenuItem("Řadit podle času", "Ctrl+F5", panel.sortKey == SortKey.Time) { app.sortBy(SortKey.Time) },
            MenuItem("Řadit podle velikosti", "Ctrl+F6", panel.sortKey == SortKey.Size) { app.sortBy(SortKey.Size) },
            null,
            MenuItem("Sloupec Přípona", checked = config.showExtColumn) { app.updateConfig { it.copy(showExtColumn = !it.showExtColumn) } },
            MenuItem("Sloupec Práva", checked = config.showPermissionsColumn) { app.updateConfig { it.copy(showPermissionsColumn = !it.showPermissionsColumn) } },
            MenuItem("Sloupec Vlastník", checked = config.showOwnerColumn) { app.updateConfig { it.copy(showOwnerColumn = !it.showOwnerColumn) } },
        ),
        "Nástroje" to listOf(
            MenuItem("Hledat soubory…", "Alt+F7") { app.find() },
            MenuItem("Nastavení…", "Ctrl+,") { app.dialogs += Dlg.Settings() },
        ),
        "Vzdálené" to listOf(
            MenuItem("Připojit k serveru…", "Ctrl+K") { app.serverManager() },
            MenuItem("Odpojit") { app.disconnectActive() },
            MenuItem("Cloudové účty (OneDrive, Google Drive)…", "Ctrl+Shift+K") { app.cloudAccountsDialog() },
            null,
            MenuItem("Log vzdálených operací") { app.showRemoteLog() },
            MenuItem("Zamknout trezor hesel") { app.lockVault() },
            MenuItem("Změnit hlavní heslo trezoru…") { app.changeMaster() },
        ),
        "Nápověda" to listOf(
            MenuItem("Klávesové zkratky", "F1") { app.showShortcuts() },
            MenuItem("O aplikaci") {
                app.dialogs += Dlg.Message(
                    "Salamander Droid ${BuildConfig.VERSION_NAME}",
                    "Dvoupanelový správce souborů pro Android inspirovaný Open Salamanderem; sesterský projekt " +
                        "Salamander RS pro Linux.\n\nhttps://github.com/tondakrap-cmd/SalamanderDroid\n\nLicence GPL-3.0-or-later.",
                )
            },
        ),
    )
}

@Composable
private fun MenuBar(app: AppState) {
    var open by remember { mutableIntStateOf(-1) }
    LaunchedEffect(app.menuRequest) {
        if (app.menuRequest >= 0) {
            open = app.menuRequest
            app.menuRequest = -1
        }
    }
    SideEffect { app.menuOpen = open >= 0 }
    val all = menus(app)
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
        all.forEachIndexed { index, (title, items) ->
            Box {
                Text(
                    title, fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier.clickable { open = index }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
                DropdownMenu(expanded = open == index, onDismissRequest = { open = -1 }) {
                    for (item in items) {
                        if (item == null) {
                            HorizontalDivider()
                        } else {
                            DropdownMenuItem(
                                text = { Text((if (item.checked == true) "✓ " else if (item.checked == false) "    " else "") + item.label) },
                                trailingIcon = { if (item.shortcut.isNotEmpty()) Text(item.shortcut, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = {
                                    open = -1
                                    item.action()
                                },
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f))
        Text("⇄", fontSize = 16.sp, modifier = Modifier.clickable { app.switchPanel() }.padding(horizontal = 14.dp, vertical = 4.dp))
    }
}

// ---- Button bar ----

@Composable
private fun ButtonBar(app: AppState) {
    val buttons: List<Triple<String, String, () -> Unit>> = listOf(
        Triple("F2", "Přejmenovat") { app.rename() },
        Triple("F3", "Zobrazit") { app.view() },
        Triple("F4", "Upravit") { app.edit() },
        Triple("F5", "Kopírovat") { app.copyMove(OpKind.Copy) },
        Triple("F6", "Přesunout") { app.copyMove(OpKind.Move) },
        Triple("F7", "Nová složka") { app.create(file = false) },
        Triple("F8", "Smazat") { app.remove(permanent = false) },
        Triple("Alt+F7", "Hledat") { app.find() },
    )
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        for ((key, label, action) in buttons) {
            Row(
                Modifier.weight(1f).clickable(onClick = action).padding(horizontal = 4.dp, vertical = 7.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Text("$key ", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}

// ---- Panel ----

private fun iconOf(entry: FileEntry): String = when {
    entry.isParent -> "⬆"
    entry.kind == EntryKind.Symlink -> "🔗"
    entry.isDirLike -> "📁"
    ArchiveFormat.of(entry.name) != null -> "📦"
    else -> "📄"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelView(app: AppState, panel: PanelState, modifier: Modifier) {
    val isLeft = panel === app.left
    val isActive = app.active === panel
    val config = app.config
    val font = config.fontSize.sp
    val unit = config.fontSize.dp
    val colors = MaterialTheme.colorScheme
    val activate = { app.leftActive = isLeft }

    BoxWithConstraints(modifier.fillMaxHeight()) {
        val wide = maxWidth > unit * 34
        val showExt = config.showExtColumn && wide
        val showDate = maxWidth > unit * 26
        val showPermissions = config.showPermissionsColumn && wide
        val showOwner = config.showOwnerColumn && wide
        val extWidth = unit * 4
        val sizeWidth = unit * 6
        val dateWidth = unit * 9
        val permissionsWidth = unit * 7
        val ownerWidth = unit * 8

        Column(Modifier.fillMaxSize()) {
            // Path line.
            Row(
                Modifier.fillMaxWidth().background(if (isActive) colors.primaryContainer else colors.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (app.pathEditing && isActive) {
                    PathEditor(app, panel, Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 5.dp), font)
                } else {
                    Text(
                        panel.loc.display(), fontSize = font, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.StartEllipsis,
                        color = if (isActive) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f).clickable {
                            activate()
                            app.pathEditing = true
                        }.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
                Text("▾", fontSize = font, modifier = Modifier.clickable { app.places(panel) }.padding(horizontal = 12.dp, vertical = 5.dp))
            }
            if (panel.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.fillMaxWidth().padding(top = 4.dp))

            // Column headers; a click sorts.
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                @Composable
                fun Header(label: String, key: SortKey?, modifier: Modifier, align: TextAlign = TextAlign.Start) {
                    val arrow = if (key != null && panel.sortKey == key) (if (panel.sortDescending) " ▼" else " ▲") else ""
                    Text(
                        label + arrow, fontSize = (config.fontSize - 2).sp, color = colors.onSurfaceVariant, maxLines = 1, textAlign = align,
                        modifier = modifier.then(if (key != null) Modifier.clickable { activate(); panel.sortBy(key) } else Modifier).padding(vertical = 2.dp),
                    )
                }
                Header("Název", SortKey.Name, Modifier.weight(1f))
                if (showExt) Header("Příp.", SortKey.Extension, Modifier.width(extWidth))
                Header("Velikost", SortKey.Size, Modifier.width(sizeWidth), TextAlign.End)
                if (showDate) Header("Změněno", SortKey.Time, Modifier.width(dateWidth).padding(start = 10.dp))
                if (showPermissions) Header("Práva", null, Modifier.width(permissionsWidth))
                if (showOwner) Header("Vlastník", null, Modifier.width(ownerWidth))
            }
            HorizontalDivider()

            val rows = panel.rows
            val cursor = panel.cursor
            val marked = panel.marked
            LaunchedEffect(cursor, rows) {
                val info = panel.listState.layoutInfo
                val visible = info.visibleItemsInfo
                if (visible.isEmpty()) return@LaunchedEffect
                val full = visible.filter { it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }
                panel.pageRows = maxOf(full.size - 1, 1)
                val first = full.firstOrNull()?.index ?: visible.first().index
                val last = full.lastOrNull()?.index ?: visible.last().index
                if (cursor < first) panel.listState.scrollToItem(cursor)
                else if (cursor > last) panel.listState.scrollToItem(maxOf(cursor - full.size + 1, 0))
            }
            LazyColumn(state = panel.listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(rows, key = { _, entry -> if (entry.isParent) "\u0000.." else entry.name }) { index, entry ->
                    val isCursor = index == cursor
                    val isMarked = entry.name in marked && !entry.isParent
                    val color = when {
                        isMarked -> MARKED
                        entry.isHidden && !entry.isParent -> colors.onSurface.copy(alpha = 0.6f)
                        else -> colors.onSurface
                    }
                    val style = TextStyle(
                        fontSize = font, color = color,
                        fontWeight = if (isMarked) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (entry.kind == EntryKind.Symlink) FontStyle.Italic else FontStyle.Normal,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    isCursor && isActive -> colors.primary.copy(alpha = 0.28f)
                                    isCursor -> colors.onSurface.copy(alpha = 0.08f)
                                    else -> Color.Transparent
                                }
                            )
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    val wasActive = app.active === panel
                                    activate()
                                    panel.quickSearchEnd()
                                    if (wasActive && panel.cursor == index) app.activateEntry(panel) else panel.moveCursor(index)
                                },
                                onLongClick = {
                                    activate()
                                    panel.moveCursor(index)
                                    panel.toggleMark(index)
                                },
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(iconOf(entry), fontSize = font, modifier = Modifier.width(unit * 1.9f))
                        Text(entry.name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (showExt) Text(entry.extension, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(extWidth))
                        Text(panel.displaySize(entry), style = style, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(sizeWidth))
                        if (showDate) Text(formatTime(entry.modified), style = style, maxLines = 1, modifier = Modifier.width(dateWidth).padding(start = 10.dp))
                        if (showPermissions) Text(entry.modeText(), style = style, maxLines = 1, modifier = Modifier.width(permissionsWidth))
                        if (showOwner) Text(if (entry.owner.isEmpty()) "" else "${entry.owner}:${entry.group}", style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(ownerWidth))
                    }
                }
            }
            HorizontalDivider()
            val small = (config.fontSize - 2).sp
            Text(
                panel.infoText(), fontSize = small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (panel.quickSearch.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth().clickable(onClick = activate).padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Text(
                panel.statusText(), fontSize = small, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun PathEditor(app: AppState, panel: PanelState, modifier: Modifier, font: androidx.compose.ui.unit.TextUnit) {
    val value = remember { fieldOf(panel.loc.display()) }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    val go = {
        app.pathEditing = false
        app.goTyped(value.value.text, panel)
    }
    BasicTextField(
        value = value.value,
        onValueChange = { value.value = it },
        singleLine = true,
        textStyle = TextStyle(fontSize = font, color = MaterialTheme.colorScheme.onPrimaryContainer),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { go() }),
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) app.pathEditing = false
            }
            .onPreviewKeyEvent { event ->
                when {
                    event.isEnterDown -> { go(); true }
                    event.isDown(Key.Escape) -> { app.pathEditing = false; true }
                    else -> false
                }
            },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

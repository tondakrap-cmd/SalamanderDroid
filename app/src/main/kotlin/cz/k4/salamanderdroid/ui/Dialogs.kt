package cz.k4.salamanderdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.k4.salamanderdroid.core.ConflictAction
import cz.k4.salamanderdroid.core.formatBytes
import cz.k4.salamanderdroid.core.formatTime
import cz.k4.salamanderdroid.remote.PasswordAnswer
import cz.k4.salamanderdroid.remote.Vault

fun KeyEvent.isDown(first: Key, second: Key? = null): Boolean = type == KeyEventType.KeyDown && (key == first || key == second)
val KeyEvent.isEnterDown: Boolean get() = isDown(Key.Enter, Key.NumPadEnter)

/**
 * Common dialog window: title, content, button row. Escape dismisses; [onEnter] (when set)
 * runs on Enter. [focusRoot] grabs the keyboard for dialogs without a text field.
 */
@Composable
fun DialogFrame(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    onEnter: (() -> Unit)? = null,
    onKey: (KeyEvent) -> Boolean = { false },
    focusRoot: Boolean = false,
    dismissOutside: Boolean = true,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = dismissOutside),
    ) {
        val rootFocus = remember { FocusRequester() }
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = width)
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    when {
                        event.isDown(Key.Escape) -> {
                            onDismiss()
                            true
                        }
                        onKey(event) -> true
                        onEnter != null && event.isEnterDown -> {
                            onEnter()
                            true
                        }
                        else -> false
                    }
                }
                .then(if (focusRoot) Modifier.focusRequester(rootFocus).focusable() else Modifier),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                content()
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = buttons)
            }
        }
        if (focusRoot) LaunchedEffect(Unit) { rootFocus.requestFocus() }
    }
}

@Composable
fun Field(
    value: MutableState<TextFieldValue>,
    label: String? = null,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    password: Boolean = false,
    number: Boolean = false,
    mono: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    OutlinedTextField(
        value = value.value,
        onValueChange = { value.value = it },
        label = label?.let { { Text(it) } },
        singleLine = true,
        textStyle = if (mono) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else if (number) KeyboardType.Number else KeyboardType.Text,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth().focusRequester(focus),
    )
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
}

fun fieldOf(text: String, selection: TextRange = TextRange(0, text.length)) = mutableStateOf(TextFieldValue(text, selection))

@Composable
fun CheckRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

/** Keyboard-driven list used by the places, servers and find dialogs. */
@Composable
fun <T> KeyList(
    items: List<T>,
    cursor: MutableState<Int>,
    selectable: (T) -> Boolean = { true },
    onActivate: (T) -> Unit,
    onKey: (KeyEvent, T?) -> Boolean = { _, _ -> false },
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onFocus: (Boolean) -> Unit = {},
    row: @Composable (T, Boolean) -> Unit,
) {
    val state = rememberLazyListState()
    fun step(delta: Int) {
        var index = cursor.value
        while (true) {
            index += delta
            if (index !in items.indices) return
            if (selectable(items[index])) break
        }
        cursor.value = index
    }
    LaunchedEffect(cursor.value, items.size) {
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || cursor.value !in items.indices) return@LaunchedEffect
        if (cursor.value <= visible.first().index) state.scrollToItem(maxOf(cursor.value - 1, 0))
        else if (cursor.value >= visible.last().index) state.scrollToItem(maxOf(cursor.value - visible.size + 2, 0))
    }
    LazyColumn(
        state = state,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { onFocus(it.hasFocus) }
            .onPreviewKeyEvent { event ->
                val current = items.getOrNull(cursor.value)
                when {
                    onKey(event, current) -> true
                    event.isDown(Key.DirectionDown) -> { step(1); true }
                    event.isDown(Key.DirectionUp) -> { step(-1); true }
                    event.isDown(Key.PageDown) -> { repeat(10) { step(1) }; true }
                    event.isDown(Key.PageUp) -> { repeat(10) { step(-1) }; true }
                    event.isEnterDown && current != null && selectable(current) -> { onActivate(current); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        itemsIndexed(items) { index, item ->
            val selected = index == cursor.value && selectable(item)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                    .then(
                        if (selectable(item)) Modifier.clickable {
                            if (cursor.value == index) onActivate(item) else cursor.value = index
                            focusRequester.requestFocus()
                        } else Modifier
                    )
            ) { row(item, selected) }
        }
    }
    if (autoFocus) LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

// ---- Simple dialogs ----

@Composable
private fun MessageDialog(app: AppState, dialog: Dlg.Message) {
    DialogFrame(
        dialog.title, { app.close(dialog) }, width = if (dialog.mono) 900.dp else 520.dp,
        onEnter = { app.close(dialog) }, focusRoot = true,
        buttons = { Button(onClick = { app.close(dialog) }) { Text("Zavřít") } },
    ) {
        val vertical = rememberScrollState()
        Box(Modifier.heightIn(max = 480.dp).verticalScroll(vertical)) {
            if (dialog.mono) {
                Text(
                    dialog.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            } else {
                Text(dialog.text)
            }
        }
        if (dialog.mono) LaunchedEffect(Unit) { vertical.scrollTo(if (dialog.title.startsWith("Log")) vertical.maxValue else 0) }
    }
}

@Composable
private fun ConfirmDialog(app: AppState, dialog: Dlg.Confirm) {
    val confirm = {
        app.close(dialog)
        dialog.onConfirm()
    }
    DialogFrame(
        dialog.title, { app.close(dialog) }, onEnter = confirm, focusRoot = true,
        buttons = {
            TextButton(onClick = { app.close(dialog) }) { Text("Zrušit") }
            Button(onClick = confirm) { Text(dialog.confirmLabel) }
        },
    ) { Text(dialog.text) }
}

@Composable
private fun PromptDialog(app: AppState, dialog: Dlg.Prompt) {
    val value = remember {
        val dot = dialog.initial.lastIndexOf('.')
        val end = if (dialog.selectStem && dot > 0) dot else dialog.initial.length
        fieldOf(dialog.initial, TextRange(0, end))
    }
    val confirm = {
        app.close(dialog)
        dialog.onConfirm(value.value.text)
    }
    DialogFrame(
        dialog.title, { app.close(dialog) }, onEnter = confirm,
        buttons = {
            TextButton(onClick = { app.close(dialog) }) { Text("Zrušit") }
            Button(onClick = confirm) { Text(dialog.confirmLabel) }
        },
    ) {
        Text(dialog.label)
        Spacer(Modifier.height(8.dp))
        Field(value, autoFocus = true, onDone = confirm)
    }
}

@Composable
private fun ErrorsDialog(app: AppState, dialog: Dlg.Errors) {
    DialogFrame(
        "Operace skončila s chybami (${dialog.errors.size})", { app.close(dialog) }, width = 720.dp,
        onEnter = { app.close(dialog) }, focusRoot = true,
        buttons = { Button(onClick = { app.close(dialog) }) { Text("Zavřít") } },
    ) {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(dialog.errors) { _, error ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(error.path, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(error.message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun ConflictDialog(dialog: Dlg.ConflictAsk) {
    val conflict = dialog.conflict
    val answer = { action: ConflictAction -> dialog.answer.complete(action); Unit }
    DialogFrame(
        "Soubor již existuje", { answer(ConflictAction.Abort) }, width = 680.dp, focusRoot = true, dismissOutside = false,
        buttons = {},
    ) {
        Text("Cíl:", fontWeight = FontWeight.Bold)
        Text(conflict.target)
        Text("${formatBytes(conflict.targetEntry.size)}, ${formatTime(conflict.targetEntry.modified, "yyyy-MM-dd HH:mm:ss")}")
        Spacer(Modifier.height(8.dp))
        Text("Zdroj:", fontWeight = FontWeight.Bold)
        Text(conflict.source)
        Text("${formatBytes(conflict.sourceSize)}, ${formatTime(conflict.sourceModified, "yyyy-MM-dd HH:mm:ss")}")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Button(onClick = { answer(ConflictAction.Overwrite) }) { Text("Přepsat") }
            Button(onClick = { answer(ConflictAction.OverwriteAll) }) { Text("Přepsat vše") }
            Button(onClick = { answer(ConflictAction.Skip) }) { Text("Přeskočit") }
            Button(onClick = { answer(ConflictAction.SkipAll) }) { Text("Přeskočit vše") }
            TextButton(onClick = { answer(ConflictAction.Abort) }) { Text("Zrušit") }
        }
    }
}

@Composable
private fun HostKeyDialog(dialog: Dlg.HostKey) {
    DialogFrame(
        "Neznámý server", { dialog.answer.complete(false) }, width = 680.dp, focusRoot = true, dismissOutside = false,
        buttons = {
            TextButton(onClick = { dialog.answer.complete(false) }) { Text("Zrušit") }
            Button(onClick = { dialog.answer.complete(true) }) { Text("Důvěřovat a připojit") }
        },
    ) {
        Text(
            "Server ${dialog.server.label()} zatím není mezi známými. Ověřte otisk klíče, " +
                "např. u správce serveru, než mu budete důvěřovat."
        )
        Spacer(Modifier.height(12.dp))
        Text("${dialog.keyType}\n${dialog.fingerprint}", fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun PasswordDialog(dialog: Dlg.PasswordAsk) {
    val value = remember { fieldOf("") }
    var save by remember { mutableStateOf(dialog.server.savePassword) }
    val confirm = { dialog.answer.complete(PasswordAnswer(value.value.text.toCharArray(), save && dialog.server.savePassword)); Unit }
    DialogFrame(
        "Přihlášení", { dialog.answer.complete(null) }, onEnter = confirm, dismissOutside = false,
        buttons = {
            TextButton(onClick = { dialog.answer.complete(null) }) { Text("Zrušit") }
            Button(onClick = confirm) { Text("Přihlásit") }
        },
    ) {
        if (dialog.failed) Text("Server heslo odmítl, zkuste to znovu.", color = MaterialTheme.colorScheme.error)
        Text("Heslo pro ${dialog.server.label()}:")
        Spacer(Modifier.height(8.dp))
        Field(value, autoFocus = true, password = true, onDone = confirm)
        if (dialog.server.savePassword) CheckRow(save, "Uložit heslo do šifrovaného trezoru") { save = it }
    }
}

@Composable
private fun SecretDialog(dialog: Dlg.Secret) {
    val value = remember { fieldOf("") }
    val confirm = { dialog.answer.complete(value.value.text.toCharArray()); Unit }
    DialogFrame(
        dialog.title, { dialog.answer.complete(null) }, onEnter = confirm, dismissOutside = false,
        buttons = {
            TextButton(onClick = { dialog.answer.complete(null) }) { Text("Zrušit") }
            Button(onClick = confirm) { Text(dialog.confirmLabel) }
        },
    ) {
        dialog.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(dialog.text)
        Spacer(Modifier.height(8.dp))
        Field(value, autoFocus = true, password = true, onDone = confirm)
    }
}

@Composable
private fun NewMasterDialog(dialog: Dlg.NewMaster) {
    val first = remember { fieldOf("") }
    val second = remember { fieldOf("") }
    var status by remember { mutableStateOf("Alespoň ${Vault.MIN_MASTER_LENGTH} znaků; doporučena delší věta.") }
    val confirm = {
        when {
            first.value.text.length < Vault.MIN_MASTER_LENGTH -> status = "Hlavní heslo musí mít alespoň ${Vault.MIN_MASTER_LENGTH} znaků."
            first.value.text != second.value.text -> status = "Hesla se neshodují."
            else -> dialog.answer.complete(first.value.text.toCharArray())
        }
        Unit
    }
    DialogFrame(
        dialog.title, { dialog.answer.complete(null) }, onEnter = confirm, dismissOutside = false,
        buttons = {
            TextButton(onClick = { dialog.answer.complete(null) }) { Text("Zrušit") }
            Button(onClick = confirm) { Text("Nastavit") }
        },
    ) {
        Text(
            "Hesla k serverům se ukládají zašifrovaná (Argon2id + AES-256-GCM) a odemykají se hlavním heslem. " +
                "Hlavní heslo se nikam neukládá; při jeho ztrátě je nutné hesla k serverům zadat znovu."
        )
        Spacer(Modifier.height(8.dp))
        Field(first, "Hlavní heslo", autoFocus = true, password = true)
        Spacer(Modifier.height(8.dp))
        Field(second, "Znovu", password = true, onDone = confirm)
        Spacer(Modifier.height(8.dp))
        Text(status, fontSize = 13.sp)
    }
}

@Composable
fun ProgressDialog(app: AppState, progress: ProgressUi) {
    DialogFrame(
        progress.title, { app.cancelJob() }, width = 620.dp, focusRoot = true, dismissOutside = false,
        buttons = { TextButton(onClick = { app.cancelJob() }) { Text("Zrušit") } },
    ) {
        Text(progress.text.ifEmpty { "Pracuji…" }, maxLines = 2, overflow = TextOverflow.StartEllipsis, fontSize = 14.sp, modifier = Modifier.height(40.dp))
        Spacer(Modifier.height(8.dp))
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Text(progress.detail, fontSize = 13.sp)
    }
}

/** Renders the dialog stack; later dialogs are on top. */
@Composable
fun DialogHost(app: AppState) {
    app.progress?.let { ProgressDialog(app, it) }
    for (dialog in app.dialogs.toList()) {
        key(dialog) {
            when (dialog) {
                is Dlg.Message -> MessageDialog(app, dialog)
                is Dlg.Confirm -> ConfirmDialog(app, dialog)
                is Dlg.Prompt -> PromptDialog(app, dialog)
                is Dlg.Errors -> ErrorsDialog(app, dialog)
                is Dlg.ConflictAsk -> ConflictDialog(dialog)
                is Dlg.HostKey -> HostKeyDialog(dialog)
                is Dlg.PasswordAsk -> PasswordDialog(dialog)
                is Dlg.Secret -> SecretDialog(dialog)
                is Dlg.NewMaster -> NewMasterDialog(dialog)
                is Dlg.Places -> PlacesDialog(app, dialog)
                is Dlg.Find -> FindDialog(app, dialog)
                is Dlg.Properties -> PropertiesDialog(app, dialog)
                is Dlg.Attributes -> AttributesDialog(app, dialog)
                is Dlg.Pack -> PackDialog(app, dialog)
                is Dlg.Unpack -> UnpackDialog(app, dialog)
                is Dlg.ServerManager -> ServerManagerDialog(app, dialog)
                is Dlg.ServerEdit -> ServerEditDialog(app, dialog)
                is Dlg.Settings -> SettingsDialog(app, dialog)
                is Dlg.Viewer -> ViewerDialog(app, dialog)
                is Dlg.Editor -> EditorDialog(app, dialog)
            }
        }
    }
}

@Composable
fun LabeledRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, modifier = Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.weight(1f))
    }
}

package cz.k4.salamanderdroid.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.k4.salamanderdroid.cloud.CloudAccount
import cz.k4.salamanderdroid.cloud.CloudKind
import cz.k4.salamanderdroid.core.ArchiveFormat
import cz.k4.salamanderdroid.core.EntryKind
import cz.k4.salamanderdroid.core.FindEvent
import cz.k4.salamanderdroid.core.FindQuery
import cz.k4.salamanderdroid.core.Mask
import cz.k4.salamanderdroid.core.Measure
import cz.k4.salamanderdroid.core.findFiles
import cz.k4.salamanderdroid.core.formatBytes
import cz.k4.salamanderdroid.core.formatBytesExact
import cz.k4.salamanderdroid.core.formatOctal
import cz.k4.salamanderdroid.core.formatTime
import cz.k4.salamanderdroid.core.joinPath
import cz.k4.salamanderdroid.core.parseLocalTime
import cz.k4.salamanderdroid.core.parseOctal
import cz.k4.salamanderdroid.remote.AuthMethod
import cz.k4.salamanderdroid.remote.Server
import cz.k4.salamanderdroid.remote.randomHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// ---- Places (Alt+F1 / Alt+F2) ----

private class Place(val label: String, val detail: String = "", val header: Boolean = false, val go: () -> Unit = {})

@Composable
fun PlacesDialog(app: AppState, dialog: Dlg.Places) {
    val panel = dialog.target
    val places = remember {
        val list = ArrayList<Place>()
        fun local(label: String, path: String) = Place(label, path) { panel.navigate(Loc.Local(path)) }
        list += Place("Úložiště", header = true)
        for (volume in app.volumes()) {
            val dir = File(volume.path)
            list += local("${volume.label}  (volno ${formatBytes(dir.usableSpace)} z ${formatBytes(dir.totalSpace)})", volume.path)
        }
        val folders = listOf("Download" to "Stažené", "Documents" to "Dokumenty", "DCIM" to "Fotoaparát", "Pictures" to "Obrázky", "Music" to "Hudba", "Movies" to "Videa")
            .filter { File(app.home, it.first).isDirectory }
        if (folders.isNotEmpty()) list += Place("Složky", header = true)
        for ((dir, label) in folders) list += local(label, joinPath(app.home, dir))
        for (trash in app.trashDirs()) list += local("Koš", trash)
        val hotpaths = app.config.hotpaths.withIndex().filter { it.value != null }
        if (hotpaths.isNotEmpty()) list += Place("Klávesové cesty", header = true)
        for ((index, path) in hotpaths) list += Place("Ctrl+$index", path!!) { app.goTyped(path, panel) }
        if (app.cloudAccounts.isNotEmpty()) list += Place("Cloud", header = true)
        for (account in app.cloudAccounts) list += Place(account.displayName(), "${account.kind.label} — ${account.user.ifEmpty { "nepřihlášeno" }}") { app.openCloud(account, panel) }
        if (app.servers.isNotEmpty()) list += Place("Servery (SSH/SFTP)", header = true)
        for (server in app.servers) list += Place(server.displayName(), server.label()) { app.connect(server, panel) }
        val history = panel.history().drop(1).take(15)
        if (history.isNotEmpty()) list += Place("Historie", header = true)
        for (loc in history) list += Place(loc.display()) { panel.navigate(loc) }
        list
    }
    val cursor = remember { mutableIntStateOf(1) }
    val activate = { place: Place ->
        app.close(dialog)
        place.go()
    }
    DialogFrame(
        if (panel === app.left) "Levý panel: místa a svazky" else "Pravý panel: místa a svazky",
        { app.close(dialog) }, width = 640.dp,
        buttons = { TextButton(onClick = { app.close(dialog) }) { Text("Zavřít") } },
    ) {
        KeyList(places, cursor, selectable = { !it.header }, onActivate = activate, modifier = Modifier.heightIn(max = 480.dp)) { place, _ ->
            if (place.header) {
                Text(place.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            } else {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(place.label, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    if (place.detail.isNotEmpty()) Text(place.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                }
            }
        }
    }
}

// ---- Find files (Alt+F7) ----

@Composable
fun FindDialog(app: AppState, dialog: Dlg.Find) {
    val root = remember { fieldOf(dialog.root) }
    val mask = remember { fieldOf("*") }
    val content = remember { fieldOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<String>() }
    val cursor = remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Enter spustí hledání. Ve výsledcích: Enter přejde na soubor, F3 zobrazí.") }
    var running by remember { mutableStateOf(false) }
    val cancel = remember { mutableStateOf(AtomicBoolean(false)) }
    val listFocus = remember { FocusRequester() }

    fun stop() = cancel.value.set(true)
    fun start() {
        stop()
        val mine = AtomicBoolean(false)
        cancel.value = mine
        results.clear()
        cursor.intValue = 0
        running = true
        val query = FindQuery(root.value.text.trim(), Mask.parse(mask.value.text), content.value.text.toByteArray(), caseSensitive, hidden)
        app.scope.launch {
            withContext(Dispatchers.IO) {
                val batch = ArrayList<String>()
                var lastFlush = 0L
                var directory = ""
                suspend fun flush(force: Boolean) {
                    val now = System.currentTimeMillis()
                    if (!force && now - lastFlush < 150) return
                    lastFlush = now
                    val found = ArrayList(batch)
                    batch.clear()
                    val dir = directory
                    withContext(Dispatchers.Main) {
                        if (!mine.get()) {
                            results += found
                            status = "Prohledávám $dir"
                        }
                    }
                }
                var total = 0
                findFiles(query, mine) { event ->
                    when (event) {
                        is FindEvent.Directory -> directory = event.path
                        is FindEvent.Found -> {
                            batch += event.path
                            total++
                        }
                        is FindEvent.Finished -> kotlinx.coroutines.runBlocking {
                            flush(true)
                            withContext(Dispatchers.Main) {
                                if (cancel.value === mine) {
                                    running = false
                                    status = "Nalezeno ${results.size}" + (if (event.cancelled) " (zastaveno)" else "") +
                                        (if (event.errors > 0) ", nepřístupných adresářů: ${event.errors}" else "")
                                    if (results.isNotEmpty()) listFocus.requestFocus()
                                }
                            }
                        }
                    }
                    if (event !is FindEvent.Finished) kotlinx.coroutines.runBlocking { flush(false) }
                    total < 50_000
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { stop() } }
    val close = {
        stop()
        app.close(dialog)
    }
    var listFocused by remember { mutableStateOf(false) }
    DialogFrame(
        "Hledat soubory", close, width = 820.dp,
        // Enter in the fields starts the search; in the result list it opens the result.
        onKey = { event -> if (event.isEnterDown && !listFocused) { start(); true } else false },
        buttons = {
            if (running) OutlinedButton(onClick = { stop() }) { Text("Zastavit") }
            Button(onClick = { start() }) { Text("Hledat") }
            TextButton(onClick = close) { Text("Zavřít") }
        },
    ) {
        Field(root, "Hledat v", onDone = { start() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(mask, "Maska (*.txt;*.md|tmp*)", Modifier.weight(1f), autoFocus = true, onDone = { start() })
            Field(content, "Obsahuje text", Modifier.weight(1f), onDone = { start() })
        }
        Row {
            Column(Modifier.weight(1f)) { CheckRow(caseSensitive, "Rozlišovat velikost písmen") { caseSensitive = it } }
            Column(Modifier.weight(1f)) { CheckRow(hidden, "Včetně skrytých") { hidden = it } }
        }
        KeyList(
            results, cursor,
            onActivate = { path ->
                close()
                app.goToFound(path)
            },
            onKey = { event, path ->
                when {
                    event.isDown(Key.F3) && path != null -> {
                        if (File(path).isFile) app.viewFile(File(path))
                        true
                    }
                    else -> false
                }
            },
            modifier = Modifier.fillMaxWidth().height(280.dp),
            autoFocus = false,
            focusRequester = listFocus,
            onFocus = { listFocused = it },
        ) { path, _ ->
            Text(path, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Text(status, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis)
    }
}

// ---- Properties (Alt+Enter) ----

@Composable
fun PropertiesDialog(app: AppState, dialog: Dlg.Properties) {
    val entries = dialog.entries
    val single = entries.singleOrNull()
    var measured by remember { mutableStateOf<Measure?>(null) }
    val cancel = remember { AtomicBoolean(false) }
    val needsMeasure = entries.any { it.kind == EntryKind.Directory } && dialog.loc !is Loc.Archive
    LaunchedEffect(Unit) { if (needsMeasure) measured = app.measureEntries(dialog.loc, entries, cancel) }
    DisposableEffect(Unit) { onDispose { cancel.set(true) } }
    val close = { app.close(dialog) }
    DialogFrame(
        "Vlastnosti", close, width = 640.dp, onEnter = close, focusRoot = true,
        buttons = { Button(onClick = close) { Text("Zavřít") } },
    ) {
        if (single != null) {
            LabeledRow("Název", single.name)
            LabeledRow("Umístění", dialog.loc.display())
            LabeledRow(
                "Typ",
                when (single.kind) {
                    EntryKind.Directory -> "Adresář"
                    EntryKind.Symlink -> "Symbolický odkaz → ${single.linkTarget ?: "?"}"
                    EntryKind.Other -> "Speciální soubor"
                    else -> app.mimeOf(single.name) ?: "Soubor"
                },
            )
        } else {
            LabeledRow("Výběr", "${entries.size} položek")
            LabeledRow("Umístění", dialog.loc.display())
        }
        val plain = entries.filter { it.kind != EntryKind.Directory }.sumOf { it.size }
        val total = measured
        val sizeText = when {
            total != null -> "${formatBytes(total.bytes)} (${formatBytesExact(total.bytes)} B) — ${total.files} souborů, ${total.dirs} adresářů"
            needsMeasure -> "počítám…"
            else -> "${formatBytes(plain)} (${formatBytesExact(plain)} B)"
        }
        LabeledRow("Velikost", sizeText)
        if (single != null) {
            LabeledRow("Změněno", formatTime(single.modified, "yyyy-MM-dd HH:mm:ss"))
            single.mode?.let { LabeledRow("Práva", "${single.modeText()}  (${formatOctal(it)})", mono = true) }
            if (single.owner.isNotEmpty()) LabeledRow("Vlastník", "${single.owner}:${single.group}")
        }
    }
}

// ---- Attributes (Ctrl+F2) ----

@Composable
fun AttributesDialog(app: AppState, dialog: Dlg.Attributes) {
    val first = dialog.entries.first()
    val remote = dialog.loc is Loc.Remote
    val mode = remember { fieldOf(formatOctal(first.mode ?: 0x1A4)) }
    val time = remember { fieldOf(formatTime(first.modified ?: System.currentTimeMillis(), "yyyy-MM-dd HH:mm:ss")) }
    var changeMode by remember { mutableStateOf(remote) }
    var changeTime by remember { mutableStateOf(!remote) }
    var recursive by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val confirm = confirm@{
        val newMode = if (changeMode) parseOctal(mode.value.text) ?: run { error = "Práva zadejte osmičkově, např. 644 nebo 0755."; return@confirm } else null
        val newTime = if (changeTime) parseLocalTime(time.value.text) ?: run { error = "Čas zadejte jako RRRR-MM-DD HH:MM:SS."; return@confirm } else null
        app.close(dialog)
        app.applyAttributes(dialog.loc, dialog.entries, newMode, newTime, recursive)
    }
    DialogFrame(
        "Oprávnění a atributy", { app.close(dialog) }, onEnter = confirm,
        buttons = {
            TextButton(onClick = { app.close(dialog) }) { Text("Zrušit") }
            Button(onClick = confirm) { Text("Použít") }
        },
    ) {
        Text(if (dialog.entries.size == 1) first.name else "${dialog.entries.size} položek", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (remote) {
            CheckRow(changeMode, "Změnit práva") { changeMode = it }
            Field(mode, "Práva osmičkově (např. 644)", autoFocus = true, number = true, mono = true)
        } else {
            if (dialog.loc is Loc.Local) Text("Práva a vlastníka nelze na sdíleném úložišti Androidu měnit; lze nastavit čas změny.", fontSize = 13.sp)
        }
        CheckRow(changeTime, "Změnit čas změny") { changeTime = it }
        Field(time, "Čas změny (RRRR-MM-DD HH:MM:SS)", autoFocus = !remote, mono = true)
        CheckRow(recursive, "Použít i na obsah adresářů (rekurzivně)") { recursive = it }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

// ---- Pack (Alt+F5) and unpack (Alt+F9) ----

@Composable
fun PackDialog(app: AppState, dialog: Dlg.Pack) {
    var format by remember { mutableStateOf(ArchiveFormat.Zip) }
    val base = joinPath(dialog.destDir, dialog.name)
    val target = remember { fieldOf("$base.zip", TextRange(dialog.destDir.length + 1, base.length)) }
    val confirm = {
        app.close(dialog)
        dialog.onConfirm(target.value.text.trim())
    }
    DialogFrame(
        "Zabalit", { app.close(dialog) }, width = 640.dp, onEnter = confirm,
        buttons = {
            TextButton(onClick = { app.close(dialog) }) { Text("Zrušit") }
            Button(onClick = confirm) { Text("Zabalit") }
        },
    ) {
        Text("Zabalit ${dialog.count} položek do archivu:")
        Spacer(Modifier.height(8.dp))
        Field(target, autoFocus = true, onDone = confirm)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            for (candidate in ArchiveFormat.PACKABLE) {
                FilterChip(selected = format == candidate, label = { Text(candidate.label) }, onClick = {
                    val text = target.value.text
                    val dir = text.substringBeforeLast('/', "")
                    val stem = ArchiveFormat.stem(text.substringAfterLast('/'))
                    format = candidate
                    val updated = joinPath(dir, "$stem.${candidate.label}")
                    target.value = TextFieldValue(updated, TextRange(updated.length))
                })
            }
        }
    }
}

@Composable
fun UnpackDialog(app: AppState, dialog: Dlg.Unpack) {
    val target = remember { fieldOf(dialog.destDir) }
    var subfolders by remember { mutableStateOf(dialog.count > 1) }
    val confirm = {
        app.close(dialog)
        dialog.onConfirm(target.value.text.trim(), subfolders)
    }
    DialogFrame(
        "Rozbalit", { app.close(dialog) }, width = 640.dp, onEnter = confirm,
        buttons = {
            TextButton(onClick = { app.close(dialog) }) { Text("Zrušit") }
            Button(onClick = confirm) { Text("Rozbalit") }
        },
    ) {
        Text(if (dialog.count == 1) "Rozbalit archiv do:" else "Rozbalit ${dialog.count} archivů do:")
        Spacer(Modifier.height(8.dp))
        Field(target, autoFocus = true, onDone = confirm)
        CheckRow(subfolders, "Každý archiv do vlastní složky") { subfolders = it }
    }
}

// ---- Remote servers (Ctrl+K) ----

@Composable
fun ServerManagerDialog(app: AppState, dialog: Dlg.ServerManager) {
    val servers = app.servers
    val cursor = remember { mutableIntStateOf(0) }
    val current = servers.getOrNull(cursor.intValue)
    val connect = { server: Server ->
        app.close(dialog)
        app.connect(server)
    }
    DialogFrame(
        "Vzdálené servery", { app.close(dialog) }, width = 760.dp, focusRoot = servers.isEmpty(),
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Button(enabled = current != null, onClick = { current?.let(connect) }) { Text("Připojit") }
                OutlinedButton(onClick = { app.editServer(Server(), creating = true) }) { Text("Nový…") }
                OutlinedButton(enabled = current != null, onClick = { current?.let { app.editServer(it, creating = false) } }) { Text("Upravit…") }
                OutlinedButton(enabled = current != null, onClick = { current?.let { app.duplicateServer(it) } }) { Text("Duplikovat") }
                OutlinedButton(enabled = current != null, onClick = { current?.let { app.deleteServer(it) } }) { Text("Smazat") }
                OutlinedButton(enabled = current != null, onClick = { current?.let { app.forgetHostKey(it) } }) { Text("Zapomenout klíč") }
                TextButton(onClick = { app.close(dialog) }) { Text("Zavřít") }
            }
        },
    ) {
        if (servers.isEmpty()) {
            Text("Zatím není uložen žádný server. Tlačítkem „Nový…“ jej přidáte.")
        } else {
            KeyList(
                servers, cursor, onActivate = connect,
                onKey = { event, server ->
                    when {
                        event.isDown(Key.F4) && server != null -> { app.editServer(server, creating = false); true }
                        event.isDown(Key.Insert) -> { app.editServer(Server(), creating = true); true }
                        event.isDown(Key.Delete) && server != null -> { app.deleteServer(server); true }
                        else -> false
                    }
                },
                modifier = Modifier.heightIn(max = 360.dp),
            ) { server, _ ->
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(server.displayName(), fontWeight = FontWeight.Bold)
                    val detail = "${server.label()} — ${server.auth.label}" +
                        (if (app.hasStoredPassword(server)) " — heslo v trezoru" else "") +
                        (if (app.connections.isConnected(server.id)) " — připojeno" else "")
                    Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter připojí vybraný server do aktivního panelu (F4 upravit, Delete smazat). Hesla jsou jen v šifrovaném " +
                "trezoru; práce se servery se zapisuje do logu (Vzdálené → Log vzdálených operací).",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ServerEditDialog(app: AppState, dialog: Dlg.ServerEdit) {
    val server = dialog.server
    val name = remember { fieldOf(server.name) }
    val host = remember { fieldOf(server.host) }
    val port = remember { fieldOf(server.port.toString()) }
    val user = remember { fieldOf(server.user) }
    var auth by remember { mutableStateOf(server.auth) }
    val identity = remember { fieldOf(server.identity.orEmpty()) }
    val dir = remember { fieldOf(server.initialDir) }
    var save by remember { mutableStateOf(server.savePassword) }
    val password = remember { fieldOf("") }
    var forget by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val confirm = confirm@{
        val edited = server.copy(
            name = name.value.text.trim(), host = host.value.text.trim(), port = port.value.text.trim().toIntOrNull() ?: 0,
            user = user.value.text.trim(), auth = auth, identity = identity.value.text.trim().takeIf { it.isNotEmpty() },
            initialDir = dir.value.text.trim(), savePassword = save,
        )
        edited.validate()?.let {
            error = it
            return@confirm
        }
        app.close(dialog)
        dialog.onSave(edited, password.value.text, forget)
    }
    DialogFrame(
        if (dialog.creating) "Nový server" else "Upravit server", { app.close(dialog) }, width = 640.dp, onEnter = confirm,
        buttons = {
            TextButton(onClick = { app.close(dialog) }) { Text("Zrušit") }
            Button(onClick = confirm) { Text("Uložit") }
        },
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(name, "Název (např. Produkční web)", autoFocus = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(host, "Server (adresa)", Modifier.weight(3f))
                Field(port, "Port", Modifier.weight(1f), number = true)
            }
            Field(user, "Uživatel")
            Text("Ověření:", fontSize = 13.sp)
            for (method in AuthMethod.entries) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = auth == method, onClick = { auth = method })
                    Text(method.label)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(identity, "Soubor se soukromým klíčem (nepovinné)", Modifier.weight(1f))
                OutlinedButton(onClick = {
                    val panel = app.active
                    val entry = panel.cursorEntry()
                    if (panel.loc is Loc.Local && entry != null && !entry.isDirLike) {
                        identity.value = TextFieldValue(panel.loc.pathOf(entry.name))
                    } else {
                        error = "Najeďte v panelu kurzorem na soubor s klíčem a zkuste to znovu."
                    }
                }) { Text("Z kurzoru") }
            }
            Field(dir, "Počáteční adresář (prázdné = domovský)")
            CheckRow(save, "Ukládat heslo do šifrovaného trezoru") { save = it }
            if (save) {
                Field(password, if (dialog.hasStoredPassword) "Heslo (uloženo — vyplňte jen pro změnu)" else "Heslo (nepovinné; jinak se zeptá při připojení)", password = true)
                if (dialog.hasStoredPassword) CheckRow(forget, "Smazat uložené heslo") { forget = it }
            }
            Text(
                "Při prvním připojení se zobrazí otisk klíče serveru k potvrzení. Soukromý klíč může být ve formátu OpenSSH nebo PEM.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ---- Cloud accounts ----

@Composable
fun CloudAccountsDialog(app: AppState, dialog: Dlg.CloudAccounts) {
    val accounts = app.cloudAccounts
    val cursor = remember { mutableIntStateOf(0) }
    val current = accounts.getOrNull(cursor.intValue)
    val open = { account: CloudAccount ->
        if (app.cloudSignedIn(account)) {
            app.close(dialog)
            app.openCloud(account)
        } else {
            app.signInCloud(account)
        }
    }
    DialogFrame(
        "Cloudové účty", { app.close(dialog) }, width = 760.dp, focusRoot = accounts.isEmpty(),
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Button(enabled = current != null, onClick = { current?.let(open) }) { Text("Otevřít") }
                OutlinedButton(onClick = { app.editCloudAccount(CloudAccount(randomHex(8), CloudKind.OneDrive, "", ""), creating = true) }) { Text("Nový…") }
                OutlinedButton(enabled = current != null, onClick = { current?.let { app.editCloudAccount(it, creating = false) } }) { Text("Upravit…") }
                OutlinedButton(enabled = current != null, onClick = { current?.let { app.signInCloud(it) } }) { Text("Přihlásit znovu") }
                OutlinedButton(enabled = current != null, onClick = { current?.let { app.removeCloudAccount(it) } }) { Text("Odebrat") }
                TextButton(onClick = { app.close(dialog) }) { Text("Zavřít") }
            }
        },
    ) {
        if (accounts.isEmpty()) {
            Text("Zatím není přidán žádný účet. Tlačítkem „Nový…“ přidáte OneDrive (osobní i firemní) nebo Google Drive.")
        } else {
            KeyList(
                accounts, cursor, onActivate = open,
                onKey = { event, account ->
                    when {
                        event.isDown(Key.F4) && account != null -> { app.editCloudAccount(account, creating = false); true }
                        event.isDown(Key.Delete) && account != null -> { app.removeCloudAccount(account); true }
                        else -> false
                    }
                },
                modifier = Modifier.heightIn(max = 360.dp),
            ) { account, _ ->
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(account.displayName(), fontWeight = FontWeight.Bold)
                    Text(
                        "${account.kind.label} — " + (if (app.cloudSignedIn(account)) account.user.ifEmpty { "přihlášeno" } else "nepřihlášeno"),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter otevře účet v aktivním panelu. Každý účet potřebuje vlastní registraci aplikace u Microsoftu nebo Googlu " +
                "(ID aplikace); postup je v README projektu. Přihlášení probíhá v prohlížeči, aplikace heslo nikdy nevidí.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CloudAccountEditDialog(app: AppState, dialog: Dlg.CloudAccountEdit) {
    val account = dialog.account
    var kind by remember { mutableStateOf(account.kind) }
    val name = remember { fieldOf(account.name) }
    val clientId = remember { fieldOf(account.clientId) }
    val secret = remember { fieldOf(account.clientSecret) }
    val tenant = remember { fieldOf(account.tenant) }
    var error by remember { mutableStateOf("") }
    val confirm = confirm@{
        val edited = account.copy(
            kind = kind, name = name.value.text.trim(), clientId = clientId.value.text.trim(),
            clientSecret = if (kind == CloudKind.GoogleDrive) secret.value.text.trim() else "",
            tenant = tenant.value.text.trim().ifEmpty { "common" },
        )
        edited.validate()?.let {
            error = it
            return@confirm
        }
        if (app.cloudAccounts.any { it.id != edited.id && it.kind == edited.kind && it.pathName() == edited.pathName() }) {
            error = "Účet s tímto názvem už existuje; zvolte jiný název."
            return@confirm
        }
        app.close(dialog)
        dialog.onSave(edited)
    }
    DialogFrame(
        if (dialog.creating) "Nový cloudový účet" else "Upravit cloudový účet", { app.close(dialog) }, width = 640.dp, onEnter = confirm,
        buttons = {
            TextButton(onClick = { app.close(dialog) }) { Text("Zrušit") }
            Button(onClick = confirm) { Text(if (dialog.creating) "Uložit a přihlásit" else "Uložit") }
        },
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dialog.creating) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (candidate in CloudKind.entries) {
                        FilterChip(selected = kind == candidate, label = { Text(candidate.label) }, onClick = { kind = candidate })
                    }
                }
            } else {
                Text(kind.label, fontWeight = FontWeight.Bold)
            }
            Field(name, "Název (např. Práce, Soukromý)", autoFocus = true)
            Field(clientId, if (kind == CloudKind.OneDrive) "ID aplikace (Application/client ID z Microsoft Entra)" else "Client ID (Google Cloud Console)", mono = true)
            if (kind == CloudKind.GoogleDrive) {
                Field(secret, "Client secret (klient typu „Desktop app“)", mono = true)
            } else {
                Field(tenant, "Tenant (common = osobní i firemní účty)", mono = true)
            }
            Text(
                if (kind == CloudKind.OneDrive) {
                    "Registrace v Microsoft Entra: typ účtů „libovolná organizace a osobní účty Microsoft“, platforma " +
                        "„Mobilní a desktopové aplikace“ s adresou přesměrování http://localhost, oprávnění Files.ReadWrite.All."
                } else {
                    "Projekt v Google Cloud Console: povolit Google Drive API, OAuth klient typu „Desktop app“, " +
                        "stav publikování „In production“ (jinak přihlášení po 7 dnech vyprší)."
                },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ---- Settings (Ctrl+,) ----

@Composable
fun SettingsDialog(app: AppState, dialog: Dlg.Settings) {
    val config = app.config
    val close = { app.close(dialog) }
    DialogFrame(
        "Nastavení", close, width = 600.dp, focusRoot = true, onEnter = close,
        buttons = { Button(onClick = close) { Text("Zavřít") } },
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text("Procházení", fontWeight = FontWeight.Bold)
            CheckRow(config.showParentEntry, "Zobrazovat položku „..“ pro přechod do nadřazené složky") { value -> app.updateConfig { it.copy(showParentEntry = value) } }
            CheckRow(config.showHidden, "Zobrazovat skryté položky (Ctrl+H)") { value -> app.updateConfig { it.copy(showHidden = value) } }
            Spacer(Modifier.height(8.dp))
            Text("Sloupce", fontWeight = FontWeight.Bold)
            CheckRow(config.showExtColumn, "Přípona") { value -> app.updateConfig { it.copy(showExtColumn = value) } }
            CheckRow(config.showPermissionsColumn, "Práva") { value -> app.updateConfig { it.copy(showPermissionsColumn = value) } }
            CheckRow(config.showOwnerColumn, "Vlastník a skupina") { value -> app.updateConfig { it.copy(showOwnerColumn = value) } }
            Spacer(Modifier.height(8.dp))
            Text("Vzhled", fontWeight = FontWeight.Bold)
            CheckRow(config.showButtonBar, "Zobrazovat lištu tlačítek F2–F8") { value -> app.updateConfig { it.copy(showButtonBar = value) } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Velikost písma panelů:")
                OutlinedButton(onClick = { app.updateConfig { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(9)) } }) { Text("−") }
                Text("${config.fontSize}", modifier = Modifier.width(28.dp))
                OutlinedButton(onClick = { app.updateConfig { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(28)) } }) { Text("+") }
            }
        }
    }
}

package cz.k4.salamanderdroid.ui

import android.view.KeyCharacterMap
import android.view.KeyEvent
import cz.k4.salamanderdroid.core.OpKind
import cz.k4.salamanderdroid.core.SortKey

/** Character typed without Ctrl/Alt/Meta, used for quick search. */
private fun typedChar(event: KeyEvent): Char? {
    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return null
    val code = event.unicodeChar
    if (code == 0 || code and KeyCharacterMap.COMBINING_ACCENT != 0) return null
    val char = code.toChar()
    return if (char.isISOControl()) null else char
}

/** Main-window keyboard handling (key down). Returns true when the key was used. */
fun handleKey(app: AppState, event: KeyEvent): Boolean {
    val panel = app.active
    val ctrl = event.isCtrlPressed
    val shift = event.isShiftPressed
    val alt = event.isAltPressed
    val plain = !ctrl && !shift && !alt
    val code = event.keyCode

    if (panel.quickSearch.isNotEmpty()) {
        when {
            code == KeyEvent.KEYCODE_ESCAPE -> {
                panel.quickSearchEnd()
                return true
            }
            code == KeyEvent.KEYCODE_DEL -> {
                panel.quickSearchPop()
                return true
            }
            code == KeyEvent.KEYCODE_DPAD_DOWN && plain -> {
                panel.quickSearchNext(true)
                return true
            }
            code == KeyEvent.KEYCODE_DPAD_UP && plain -> {
                panel.quickSearchNext(false)
                return true
            }
        }
        typedChar(event)?.let {
            panel.quickSearchPush(it)
            return true
        }
        panel.quickSearchEnd()
    }

    // Hot paths use the key codes of the digit row, so they also work on layouts such as
    // Czech where the unshifted row produces accented letters.
    if (ctrl && !alt && code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
        val index = code - KeyEvent.KEYCODE_0
        if (shift) app.setHotpath(index) else app.goHotpath(index)
        return true
    }

    val enter = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
    when {
        code == KeyEvent.KEYCODE_TAB -> app.switchPanel()
        enter && alt && shift -> app.calculateSize()
        enter && alt -> app.properties()
        enter && shift -> app.openExternal()
        enter -> app.activateEntry()
        code == KeyEvent.KEYCODE_DEL && plain -> panel.navigateParent()
        (code == KeyEvent.KEYCODE_INSERT || code == KeyEvent.KEYCODE_SPACE) && plain -> panel.toggleMarkAndAdvance()
        code == KeyEvent.KEYCODE_DPAD_UP && shift && !ctrl -> panel.markAndMove(-1)
        code == KeyEvent.KEYCODE_DPAD_DOWN && shift && !ctrl -> panel.markAndMove(1)
        code == KeyEvent.KEYCODE_DPAD_UP && plain -> panel.moveCursorBy(-1)
        code == KeyEvent.KEYCODE_DPAD_DOWN && plain -> panel.moveCursorBy(1)
        code == KeyEvent.KEYCODE_PAGE_UP && ctrl -> panel.navigateParent()
        code == KeyEvent.KEYCODE_PAGE_DOWN && ctrl -> app.activateEntry()
        code == KeyEvent.KEYCODE_PAGE_UP && plain -> panel.moveCursorBy(-panel.pageRows)
        code == KeyEvent.KEYCODE_PAGE_DOWN && plain -> panel.moveCursorBy(panel.pageRows)
        code == KeyEvent.KEYCODE_MOVE_HOME && alt -> panel.navigate(Loc.Local(app.home))
        code == KeyEvent.KEYCODE_MOVE_HOME && plain -> panel.moveCursor(0)
        code == KeyEvent.KEYCODE_MOVE_END && plain -> panel.moveCursor(Int.MAX_VALUE)
        code == KeyEvent.KEYCODE_NUMPAD_ADD || (ctrl && (code == KeyEvent.KEYCODE_EQUALS || code == KeyEvent.KEYCODE_PLUS)) -> app.markGroup(true)
        code == KeyEvent.KEYCODE_NUMPAD_SUBTRACT || (ctrl && code == KeyEvent.KEYCODE_MINUS) -> app.markGroup(false)
        code == KeyEvent.KEYCODE_NUMPAD_MULTIPLY || (ctrl && code == KeyEvent.KEYCODE_I) -> panel.invertMarks()
        code == KeyEvent.KEYCODE_A && ctrl -> panel.markAll()
        code == KeyEvent.KEYCODE_D && ctrl -> panel.clearMarks()
        code == KeyEvent.KEYCODE_C && ctrl && shift -> app.copyPathText()
        code == KeyEvent.KEYCODE_C && ctrl -> app.clipboardCopy(cut = false)
        code == KeyEvent.KEYCODE_X && ctrl -> app.clipboardCopy(cut = true)
        code == KeyEvent.KEYCODE_V && ctrl -> app.clipboardPaste()
        code == KeyEvent.KEYCODE_DPAD_LEFT && ctrl && !alt -> app.openInPanel(app.left)
        code == KeyEvent.KEYCODE_DPAD_RIGHT && ctrl && !alt -> app.openInPanel(app.right)
        code == KeyEvent.KEYCODE_DPAD_LEFT && alt -> panel.historyStep(-1)
        code == KeyEvent.KEYCODE_DPAD_RIGHT && alt -> panel.historyStep(1)
        code == KeyEvent.KEYCODE_FORWARD_DEL && shift -> app.remove(permanent = true)
        code == KeyEvent.KEYCODE_FORWARD_DEL -> app.remove(permanent = false)
        (code == KeyEvent.KEYCODE_L && ctrl) || (code == KeyEvent.KEYCODE_F7 && shift) -> app.pathEditing = true
        code == KeyEvent.KEYCODE_U && ctrl -> app.swapPanels()
        code == KeyEvent.KEYCODE_H && ctrl -> app.updateConfig { it.copy(showHidden = !it.showHidden) }
        code == KeyEvent.KEYCODE_R && ctrl -> panel.reload(quiet = false)
        code == KeyEvent.KEYCODE_K && ctrl && shift -> app.cloudAccountsDialog()
        code == KeyEvent.KEYCODE_K && ctrl -> app.serverManager()
        code == KeyEvent.KEYCODE_COMMA && ctrl -> app.dialogs += Dlg.Settings()
        code == KeyEvent.KEYCODE_Q && ctrl -> app.onExit()
        code == KeyEvent.KEYCODE_P && ctrl -> app.quickView = !app.quickView
        code == KeyEvent.KEYCODE_S && ctrl -> app.share()
        code == KeyEvent.KEYCODE_N && ctrl -> app.onNewWindow()
        code == KeyEvent.KEYCODE_F1 && alt -> app.places(app.left)
        code == KeyEvent.KEYCODE_F2 && alt -> app.places(app.right)
        code == KeyEvent.KEYCODE_F1 && plain -> app.showShortcuts()
        code == KeyEvent.KEYCODE_F2 && shift -> app.compareDirectories()
        code == KeyEvent.KEYCODE_F2 && ctrl -> app.attributes()
        code == KeyEvent.KEYCODE_F2 && plain -> app.rename()
        code == KeyEvent.KEYCODE_F3 && ctrl -> app.sortBy(SortKey.Name)
        code == KeyEvent.KEYCODE_F4 && ctrl -> app.sortBy(SortKey.Extension)
        code == KeyEvent.KEYCODE_F5 && ctrl && shift -> app.showError("Symbolické odkazy nelze na sdíleném úložišti Androidu vytvářet.")
        code == KeyEvent.KEYCODE_F5 && ctrl -> app.sortBy(SortKey.Time)
        code == KeyEvent.KEYCODE_F6 && ctrl -> app.sortBy(SortKey.Size)
        code == KeyEvent.KEYCODE_F3 && plain -> app.view()
        code == KeyEvent.KEYCODE_F4 && shift -> app.create(file = true)
        code == KeyEvent.KEYCODE_F4 && plain -> app.edit()
        code == KeyEvent.KEYCODE_F5 && alt -> app.pack()
        code == KeyEvent.KEYCODE_F5 && plain -> app.copyMove(OpKind.Copy)
        code == KeyEvent.KEYCODE_F6 && plain -> app.copyMove(OpKind.Move)
        code == KeyEvent.KEYCODE_F7 && alt -> app.find()
        code == KeyEvent.KEYCODE_F7 && plain -> app.create(file = false)
        code == KeyEvent.KEYCODE_F8 && shift -> app.remove(permanent = true)
        code == KeyEvent.KEYCODE_F8 && plain -> app.remove(permanent = false)
        code == KeyEvent.KEYCODE_F9 && alt -> app.unpack()
        (code == KeyEvent.KEYCODE_F9 || code == KeyEvent.KEYCODE_F10) && plain -> app.menuRequest = 0
        code == KeyEvent.KEYCODE_ESCAPE -> {}
        else -> {
            val char = typedChar(event)?.takeIf { it != ' ' } ?: return false
            panel.quickSearchPush(char)
        }
    }
    return true
}

const val SHORTCUTS = """Panely
  Tab — přepnout panel          Ctrl+U — prohodit panely
  Enter — otevřít               Backspace, Ctrl+PgUp — nadřazený adresář
  Alt+←/→ — historie zpět/vpřed Alt+Home — hlavní úložiště
  Alt+F1 / Alt+F2 — místa a svazky pro levý / pravý panel
  Ctrl+← / Ctrl+→ — otevřít adresář pod kurzorem v levém / pravém panelu
  Ctrl+L, Shift+F7 — upravit cestu
  Ctrl+1…0 — klávesová cesta, Ctrl+Shift+1…0 — uložit klávesovou cestu
  psaní písmen — rychlé hledání (↑/↓ další shoda, Esc konec)

Výběr
  Insert, mezerník — označit a posunout   Shift+↑/↓ — označovat
  Num + / Num − (nebo Ctrl+= / Ctrl+−) — vybrat / odznačit skupinu podle masky
  Num * (nebo Ctrl+I) — invertovat výběr   Ctrl+A / Ctrl+D — vše / nic
  Shift+F2 — porovnat adresáře (označí odlišné soubory)

Soubory
  F2 přejmenovat, F3 zobrazit, F4 upravit, Shift+F4 nový soubor
  F5 kopírovat, F6 přesunout, F7 nová složka
  F8, Delete — do koše; Shift+F8, Shift+Delete — trvale smazat
  Alt+Enter — vlastnosti; Ctrl+F2 — atributy (čas změny, na serveru i práva)
  Alt+Shift+Enter — spočítat obsazené místo
  Shift+Enter — otevřít v jiné aplikaci (výběr aplikace)
  Ctrl+C / Ctrl+X / Ctrl+V — schránka souborů (uvnitř aplikace)
  Ctrl+Shift+C — zkopírovat cestu jako text

Archivy (zip, tar, tar.gz/xz/zst/bz2, 7z; jen čtení: cpio, ar/deb)
  Enter na archivu — procházet jako adresář, Backspace — zpět ven
  F5 z archivu — rozbalit vybrané; F5 do archivu — přidat; F8 v archivu — odstranit
  Alt+F5 — zabalit výběr, Alt+F9 — rozbalit vybrané archivy

Zobrazení a nástroje
  Ctrl+H skryté soubory, Ctrl+R obnovit, Ctrl+F3…F6 řadit jméno/přípona/čas/velikost
  Ctrl+P rychlé zobrazení (náhled souboru pod kurzorem v druhém panelu)
  Alt+F7 hledat soubory, Ctrl+, nastavení, F9 nabídka, Ctrl+N nové okno, Ctrl+Q konec
  Ctrl+S sdílet vybrané soubory do jiné aplikace

Vzdálené servery (SSH/SFTP)
  Ctrl+K — správce serverů a připojení; servery jsou i v Alt+F1/Alt+F2
  F5/F6 — stáhnout nebo nahrát (přesunout); F4 — upravit, uložení se nahraje zpět
  F2, F7, Shift+F4, F8 — přejmenovat, složka, soubor, smazat; Ctrl+F2 — oprávnění

Cloud (OneDrive, Google Drive)
  Ctrl+Shift+K — cloudové účty; účty jsou i v Alt+F1/Alt+F2
  F5/F6 — stáhnout nebo nahrát, i mezi cloudem a serverem; F3, F4, F2, F7, F8 jako jinde
  smazané položky se přesunou do koše služby

Prohlížeč (F3)
  Ctrl+F / F7 hledat, F3 / Shift+F3 další / předchozí, H text/hex, W zalamování, Esc zavřít
Editor (F4)
  Ctrl+S / F2 uložit, Esc zavřít

Myš a trackpad
  klik — kurzor, klik na kurzor — otevřít, pravé tlačítko — místní nabídka
  Ctrl+klik — označit, Shift+klik — označit rozsah
  tažení souborů do jiného okna nebo druhého panelu; puštění souborů z jiné aplikace je uloží do panelu

Dotykové ovládání
  klepnutí — kurzor, klepnutí na kurzor — otevřít, dlouhý stisk — označit
  klepnutí na cestu — upravit cestu, ▾ — místa a svazky, ⇄ — přepnout panel"""

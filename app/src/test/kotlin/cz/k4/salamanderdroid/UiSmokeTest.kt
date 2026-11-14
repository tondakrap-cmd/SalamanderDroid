package cz.k4.salamanderdroid

import android.os.Environment
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.lifecycle.ViewModelProvider
import cz.k4.salamanderdroid.ui.AppState
import cz.k4.salamanderdroid.ui.Dlg
import cz.k4.salamanderdroid.ui.Loc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Keeps the embedded SSH test server's classes out of the Robolectric sandbox class loader. */
class AppTestRunner(klass: Class<*>) : RobolectricTestRunner(klass) {
    override fun createClassLoaderConfig(method: org.junit.runners.model.FrameworkMethod): org.robolectric.internal.bytecode.InstrumentationConfiguration =
        org.robolectric.internal.bytecode.InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquirePackage("org.apache.sshd.")
            .build()
}

/** Drives the real activity on the JVM: composition, keyboard handling and file operations. */
@RunWith(AppTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land")
class UiSmokeTest {
    // Must run before the activity starts: the platform check does not work off-device.
    @get:Rule(order = 0)
    val access = object : org.junit.rules.ExternalResource() {
        override fun before() {
            AppState.storageAccessCheck = { false }
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var root: File
    private val app: AppState get() = ViewModelProvider(rule.activity)[AppState::class.java]

    @Before
    fun setUp() {
        root = Environment.getExternalStorageDirectory()
        File(root, "docs").mkdirs()
        File(root, "docs/inner.txt").writeText("inner")
        File(root, "alpha.txt").writeText("první řádek\ndruhý řádek hledané slovo\n")
        File(root, "beta.txt").writeText("beta")
        File(root, ".hidden").writeText("h")
    }

    private fun grantAndStart() {
        AppState.storageAccessCheck = { true }
        rule.runOnUiThread { app.checkStorageAccess() }
        waitFor { app.left.loaded && app.right.loaded }
    }

    private fun waitFor(condition: () -> Boolean) = try {
        rule.waitUntil(15_000) { condition() }
    } catch (error: Throwable) {
        val dialogs = app.dialogs.map { (it as? Dlg.Message)?.text ?: (it as? Dlg.Errors)?.errors?.toString() ?: it.javaClass.simpleName }
        throw AssertionError(
            "state: active=${app.active.loc.display()} cursor=${app.active.cursorEntry()?.name} rows=${app.active.rows.map { it.name }} " +
                "busy=${app.busy} dialogs=$dialogs files=${root.walkTopDown().map { it.relativeTo(root).path }.toList()}",
            error,
        )
    }

    private fun key(code: Int, meta: Int = 0) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))
            rule.activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, meta))
        }
        rule.waitForIdle()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    private fun pressEnterInDialog() {
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        rule.waitForIdle()
    }

    private fun names() = app.active.rows.filter { !it.isParent }.map { it.name }

    private fun cursorTo(name: String) {
        rule.runOnUiThread { app.active.moveCursor(app.active.rows.indexOfFirst { it.name == name }) }
        rule.waitForIdle()
    }

    @Test
    fun asksForStorageAccessFirst() {
        rule.onNodeWithText("Otevřít nastavení oprávnění").assertIsDisplayed()
    }

    @Test
    fun browsesMarksAndRunsFileOperationsFromKeyboard() {
        grantAndStart()
        rule.onNodeWithText("Soubor").assertIsDisplayed()
        assertEquals(listOf("docs", "alpha.txt", "beta.txt"), names())
        assertTrue(rule.onAllNodesWithText("alpha.txt").fetchSemanticsNodes().size == 2)

        // Hidden files toggle (Ctrl+H) and quick search by typing.
        key(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
        assertEquals(listOf(".hidden", "docs", "alpha.txt", "beta.txt"), names().sortedBy { it != ".hidden" })
        key(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
        key(KeyEvent.KEYCODE_B)
        assertEquals("b", app.active.quickSearch)
        assertEquals("beta.txt", app.active.cursorEntry()!!.name)
        key(KeyEvent.KEYCODE_ESCAPE)
        assertEquals("", app.active.quickSearch)

        // F7 creates a directory through the prompt dialog.
        key(KeyEvent.KEYCODE_F7)
        rule.onNodeWithText("Název nové složky (lze i a/b/c):").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("nová/pod")
        rule.onNodeWithText("Vytvořit").performClick()
        waitFor { File(root, "nová/pod").isDirectory && app.active.cursorEntry()?.name == "nová" }

        // Enter into "docs", Backspace back with the cursor on it.
        cursorTo("docs")
        key(KeyEvent.KEYCODE_ENTER)
        waitFor { (app.active.loc as Loc.Local).path.endsWith("/docs") }
        assertEquals(listOf("inner.txt"), names())
        key(KeyEvent.KEYCODE_DEL)
        waitFor { app.active.loc == Loc.Local(root.path) && app.active.cursorEntry()?.name == "docs" }

        // Mark two files with Space, copy with F5 into the other panel's directory.
        rule.runOnUiThread { app.right.navigate(Loc.Local(File(root, "nová").path)) }
        waitFor { app.right.loc == Loc.Local(File(root, "nová").path) }
        cursorTo("alpha.txt")
        key(KeyEvent.KEYCODE_SPACE)
        key(KeyEvent.KEYCODE_SPACE)
        assertEquals(setOf("alpha.txt", "beta.txt"), app.active.marked)
        key(KeyEvent.KEYCODE_F5)
        rule.onNodeWithText("Kopírovat 2 položek do:").assertIsDisplayed()
        pressEnterInDialog()
        waitFor { File(root, "nová/beta.txt").isFile && !app.busy && app.dialogs.isEmpty() }
        assertEquals("beta", File(root, "nová/beta.txt").readText())
        waitFor { app.right.rows.any { it.name == "alpha.txt" } }

        // Tab switches panels; Shift+F8 deletes permanently after confirmation.
        key(KeyEvent.KEYCODE_TAB)
        assertTrue(!app.leftActive)
        cursorTo("beta.txt")
        key(KeyEvent.KEYCODE_F8, KeyEvent.META_SHIFT_ON)
        rule.onNodeWithText("Trvale smazat „beta.txt“? Tuto akci nelze vrátit.").assertIsDisplayed()
        rule.onNode(
            androidx.compose.ui.test.hasText("Smazat") and androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button,
            )
        ).performClick()
        waitFor { !File(root, "nová/beta.txt").exists() && !app.busy }

        // Pack the remaining file to zip, open the archive like a directory.
        waitFor { names().contains("alpha.txt") && !names().contains("beta.txt") }
        cursorTo("alpha.txt")
        key(KeyEvent.KEYCODE_F5, KeyEvent.META_ALT_ON)
        rule.onNodeWithText("Zabalit 1 položek do archivu:").assertIsDisplayed()
        pressEnterInDialog()
        waitFor { File(root, "alpha.zip").isFile && !app.busy }
        key(KeyEvent.KEYCODE_TAB)
        waitFor { names().contains("alpha.zip") }
        cursorTo("alpha.zip")
        key(KeyEvent.KEYCODE_ENTER)
        waitFor { app.active.loc is Loc.Archive }
        assertEquals(listOf("alpha.txt"), names())

        // F3 inside the archive extracts to a temporary file and opens the viewer.
        cursorTo("alpha.txt")
        key(KeyEvent.KEYCODE_F3)
        waitFor { app.dialogs.any { it is Dlg.Viewer } }
        rule.waitUntil(15_000) { rule.onAllNodesWithText("druhý řádek hledané slovo").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnUiThread { app.dialogs.clear() }

        // The cloud account editor composes for both services and validates its input.
        rule.runOnUiThread {
            app.editCloudAccount(cz.k4.salamanderdroid.cloud.CloudAccount("x", cz.k4.salamanderdroid.cloud.CloudKind.GoogleDrive, "", ""), creating = true)
        }
        rule.waitForIdle()
        rule.onNodeWithText("Uložit a přihlásit").performClick()
        rule.onNodeWithText("Zadejte ID aplikace (client ID)").assertIsDisplayed()
        rule.runOnUiThread { app.dialogs.clear() }
        rule.waitForIdle()
        assertEquals(Loc.Cloud(cz.k4.salamanderdroid.cloud.CloudAccount("x", cz.k4.salamanderdroid.cloud.CloudKind.OneDrive, "Práce", "id"), "/a").display(), "onedrive://Práce/a")

        // Menus, places, settings, properties, find, servers and help all compose.
        key(KeyEvent.KEYCODE_DEL)
        waitFor { app.active.loc is Loc.Local }
        rule.onAllNodesWithText("Zobrazit")[0].performClick()
        rule.onNodeWithText("Řadit podle velikosti", substring = true).performClick()
        rule.waitForIdle()
        assertEquals(cz.k4.salamanderdroid.core.SortKey.Size, app.active.sortKey)
        assertTrue(!app.menuOpen)
        rule.waitForIdle()
        for ((code, meta, type) in listOf(
            Triple(KeyEvent.KEYCODE_F1, KeyEvent.META_ALT_ON, Dlg.Places::class),
            Triple(KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON, Dlg.Settings::class),
            Triple(KeyEvent.KEYCODE_ENTER, KeyEvent.META_ALT_ON, Dlg.Properties::class),
            Triple(KeyEvent.KEYCODE_F2, KeyEvent.META_CTRL_ON, Dlg.Attributes::class),
            Triple(KeyEvent.KEYCODE_F7, KeyEvent.META_ALT_ON, Dlg.Find::class),
            Triple(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON, Dlg.ServerManager::class),
            Triple(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, Dlg.CloudAccounts::class),
            Triple(KeyEvent.KEYCODE_F1, 0, Dlg.Message::class),
        )) {
            key(code, meta)
            assertTrue("dialog $type", app.dialogs.singleOrNull()?.let { type.isInstance(it) } == true)
            rule.waitForIdle()
            rule.runOnUiThread { app.dialogs.clear() }
            rule.waitForIdle()
        }
    }
}

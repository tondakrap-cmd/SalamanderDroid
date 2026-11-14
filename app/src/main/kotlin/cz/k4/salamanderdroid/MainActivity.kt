package cz.k4.salamanderdroid

import android.content.Intent
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import cz.k4.salamanderdroid.ui.AppState
import cz.k4.salamanderdroid.ui.DialogHost
import cz.k4.salamanderdroid.ui.MainScreen
import cz.k4.salamanderdroid.ui.handleKey

class MainActivity : ComponentActivity() {
    private val app: AppState by viewModels()
    private val handledKeys = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.onExit = { finish() }
        app.onNewWindow = {
            // A second, independent window (side by side where the system allows it).
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                )
            )
        }
        if (savedInstanceState == null) handleShared(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (app.hasStorageAccess) MainScreen(app) else PermissionScreen()
                }
                DialogHost(app)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun PermissionScreen() {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Salamander Droid", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                "Správce souborů potřebuje oprávnění „Přístup ke všem souborům“, aby mohl procházet a spravovat " +
                    "úložiště tabletu, paměťové karty a USB disky. Data nikam neodesílá.",
                modifier = Modifier.widthIn(max = 560.dp),
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { requestStorageAccess() }) { Text("Otevřít nastavení oprávnění") }
        }
    }

    private fun requestStorageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /** Files sent from another app ("Share") are offered for saving into the active panel. */
    private fun handleShared(intent: Intent?) {
        val uris: List<android.net.Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) pendingShared = uris
    }

    private var pendingShared: List<android.net.Uri>? = null

    override fun onResume() {
        super.onResume()
        app.checkStorageAccess()
        if (app.hasStorageAccess) {
            pendingShared?.let { app.receiveUris(it) }
            pendingShared = null
        }
    }

    override fun onPause() {
        app.savePanelDirs()
        super.onPause()
    }

    /**
     * The file panels are driven from here rather than through Compose focus, so the keyboard
     * always works in the main window. Dialogs and menus are separate windows with their own
     * key handling; while the path field is edited, keys go to it.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ours = app.hasStorageAccess && app.dialogs.isEmpty() && app.progress == null &&
            !app.pathEditing && !app.menuOpen && !app.popupOpen && !event.isSystem && !event.isMetaPressed
        if (ours) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (handleKey(app, event)) {
                    handledKeys += event.keyCode
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP && handledKeys.remove(event.keyCode)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

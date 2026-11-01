package com.musicly.musicly

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.musicly.musicly.data.ThemeChoice
import com.musicly.musicly.ui.Env
import com.musicly.musicly.ui.LocalEnv
import com.musicly.musicly.ui.MusiclyTheme
import com.musicly.musicly.ui.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
class MainActivity : ComponentActivity() {
    private var permissionResult: CompletableDeferred<Boolean>? = null
    private var audioResult: CompletableDeferred<List<Pair<String, String>>>? = null
    private var backupResult: CompletableDeferred<String?>? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionResult?.complete(grants.isNotEmpty() && grants.values.all { it })
        permissionResult = null
    }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val out = uris.map { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            uri.toString() to displayName(uri)
        }
        audioResult?.complete(out)
        audioResult = null
    }
    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val text = uri?.let { runCatching { contentResolver.openInputStream(it)?.use { s -> s.readBytes().decodeToString() } }.getOrNull() }
        backupResult?.complete(text)
        backupResult = null
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment ?: "Song"
    }

    private suspend fun ask(perms: Array<String>): Boolean {
        val d = CompletableDeferred<Boolean>()
        permissionResult?.complete(false)
        permissionResult = d
        permissions.launch(perms)
        return d.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MusiclyApp
        val env = Env(app)
        val lib = app.library
        lib.requestMusicPermission = {
            if (app.device.hasPermission()) true else ask(app.device.neededPermissions)
        }
        app.player.requestNotifications = {
            app.device.notificationPermission?.let { p ->
                if (!app.device.hasNotificationPermission()) {
                    // Asked once, without waiting for the answer.
                    permissions.launch(arrayOf(p))
                }
            }
        }
        env.pickAudioFiles = {
            val d = CompletableDeferred<List<Pair<String, String>>>()
            audioResult = d
            pickAudio.launch(arrayOf("audio/*"))
            d.await()
        }
        env.pickBackupFile = {
            val d = CompletableDeferred<String?>()
            backupResult = d
            pickBackup.launch(arrayOf("*/*"))
            withContext(Dispatchers.Main) { d.await() }
        }
        env.openUrl = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        env.shareText = { text ->
            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }
        }
        env.copyText = { text ->
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Musicly", text))
        }
        // Pending library changes are backed up when the app leaves the screen.
        lifecycle.addObserver(LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) lib.flushBackup() })

        setContent {
            CompositionLocalProvider(LocalEnv provides env) {
                MusiclyTheme(dark = lib.observe().theme == ThemeChoice.dark) {
                    BackHandler(enabled = env.sheet != null || env.dialog != null || env.nowPlayingOpen || env.nav.stack.isNotEmpty()) {
                        when {
                            env.sheet != null -> env.closeSheet()
                            env.dialog != null -> env.dialog = null
                            env.nowPlayingOpen -> env.nowPlayingOpen = false
                            else -> env.nav.pop()
                        }
                    }
                    Shell()
                }
            }
        }
    }
}

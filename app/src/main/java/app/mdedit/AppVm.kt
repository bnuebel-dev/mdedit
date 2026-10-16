package app.mdedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, BROWSE, EDIT, LOGIN }

sealed interface Loc {
    val title: String

    data class LocalDir(val dir: DocumentFile) : Loc {
        override val title: String get() = dir.name ?: "Ordner"
    }

    data class CloudDir(val id: Long, override val title: String) : Loc
}

sealed interface Doc {
    val name: String

    data class Local(val uri: Uri, override val name: String) : Doc
    data class Cloud(val id: Long, val parentId: Long, override val name: String) : Doc
}

data class Entry(val name: String, val dir: Loc?, val doc: Doc?)

class AppVm(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()
    val prefs = Prefs(app)

    var screen by mutableStateOf(Screen.HOME)
        private set
    var trees by mutableStateOf(prefs.treeUris)
        private set
    var loggedIn by mutableStateOf(prefs.pcloudToken != null)
        private set
    var stack by mutableStateOf<List<Loc>>(emptyList())
        private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var doc by mutableStateOf<Doc?>(null)
        private set
    var text by mutableStateOf("")
    var savedText by mutableStateOf("")
        private set
    var preview by mutableStateOf(false)
    var showNew by mutableStateOf(false)
    var confirmDiscard by mutableStateOf(false)

    // Anmeldedaten im ViewModel: bleiben beim Wechsel in die Mail-App (2FA-Code) und bei Drehung erhalten.
    // Nur im Arbeitsspeicher, nie auf dem Datentraeger. Passwort und Code werden nach Erfolg geleert.
    var loginUser by mutableStateOf("")
    var loginPass by mutableStateOf("")
    var loginTfa by mutableStateOf("")
    var loginHost by mutableStateOf(prefs.pcloudHost)
    var loginNeedsCode by mutableStateOf(false)

    val dirty: Boolean get() = doc != null && text != savedText

    private var inflight = 0

    private fun <T> launchIO(block: () -> T, onOk: (T) -> Unit = {}) {
        viewModelScope.launch {
            inflight++
            busy = true
            try {
                val r = withContext(Dispatchers.IO) { block() }
                onOk(r)
            } catch (e: Exception) {
                message = e.message ?: e.toString()
            } finally {
                inflight--
                busy = inflight > 0
            }
        }
    }

    private fun cloud(): PCloud {
        val token = prefs.pcloudToken ?: throw PCloudException("Nicht bei pCloud angemeldet")
        return PCloud(prefs.pcloudHost, token)
    }

    private fun isMd(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".txt")
    }

    // ---------- Navigation ----------

    fun showLogin() {
        screen = Screen.LOGIN
    }

    fun onBack() {
        when (screen) {
            Screen.EDIT -> if (dirty) confirmDiscard = true else closeDoc()
            Screen.BROWSE -> back()
            Screen.LOGIN -> screen = Screen.HOME
            Screen.HOME -> {}
        }
    }

    fun discardAndClose() {
        confirmDiscard = false
        closeDoc()
    }

    private fun closeDoc() {
        doc = null
        text = ""
        savedText = ""
        screen = if (stack.isEmpty()) Screen.HOME else Screen.BROWSE
    }

    fun openRoot(loc: Loc) {
        stack = listOf(loc)
        screen = Screen.BROWSE
        refresh()
    }

    fun openDir(loc: Loc) {
        stack = stack + loc
        refresh()
    }

    private fun back() {
        if (stack.size > 1) {
            stack = stack.dropLast(1)
            refresh()
        } else {
            stack = emptyList()
            entries = emptyList()
            screen = Screen.HOME
        }
    }

    fun refresh() {
        val loc = stack.lastOrNull() ?: return
        entries = emptyList()
        launchIO({ listLoc(loc) }) { entries = it }
    }

    private fun listLoc(loc: Loc): List<Entry> {
        val list: List<Entry> = when (loc) {
            is Loc.LocalDir -> loc.dir.listFiles().mapNotNull { f ->
                val n = f.name
                if (n == null) null
                else if (f.isDirectory) Entry(n, Loc.LocalDir(f), null)
                else if (isMd(n)) Entry(n, null, Doc.Local(f.uri, n))
                else null
            }

            is Loc.CloudDir -> cloud().list(loc.id).mapNotNull { c ->
                if (c.isDir) Entry(c.name, Loc.CloudDir(c.id, c.name), null)
                else if (isMd(c.name)) Entry(c.name, null, Doc.Cloud(c.id, loc.id, c.name))
                else null
            }
        }
        return list.sortedWith(compareByDescending<Entry> { it.dir != null }.thenBy { it.name.lowercase() })
    }

    // ---------- Dokumente ----------

    fun openDoc(d: Doc) {
        launchIO({ readDoc(d) }) { t ->
            doc = d
            text = t
            savedText = t
            preview = false
            screen = Screen.EDIT
        }
    }

    private fun readDoc(d: Doc): String = when (d) {
        is Doc.Local -> ctx.contentResolver.openInputStream(d.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Datei nicht lesbar")

        is Doc.Cloud -> cloud().download(d.id)
    }

    fun save() {
        val d = doc ?: return
        val t = text
        launchIO({ writeDoc(d, t) }) {
            savedText = t
            message = "Gespeichert"
        }
    }

    private fun writeDoc(d: Doc, t: String) {
        when (d) {
            is Doc.Local -> {
                val out = ctx.contentResolver.openOutputStream(d.uri, "wt")
                    ?: throw IllegalStateException("Datei nicht beschreibbar")
                out.use { it.write(t.toByteArray(Charsets.UTF_8)) }
            }

            is Doc.Cloud -> {
                cloud().upload(d.parentId, d.name, t)
            }
        }
    }

    fun createFile(raw: String) {
        val loc = stack.lastOrNull() ?: return
        val name = if (raw.contains('.')) raw else "$raw.md"
        showNew = false
        launchIO<Doc>({
            when (loc) {
                is Loc.LocalDir -> {
                    val f = loc.dir.createFile("application/octet-stream", name)
                        ?: throw IllegalStateException("Anlegen fehlgeschlagen")
                    Doc.Local(f.uri, f.name ?: name)
                }

                is Loc.CloudDir -> {
                    val id = cloud().upload(loc.id, name, "")
                    Doc.Cloud(id, loc.id, name)
                }
            }
        }) { d ->
            doc = d
            text = ""
            savedText = ""
            preview = false
            screen = Screen.EDIT
            refresh()
        }
    }

    /** Datei von aussen: Einzeldatei-Auswahl oder "Oeffnen mit" aus dem Dateimanager. */
    fun openExternal(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // nicht jede Quelle erlaubt dauerhafte Rechte - fuer diese Sitzung reicht es
        }
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "Datei.md"
        openDoc(Doc.Local(uri, name))
    }

    // ---------- Quellen ----------

    fun addTree(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            message = "Dauerhafter Zugriff nicht moeglich: ${e.message}"
            return
        }
        prefs.addTree(uri.toString())
        trees = prefs.treeUris
    }

    fun removeTree(s: String) {
        try {
            ctx.contentResolver.releasePersistableUriPermission(
                Uri.parse(s),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // schon freigegeben
        }
        prefs.removeTree(s)
        trees = prefs.treeUris
    }

    fun openTree(s: String) {
        val d = DocumentFile.fromTreeUri(ctx, Uri.parse(s))
        if (d == null || !d.exists()) {
            message = "Ordner nicht mehr verfuegbar"
        } else {
            openRoot(Loc.LocalDir(d))
        }
    }

    fun openCloud() {
        openRoot(Loc.CloudDir(0L, "pCloud"))
    }

    fun login() {
        if (!prefs.secureAvailable) {
            message = "Sicherer Speicher (Android Keystore) nicht verfuegbar - Anmeldung nicht moeglich"
            return
        }
        val user = loginUser.trim()
        val pass = loginPass
        val tfa = loginTfa
        val host = loginHost
        launchIO({
            try {
                PCloud.login(host, user, pass, tfa)
            } catch (e: PCloudException) {
                if (e.code == 1022) loginNeedsCode = true
                throw e
            }
        }) { token ->
            prefs.pcloudHost = host
            prefs.pcloudToken = token
            loggedIn = true
            loginPass = ""
            loginTfa = ""
            loginNeedsCode = false
            screen = Screen.HOME
            message = "Bei pCloud angemeldet"
        }
    }

    fun logout() {
        val token = prefs.pcloudToken
        val host = prefs.pcloudHost
        prefs.pcloudToken = null
        loggedIn = false
        if (token != null) {
            // Token auch bei pCloud ungueltig machen; schlaegt das fehl (offline), ist es lokal trotzdem weg.
            launchIO({
                try {
                    PCloud(host, token).logout()
                } catch (e: Exception) {
                    // ignorieren
                }
            })
        }
    }
}

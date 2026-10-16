@file:OptIn(ExperimentalMaterial3Api::class)

package app.mdedit

import android.net.Uri
import android.os.Build
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

@Composable
fun MdEditTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun MdEditApp(vm: AppVm, pickFolder: () -> Unit, pickFile: () -> Unit) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snack.showSnackbar(m)
            vm.message = null
        }
    }
    BackHandler(enabled = vm.screen != Screen.HOME) { vm.onBack() }

    val title = when (vm.screen) {
        Screen.HOME -> "MdEdit"
        Screen.BROWSE -> vm.stack.lastOrNull()?.title ?: "MdEdit"
        Screen.EDIT -> (vm.doc?.name ?: "") + if (vm.dirty) " •" else ""
        Screen.LOGIN -> "pCloud-Anmeldung"
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (vm.screen != Screen.HOME) {
                        TextButton(onClick = { vm.onBack() }) { Text("←") }
                    }
                },
                actions = {
                    when (vm.screen) {
                        Screen.BROWSE -> {
                            TextButton(onClick = { vm.showNew = true }) { Text("Neu") }
                        }

                        Screen.EDIT -> {
                            TextButton(onClick = { vm.preview = !vm.preview }) {
                                Text(if (vm.preview) "Bearbeiten" else "Vorschau")
                            }
                            TextButton(onClick = { vm.save() }, enabled = vm.dirty && !vm.busy) {
                                Text("Speichern")
                            }
                        }

                        else -> {}
                    }
                }
            )
        }
    ) { pad ->
        // Edge-to-edge (Android 15+): Systemleisten via Scaffold, Tastatur ueber imePadding.
        val body = Modifier
            .padding(pad)
            .consumeWindowInsets(pad)
            .imePadding()
        when (vm.screen) {
            Screen.HOME -> Home(vm, pickFolder, pickFile, body)
            Screen.BROWSE -> Browse(vm, body)
            Screen.EDIT -> Editor(vm, body)
            Screen.LOGIN -> Login(vm, body)
        }
    }

    if (vm.showNew) {
        NewFileDialog(onDismiss = { vm.showNew = false }, onCreate = { vm.createFile(it) })
    }
    if (vm.confirmDiscard) {
        AlertDialog(
            onDismissRequest = { vm.confirmDiscard = false },
            title = { Text("Ungespeicherte Änderungen") },
            text = { Text("Die Änderungen gehen verloren, wenn du jetzt schließt.") },
            confirmButton = { TextButton(onClick = { vm.discardAndClose() }) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { vm.confirmDiscard = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun Home(vm: AppVm, pickFolder: () -> Unit, pickFile: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text("Quellen", style = MaterialTheme.typography.titleMedium) }
        item {
            if (vm.loggedIn) {
                SourceCard(
                    title = "pCloud",
                    subtitle = vm.prefs.pcloudHost,
                    actionLabel = "Abmelden",
                    onOpen = { vm.openCloud() },
                    onAction = { vm.logout() }
                )
            } else {
                OutlinedButton(onClick = { vm.showLogin() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Bei pCloud anmelden")
                }
            }
        }
        items(vm.trees) { t ->
            val name = remember(t) { DocumentFile.fromTreeUri(ctx, Uri.parse(t))?.name ?: "Ordner" }
            SourceCard(
                title = name,
                subtitle = "Lokaler Ordner",
                actionLabel = "Entfernen",
                onOpen = { vm.openTree(t) },
                onAction = { vm.removeTree(t) }
            )
        }
        item {
            Button(onClick = pickFolder, modifier = Modifier.fillMaxWidth()) { Text("Lokalen Ordner hinzufügen") }
        }
        item {
            OutlinedButton(onClick = pickFile, modifier = Modifier.fillMaxWidth()) { Text("Einzelne Datei öffnen") }
        }
    }
}

@Composable
private fun SourceCard(
    title: String,
    subtitle: String,
    actionLabel: String,
    onOpen: () -> Unit,
    onAction: () -> Unit
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun Browse(vm: AppVm, modifier: Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            vm.stack.joinToString(" / ") { it.title },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (vm.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (!vm.busy && vm.entries.isEmpty()) {
            Text(
                "Keine Markdown-Dateien oder Ordner hier.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(vm.entries) { e ->
                ListItem(
                    headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Text(if (e.dir != null) "📁" else "📄") },
                    modifier = Modifier.clickable {
                        val d = e.dir
                        val f = e.doc
                        if (d != null) vm.openDir(d) else if (f != null) vm.openDoc(f)
                    }
                )
            }
        }
    }
}

@Composable
private fun Editor(vm: AppVm, modifier: Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        if (vm.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (vm.preview) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                MarkdownView(vm.text)
            }
        } else {
            OutlinedTextField(
                value = vm.text,
                onValueChange = { vm.text = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            )
        }
    }
}

@Composable
private fun MarkdownView(text: String) {
    val ctx = LocalContext.current
    val markwon = remember {
        Markwon.builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(ctx))
            .usePlugin(TaskListPlugin.create(ctx))
            .build()
    }
    val color = MaterialTheme.colorScheme.onBackground.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { c ->
            TextView(c).apply {
                textSize = 16f
                setTextIsSelectable(true)
            }
        },
        update = { tv ->
            tv.setTextColor(color)
            markwon.setMarkdown(tv, text)
        }
    )
}

@Composable
private fun Login(vm: AppVm, modifier: Modifier) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var tfa by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(vm.prefs.pcloudHost) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Zugangsdaten gehen ausschließlich per HTTPS an pCloud. Gespeichert wird nur ein verschlüsseltes Token, nie das Passwort.",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = host == Prefs.HOST_EU, onClick = { host = Prefs.HOST_EU }, label = { Text("EU-Region") })
            FilterChip(selected = host == Prefs.HOST_US, onClick = { host = Prefs.HOST_US }, label = { Text("US-Region") })
        }
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("E-Mail") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Passwort") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = tfa,
            onValueChange = { tfa = it },
            label = { Text("2FA-Code (falls aktiv)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (vm.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { vm.login(user.trim(), pass, tfa, host) },
            enabled = user.isNotBlank() && pass.isNotEmpty() && !vm.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Anmelden") }
    }
}

@Composable
private fun NewFileDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neue Datei") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Dateiname (.md)") }
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }) { Text("Anlegen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

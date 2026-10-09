package org.kaze.camkaze.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kaze.camkaze.AppState
import org.kaze.camkaze.Route
import org.kaze.camkaze.Services
import org.kaze.camkaze.crypto.Vault
import org.kaze.camkaze.data.MediaRepo
import org.kaze.camkaze.data.SecureItem
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ create / unlock
@Composable
fun LockScreen(state: AppState) {
    val vault = Services.vault
    val setup = remember { !vault.isConfigured }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        if (setup) {
            if (pw.length < Vault.MIN_PASSWORD_LEN) { error = "Use at least ${Vault.MIN_PASSWORD_LEN} characters"; return }
            if (pw != pw2) { error = "Passwords do not match"; return }
        } else if (pw.isEmpty()) return
        val chars = pw.toCharArray(); pw = ""; pw2 = ""; busy = true; error = null
        scope.launch {
            val ok = withContext(Dispatchers.Default) {
                try { if (setup) { vault.create(chars); true } else vault.unlock(chars) }
                catch (e: Exception) { false } finally { chars.fill('\u0000') }
            }
            busy = false
            if (ok) state.go(Route.SecureGallery) else error = if (setup) "Could not create vault" else "Wrong password"
        }
    }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, null, tint = SecureAccent, modifier = Modifier.size(48.dp))
        Text(if (setup) "Create Secure Password" else "Secure Vault",
            style = MaterialTheme.typography.headlineSmall, color = SecureAccent)
        Text(
            if (setup) "Minimum ${Vault.MIN_PASSWORD_LEN} characters. There is NO way to recover it: if you forget it, secured photos are lost."
            else "Enter your password to unlock.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PasswordField(pw, { pw = it }, "Password", enabled = !busy)
        if (setup) PasswordField(pw2, { pw2 = it }, "Confirm password", enabled = !busy)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = ::submit, enabled = !busy) {
            Text(if (busy) "Working..." else if (setup) "Create vault" else "Unlock")
        }
    }
}

// ------------------------------------------------------------------ secure gallery
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureGalleryScreen(state: AppState) {
    var items by remember { mutableStateOf<List<SecureItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<SecureItem?>(null) }
    var deleteFor by remember { mutableStateOf<SecureItem?>(null) }
    var detailsFor by remember { mutableStateOf<SecureItem?>(null) }

    LaunchedEffect(state.secureVersion) {
        items = withContext(Dispatchers.IO) { try { Services.secure.list() } catch (e: Exception) { emptyList() } }
        loaded = true
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Secure Vault", color = SecureAccent) },
            actions = {
                IconButton(onClick = { state.go(Route.SecureCamera) }) {
                    Icon(Icons.Filled.CameraAlt, "Secure camera", tint = SecureAccent)
                }
                IconButton(onClick = { state.lockNow() }) { Icon(Icons.Filled.Lock, "Lock now", tint = SecureAccent) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        )
        Box(Modifier.fillMaxSize()) {
            PhotoGrid(
                items = items, takenMs = { it.takenMs }, itemKey = { it.id },
                load = { Services.secure.thumb(it) },          // decrypted in RAM only
                onClick = { state.go(Route.SecureViewer(it)) },
                onLongClick = { sheetFor = it },
                placeholder = MaterialTheme.colorScheme.surfaceVariant,
            )
            if (loaded && items.isEmpty()) {
                Text("Vault is empty", Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    sheetFor?.let { item ->
        ActionSheet(
            actions = listOf(
                SheetAction("Delete", Icons.Filled.Delete) { deleteFor = item },
                SheetAction("Details", Icons.Filled.Info) { detailsFor = item },
            ),
            onDismiss = { sheetFor = null },
        )
    }
    deleteFor?.let { item ->
        ConfirmDialog("Delete secure photo?", "This encrypted photo will be permanently deleted.",
            onConfirm = { Services.secure.delete(item); state.secureVersion++; state.toast("Deleted") },
            onDismiss = { deleteFor = null })
    }
    detailsFor?.let { item ->
        InfoDialog("Details", secureDetails(item)) { detailsFor = null }
    }
}

private fun secureDetails(i: SecureItem): String =
    "ID: ${i.id.take(8)}...\nSecured: ${DateFormat.getDateTimeInstance().format(Date(i.takenMs))}\n" +
        "Encrypted size: ${MediaRepo.fmtSize(i.size)}\nCipher: AES-256-GCM (.kaze v1)"

// ------------------------------------------------------------------ secure viewer
@Composable
fun SecureViewerScreen(item: SecureItem, state: AppState) {
    var bmp by remember(item.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        // Full image is decrypted only now, only in RAM, downscaled to 2048 px.
        bmp = withContext(Dispatchers.IO) { try { Services.secure.full(item, 2048) } catch (e: Exception) { null } }
    }
    DisposableEffect(bmp) { onDispose { bmp?.recycle() } }

    ViewerChrome("Secure photo", bmp, onBack = { state.back() }) {
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete", tint = SecureAccent) }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete secure photo?", "This encrypted photo will be permanently deleted.",
            onConfirm = { Services.secure.delete(item); state.secureVersion++; state.toast("Deleted"); state.back() },
            onDismiss = { confirmDelete = false })
    }
}

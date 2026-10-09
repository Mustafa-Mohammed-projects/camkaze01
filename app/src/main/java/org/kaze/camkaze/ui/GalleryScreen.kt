package org.kaze.camkaze.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kaze.camkaze.AppState
import org.kaze.camkaze.Route
import org.kaze.camkaze.Services
import org.kaze.camkaze.data.Images
import org.kaze.camkaze.data.MediaRepo
import org.kaze.camkaze.data.Photo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<Photo?>(null) }
    var deleteFor by remember { mutableStateOf<Photo?>(null) }
    var detailsFor by remember { mutableStateOf<Photo?>(null) }

    LaunchedEffect(state.galleryVersion) {
        photos = withContext(Dispatchers.IO) { try { MediaRepo.list(ctx) } catch (e: Exception) { emptyList() } }
        loaded = true
    }

    // Fallback when the photo is not owned by this app (e.g. after reinstall): system prompt.
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        state.galleryVersion++
    }

    fun deleteOriginal(p: Photo) {
        try {
            if (MediaRepo.delete(ctx, p)) state.galleryVersion++
        } catch (e: SecurityException) {
            val pi = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(p.uri))
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            state.toast("Could not delete: ${e.message}")
        }
    }

    /** read -> encrypt in RAM -> write .kaze -> delete original */
    fun moveToSecure(p: Photo) {
        state.toast("Securing photo...")
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                var bytes: ByteArray? = null
                try {
                    bytes = MediaRepo.readBytes(ctx, p)
                    Services.secure.save(bytes, Images.exifRotation(bytes), p.takenMs)
                    null
                } catch (e: Exception) {
                    e.message ?: "unknown error"
                } finally {
                    bytes?.fill(0)
                }
            }
            if (error != null) {
                state.toast("Could not secure photo: $error")
            } else {
                state.secureVersion++
                deleteOriginal(p)
                state.toast("Moved to Secure")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Gallery") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Box(Modifier.fillMaxSize()) {
            PhotoGrid(
                items = photos, takenMs = { it.takenMs }, itemKey = { it.id },
                load = { MediaRepo.thumb(ctx, it) },
                onClick = { state.go(Route.Viewer(it)) },
                onLongClick = { sheetFor = it },
                placeholder = MaterialTheme.colorScheme.surfaceVariant,
            )
            if (loaded && photos.isEmpty()) {
                Text("No photos yet", Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    sheetFor?.let { p ->
        ActionSheet(
            actions = listOf(
                SheetAction("Share", Icons.Filled.Share) {
                    try { MediaRepo.share(ctx, p) } catch (e: Exception) { state.toast("Share failed") }
                },
                SheetAction("Move to Secure", Icons.Filled.Lock) { state.requireUnlocked { moveToSecure(p) } },
                SheetAction("Delete", Icons.Filled.Delete) { deleteFor = p },
                SheetAction("Details", Icons.Filled.Info) { detailsFor = p },
            ),
            onDismiss = { sheetFor = null },
        )
    }
    deleteFor?.let { p ->
        ConfirmDialog("Delete photo?", "This photo will be permanently deleted.",
            onConfirm = { deleteOriginal(p) }, onDismiss = { deleteFor = null })
    }
    detailsFor?.let { p ->
        InfoDialog("Details", MediaRepo.details(p)) { detailsFor = null }
    }
}

@Composable
fun ViewerScreen(photo: Photo, state: AppState) {
    val ctx = LocalContext.current
    var bmp by remember(photo.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(photo.id) {
        bmp = withContext(Dispatchers.IO) {
            try { MediaRepo.loadFull(ctx, photo, 2048) } catch (e: Exception) { null }
        }
    }
    ViewerChrome(photo.name, bmp, onBack = { state.back() }) {
        IconButton(onClick = { try { MediaRepo.share(ctx, photo) } catch (e: Exception) { state.toast("Share failed") } }) {
            Icon(Icons.Filled.Share, "Share")
        }
    }
}

package org.kaze.camkaze.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.kaze.camkaze.AppState
import org.kaze.camkaze.Route
import org.kaze.camkaze.Services

@Composable
fun CamKazeApp(state: AppState) {
    val route = state.route
    KazeTheme(secure = route.isSecure) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(state.snackbar) },
            bottomBar = { if (route.showNav) KazeNavBar(state) },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (route) {
                    Route.Camera -> CameraScreen(state, secure = false)
                    Route.Gallery -> GalleryScreen(state)
                    is Route.Viewer -> ViewerScreen(route.photo, state)
                    Route.Lock -> LockScreen(state)
                    Route.SecureGallery -> SecureGalleryScreen(state)
                    Route.SecureCamera -> CameraScreen(state, secure = true)
                    is Route.SecureViewer -> SecureViewerScreen(route.item, state)
                }
            }
        }
        state.unlockAction?.let { UnlockDialog(state, it) }
        BackHandler(enabled = route != Route.Camera) { state.back() }
    }
}

@Composable
private fun KazeNavBar(state: AppState) {
    val selected = when (state.route) {
        Route.Camera -> 0
        Route.Gallery -> 1
        else -> 2
    }
    NavigationBar {
        NavigationBarItem(selected == 0, { state.go(Route.Camera) },
            { Icon(Icons.Filled.CameraAlt, null) }, label = { Text("Camera") })
        NavigationBarItem(selected == 1, { state.go(Route.Gallery) },
            { Icon(Icons.Filled.PhotoLibrary, null) }, label = { Text("Gallery") })
        NavigationBarItem(selected == 2,
            { state.go(if (Services.vault.isUnlocked) Route.SecureGallery else Route.Lock) },
            { Icon(Icons.Filled.Security, null) }, label = { Text("Secure") })
    }
}

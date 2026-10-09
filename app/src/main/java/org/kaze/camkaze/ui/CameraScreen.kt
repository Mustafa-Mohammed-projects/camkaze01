package org.kaze.camkaze.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.kaze.camkaze.AppState
import org.kaze.camkaze.Route
import org.kaze.camkaze.Services
import org.kaze.camkaze.data.MediaRepo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One camera screen, two modes.
 *  normal: CameraX saves straight into MediaStore (Pictures/cam_kaze).
 *  secure: CameraX hands us the JPEG as an in-memory ImageProxy; we encrypt it and write
 *          only ciphertext. No plaintext image file (or MediaStore row) is ever created.
 */
@Composable
fun CameraScreen(state: AppState, secure: Boolean) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val accent = if (secure) SecureAccent else NormalAccent

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it[Manifest.permission.CAMERA] == true
    }
    LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES))
    }

    val worker = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { worker.shutdown() } }

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    var backCamera by remember { mutableStateOf(true) }
    var flash by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(flash, backCamera) {
        imageCapture.flashMode = if (flash && backCamera) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    DisposableEffect(granted, backCamera, owner) {
        var provider: ProcessCameraProvider? = null
        if (granted) {
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val selector = if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                try {
                    provider?.unbindAll()
                    provider?.bindToLifecycle(owner, selector, preview, imageCapture)
                } catch (e: Exception) {
                    state.toast("Camera error: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))
        }
        onDispose { provider?.unbindAll() }
    }

    fun capture() {
        if (busy) return
        busy = true
        if (secure) {
            imageCapture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    var bytes: ByteArray? = null
                    try {
                        val rotation = image.imageInfo.rotationDegrees
                        val buf = image.planes[0].buffer
                        bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        Services.secure.save(bytes, rotation, System.currentTimeMillis())
                        state.post { state.secureVersion++; state.toast("Saved securely"); busy = false }
                    } catch (e: Exception) {
                        state.post { state.toast("Secure save failed: ${e.message}"); busy = false }
                    } finally {
                        bytes?.fill(0)
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    state.post { state.toast("Capture failed"); busy = false }
                }
            })
        } else {
            val name = "CamKaze_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, MediaRepo.REL_PATH)
            }
            val options = ImageCapture.OutputFileOptions.Builder(
                ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
            imageCapture.takePicture(options, ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        state.galleryVersion++; state.toast("Photo saved"); busy = false
                    }

                    override fun onError(exception: ImageCaptureException) {
                        state.toast("Capture failed: ${exception.message}"); busy = false
                    }
                })
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is required", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES)) }) {
                    Text("Grant permission")
                }
            }
        }

        if (secure) {
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x992A1512)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.go(Route.SecureGallery) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = SecureAccent)
                }
                Text("SECURE MODE  -  encrypted in RAM, no plaintext on disk",
                    style = MaterialTheme.typography.labelMedium, color = SecureAccent)
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { flash = !flash }, enabled = backCamera) {
                Icon(if (flash) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Flash", tint = Color.White)
            }
            Box(
                Modifier.size(76.dp).clip(CircleShape).background(if (busy) Color.Gray else accent)
                    .border(4.dp, Color.White, CircleShape)
                    .clickable(enabled = granted && !busy) { capture() },
            )
            IconButton(onClick = { backCamera = !backCamera }) {
                Icon(Icons.Filled.FlipCameraAndroid, "Switch camera", tint = Color.White)
            }
        }
    }
}

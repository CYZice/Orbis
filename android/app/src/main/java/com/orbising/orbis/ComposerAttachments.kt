package com.orbising.orbis

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

@Composable
internal fun ComposerAddMenu(
    attachmentEnabled: Boolean,
    conversationKey: String,
    onPick: (List<Uri>) -> Unit,
    onError: (String) -> Unit,
    onSkills: (() -> Unit)?,
    onCommands: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(conversationKey) { mutableStateOf(false) }
    var captureUri by rememberSaveable(conversationKey) { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = captureUri
        captureUri = null
        if (saved && uri != null) onPick(listOf(uri))
    }
    fun openCamera() {
        try {
            val dir = File(context.cacheDir, "composer-camera").apply { mkdirs() }
            val file = File.createTempFile("photo-", ".jpg", dir)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.composer-camera", file)
            captureUri = uri
            takePicture.launch(uri)
        } catch (_: Exception) {
            captureUri = null
            onError(L10n.tr("无法打开相机"))
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else onError(L10n.tr("允许相机权限后才能拍照"))
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS_PER_MESSAGE), onPick)
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), onPick)
    Box(modifier) {
        NeumorphIconButton(onClick = { expanded = true }, icon = OrbisSymbols.Add,
            contentDescription = L10n.tr("添加附件或技能"))
        NeumorphMenu(expanded, { expanded = false }, preferAbove = true) {
            NeumorphMenuItem(text = { Text(L10n.tr("拍照")) }, enabled = attachmentEnabled, onClick = {
                expanded = false
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            })
            NeumorphMenuItem(text = { Text(L10n.tr("照片图库")) }, enabled = attachmentEnabled, onClick = {
                expanded = false
                try { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                catch (_: ActivityNotFoundException) { onError(L10n.tr("无法打开照片图库")) }
            })
            NeumorphMenuItem(text = { Text(L10n.tr("浏览文件")) }, enabled = attachmentEnabled, onClick = {
                expanded = false
                try { documents.launch(arrayOf("*/*")) }
                catch (_: ActivityNotFoundException) { onError(L10n.tr("无法打开文件选择器")) }
            })
            onCommands?.let { open ->
                NeumorphMenuItem(text = { Text(L10n.tr("/ 命令")) }, onClick = { expanded = false; open() })
            }
            onSkills?.let { open ->
                NeumorphMenuItem(text = { Text(L10n.tr("$ 技能")) }, onClick = { expanded = false; open() })
            }
        }
    }
}

class ComposerCameraProvider : FileProvider()

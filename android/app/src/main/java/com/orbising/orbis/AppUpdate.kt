package com.orbising.orbis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AppUpdateInfo(
    val version: String,
    val versionCode: Int,
    val apkUrl: String,
    val sha256: String,
)

internal data class AppUpdateProgress(val receivedBytes: Long, val totalBytes: Long?)

private const val RELEASES_URL = "https://api.github.com/repos/CYZice/Orbis/releases/latest"
private const val APK_NAME = "orbis.apk"
private const val META_NAME = "orbis.apk.version.json"

private val updateHttp = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

internal fun parseReleaseMetadata(releaseJson: String, metadataJson: String): AppUpdateInfo? = runCatching {
    val root = Json.parseToJsonElement(releaseJson).jsonObject
    if (root["draft"]?.jsonPrimitive?.booleanOrNull == true || root["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
    val assets = root["assets"]?.jsonArray ?: return null
    val assetByName = assets.mapNotNull { value ->
        val asset = value.jsonObject
        val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        name to asset
    }.toMap()
    val metaAsset = assetByName[META_NAME] ?: return null
    val apk = assetByName[APK_NAME] ?: return null
    val apkUrl = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
    val meta = Json.parseToJsonElement(metadataJson).jsonObject
    val version = meta["version"]?.jsonPrimitive?.contentOrNull ?: root["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: return null
    val versionCode = meta["versionCode"]?.jsonPrimitive?.intOrNull ?: return null
    val sha256 = meta["sha256"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
    if (metaAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() || !Regex("^[a-f0-9]{64}$").matches(sha256) || versionCode <= 0) return null
    AppUpdateInfo(version, versionCode, apkUrl, sha256)
}.getOrNull()

internal fun parseLatestRelease(json: String): AppUpdateInfo? = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    if (root["draft"]?.jsonPrimitive?.booleanOrNull == true || root["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
    val assets = root["assets"]?.jsonArray ?: return null
    val assetByName = assets.mapNotNull { value ->
        val asset = value.jsonObject
        val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        name to asset
    }.toMap()
    val metaAsset = assetByName[META_NAME] ?: return null
    val metaUrl = metaAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
    val metaRequest = Request.Builder().url(metaUrl).header("Accept", "application/json").build()
    val metadataJson = updateHttp.newCall(metaRequest).execute().use { response ->
        if (!response.isSuccessful) return null
        response.body?.string() ?: return null
    }
    parseReleaseMetadata(json, metadataJson)
}.getOrNull()

internal suspend fun checkLatestAppUpdate(context: Context): AppUpdateInfo? = withContext(Dispatchers.IO) {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val currentCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    val request = Request.Builder().url(RELEASES_URL).header("Accept", "application/vnd.github+json")
        .header("User-Agent", "Orbis-Android/${packageInfo.versionName ?: "unknown"}").build()
    val info = updateHttp.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@withContext null
        parseLatestRelease(response.body?.string() ?: return@withContext null)
    }
    info?.takeIf { it.versionCode > currentCode }
}

private fun updateApkFile(context: Context, info: AppUpdateInfo): File =
    File(context.cacheDir, "updates/orbis-${info.versionCode}.apk")

internal suspend fun downloadAppUpdate(
    context: Context,
    info: AppUpdateInfo,
    onProgress: (AppUpdateProgress) -> Unit = {},
): File = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "updates").apply { mkdirs() }
    val temporary = File(directory, "orbis-${info.versionCode}.apk.tmp")
    val target = updateApkFile(context, info)
    if (target.isFile) return@withContext target
    val request = Request.Builder().url(info.apkUrl).header("User-Agent", "Orbis-Android").build()
    updateHttp.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "下载更新失败：HTTP ${response.code}" }
        val body = checkNotNull(response.body)
        val total = body.contentLength().takeIf { it >= 0L }
        var received = 0L
        onProgress(AppUpdateProgress(received, total))
        body.byteStream().use { input -> temporary.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                received += count
                onProgress(AppUpdateProgress(received, total))
            }
        } }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    temporary.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    check(digest.digest().joinToString("") { "%02x".format(it) } == info.sha256) { "更新包校验失败" }
    check(temporary.renameTo(target)) { "无法保存更新包" }
    target
}

private fun installAppUpdate(context: Context, apk: File): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        return false
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", apk)
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
    return true
}

@Composable
internal fun AppUpdateSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var cached by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<AppUpdateProgress?>(null) }
    var status by remember { mutableStateOf(L10n.tr("尚未检查")) }
    var busy by remember { mutableStateOf(false) }
    SettingsGroup(L10n.tr("软件更新"), tag = "settings-updates") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L10n.tr("只检查 CYZice/Orbis 的正式 GitHub Release"), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeumorphTextButton(L10n.tr("检查更新"), onClick = {
                    if (!busy) {
                        busy = true; status = L10n.tr("正在检查…")
                        scope.launch {
                            runCatching { checkLatestAppUpdate(context) }
                                .onSuccess { value ->
                                    update = value
                                    cached = value?.let { updateApkFile(context, it).isFile } == true
                                    progress = null
                                    status = if (value == null) L10n.tr("当前已是最新版本") else if (cached) {
                                        L10n.tr("版本 {0} 已下载，等待安装", value.version)
                                    } else L10n.tr("发现版本 {0}", value.version)
                                }
                                .onFailure { status = it.message ?: L10n.tr("检查更新失败") }
                            busy = false
                        }
                    }
                }, enabled = !busy)
                update?.let { info ->
                    NeumorphTextButton(L10n.tr(if (cached) "安装" else "下载并安装"), onClick = {
                        if (!busy) {
                            busy = true
                            status = if (cached) L10n.tr("正在打开安装程序…") else L10n.tr("正在下载 {0}…", info.version)
                            scope.launch {
                                runCatching {
                                    if (cached) updateApkFile(context, info)
                                    else downloadAppUpdate(context, info) { progress = it }
                                }.onSuccess { apk ->
                                    cached = true
                                    progress = null
                                    status = if (installAppUpdate(context, apk)) {
                                        L10n.tr("安装程序已打开，请确认安装")
                                    } else {
                                        L10n.tr("安装包已下载，请允许安装后再次点击安装")
                                    }
                                }
                                    .onFailure {
                                        progress = null
                                        status = it.message ?: L10n.tr("下载更新失败")
                                    }
                                busy = false
                            }
                        }
                    }, enabled = !busy)
                }
                if (busy && progress == null) CircularProgressIndicator(Modifier.padding(8.dp).size(18.dp), strokeWidth = 2.dp)
            }
            progress?.let { current ->
                val fraction = current.totalBytes?.takeIf { it > 0L }?.let { current.receivedBytes.toFloat() / it }
                if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator({ fraction.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                Text(
                    current.totalBytes?.let { total ->
                        L10n.tr("已下载 {0} / {1}", formatBytes(current.receivedBytes), formatBytes(total))
                    } ?: L10n.tr("已下载 {0}", formatBytes(current.receivedBytes)),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(status, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

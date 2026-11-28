package com.sukisu.nova.ui.util

import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import com.sukisu.nova.data.registry.ExpectedFile
import com.sukisu.nova.ksuApp
import com.sukisu.nova.ui.util.module.LatestVersionInfo
import okhttp3.Request

/**
 * @author weishu
 * @date 2023/6/22.
 */
suspend fun download(
    url: String,
    fileName: String,
    onDownloaded: (Uri) -> Unit = {},
    onDownloading: () -> Unit = {},
    onProgress: (Int) -> Unit = {},
    expected: ExpectedFile? = null,
    onFailed: (DownloadManager.DownloadState) -> Unit = {},
) {
    onDownloading()

    val downloadId = DownloadManager.enqueue(
        context = ksuApp,
        url = url,
        fileName = fileName,
        onCompleted = onDownloaded,
        expected = expected,
    )

    val finalState = DownloadManager.downloads
        .onEach { map -> map[downloadId]?.let { onProgress(it.progress) } }
        .first { map ->
            val status = map[downloadId]?.status
            status == DownloadManager.Status.COMPLETED ||
                status == DownloadManager.Status.FAILED
        }[downloadId]
    if (finalState?.status == DownloadManager.Status.FAILED) {
        withContext(Dispatchers.Main) { onFailed(finalState) }
    }
}

internal suspend fun isDownloadAvailable(uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        ksuApp.contentResolver.openFileDescriptor(uri, "r").use { it != null }
    }.getOrDefault(false)
}


// This fork is published on GitHub, so the release check reads its own repository instead of
// the upstream GitLab. Every build shares one versionCode, so the tag name is what counts.
private const val GITHUB_RELEASE_API =
    "https://api.github.com/repos/AppleMac13/SukiSU-Next/releases/latest"
private const val GITHUB_DOWNLOAD_PREFIX =
    "https://github.com/AppleMac13/SukiSU-Next/releases/download/"

fun checkNewVersion(): LatestVersionInfo = checkGithubRelease()

private fun checkGithubRelease(): LatestVersionInfo {
    if (!isNetworkAvailable(ksuApp)) return LatestVersionInfo()
    return runCatching {
        ksuApp.okhttpClient.newCall(
            Request.Builder()
                .url(GITHUB_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("Cache-Control", "no-store")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                android.util.Log.w("UpdateCheck", "github: HTTP ${response.code}")
                return LatestVersionInfo()
            }
            val json = org.json.JSONObject(response.body.string())
            val versionName = json.optString("tag_name").trim().removePrefix("v")
            if (versionName.isEmpty()) return LatestVersionInfo()
            val assets = json.optJSONArray("assets") ?: return LatestVersionInfo()
            val apk = (0 until assets.length()).asSequence()
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull {
                    it.optString("name").endsWith("-release.apk") &&
                        it.optString("browser_download_url").startsWith(GITHUB_DOWNLOAD_PREFIX)
                } ?: run {
                android.util.Log.w("UpdateCheck", "github: no release APK for $versionName")
                return LatestVersionInfo()
            }
            LatestVersionInfo(
                versionCode = 0L,
                downloadUrl = apk.optString("browser_download_url"),
                changelog = json.optString("body"),
                versionName = versionName,
            )
        }
    }.onFailure {
        android.util.Log.w("UpdateCheck", "github: update check failed", it)
    }.getOrDefault(LatestVersionInfo())
}

fun checkNewCiVersion(): LatestVersionInfo = checkReleaseVersion("tags/manager-ci", isCi = true)

private const val RELEASE_API =
    "https://git.zako.shirkneko.top:20004/api/v1/repos/shirkneko/SukiSU/releases"

private fun checkReleaseVersion(endpoint: String, isCi: Boolean): LatestVersionInfo {
    if (!isNetworkAvailable(ksuApp)) return LatestVersionInfo()
    val url = "$RELEASE_API/$endpoint"
    return runCatching {
        ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                android.util.Log.w("UpdateCheck", "$endpoint: HTTP ${response.code}")
                return LatestVersionInfo()
            }
            val json = org.json.JSONObject(response.body.string())
            val assets = json.getJSONArray("assets")
            val changelog = json.optString("body")
            if (isCi) {
                val versionCode = Regex("""\((\d+)\)""").find(json.optString("name"))
                    ?.groupValues?.get(1)?.toLongOrNull() ?: return LatestVersionInfo()
                val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return LatestVersionInfo()
                val apkPattern = Regex(
                    """^SukiSU_Nova_.+_${versionCode}-${Regex.escape(abi)}-release\.apk$"""
                )
                val apk = (0 until assets.length()).asSequence()
                    .mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { apkPattern.matches(it.optString("name")) }
                    ?: run {
                        android.util.Log.w("UpdateCheck", "CI: no APK for version=$versionCode, abi=$abi")
                        return LatestVersionInfo()
                    }
                val downloadUrl = apk.optString("browser_download_url")
                    .takeIf { it.startsWith("https://git.zako.shirkneko.top:20004/shirkneko/SukiSU/releases/download/manager-ci/") }
                    ?: return LatestVersionInfo()
                return LatestVersionInfo(versionCode, downloadUrl, changelog)
            }
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return LatestVersionInfo()
            val archPattern = Regex("""^SukiSU_Nova_v(.+?)_(\d+)-${Regex.escape(abi)}-release\.apk$""")
            val universalPattern = Regex("""^SukiSU_Nova_v(.+?)_(\d+)-release\.apk$""")
            val apkAssets = (0 until assets.length()).map { assets.getJSONObject(it) }
            // Prefer the device-specific asset, falling back to an actual universal APK only.
            for (pattern in listOf(archPattern, universalPattern)) {
                for (asset in apkAssets) {
                    val match = pattern.matchEntire(asset.getString("name")) ?: continue
                    return LatestVersionInfo(
                        match.groupValues[2].toLong(),
                        asset.getString("browser_download_url"),
                        changelog,
                    )
                }
            }
            LatestVersionInfo()
        }
    }.onFailure {
        android.util.Log.w("UpdateCheck", "$endpoint: update check failed", it)
    }.getOrDefault(LatestVersionInfo())
}

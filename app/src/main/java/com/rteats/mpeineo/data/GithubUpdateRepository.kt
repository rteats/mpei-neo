package com.rteats.mpeineo.data

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val RELEASES_URL =
    "https://api.github.com/repos/rteats/mpei-neo/releases?per_page=20"

data class GithubReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val tagName: String,
    val title: String,
    val notes: String,
    val releasePageUrl: String,
    val apkUrl: String,
    val apkName: String,
    val sha256: String?,
)

class GithubUpdateRepository(
    private val application: Application,
    private val httpClient: OkHttpClient,
    private val gson: Gson,
) {

    suspend fun getLatestRelease(): GithubReleaseInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MPEI-Neo-Android")
            .build()

        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "GitHub Releases returned HTTP ${response.code}"
            }
            val body = response.body?.string()
                ?: error("GitHub Releases returned an empty response")

            val type = object : TypeToken<List<ReleaseDto>>() {}.type
            val releases: List<ReleaseDto> = gson.fromJson(body, type)

            releases
                .asSequence()
                .filterNot { it.draft }
                .mapNotNull { release ->
                    val versionCode = parseVersionCode(release.tagName) ?: return@mapNotNull null
                    val apk = release.assets.firstOrNull { asset ->
                        asset.name.endsWith(".apk", ignoreCase = true) ||
                            asset.contentType == "application/vnd.android.package-archive"
                    } ?: return@mapNotNull null

                    GithubReleaseInfo(
                        versionCode = versionCode,
                        versionName = release.tagName.removePrefix("v"),
                        tagName = release.tagName,
                        title = release.name.ifBlank { release.tagName },
                        notes = release.body.trim(),
                        releasePageUrl = release.htmlUrl,
                        apkUrl = apk.browserDownloadUrl,
                        apkName = apk.name,
                        sha256 = apk.digest
                            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                            ?.substringAfter(':')
                            ?.lowercase(),
                    )
                }
                .maxByOrNull { it.versionCode }
                ?: error("No APK release was found on GitHub")
        }
    }

    suspend fun downloadRelease(
        release: GithubReleaseInfo,
        onProgress: (Int?) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val updateDir = File(application.cacheDir, "updates").apply { mkdirs() }
        val finalFile = File(updateDir, release.apkName)
        val partFile = File(updateDir, "${release.apkName}.part")

        partFile.delete()
        finalFile.delete()

        val request = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", "MPEI-Neo-Android")
            .build()

        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "APK download returned HTTP ${response.code}"
            }

            val body = response.body ?: error("APK download returned an empty response")
            val totalBytes = body.contentLength()
            var copied = 0L
            var lastPercent = -1

            body.byteStream().use { input ->
                partFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count

                        val percent = if (totalBytes > 0) {
                            ((copied * 100L) / totalBytes)
                                .coerceIn(0L, 100L)
                                .toInt()
                        } else {
                            null
                        }
                        if (percent == null || percent != lastPercent) {
                            onProgress(percent)
                            if (percent != null) lastPercent = percent
                        }
                    }
                }
            }
        }

        verifyDigestIfPresent(partFile, release.sha256)

        check(partFile.renameTo(finalFile)) {
            "Unable to finalize downloaded APK"
        }
        onProgress(100)
        finalFile
    }

    fun createInstallIntent(apkFile: File): Intent {
        check(apkFile.exists()) { "Downloaded APK no longer exists" }

        val uri = FileProvider.getUriForFile(
            application,
            "${application.packageName}.fileprovider",
            apkFile,
        )

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun verifyDigestIfPresent(file: File, expectedSha256: String?) {
        if (expectedSha256.isNullOrBlank()) return

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }

        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        check(actual.equals(expectedSha256, ignoreCase = true)) {
            file.delete()
            "Downloaded APK failed SHA-256 verification"
        }
    }

    private fun parseVersionCode(tag: String): Int? =
        Regex("""(\d+)(?:\D*)$""")
            .find(tag)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
}

private data class ReleaseDto(
    @SerializedName("tag_name")
    val tagName: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName("body")
    val body: String = "",
    @SerializedName("html_url")
    val htmlUrl: String = "",
    @SerializedName("draft")
    val draft: Boolean = false,
    @SerializedName("assets")
    val assets: List<AssetDto> = emptyList(),
)

private data class AssetDto(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("content_type")
    val contentType: String = "",
    @SerializedName("browser_download_url")
    val browserDownloadUrl: String = "",
    @SerializedName("digest")
    val digest: String? = null,
)

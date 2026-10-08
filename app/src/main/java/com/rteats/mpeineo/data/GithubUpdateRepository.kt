package com.rteats.mpeineo.data

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val RELEASES_URL =
    "https://api.github.com/repos/rteats/mpei-neo/releases?per_page=30"
private const val DEV_RELEASE_URL =
    "https://api.github.com/repos/rteats/mpei-neo/releases/tags/dev"

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

data class UpdateCleanupResult(
    val deletedFiles: Int,
    val completedInstalledUpdate: Boolean,
)

class GithubUpdateRepository(
    private val application: Application,
    httpClient: OkHttpClient,
    private val gson: Gson,
    private val diagnostics: DiagnosticLog,
) {
    private val preferences =
        application.getSharedPreferences("github_updater", Context.MODE_PRIVATE)

    private val updateDirectory =
        File(application.cacheDir, "updates").apply { mkdirs() }

    private val updateHttpClient = httpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun getLatestRelease(channel: String): GithubReleaseInfo =
        withContext(Dispatchers.IO) {
            diagnostics.log("UPDATE", "check started channel=$channel")
            val result = if (channel == "dev") {
                fetchDevRelease()
            } else {
                fetchLatestStableRelease()
            }
            diagnostics.log(
                "UPDATE",
                "check completed channel=$channel version=${result.versionName} code=${result.versionCode}",
            )
            result
        }

    suspend fun downloadRelease(
        release: GithubReleaseInfo,
        onProgress: (Int?) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        pruneBeforeDownload()

        val finalFile = File(updateDirectory, release.apkName)
        val partFile = File(updateDirectory, "${release.apkName}.part")

        partFile.delete()
        finalFile.delete()

        diagnostics.log(
            "UPDATE",
            "download started version=${release.versionName} code=${release.versionCode}",
        )

        val request = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", "MPEI-Neo-Android")
            .build()

        updateHttpClient.newCall(request).execute().use { response ->
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

        preferences.edit()
            .putInt(KEY_PENDING_VERSION, release.versionCode)
            .putString(KEY_PENDING_PATH, finalFile.absolutePath)
            .putLong(KEY_PENDING_TIMESTAMP, System.currentTimeMillis())
            .apply()

        diagnostics.log(
            "UPDATE",
            "download completed version=${release.versionName} bytes=${finalFile.length()}",
        )
        onProgress(100)
        finalFile
    }

    fun cleanupAfterLaunch(currentVersionCode: Int): UpdateCleanupResult {
        var deleted = 0
        var completedUpdate = false

        updateDirectory.mkdirs()

        updateDirectory.listFiles()
            ?.filter { it.name.endsWith(".part") }
            ?.forEach {
                if (it.delete()) deleted += 1
            }

        val pendingVersion = preferences.getInt(KEY_PENDING_VERSION, -1)
        val pendingPath = preferences.getString(KEY_PENDING_PATH, null)

        if (pendingVersion >= 0 && currentVersionCode >= pendingVersion) {
            completedUpdate = true
            pendingPath
                ?.let(::File)
                ?.takeIf { it.isInside(updateDirectory) && it.exists() }
                ?.let {
                    if (it.delete()) deleted += 1
                }

            preferences.edit()
                .remove(KEY_PENDING_VERSION)
                .remove(KEY_PENDING_PATH)
                .remove(KEY_PENDING_TIMESTAMP)
                .apply()
        }

        val activePendingPath = if (completedUpdate) null else pendingPath
        val staleBefore = System.currentTimeMillis() - STALE_DOWNLOAD_AGE_MS

        updateDirectory.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.absolutePath != activePendingPath &&
                    file.lastModified() < staleBefore
            }
            ?.forEach {
                if (it.delete()) deleted += 1
            }

        return UpdateCleanupResult(
            deletedFiles = deleted,
            completedInstalledUpdate = completedUpdate,
        )
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

    private fun fetchDevRelease(): GithubReleaseInfo {
        val release = getReleaseDto(DEV_RELEASE_URL)
        val versionCode = Regex("""versionCode:\s*(\d+)""")
            .find(release.body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""dev\.(\d+)""")
                .find(release.name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: error("Dev release does not expose a versionCode")

        val versionName = Regex("""versionName:\s*([^\s]+)""")
            .find(release.body)
            ?.groupValues
            ?.getOrNull(1)
            ?: release.name.substringAfter("MPEI Neo Dev ", "dev.$versionCode")

        return release.toReleaseInfo(
            versionCode = versionCode,
            versionName = versionName,
        )
    }

    private fun fetchLatestStableRelease(): GithubReleaseInfo {
        val request = githubRequest(RELEASES_URL)

        updateHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "GitHub Releases returned HTTP ${response.code}"
            }
            val body = response.body?.string()
                ?: error("GitHub Releases returned an empty response")

            val type = object : TypeToken<List<ReleaseDto>>() {}.type
            val releases: List<ReleaseDto> = gson.fromJson(body, type)

            return releases
                .asSequence()
                .filter { !it.draft && !it.prerelease }
                .mapNotNull { release ->
                    val parsed = parseStableVersion(release.tagName)
                        ?: return@mapNotNull null
                    release.toReleaseInfo(
                        versionCode = parsed.versionCode,
                        versionName = parsed.versionName,
                    )
                }
                .maxByOrNull { it.versionCode }
                ?: error("No stable APK release was found on GitHub")
        }
    }

    private fun getReleaseDto(url: String): ReleaseDto {
        val request = githubRequest(url)

        updateHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "GitHub Releases returned HTTP ${response.code}"
            }
            val body = response.body?.string()
                ?: error("GitHub Releases returned an empty response")
            return gson.fromJson(body, ReleaseDto::class.java)
        }
    }

    private fun githubRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MPEI-Neo-Android")
            .build()

    private fun ReleaseDto.toReleaseInfo(
        versionCode: Int,
        versionName: String,
    ): GithubReleaseInfo {
        val apk = assets.firstOrNull { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) ||
                asset.contentType == "application/vnd.android.package-archive"
        } ?: error("Release $tagName has no APK asset")

        return GithubReleaseInfo(
            versionCode = versionCode,
            versionName = versionName,
            tagName = tagName,
            title = name.ifBlank { tagName },
            notes = body.trim(),
            releasePageUrl = htmlUrl,
            apkUrl = apk.browserDownloadUrl,
            apkName = apk.name,
            sha256 = apk.digest
                ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.lowercase(),
        )
    }

    private fun parseStableVersion(tag: String): ParsedStableVersion? {
        val match = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""").matchEntire(tag)
            ?: return null

        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        val patch = match.groupValues[3].toInt()

        return ParsedStableVersion(
            versionName = "$major.$minor.$patch",
            versionCode = major * 1_000_000 + minor * 1_000 + patch,
        )
    }

    private fun pruneBeforeDownload() {
        updateDirectory.mkdirs()

        updateDirectory.listFiles()?.forEach { file ->
            if (file.isFile && (file.extension == "apk" || file.name.endsWith(".part"))) {
                file.delete()
            }
        }

        preferences.edit()
            .remove(KEY_PENDING_VERSION)
            .remove(KEY_PENDING_PATH)
            .remove(KEY_PENDING_TIMESTAMP)
            .apply()
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

    private fun File.isInside(parent: File): Boolean =
        runCatching {
            canonicalFile.path.startsWith(parent.canonicalFile.path + File.separator)
        }.getOrDefault(false)

    private data class ParsedStableVersion(
        val versionName: String,
        val versionCode: Int,
    )

    private companion object {
        const val KEY_PENDING_VERSION = "pending_version"
        const val KEY_PENDING_PATH = "pending_path"
        const val KEY_PENDING_TIMESTAMP = "pending_timestamp"
        const val STALE_DOWNLOAD_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
    }
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
    @SerializedName("prerelease")
    val prerelease: Boolean = false,
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

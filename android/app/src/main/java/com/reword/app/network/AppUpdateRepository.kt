package com.reword.app.network

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal data class AppUpdate(val version: String, val downloadUrl: String)

/** Public release metadata only. No vault identifiers or credentials are sent to GitHub. */
internal class AppUpdateRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersion: String): AppUpdate? = withContext(Dispatchers.IO) {
        // /latest can point at an extension release, so inspect Android tags and APK assets instead.
        val request = Request.Builder()
            .url("https://api.github.com/repos/reyad-dev/reword-by-reyad/releases?per_page=100")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ReWord-Android/$currentVersion")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Update check failed: ${response.code}" }
            val releases = Gson().fromJson(response.body?.string(), Array<GitHubRelease>::class.java)
                ?: error("Missing releases")
            selectUpdate(releases.toList(), currentVersion)
        }
    }

    internal data class GitHubAsset(val name: String? = null, val browser_download_url: String? = null)
    internal data class GitHubRelease(
        val tag_name: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset>? = null
    )

    companion object {
        private fun versionParts(version: String): List<Int>? {
            if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version)) return null
            return version.split('.').map { it.toIntOrNull() ?: return null }
        }

        internal fun compareVersions(left: String, right: String): Int {
            val a = requireNotNull(versionParts(left))
            val b = requireNotNull(versionParts(right))
            for (index in a.indices) if (a[index] != b[index]) return a[index].compareTo(b[index])
            return 0
        }

        internal fun selectUpdate(releases: List<GitHubRelease>, currentVersion: String): AppUpdate? {
            if (versionParts(currentVersion) == null) return null
            return releases.mapNotNull { release ->
                val tag = release.tag_name ?: return@mapNotNull null
                if (!tag.startsWith("v") || release.draft || release.prerelease) return@mapNotNull null
                val version = tag.removePrefix("v")
                if (versionParts(version) == null || compareVersions(version, currentVersion) <= 0) return@mapNotNull null
                val name = "ReWord-v$version.apk"
                val expectedUrl = "https://github.com/reyad-dev/reword-by-reyad/releases/download/$tag/$name"
                if (release.assets.orEmpty().none { it.name == name && it.browser_download_url == expectedUrl }) return@mapNotNull null
                AppUpdate(version, expectedUrl)
            }.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
        }
    }
}

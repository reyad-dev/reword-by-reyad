package com.reword.app.network

import com.reword.app.network.AppUpdateRepository.GitHubAsset
import com.reword.app.network.AppUpdateRepository.GitHubRelease
import org.junit.Assert.*
import org.junit.Test

class AppUpdateRepositoryTest {
    private fun release(version: String) = GitHubRelease(
        tag_name = "v$version",
        assets = listOf(GitHubAsset("ReWord-v$version.apk",
            "https://github.com/reyad-dev/reword-by-reyad/releases/download/v$version/ReWord-v$version.apk"))
    )

    @Test fun picksHighestAndroidVersionEvenWhenExtensionReleaseIsNewest() {
        val releases = listOf(GitHubRelease(tag_name = "chrome-v9.0.0"), release("1.9.0"), release("1.10.0"))
        assertEquals("1.10.0", AppUpdateRepository.selectUpdate(releases, "1.4.0")?.version)
    }

    @Test fun neverOffersSameVersionOrDowngrade() {
        assertNull(AppUpdateRepository.selectUpdate(listOf(release("1.3.3"), release("1.4.0")), "1.4.0"))
    }

    @Test fun skipsDraftPrereleaseMissingApkAndMalformedTags() {
        val releases = listOf(release("2.0.0").copy(draft = true), release("2.1.0").copy(prerelease = true),
            release("2.2.0").copy(assets = emptyList()), release("2.3.0").copy(tag_name = "v2.3.0-beta"),
            release("2.4.0").copy(tag_name = "v99999999999999999999.0.0"))
        assertNull(AppUpdateRepository.selectUpdate(releases, "1.4.0"))
    }

    @Test fun onlyAcceptsExactOfficialApkUrl() {
        val bad = release("1.5.0").copy(assets = listOf(GitHubAsset("ReWord-v1.5.0.apk", "https://example.test/update.apk")))
        assertNull(AppUpdateRepository.selectUpdate(listOf(bad), "1.4.0"))
    }

    @Test fun emptyListAndInvalidInstalledVersionAreSafe() {
        assertNull(AppUpdateRepository.selectUpdate(emptyList(), "1.4.0"))
        assertNull(AppUpdateRepository.selectUpdate(listOf(release("1.5.0")), "unknown"))
    }
}

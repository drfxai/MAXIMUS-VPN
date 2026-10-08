package com.example

import com.example.update.AppUpdateChecker
import com.example.update.AppUpdateChecker.ReleaseInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The update pop-up fires only for a newer release build and only opens this repository's pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateCheckerTest {

    private val apiJson = """{"tag_name":"V1.0.0","html_url":"https://github.com/drfxai/MAXIMUS-VPN/releases/tag/V1.0.0",
        "body":"Build 142\n\n<!-- maximus-build: 142 -->"}"""

    @Test
    fun readsTheBuildNumberFromTheReleaseNotes() {
        assertEquals(
            ReleaseInfo("V1.0.0", 142, "https://github.com/drfxai/MAXIMUS-VPN/releases/tag/V1.0.0"),
            AppUpdateChecker.parseGitHubRelease(apiJson)
        )
    }

    @Test
    fun aReleaseWithoutABuildNumberIsIgnored() {
        assertNull(AppUpdateChecker.parseGitHubRelease("""{"tag_name":"V1.0.0","html_url":"x","body":""}"""))
        assertNull(AppUpdateChecker.parseGitHubRelease("not json"))
    }

    @Test
    fun readsTheMirroredManifest() {
        val info = AppUpdateChecker.parseManifest("""{"version":"v1.0.1","build":150,"url":"https://github.com/drfxai/MAXIMUS-VPN/releases/tag/v1.0.1"}""")
        assertEquals(ReleaseInfo("V1.0.1", 150, "https://github.com/drfxai/MAXIMUS-VPN/releases/tag/v1.0.1"), info)
        assertNull(AppUpdateChecker.parseManifest("""{"version":"<script>","build":150,"url":""}"""))
        assertNull(AppUpdateChecker.parseManifest("""{"version":"V1.0.0","build":0}"""))
    }

    @Test
    fun aForeignDownloadLinkFallsBackToTheReleasesPage() {
        val info = AppUpdateChecker.parseManifest("""{"version":"V1.0.0","build":150,"url":"https://evil.example/app.apk"}""")
        assertEquals(AppUpdateChecker.RELEASES_URL, info?.pageUrl)
        assertEquals(AppUpdateChecker.RELEASES_URL, AppUpdateChecker.safePageUrl("https://github.com/drfxai/MAXIMUS-VPN/releases/../../other"))
        assertEquals(AppUpdateChecker.RELEASES_URL, AppUpdateChecker.safePageUrl("https://github.com/drfxai/MAXIMUS-VPN-fake/releases/x"))
    }

    @Test
    fun comparesTheVersionNumberThenTheBuildNumber() {
        val release = ReleaseInfo("V1.0.0", 142, AppUpdateChecker.RELEASES_URL)
        val now = 1_000_000L
        fun show(r: ReleaseInfo, version: String, build: Int, snoozedBuild: Int = 0, snoozedUntil: Long = 0) =
            AppUpdateChecker.shouldShow(r, version, build, snoozedBuild, snoozedUntil, now)
        assertTrue("same version, newer build", show(release, "1.0.0", 138))
        assertFalse("the installed build", show(release, "1.0.0", 142))
        assertFalse("same version, older build", show(release, "1.0.0", 150))
        assertFalse("same version, local build", show(release, "1.0.0-debug", 0))
        assertTrue("newer version, lower build", show(ReleaseInfo("V1.0.1", 5, AppUpdateChecker.RELEASES_URL), "1.0.0", 150))
        assertTrue("newer version on a local build", show(ReleaseInfo("V1.1.0", 5, AppUpdateChecker.RELEASES_URL), "1.0.0-debug", 0))
        assertFalse("older version, higher build", show(release, "1.0.1", 100))
        assertFalse("Later on this build", show(release, "1.0.0", 138, 142, now + 1))
        assertTrue("Later has run out", show(release, "1.0.0", 138, 142, now - 1))
        assertTrue("a newer build than the snoozed one", show(release.copy(build = 143), "1.0.0", 138, 142, now + 1))
    }

    @Test
    fun versionNumbersCompareAsNumbers() {
        assertTrue(AppUpdateChecker.compareVersions("V1.0.10", "1.0.9") > 0)
        assertEquals(0, AppUpdateChecker.compareVersions("V1.0", "1.0.0-debug"))
        assertTrue(AppUpdateChecker.compareVersions("1.2.0", "V1.10.0") < 0)
    }

    @Test
    fun fallsBackToTheMirrorsWhenGitHubIsBlocked() = runBlocking {
        val asked = mutableListOf<String>()
        val info = AppUpdateChecker.fetchLatest { url ->
            asked += url
            if (url.contains("jsdelivr")) """{"version":"V1.0.0","build":150,"url":"https://github.com/drfxai/MAXIMUS-VPN/releases/tag/V1.0.0"}"""
            else throw java.io.IOException("blocked")
        }
        assertEquals(150, info?.build)
        assertTrue(asked.first().startsWith("https://api.github.com/"))
        assertNull(AppUpdateChecker.fetchLatest { null })
    }
}

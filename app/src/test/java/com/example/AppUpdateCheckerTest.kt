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
    fun showsOnlyForANewerBuildThatIsNotSnoozed() {
        val release = ReleaseInfo("V1.0.0", 142, AppUpdateChecker.RELEASES_URL)
        val now = 1_000_000L
        assertTrue(AppUpdateChecker.shouldShow(release, 138, 0, 0, now))
        assertFalse("the installed build", AppUpdateChecker.shouldShow(release, 142, 0, 0, now))
        assertFalse("an older release", AppUpdateChecker.shouldShow(release, 150, 0, 0, now))
        assertFalse("a local build", AppUpdateChecker.shouldShow(release, 0, 0, 0, now))
        assertFalse("Later on this build", AppUpdateChecker.shouldShow(release, 138, 142, now + 1, now))
        assertTrue("Later has run out", AppUpdateChecker.shouldShow(release, 138, 142, now - 1, now))
        assertTrue("a newer build than the snoozed one", AppUpdateChecker.shouldShow(release.copy(build = 143), 138, 142, now + 1, now))
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

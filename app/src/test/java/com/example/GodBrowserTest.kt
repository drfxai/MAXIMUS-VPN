package com.example

import com.example.browser.TrackerBlocker
import com.example.ui.browser.GodBrowser
import org.junit.Assert.*
import org.junit.Test

class GodBrowserTest {
    @Test fun searchesGoToGoogle() {
        assertEquals("https://www.google.com/search?q=youtube+music", GodBrowser.target("youtube music"))
        assertEquals("https://www.google.com", GodBrowser.target("  "))
        assertEquals("https://example.com", GodBrowser.target("example.com"))
        assertEquals("HTTPS://example.com/a", GodBrowser.target(" HTTPS://example.com/a "))
    }

    @Test fun trackersMatchByHostNotBySearchText() {
        assertTrue(TrackerBlocker.isBlocked("https://www.google-analytics.com/collect"))
        assertTrue(TrackerBlocker.isBlocked("https://connect.facebook.com/tr?id=1"))
        assertTrue(TrackerBlocker.isBlocked("https://telemetry.example.org/x"))
        assertFalse(TrackerBlocker.isBlocked("https://www.google.com/search?q=telemetry+segment.com"))
        assertFalse(TrackerBlocker.isBlocked("https://www.facebook.com/travel"))
        assertFalse(TrackerBlocker.isBlocked("https://notbranch.io.example.com/"))
        assertFalse(TrackerBlocker.isBlocked("https://www.youtube.com/watch?v=adjust.com"))
    }
}

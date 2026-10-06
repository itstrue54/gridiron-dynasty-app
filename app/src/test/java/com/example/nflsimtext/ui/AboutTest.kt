package com.example.nflsimtext.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The About screen says what the store listing and the published policy say. */
class AboutTest {

    private fun repo(path: String) = File(File(System.getProperty("user.dir")).parentFile, path).readText()

    @Test
    fun `the policy link and support address match the release notes`() {
        val release = repo("docs/play-store/RELEASE.md")
        assertTrue("RELEASE.md gives ${About.PRIVACY_URL}", About.PRIVACY_URL in release)
        assertTrue("RELEASE.md gives ${About.SUPPORT_EMAIL}", About.SUPPORT_EMAIL in release)
        assertTrue("the policy gives ${About.SUPPORT_EMAIL}", About.SUPPORT_EMAIL in repo("docs/play-store/privacy-policy.md"))
    }

    @Test
    fun `an email to support names the version`() {
        assertEquals("Gridiron Dynasty 1.0 (1)", About.subject("1.0 (1)"))
    }
}

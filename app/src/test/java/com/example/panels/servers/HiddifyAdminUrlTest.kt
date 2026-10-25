package com.example.panels.servers

import com.example.panels.servers.panel.HiddifyPanelProvider
import com.example.panels.servers.panel.HiddifyScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddifyAdminUrlTest {
    @Test fun acceptsOfficialV14AdminUrlShape() {
        val secretUrl = "https://panel.example.com/SecretProxyPath/8b7f6b5a-1234-4567-89ab-1234567890ab/"
        assertEquals(secretUrl, HiddifyPanelProvider.validatedAdminUrl(secretUrl))
    }

    @Test fun rejectsInsecureAndMisleadingLinks() {
        assertNull(HiddifyPanelProvider.validatedAdminUrl(null))
        assertNull(HiddifyPanelProvider.validatedAdminUrl("http://panel.example.com/secret/admin-uuid/"))
        assertNull(HiddifyPanelProvider.validatedAdminUrl("https://panel.example.com"))
        assertNull(HiddifyPanelProvider.validatedAdminUrl("https://user:password@panel.example.com/secret/admin-uuid/"))
        assertNull(HiddifyPanelProvider.validatedAdminUrl("https://panel.example.com:8080/secret/admin-uuid/"))
    }

    @Test fun scriptsUseOfficialAdminCommandNotLegacyPath() {
        val install = HiddifyScripts.install(update = false).text
        val recover = HiddifyScripts.adminUrl().text
        assertTrue(install.contains("hiddify admin"))
        assertTrue(recover.contains("hiddify admin"))
        assertTrue(!install.contains("grep -o 'https://[^\" ]*/admin/"))
        assertTrue(recover.contains("result ADMIN_URL"))
    }
}

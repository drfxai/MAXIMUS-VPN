package com.example.b178

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.model.AppSettings
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.OperationalMode
import com.example.ui.home.ConfigFreeMethodText
import com.example.ui.language.AppLanguage
import com.example.ui.language.LanguageStore
import com.example.ui.language.UiText
import com.example.ui.settings.SettingsLabels
import com.example.ui.settings.SettingsPage
import com.example.ui.settings.UpdateUi
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Build 177 (B12, B13, B14, B18, B19): the texts the Russian and Persian UI showed in English, or wrongly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Build177LocalizationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val en = LanguageStore.contextFor(context, AppLanguage.ENGLISH)
    private val ru = LanguageStore.contextFor(context, AppLanguage.RUSSIAN)
    private val fa = LanguageStore.contextFor(context, AppLanguage.PERSIAN)

    private fun hub(ctx: Context, settings: AppSettings, page: SettingsPage, update: UpdateUi = UpdateUi(checked = true)) =
        SettingsLabels.hubText(settings, page, update, "1.0.4") { id, args -> ctx.getString(id, *args) }

    @Test fun b19_settingsHubSubtitlesFollowTheAppLanguage() {
        val settings = AppSettings()
        // English stays as before (the screenshot test checks "Balanced · Auto engine").
        assertEquals("Balanced · Auto engine", hub(en, settings, SettingsPage.CONNECTION))
        assertEquals("Up to date · v1.0.4", hub(en, settings, SettingsPage.UPDATES))
        assertEquals("Сбалансированный · Авто-движок", hub(ru, settings, SettingsPage.CONNECTION))
        assertEquals("Раздача через точку доступа выкл.", hub(ru, settings, SettingsPage.SHARING))
        assertEquals("Тёмная тема", hub(ru, settings, SettingsPage.APPEARANCE))
        assertEquals("Обновлено · v1.0.4", hub(ru, settings, SettingsPage.UPDATES))
        assertEquals("В обход LAN · Google DoH", hub(ru, settings, SettingsPage.ROUTING_DNS))
        assertEquals("متعادل · موتور خودکار", hub(fa, settings, SettingsPage.CONNECTION))
        // B18: the protection is called Kill Switch everywhere.
        assertTrue(hub(ru, settings.copy(killSwitchEnabled = true), SettingsPage.PRIVACY).startsWith("Kill Switch · "))
        assertTrue(hub(en, settings.copy(killSwitchEnabled = true), SettingsPage.PRIVACY).startsWith("Kill Switch · "))
    }

    @Test fun b18_theKillSwitchHasOneNameInEveryLanguage() {
        for (ctx in listOf(en, ru, fa)) {
            assertEquals("Kill Switch", ctx.getString(R.string.ui_in_tunnel_packet_guard))
            assertEquals("Kill Switch", ctx.getString(R.string.ui_kill_switch))
            assertFalse(ctx.getString(R.string.ui_serves_the_selected_server_to).contains("packet guard"))
        }
    }

    @Test fun b19_pickerModesAndStatusesAreTranslated() {
        assertEquals("Сеть Lantern; движок встроен в приложение", ru.getString(ConfigFreeMethodText.description(ConfigFreeMethod.RADIANCE)))
        assertEquals("Туннель Cloudflare с регистрацией на устройстве", ru.getString(ConfigFreeMethodText.description(ConfigFreeMethod.AETHER_WARP)))
        assertEquals("Ежедневный режим", ru.getString(UiText.modeTitle(OperationalMode.DAILY)))
        assertEquals("ОТКЛЮЧЕНО", ru.getString(UiText.status(ConnectionState(status = ConnectionStatus.DISCONNECTED))))
        // English keeps the model's own labels.
        OperationalMode.entries.filter { it != OperationalMode.UNLOCK }.forEach {
            assertEquals(it.displayName, en.getString(UiText.modeTitle(it)))
            assertEquals(it.subtitle, en.getString(UiText.modeSubtitle(it)))
        }
        ConnectionStatus.entries.forEach {
            val state = ConnectionState(status = it)
            assertEquals(state.statusLabel, en.getString(UiText.status(state)))
        }
        ConfigFreeMethod.entries.forEach { assertEquals(it.description, en.getString(ConfigFreeMethodText.description(it))) }
    }

    @Test fun b13_top10EmptyStatePointsToAScreenThatExists() {
        val text = en.getString(R.string.ui_run_a_benchmark_from_the)
        assertFalse(text.contains("Benchmark tab"))
        assertTrue(text.contains(en.getString(R.string.nav_nodes)))
        assertTrue(text.contains(en.getString(R.string.ui_benchmark_lab)))
        // Russian inflects the lab's name ("Лабораторию тестов"); the tab name is quoted as is.
        assertTrue(ru.getString(R.string.ui_run_a_benchmark_from_the).contains("«" + ru.getString(R.string.nav_nodes) + "»"))
        assertTrue(fa.getString(R.string.ui_run_a_benchmark_from_the).contains(fa.getString(R.string.ui_benchmark_lab)))
    }

    @Test fun b20_everyFailureNoticeIsTranslated() {
        for (id in listOf(R.string.connect_failed_block_released, R.string.connect_failed_block_kept, R.string.connect_failed_block_kill_switch)) {
            assertFalse(ru.getString(id) == en.getString(id))
            assertFalse(fa.getString(id) == en.getString(id))
        }
        assertTrue(en.getString(R.string.connect_failed_block_kill_switch).startsWith("Kill Switch is on"))
    }
}

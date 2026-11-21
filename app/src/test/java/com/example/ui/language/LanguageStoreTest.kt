package com.example.ui.language

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguageStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("language_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    fun pickerShowsUntilALanguageIsChosenThenNeverAgain() {
        assertTrue(LanguageStore.needsChoice(prefs))
        assertNull(LanguageStore.storedLanguage(prefs))
        LanguageStore.record(prefs, AppLanguage.RUSSIAN)
        assertFalse(LanguageStore.needsChoice(prefs))
        assertEquals(AppLanguage.RUSSIAN, LanguageStore.storedLanguage(prefs))
        LanguageStore.record(prefs, AppLanguage.ENGLISH)
        assertFalse(LanguageStore.needsChoice(prefs))
        assertEquals(AppLanguage.ENGLISH, LanguageStore.storedLanguage(prefs))
    }

    @Test
    fun defaultHighlightFollowsARussianPhoneOtherwiseEnglish() {
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.defaultFor("ru"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.defaultFor("ru-RU"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.defaultFor("en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.defaultFor("fa"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.defaultFor(null))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromTag("ru_RU"))
        assertNull(AppLanguage.fromTag("de"))
    }

    @Test
    fun resourcesResolveInBothLanguages() {
        val ru = LanguageStore.contextFor(context, AppLanguage.RUSSIAN)
        val en = LanguageStore.contextFor(context, AppLanguage.ENGLISH)
        assertEquals("Настройки", ru.getString(R.string.nav_settings))
        assertEquals("Settings", en.getString(R.string.nav_settings))
        assertEquals("Продолжить", ru.getString(R.string.lang_picker_continue))
        assertEquals("3 отобранных вручную сервера", ru.resources.getQuantityString(R.plurals.vip_hand_picked_servers, 3, 3))
        assertEquals("5 отобранных вручную серверов", ru.resources.getQuantityString(R.plurals.vip_hand_picked_servers, 5, 5))
        assertEquals("21 отобранный вручную сервер", ru.resources.getQuantityString(R.plurals.vip_hand_picked_servers, 21, 21))
    }
}

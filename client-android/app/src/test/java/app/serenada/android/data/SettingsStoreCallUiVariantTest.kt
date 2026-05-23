package app.serenada.android.data

import app.serenada.callui.SerenadaCallUiVariant
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SettingsStoreCallUiVariantTest {

    @Test
    fun normalizeCallUiVariant_defaultsToStandardForNullOrUnknown() {
        assertEquals(SerenadaCallUiVariant.Standard, SettingsStore.normalizeCallUiVariant(null))
        assertEquals(SerenadaCallUiVariant.Standard, SettingsStore.normalizeCallUiVariant(""))
        assertEquals(SerenadaCallUiVariant.Standard, SettingsStore.normalizeCallUiVariant("bogus"))
    }

    @Test
    fun normalizeCallUiVariant_acceptsFrontlineCaseInsensitive() {
        assertEquals(SerenadaCallUiVariant.Frontline, SettingsStore.normalizeCallUiVariant("Frontline"))
        assertEquals(SerenadaCallUiVariant.Frontline, SettingsStore.normalizeCallUiVariant("frontline"))
        assertEquals(SerenadaCallUiVariant.Frontline, SettingsStore.normalizeCallUiVariant("FRONTLINE"))
        assertEquals(SerenadaCallUiVariant.Frontline, SettingsStore.normalizeCallUiVariant("  frontline  "))
    }

    @Test
    fun normalizeCallUiVariant_acceptsStandardCaseInsensitive() {
        assertEquals(SerenadaCallUiVariant.Standard, SettingsStore.normalizeCallUiVariant("Standard"))
        assertEquals(SerenadaCallUiVariant.Standard, SettingsStore.normalizeCallUiVariant("standard"))
    }

    @Test
    fun normalizeCallUiVariant_isLocaleStable() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))

            assertEquals(SerenadaCallUiVariant.Frontline, SettingsStore.normalizeCallUiVariant("FRONTLINE"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}

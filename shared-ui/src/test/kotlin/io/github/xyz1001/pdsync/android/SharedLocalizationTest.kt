package io.github.xyz1001.pdsync.android

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pdsync.i18n.SharedMessages
import pdsync.i18n.UiLocale
import pdsync.i18n.UiText

class SharedLocalizationTest {
    private fun withLocale(locale: Locale, block: () -> Unit) {
        val previous = UiLocale.locale()
        try { UiLocale.setLocale(locale); block() } finally { UiLocale.setLocale(previous) }
    }

    @Test fun allSharedResourcesHaveUniquePairedKeys() {
        val resources = SharedMessages::class.java.fields.filter { it.type == UiText::class.java }
            .map { it.get(null) as UiText }
        assertTrue(resources.size > 150)
        assertEquals(resources.size, resources.map { it.key() }.toSet().size)
        resources.forEach {
            assertTrue(it.key().startsWith("shared."))
            assertTrue(it.chinese().isNotBlank())
            assertTrue(it.english().isNotBlank())
        }
    }

    @Test fun systemLanguageChangesResolveCurrentLabelsAndPreserveUserText() {
        val raw = "已同步 / My save 100%"
        withLocale(Locale.SIMPLIFIED_CHINESE) {
            assertEquals("设置", SharedMessages.SETTINGS.text())
            assertEquals("所选存档：$raw", SharedMessages.SELECTED_SAVE.text(raw))
            assertEquals("下一步", SharedSavePathState("/saves", false, null).primaryLabel)
        }
        withLocale(Locale.ENGLISH) {
            assertEquals("Settings", SharedMessages.SETTINGS.text())
            assertEquals("Selected save: $raw", SharedMessages.SELECTED_SAVE.text(raw))
            assertEquals("Next", SharedSavePathState("/saves", false, null).primaryLabel)
            assertEquals("Page 2 of 7", SharedMessages.PAGE_NUMBER.text(2, 7))
        }
        withLocale(Locale.FRENCH) { assertEquals("Settings", SharedMessages.SETTINGS.text()) }
    }

    @Test fun translatedLabelsCannotChangeActionSemantics() {
        withLocale(Locale.ENGLISH) {
            val home = SharedHomeState(null, "已同步", statusKind = SharedHomeStatusKind.WARNING)
            assertEquals(SharedHomeStatusKind.WARNING, home.statusKind)
            val confirmation = SharedConfirmationState(1, "Title", "Message", "恢复", destructive = false)
            assertEquals(false, confirmation.destructive)
            val row = SharedSettingsRow(SharedSettingsKey.AUTOMATIC_UPLOAD, "Title", "Paused", toggleEnabled = true)
            assertEquals(true, row.toggleEnabled)
        }
    }
    @Test fun settingsPauseLabelsAreShortInBothLanguagesWithoutChangingActions() {
        for ((locale, pause) in listOf(Locale.SIMPLIFIED_CHINESE to "暂停", Locale.ENGLISH to "Pause")) {
            withLocale(locale) {
                val enabled = SharedSettingsRow(SharedSettingsKey.AUTOMATIC_UPLOAD,
                    "title", "value", "action", true, toggleEnabled = true)
                val enabledView = automaticUploadSettingsRow(enabled)
                assertEquals(pause, enabledView.actionLabel)
                assertEquals(enabled.toggleEnabled, enabledView.toggleEnabled)
                assertEquals(enabled.actionEnabled, enabledView.actionEnabled)
                val pausedView = automaticUploadSettingsRow(enabled.copy(toggleEnabled = false))
                assertEquals(SharedMessages.AUTO_UPLOAD_PAUSED.text(), pausedView.currentValue)
                assertEquals(SharedMessages.ENABLE_AUTO_BACKUP.text(), pausedView.actionLabel)
                assertEquals(null, automaticUploadSettingsRow(enabled.copy(actionLabel = null)).actionLabel)
            }
        }
    }

}

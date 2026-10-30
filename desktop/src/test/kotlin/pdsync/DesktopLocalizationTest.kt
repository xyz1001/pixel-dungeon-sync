package pdsync

import java.util.Locale
import kotlin.test.*
import org.junit.jupiter.api.Test
import pdsync.i18n.DesktopMessages
import pdsync.i18n.UiLocale
import pdsync.i18n.UiText

class DesktopLocalizationTest {
 private fun inLocale(locale: Locale, check: () -> Unit) {
  val old = UiLocale.locale()
  try { UiLocale.setLocale(locale); check() } finally { UiLocale.setLocale(old) }
 }

 @Test fun catalogHasCompleteUniqueBilingualResourcesAndWorkingTemplates() {
  val messages = DesktopMessages::class.java.fields.filter { it.type == UiText::class.java }
   .map { it.get(null) as UiText }
  assertTrue(messages.isNotEmpty())
  val keys=messages.map {it.key()}.toSet()
  for(required in listOf(DesktopMessages.SELECT_A_GAME,DesktopMessages.SAVE_1_S,
   DesktopMessages.UPLOADING_SAVES,DesktopMessages.RESTORE_SAVE)) assertTrue(required.key() in keys)
  assertEquals(messages.size, messages.map { it.key() }.distinct().size)
  for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) inLocale(locale) {
   for (message in messages) {
    val rendered = message.text("raw-save-name", "7")
    assertTrue(rendered.isNotBlank(), message.key())
    assertFalse(rendered.contains("%1\$s") || rendered.contains("%2\$s"), message.key())
    if (locale == Locale.ENGLISH) assertFalse(Regex("[\\u4e00-\\u9fff]").containsMatchIn(rendered), message.key())
   }
  }
 }

 @Test fun chooserAndSingletonMessagesResolveAtUseTime() {
  inLocale(Locale.SIMPLIFIED_CHINESE) {
   assertEquals("选择游戏存档目录", NativeFolderChooser.DEFAULT_TITLE)
   assertEquals("正在启动游戏，请稍候。", GameLaunchResult.Busy.message)
  }
  inLocale(Locale.ENGLISH) {
   assertEquals("Choose game save folder", NativeFolderChooser.DEFAULT_TITLE)
   assertEquals("Starting the game. Please wait.", GameLaunchResult.Busy.message)
  }
 }

 @Test fun missingDirectoryUsesStableReasonRatherThanTranslatedText() {
  val save = DefaultSaveDiscovery("unmodified/path", false, "opaque diagnostics", DefaultSaveDiscoveryReason.MISSING)
  inLocale(Locale.ENGLISH) { assertEquals("Local saves: folder not created yet", DiscoveryCopy.directory(save)) }
  inLocale(Locale.SIMPLIFIED_CHINESE) { assertEquals("本机存档：目录尚未创建", DiscoveryCopy.directory(save)) }
 }

 @Test fun rawNamesAndPathsAreKeptAsArguments() {
  inLocale(Locale.ENGLISH) {
   val raw = "角色%名字/Save"
   assertEquals("Save $raw", DesktopMessages.SAVE_1_S.text(raw))
   assertEquals("Save location: $raw", DesktopMessages.SAVE_LOCATION_1_S.text(raw))
  }
 }
}

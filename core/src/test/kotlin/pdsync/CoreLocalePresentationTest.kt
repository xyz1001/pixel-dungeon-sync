package pdsync

import java.util.Locale
import org.junit.jupiter.api.Test
import pdsync.presentation.AppPresentation
import pdsync.presentation.HistoryPresentation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreLocalePresentationTest {
 private fun withLocale(locale: Locale, block: () -> Unit) {
  val previous = Locale.getDefault()
  try { Locale.setDefault(locale); block() } finally { Locale.setDefault(previous) }
 }

 @Test fun sharedPresentationAndCredentialMessagesResolveAtReadTime() {
  val failure = SecretStoreFailure.LOCKED
  val reason = SyncBlockReason.SAVE_NOT_STABLE
  withLocale(Locale.ENGLISH) {
   assertEquals("1 / 2  Choose game", AppPresentation.DISCOVERY_TITLE)
   assertTrue(failure.message.contains("locked"))
   assertTrue(reason.safeMessage.contains("stable"))
   assertEquals("1 game played", HistoryPresentation.plays(1))
   assertEquals("2 games played", HistoryPresentation.plays(2))
   assertEquals("1 file", OnboardingGate.localFileCountLabel(OnboardingKind.LOCAL_ONLY, 1, false))
  }
  withLocale(Locale.SIMPLIFIED_CHINESE) {
   assertEquals("1 / 2  选择游戏", AppPresentation.DISCOVERY_TITLE)
   assertTrue(failure.message.contains("锁定"))
   assertTrue(reason.safeMessage.contains("稳定"))
   assertEquals("已进行1场游戏", HistoryPresentation.plays(1))
  }
 }

 @Test fun comparisonAllowlistRecognizesBothLanguagesWithoutBroadeningNotices() {
  val messages = mutableListOf<String>()
  for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) withLocale(locale) {
   messages += NoviceCopy.comparison(null, true)
   messages += NoviceCopy.comparison(null, false)
   OnboardingKind.entries.forEach { messages += NoviceCopy.comparison(it, false) }
  }
  withLocale(Locale.ENGLISH) {
   assertTrue(messages.all(NoviceCopy::isComparisonMessage))
   assertFalse(NoviceCopy.isComparisonMessage("arbitrary notice"))
   assertFalse(NoviceCopy.isComparisonMessage("正在检查存档… extra"))
  }
 }

 @Test fun legacyAndEnglishErrorsHaveEquivalentPresentationClassification() = withLocale(Locale.ENGLISH) {
  assertEquals(NoviceCopy.feedback("存档仍在写入", "fallback"), NoviceCopy.feedback("Saves are still being written. Wait for files to stabilize and retry.", "fallback"))
  assertEquals(NoviceCopy.feedback("游戏正在运行", "fallback"), NoviceCopy.feedback("The game is running or its process status is unknown.", "fallback"))
 }
}

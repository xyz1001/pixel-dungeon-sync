package pdsync

import org.junit.jupiter.api.Test
import pdsync.i18n.UiLocale
import pdsync.i18n.UiText
import java.util.Locale
import kotlin.test.*

class LocalizationCatalogTest {
 private val catalogs = listOf("SharedMessages", "DesktopMessages", "AndroidHostMessages", "AndroidMessages", "PrepareMessages", "CoreSessionMessages", "CoreMessages", "CoreValidationMessages")
 private fun messages(): List<UiText> = catalogs.flatMap { name ->
  Class.forName("pdsync.i18n.$name").fields.filter { it.type == UiText::class.java }.map { it.get(null) as UiText }
 }
 @Test fun `all shipped messages have unique stable keys and both languages`() {
  val entries = messages()
  assertTrue(entries.size > 1000)
  assertEquals(entries.size, entries.map { it.key() }.distinct().size)
  entries.forEach {
   assertTrue(it.key().matches(Regex("[a-zA-Z][a-zA-Z0-9_.]*")), it.key())
   assertTrue(it.chinese().isNotBlank(), it.key())
   assertTrue(it.english().isNotBlank(), it.key())
   assertFalse(it.english().any { ch -> ch in '\u4e00'..'\u9fff' }, it.key())
  }
 }
 @Test fun `all templates render in Chinese English and unsupported locale fallbacks`() {
  val pattern = Regex("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])")
  try {
   for (tag in listOf("zh-CN", "zh-TW", "en-US", "fr-FR", "ar-EG")) {
    UiLocale.setLocale(Locale.forLanguageTag(tag))
    messages().forEach { resource ->
     val template = if (tag.startsWith("zh")) resource.chinese() else resource.english()
     val arguments = mutableMapOf<Int, Any>()
     var next = 0
     for (match in pattern.findAll(template)) {
      val conversion = match.groupValues[2].lowercase()
      if (conversion == "%" || conversion == "n") continue
      val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: next++
      arguments[index] = when (conversion) {
       "d", "o", "x" -> 7
       "e", "f", "g", "a" -> 1.25
       "c" -> 'A'
       "b" -> true
       else -> "Original_存档_%s"
      }
     }
     val args = (0..(arguments.keys.maxOrNull() ?: -1)).map { arguments[it] ?: "unused" }.toTypedArray()
     val expected = if (args.isEmpty()) template else String.format(UiLocale.formattingLocale(), template, *args)
     assertEquals(expected, resource.text(*args), "${resource.key()} / $tag")
    }
   }
  } finally { UiLocale.useSystemLocale() }
 }
 @Test fun `locale selection follows system until platform configuration overrides it`() {
  val original = Locale.getDefault()
  try {
   UiLocale.useSystemLocale()
   Locale.setDefault(Locale.JAPANESE)
   assertFalse(UiLocale.isChinese())
   Locale.setDefault(Locale.TRADITIONAL_CHINESE)
   assertTrue(UiLocale.isChinese())
   UiLocale.setLocale(Locale.ENGLISH)
   assertFalse(UiLocale.isChinese())
   UiLocale.useSystemLocale()
   assertTrue(UiLocale.isChinese())
  } finally { Locale.setDefault(original); UiLocale.useSystemLocale() }
 }
 @Test fun `mismatched numbered parameter types are rejected`() {
  assertFailsWith<IllegalArgumentException> { UiText("test.invalid", "%1\$s", "%1\$d") }
  assertFailsWith<IllegalArgumentException> { UiText("test.invalid", "%1\$s", "%2\$s") }
 }
}

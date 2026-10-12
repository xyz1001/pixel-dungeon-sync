package pdsync

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test
import kotlin.test.*

class SafeDavXmlTest {
 @Test fun validUtf8AndUtf16Documents() {
  val xml = "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response/></d:multistatus>"
  for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
   assertEquals("DAV:", SafeDavXml.parse(xml.toByteArray(charset)).documentElement.namespaceURI)
  }
 }
 @Test fun doctypeAndExternalEntitiesAlwaysReject() {
  listOf("<!DOCTYPE x><x/>", "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///never-read'>]><x>&e;</x>", "<!ENTITY e 'a'><x/>").forEach { xml ->
   assertFailsWith<IllegalArgumentException> { SafeDavXml.parse(xml.encodeToByteArray()) }
  }
 }
 @Test fun boundedDepthElementsAndEncodedInput() {
  assertFails { SafeDavXml.parse(("<x>".repeat(65) + "</x>".repeat(65)).encodeToByteArray()) }
  assertFails { SafeDavXml.parse(("<x>" + "<y/>".repeat(50_000) + "</x>").encodeToByteArray()) }
  assertFails { SafeDavXml.parse(byteArrayOf(0, 0, -2, -1, 0, 0, 0, 60)) }
  assertFails { SafeDavXml.parse(byteArrayOf(-61, 40)) }
  assertFails { SafeDavXml.parse(ByteArray(2 * 1024 * 1024 + 1)) }
 }
 @Test fun commentsCdataAndQuotesDoNotSpoofStructure() {
  assertEquals("x", SafeDavXml.parse("<x a='>'><!-- <bogus> --><![CDATA[<bogus>]]></x>".encodeToByteArray()).documentElement.nodeName)
  listOf("<x><!bad></x>", "<x a='<y/>'/>", "<x>", "</x>", "<!--", "<?", "<x><![CDATA[").forEach { assertFails { SafeDavXml.parse(it.encodeToByteArray()) } }
 }
 @Test fun platformUnsupportedFeaturesStillUseIndependentPreflight() {
  val delegate = DocumentBuilderFactory.newInstance()
  val factory = object : DocumentBuilderFactory() {
   override fun newDocumentBuilder() = delegate.newDocumentBuilder()
   override fun setAttribute(name: String, value: Any) { throw IllegalArgumentException() }
   override fun getAttribute(name: String): Any = throw IllegalArgumentException()
   override fun setFeature(name: String, value: Boolean) { throw javax.xml.parsers.ParserConfigurationException() }
   override fun getFeature(name: String): Boolean = false
  }
  assertEquals("x", SafeDavXml.parse("<x/>".encodeToByteArray(), factory).documentElement.nodeName)
  assertFails { SafeDavXml.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///never-read'>]><x>&e;</x>".encodeToByteArray(), factory) }
 }
}

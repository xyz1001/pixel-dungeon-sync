package pdsync

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** Parser-independent bounds and declaration rejection protect runtimes with partial JAXP support. */
object SafeDavXml {
 fun parse(bytes: ByteArray, factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()): Document = try {
  require(bytes.size <= 2 * 1024 * 1024) { "XML too large" }
  val text = decode(bytes)
  preflight(text)
  factory.isNamespaceAware = true
  factory.isValidating = false
  factory.isExpandEntityReferences = false
  runCatching { factory.isXIncludeAware = false }
  listOf(
   "http://javax.xml.XMLConstants/feature/secure-processing" to true,
   "http://apache.org/xml/features/disallow-doctype-decl" to true,
   "http://xml.org/sax/features/external-general-entities" to false,
   "http://xml.org/sax/features/external-parameter-entities" to false
  ).forEach { (name, value) -> runCatching { factory.setFeature(name, value) } }
  listOf(
   "http://javax.xml.XMLConstants/property/accessExternalDTD" to "",
   "http://javax.xml.XMLConstants/property/accessExternalSchema" to "",
   "http://www.oracle.com/xml/jaxp/properties/maxElementDepth" to 64
  ).forEach { (name, value) -> runCatching { factory.setAttribute(name, value) } }
  factory.newDocumentBuilder().apply {
   setEntityResolver { _, _ -> throw SAXException("External XML resources are forbidden") }
   setErrorHandler(object : DefaultHandler() {
    override fun error(e: SAXParseException) { throw e }
    override fun fatalError(e: SAXParseException) { throw e }
   })
  }.parse(InputSource(StringReader(text)))
 } catch (_: Exception) { throw IllegalArgumentException("Invalid or unsafe WebDAV listing XML") }

 private fun decode(bytes: ByteArray): String {
  fun prefix(vararg expected: Int) = bytes.size >= expected.size && expected.indices.all { (bytes[it].toInt() and 255) == expected[it] }
  require(!prefix(0, 0, 254, 255) && !prefix(255, 254, 0, 0)) { "UTF-32 is not supported" }
  val (charset, offset) = when {
   prefix(239, 187, 191) -> Charsets.UTF_8 to 3
   prefix(254, 255) -> Charsets.UTF_16BE to 2
   prefix(255, 254) -> Charsets.UTF_16LE to 2
   prefix(0, 60, 0, 63) -> Charsets.UTF_16BE to 0
   prefix(60, 0, 63, 0) -> Charsets.UTF_16LE to 0
   else -> Charsets.UTF_8 to 0
  }
  return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
   .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString().also { require('\u0000' !in it) }
 }

 internal fun preflight(text: String) {
  var position = 0; var depth = 0; var elements = 0
  while (position < text.length) {
   val start = text.indexOf('<', position)
   if (start < 0) break
   when {
    text.startsWith("<!--", start) -> { val end = text.indexOf("-->", start + 4); require(end >= 0); position = end + 3 }
    text.startsWith("<![CDATA[", start) -> { val end = text.indexOf("]]>", start + 9); require(end >= 0); position = end + 3 }
    text.startsWith("<?", start) -> { val end = text.indexOf("?>", start + 2); require(end >= 0); position = end + 2 }
    text.startsWith("<!", start) -> throw IllegalArgumentException("XML declarations are forbidden")
    else -> {
     var end = start + 1; var quote = '\u0000'
     while (end < text.length) {
      val ch = text[end]
      if (quote == '\u0000') {
       if (ch == '\'' || ch == '"') quote = ch
       else if (ch == '>') break
      } else if (ch == quote) quote = '\u0000'
      require(ch != '<'); end++
     }
     require(end < text.length && quote == '\u0000')
     if (text.startsWith("</", start)) { depth--; require(depth >= 0) }
     else { elements++; require(elements <= 50_000); require(depth + 1 <= 64); if (text[end - 1] != '/') depth++ }
     position = end + 1
    }
   }
  }
  require(depth == 0 && elements > 0)
 }
}

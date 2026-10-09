package pdsync

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Append-only names in an existing WebDAV collection. No shared head is written, and no ETag,
 * conditional PUT, LOCK or MOVE is required. AppendSync owns collision checks and readback.
 *
 * RFC 4918 has no standard listing pagination. Normally a Depth:1 207 is the complete listing;
 * an explicit RFC 8288 Link rel="next" is supported only for this exact collection and origin.
 * Its query is an opaque cursor, never attached to object requests or error messages. Callers
 * MUST follow every returned cursor, detect cycles, and bound total pages/commits explicitly.
 * XML paging extensions, errors and oversized pages fail closed. A server that silently omits
 * entries without a pagination/error indication cannot be detected by any WebDAV client.
 */
class AppendOnlyWebDav(
 base: String, game: String, profile: String, username: String, password: String,
 client: OkHttpClient = OkHttpClient()
): AppendStore {
 private val client = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false)
  .followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()
 private val collection: HttpUrl
 private val auth: String

 init {
  require(game.matches(SEGMENT) && profile.matches(SEGMENT)) { "Invalid game or profile" }
  require(!username.contains(':') && username.none { it.isISOControl() } && password.none { it.isISOControl() }) { "Invalid credentials" }
  val url = try { base.toHttpUrl() } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid WebDAV URL") }
  require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "URL must not contain credentials, query or fragment" }
  require(url.scheme == "https" || (url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost", "::1"))) { "HTTPS required except exact loopback hosts" }
  collection = url.newBuilder().apply { if (url.pathSegments.last().isNotEmpty()) addPathSegment("") }
   .addPathSegment(game).addPathSegment(profile).addPathSegment("").build()
  auth = Credentials.basic(username, password)
 }

 override fun readCommit(id: String): ByteArray? = get(fileUrl(commitName(id)), MAX_MANIFEST_BYTES)
 override fun readObject(id: String): ByteArray? = get(fileUrl(objectName(id)), MAX_SNAPSHOT_BYTES)
 override fun legacyPresent(): Boolean = get(fileUrl("head.json"), MAX_MANIFEST_BYTES) != null
 override fun writeCommit(id: String, bytes: ByteArray) = put(fileUrl(commitName(id)), bytes, MAX_MANIFEST_BYTES, "application/json")
 override fun writeObject(id: String, bytes: ByteArray) = put(fileUrl(objectName(id)), bytes, MAX_SNAPSHOT_BYTES, "application/zip")

 override fun listCommits(cursor: String?): CommitPage {
  val url = if (cursor == null) collection else listingUrl(cursor)
  val request = request(url).header("Depth", "1").method("PROPFIND", PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
  return execute(request) { response ->
   if (response.code != 207) throw WebDavException(response.code)
   // Content-Range means this is not a complete response to our Depth:1 request.
   require(response.header("Content-Range") == null) { "Partial WebDAV listing is unsupported" }
   val next = nextLink(response, url)
   require(next == null || next != url.toString()) { "Cyclic WebDAV listing cursor" }
   CommitPage(parseListing(readBounded(response, MAX_LISTING_BYTES)), next)
  }
 }

 private fun get(url: HttpUrl, limit: Int): ByteArray? = execute(request(url).get().build()) { response ->
  when (response.code) {
   404 -> null
   200 -> readBounded(response, limit)
   else -> throw WebDavException(response.code)
  }
 }

 private fun put(url: HttpUrl, bytes: ByteArray, limit: Int, mediaType: String) {
  require(bytes.size <= limit) { "Upload exceeds size limit" }
  execute(request(url).put(bytes.toRequestBody(mediaType.toMediaType())).build()) { response ->
   if (response.code !in setOf(200, 201, 204)) throw WebDavException(response.code)
   readBounded(response, MAX_MANIFEST_BYTES)
  }
 }

 private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", auth).header("Cache-Control", "no-cache, no-store")
 private fun <T> execute(request: Request, block: (Response) -> T): T = try {
  client.newCall(request).execute().use(block)
 } catch (e: WebDavException) { throw e }
 catch (_: IOException) { throw IOException("WebDAV network request failed") }

 private fun readBounded(response: Response, limit: Int): ByteArray {
  val body = response.body ?: throw IOException("WebDAV response body missing")
  require(body.contentLength() <= limit) { "Remote response exceeds size limit" }
  return body.byteStream().readNBytes(limit + 1).also { require(it.size <= limit) { "Remote response exceeds size limit" } }
 }

 private fun fileUrl(name: String) = collection.newBuilder().addPathSegment(name).build()
 private fun commitName(id: String): String { validateId(id); return "pdsync-v2-commit-$id.json" }
 private fun objectName(id: String): String { validateId(id); return "pdsync-v2-object-$id.zip" }
 private fun validateId(id: String) = require(validCommitId(id)) { "Invalid append-only identifier" }

 /** Validate before HttpUrl can normalize traversal, backslashes or URL credentials away. */
 private fun checkedUrl(value: String, allowQuery: Boolean, reference: HttpUrl = collection): HttpUrl {
  require(value.isNotEmpty() && value.length <= MAX_HREF_LENGTH && value.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) { "Unsafe WebDAV href" }
  val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Malformed WebDAV href") }
  require(!uri.isOpaque && uri.rawUserInfo == null && uri.rawFragment == null && (allowQuery || uri.rawQuery == null)) { "Unsafe WebDAV href" }
  // Refuse percent-encoded traversal/separators and ambiguous double-encoding, even on siblings.
  val rawPath = uri.rawPath ?: ""
  require(rawPath.split('/').none { raw ->
   val decoded = try { java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8) } catch (_: Exception) { throw IllegalArgumentException("Malformed WebDAV href") }
   decoded == "." || decoded == ".." || decoded.any { it == '/' || it == '\\' || it == '%' || it.isISOControl() }
  }) { "Unsafe WebDAV href" }
  val result = reference.resolve(value) ?: throw IllegalArgumentException("Malformed WebDAV href")
  require(result.scheme == collection.scheme && result.host == collection.host && result.port == collection.port &&
   result.username.isEmpty() && result.password.isEmpty() && result.fragment == null) { "Cross-origin or unsafe WebDAV href" }
  return result
 }

 private fun listingUrl(value: String, reference: HttpUrl = collection): HttpUrl = checkedUrl(value, true, reference).also {
  require(it.encodedPath == collection.encodedPath) { "WebDAV continuation must use the same collection" }
 }

 private fun parseListing(bytes: ByteArray): List<String> {
  val document = try {
   val factory = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true; isXIncludeAware = false; isExpandEntityReferences = false
    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", 64)
   }
   factory.newDocumentBuilder().apply {
    setErrorHandler(object: DefaultHandler() {
     override fun error(e: SAXParseException) { throw e }
     override fun fatalError(e: SAXParseException) { throw e }
    })
   }.parse(bytes.inputStream())
  } catch (_: Exception) { throw IllegalArgumentException("Invalid or unsafe WebDAV listing XML") }
  val root = document.documentElement
  require(root.isDav("multistatus")) { "Expected DAV multistatus listing" }
  require((0 until root.attributes.length).all { index ->
   val attribute = root.attributes.item(index)
   attribute.namespaceURI == XMLConstants.XMLNS_ATTRIBUTE_NS_URI || (attribute.namespaceURI == XMLConstants.XML_NS_URI && attribute.localName == "lang")
  }) { "Unsupported WebDAV listing attributes" }
  val entries = root.elements()
  require(entries.size <= MAX_LISTING_RESPONSES + 1 && entries.count { it.isDav("response") } <= MAX_LISTING_RESPONSES) { "WebDAV listing entry limit exceeded" }
  require(entries.all { it.isDav("response") || it.isDav("responsedescription") }) { "Unsupported or incomplete WebDAV listing extension" }
  val ids = linkedSetOf<String>()
  val paths = mutableSetOf<String>()
  for (entry in entries.filter { it.isDav("response") }) {
   val children = entry.elements()
   require(children.all { it.namespaceURI == DAV && it.localName in setOf("href", "status", "propstat", "responsedescription") }) { "Unsupported or failed WebDAV listing entry" }
   val href = children.singleOrNull { it.isDav("href") } ?: throw IllegalArgumentException("Missing or ambiguous WebDAV href")
   require(href.elements().isEmpty()) { "Malformed WebDAV href" }
   val url = checkedUrl(href.textContent.trim(), false)
   val path = url.encodedPath
   require(paths.add(path)) { "Duplicate WebDAV listing entry" }
   val own = path == collection.encodedPath || path == collection.encodedPath.removeSuffix("/")
   val relative = if (path.startsWith(collection.encodedPath)) path.removePrefix(collection.encodedPath) else null
   require(own || (relative != null && relative.isNotEmpty() && '/' !in relative.removeSuffix("/"))) { "WebDAV href is outside the requested collection" }
   val statuses = children.filter { it.isDav("status") } + children.filter { it.isDav("propstat") }.map { propstat ->
    val properties = propstat.elements()
    require(properties.all { it.namespaceURI == DAV && it.localName in setOf("prop", "status", "responsedescription") }) { "Failed WebDAV property listing" }
    properties.singleOrNull { it.isDav("status") } ?: throw IllegalArgumentException("Missing WebDAV property status")
   }
   require(statuses.isNotEmpty()) { "Missing WebDAV listing status" }
   statuses.forEach { status ->
    val code = Regex("HTTP/\\d+(?:\\.\\d+)? ([0-9]{3})(?: .*)?").matchEntire(status.textContent.trim())?.groupValues?.get(1)?.toInt()
     ?: throw IllegalArgumentException("Malformed WebDAV listing status")
    if (code !in 200..299) throw WebDavException(code)
   }
   if (own) continue
   val name = url.pathSegments.last()
   if (name.startsWith(COMMIT_PREFIX)) {
    require(!path.endsWith('/') && name.endsWith(".json") && relative == name) { "Malformed append-only commit filename" }
    val id = name.removePrefix(COMMIT_PREFIX).removeSuffix(".json")
    validateId(id)
    require(entry.getElementsByTagNameNS(DAV, "collection").length == 0) { "Commit is a collection" }
    require(ids.add(id)) { "Duplicate commit identifier" }
   } else if (relative!!.startsWith(COMMIT_PREFIX)) {
    throw IllegalArgumentException("Malformed append-only commit filename")
   }
  }
  return ids.sorted()
 }

 private fun nextLink(response: Response, reference: HttpUrl): String? {
  val headers = response.headers.values("Link")
  require(headers.sumOf { it.length } <= MAX_HREF_LENGTH * 4) { "WebDAV pagination header too large" }
  val next = mutableListOf<String>()
  for (header in headers) for (link in splitLinks(header)) {
   val match = Regex("^\\s*<([^<>]+)>((?:\\s*;.*)?)\\s*$").matchEntire(link) ?: throw IllegalArgumentException("Malformed WebDAV pagination header")
   val params = parseLinkParameters(match.groupValues[2])
   val rel = params["rel"]?.split(Regex("\\s+")) ?: emptyList()
   if ("next" in rel) {
    require("anchor" !in params) { "Anchored WebDAV pagination is unsupported" }
    next += listingUrl(match.groupValues[1], reference).toString()
   }
  }
  require(next.size <= 1) { "Ambiguous WebDAV continuation" }
  return next.singleOrNull()
 }

 private fun splitLinks(value: String): List<String> {
  val parts = mutableListOf<String>(); var start = 0; var quoted = false; var angle = false; var escaped = false
  value.forEachIndexed { i, c ->
   if (escaped) escaped = false
   else when {
    quoted && c == '\\' -> escaped = true
    c == '"' && !angle -> quoted = !quoted
    c == '<' && !quoted -> { require(!angle) { "Malformed WebDAV pagination header" }; angle = true }
    c == '>' && !quoted -> { require(angle) { "Malformed WebDAV pagination header" }; angle = false }
    c == ',' && !quoted && !angle -> { parts += value.substring(start, i); start = i + 1 }
   }
  }
  require(!quoted && !angle && !escaped) { "Malformed WebDAV pagination header" }
  parts += value.substring(start)
  return parts
 }

 private fun parseLinkParameters(value: String): Map<String, String> {
  val result = mutableMapOf<String, String>(); var remaining = value.trim()
  val parameter = Regex("""^;\s*([A-Za-z0-9!#$%&'*+.^_`|~-]+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([A-Za-z0-9!#$%&'*+.^_`|~:/-]+))\s*""")
  while (remaining.isNotEmpty()) {
   val match = parameter.find(remaining) ?: throw IllegalArgumentException("Malformed WebDAV pagination parameters")
   val name = match.groupValues[1].lowercase(java.util.Locale.ROOT)
   require(name !in result) { "Ambiguous WebDAV pagination parameters" }
   result[name] = match.groups[2]?.value?.replace(Regex("\\\\(.)"), "$1") ?: match.groupValues[3]
   remaining = remaining.substring(match.range.last + 1)
  }
  return result
 }

 private fun Element.isDav(name: String) = namespaceURI == DAV && localName == name
 private fun Element.elements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

 companion object {
  private const val DAV = "DAV:"
  private const val COMMIT_PREFIX = "pdsync-v2-commit-"
  private const val MAX_LISTING_BYTES = 2 * 1024 * 1024
  private const val MAX_LISTING_RESPONSES = 10_000
  private const val MAX_HREF_LENGTH = 8192
  private val SEGMENT = Regex("[a-zA-Z0-9_-]+")
  private const val PROPFIND = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
 }
}

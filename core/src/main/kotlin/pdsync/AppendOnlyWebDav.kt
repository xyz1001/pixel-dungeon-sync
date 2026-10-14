package pdsync

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants

/** Safe, non-secret reasons for explicit namespace setup failures. */
enum class NamespaceFailureReason {
 ROOT_MISSING, NOT_COLLECTION, AUTHENTICATION, FORBIDDEN, PARENT_MISSING,
 HTTP_ERROR, UNSAFE_RESPONSE, VERIFICATION_FAILED, NETWORK, CANCELLED
}

class NamespaceProvisioningException(
 val reason: NamespaceFailureReason, val statusCode: Int? = null
): IOException("WebDAV namespace setup failed: $reason" + (statusCode?.let { " (HTTP $it)" } ?: ""))

/**
 * Append-only names in a WebDAV collection. Namespace creation is an explicit separate operation.
 * No shared head is written, and no ETag, conditional PUT, LOCK or MOVE is required. AppendSync owns collision checks and readback.
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
 // OkHttp can repeat HTTP 503 + Retry-After:0 even with retryOnConnectionFailure(false).
 // Stop namespace error statuses before its follow-up interceptor can replay a mutation.
 private val namespaceClient = this.client.newBuilder().addNetworkInterceptor { chain ->
  val response = chain.proceed(chain.request())
  val accepted = if (chain.request().method == "MKCOL") setOf(201, 405) else setOf(207, 404)
  if (response.code !in accepted) {
   val error = namespaceHttpError(response.code)
   response.close()
   throw error
  }
  response
 }.build()
 private val baseCollection: HttpUrl
 private val gameCollection: HttpUrl
 private val collection: HttpUrl
 private val auth: String

 init {
  require(game.matches(SEGMENT) && profile.matches(SEGMENT)) { "Invalid game or profile" }
  require(!username.contains(':') && username.none { it.isISOControl() } && password.none { it.isISOControl() }) { "Invalid credentials" }
  // Validate the raw input first: HttpUrl silently normalizes dot segments and backslashes.
  val rawBase = checkedUri(base, false)
  require(rawBase.isAbsolute && rawBase.rawAuthority != null && "//" !in (rawBase.rawPath ?: "")) { "Invalid WebDAV URL" }
  val url = try { base.toHttpUrl() } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid WebDAV URL") }
  require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "URL must not contain credentials, query or fragment" }
  require(url.scheme == "https" || (url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost", "::1"))) { "HTTPS required except exact loopback hosts" }
  baseCollection = url.newBuilder().apply { if (url.pathSegments.last().isNotEmpty()) addPathSegment("") }.build()
  gameCollection = baseCollection.newBuilder().addPathSegment(game).addPathSegment("").build()
  collection = gameCollection.newBuilder().addPathSegment(profile).addPathSegment("").build()
  // Named-account registration and the DAV gate both use UTF-8 credentials.
  // OkHttp's default ISO-8859-1 encoding silently replaces many valid passwords.
  auth = Credentials.basic(username, password, StandardCharsets.UTF_8)
 }

 /**
  * Only an explicit Connect/Create action may call this. The configured DAV root must already
  * exist. Never walk URL parents, create that root, or assume MKCOL succeeded from its status.
  * Each invocation makes at most seven requests: one root check and three per app-owned child.
  * Existing append reads/writes never call this method or gain implicit creation side effects.
  */
 fun ensureNamespace(shouldContinue: () -> Boolean = { true }) {
  when (inspectCollection(baseCollection, shouldContinue)) {
   CollectionState.MISSING -> throw NamespaceProvisioningException(NamespaceFailureReason.ROOT_MISSING, 404)
   CollectionState.NOT_COLLECTION -> throw NamespaceProvisioningException(NamespaceFailureReason.NOT_COLLECTION)
   CollectionState.COLLECTION -> Unit
  }
  // These are the only writable paths, constructed from validated single path segments.
  for (child in listOf(gameCollection, collection)) {
   when (inspectCollection(child, shouldContinue)) {
    CollectionState.COLLECTION -> continue
    CollectionState.NOT_COLLECTION -> throw NamespaceProvisioningException(NamespaceFailureReason.NOT_COLLECTION)
    CollectionState.MISSING -> Unit
   }
   createAndVerifyCollection(child, shouldContinue)
  }
 }

 private enum class CollectionState { MISSING, COLLECTION, NOT_COLLECTION }

 private fun checkNamespaceActive(shouldContinue: () -> Boolean) {
  if (!shouldContinue()) throw NamespaceProvisioningException(NamespaceFailureReason.CANCELLED)
 }

 private fun inspectCollection(url: HttpUrl, shouldContinue: () -> Boolean): CollectionState = try {
  checkNamespaceActive(shouldContinue)
  val check = request(url).header("Depth", "0")
   .method("PROPFIND", PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
  execute(check, namespaceClient) { response ->
   when (response.code) {
    404 -> CollectionState.MISSING
    207 -> {
     // A Depth:0 proof must be exactly one complete response for this exact collection.
     require(response.header("Content-Range") == null && response.headers.values("Link").isEmpty()) { "Partial namespace verification" }
     parseCollection(readBounded(response, MAX_LISTING_BYTES), url)
    }
    else -> throw namespaceHttpError(response.code)
   }
  }
 } catch (e: NamespaceProvisioningException) { throw e }
 catch (_: IllegalArgumentException) { throw NamespaceProvisioningException(NamespaceFailureReason.UNSAFE_RESPONSE) }
 catch (_: IOException) { throw NamespaceProvisioningException(NamespaceFailureReason.NETWORK) }

 private fun createAndVerifyCollection(url: HttpUrl, shouldContinue: () -> Boolean) {
  // A connection loss may follow an accepted write. Never retry MKCOL blindly; read once.
  // 405 is also only a hint that another client might have created this collection.
  try {
   checkNamespaceActive(shouldContinue)
   execute(request(url).method("MKCOL", ByteArray(0).toRequestBody()).build(), namespaceClient) { response ->
    if (response.code != 201 && response.code != 405) throw namespaceHttpError(response.code)
   }
  } catch (e: NamespaceProvisioningException) { throw e }
  catch (_: IOException) { /* Resolve an uncertain accepted write with the same bounded readback. */ }
  when (inspectCollection(url, shouldContinue)) {
   CollectionState.COLLECTION -> Unit
   CollectionState.NOT_COLLECTION -> throw NamespaceProvisioningException(NamespaceFailureReason.NOT_COLLECTION)
   CollectionState.MISSING -> throw NamespaceProvisioningException(NamespaceFailureReason.VERIFICATION_FAILED, 404)
  }
 }

 private fun namespaceHttpError(code: Int) = NamespaceProvisioningException(when (code) {
  401 -> NamespaceFailureReason.AUTHENTICATION
  403 -> NamespaceFailureReason.FORBIDDEN
  409 -> NamespaceFailureReason.PARENT_MISSING
  else -> NamespaceFailureReason.HTTP_ERROR
 }, code)

 private fun parseCollection(bytes: ByteArray, expected: HttpUrl): CollectionState {
  val root = parseXml(bytes).documentElement
  validateMultistatusRoot(root)
  val entries = root.elements()
  require(entries.all { it.isDav("response") || it.isDav("responsedescription") } &&
   entries.count { it.isDav("response") } == 1 && entries.count { it.isDav("responsedescription") } <= 1) { "Ambiguous namespace response" }
  val children = entries.single { it.isDav("response") }.elements()
  require(children.all { it.namespaceURI == DAV && it.localName in setOf("href", "status", "propstat", "responsedescription") }) { "Unsupported namespace response" }
  val href = children.singleOrNull { it.isDav("href") } ?: throw IllegalArgumentException("Missing or ambiguous namespace href")
  require(href.elements().isEmpty()) { "Malformed namespace href" }
  val actual = checkedUrl(href.textContent.trim(), false, expected)
  require(actual.encodedPath == expected.encodedPath || actual.encodedPath == expected.encodedPath.removeSuffix("/")) { "Unexpected namespace href" }
  val status = children.filter { it.isDav("status") }
  val properties = children.filter { it.isDav("propstat") }
  require(status.size <= 1) { "Ambiguous namespace status" }
  if (status.isNotEmpty()) {
   val code = parseStatus(status.single())
   // Only a resource-level 404 proves absence. A missing resourcetype property does not.
   if (code == 404 && properties.isEmpty()) return CollectionState.MISSING
   if (code !in 200..299) throw namespaceHttpError(code)
  }
  require(properties.isNotEmpty()) { "Missing namespace resource type" }
  val resourceTypes = mutableListOf<Element>()
  for (propstat in properties) {
   val fields = propstat.elements()
   require(fields.all { it.namespaceURI == DAV && it.localName in setOf("prop", "status", "responsedescription") }) { "Unsupported namespace properties" }
   val propStatus = fields.singleOrNull { it.isDav("status") } ?: throw IllegalArgumentException("Missing namespace property status")
   val code = parseStatus(propStatus)
   if (code !in 200..299) throw namespaceHttpError(code)
   val prop = fields.singleOrNull { it.isDav("prop") } ?: throw IllegalArgumentException("Missing namespace properties")
   // Do not infer a collection from a nested or unrelated DAV:collection element.
   require(prop.elements().all { it.isDav("resourcetype") }) { "Unexpected namespace property" }
   resourceTypes += prop.elements()
  }
  require(resourceTypes.size == 1) { "Missing or ambiguous namespace resource type" }
  val types = resourceTypes.single().elements()
  require(types.size <= 1 && types.all { it.isDav("collection") && it.elements().isEmpty() }) { "Unsupported namespace resource type" }
  return if (types.size == 1) CollectionState.COLLECTION else CollectionState.NOT_COLLECTION
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
 private fun <T> execute(request: Request, requestClient: OkHttpClient = client, block: (Response) -> T): T = try {
  requestClient.newCall(request).execute().use { response ->
   // The recorder accepts only a closed method enum and status, never URL, headers or payload.
   DiagnosticLog.http(diagnosticMethod(request.method), response.code)
   block(response)
  }
 } catch (e: NamespaceProvisioningException) {
  // The namespace interceptor rejects bad statuses before execute can expose its response.
  e.statusCode?.let { DiagnosticLog.http(diagnosticMethod(request.method), it) }
  throw e
 }
 catch (e: WebDavException) { throw e }
 catch (error: IOException) {
  DiagnosticLog.networkFailure(error)
  // Diagnostic only: retain a plain IOException so callers' retry classification is unchanged.
  // Never copy transport text, causes or suppressed exceptions, which can contain credentials/URLs.
  val category = when (error) {
   is java.net.SocketTimeoutException -> "TIMEOUT"
   is java.net.UnknownHostException -> "DNS"
   is java.net.ConnectException -> "CONNECT"
   is javax.net.ssl.SSLException -> "TLS"
   is java.io.EOFException -> "EOF"
   is java.net.SocketException -> "CONNECTION"
   // InterruptedIOException alone does not prove a timeout or a user cancellation.
   is java.io.InterruptedIOException -> "INTERRUPTED"
   else -> "IO"
  }
  throw IOException("WebDAV network request failed [$category]")
 }

 private fun diagnosticMethod(method: String): DiagnosticHttpMethod = when (method) {
  "GET" -> DiagnosticHttpMethod.GET
  "PUT" -> DiagnosticHttpMethod.PUT
  "PROPFIND" -> DiagnosticHttpMethod.PROPFIND
  "MKCOL" -> DiagnosticHttpMethod.MKCOL
  "HEAD" -> DiagnosticHttpMethod.HEAD
  "POST" -> DiagnosticHttpMethod.POST
  else -> DiagnosticHttpMethod.OTHER
 }

 private fun readBounded(response: Response, limit: Int): ByteArray {
  val body = response.body ?: throw IOException("WebDAV response body missing")
  require(body.contentLength() <= limit) { "Remote response exceeds size limit" }
  return body.byteStream().readUpTo(limit + 1).also { require(it.size <= limit) { "Remote response exceeds size limit" } }
 }

 private fun fileUrl(name: String) = collection.newBuilder().addPathSegment(name).build()
 private fun commitName(id: String): String { validateId(id); return "pdsync-v2-commit-$id.json" }
 private fun objectName(id: String): String { validateId(id); return "pdsync-v2-object-$id.zip" }
 private fun validateId(id: String) = require(validCommitId(id)) { "Invalid append-only identifier" }

 /** Validate before HttpUrl can normalize traversal, backslashes or URL credentials away. */
 private fun checkedUri(value: String, allowQuery: Boolean): URI {
  require(value.isNotEmpty() && value.length <= MAX_HREF_LENGTH && value.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) { "Unsafe WebDAV href" }
  val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Malformed WebDAV href") }
  require(!uri.isOpaque && uri.rawUserInfo == null && uri.rawFragment == null && (allowQuery || uri.rawQuery == null)) { "Unsafe WebDAV href" }
  // Refuse percent-encoded traversal/separators and ambiguous double-encoding, even on siblings.
  val rawPath = uri.rawPath ?: ""
  require(rawPath.split('/').none { raw ->
   val decoded = try { java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name()) } catch (_: Exception) { throw IllegalArgumentException("Malformed WebDAV href") }
   decoded == "." || decoded == ".." || decoded.any { it == '/' || it == '\\' || it == '%' || it.isISOControl() }
  }) { "Unsafe WebDAV href" }
  return uri
 }

 private fun checkedUrl(value: String, allowQuery: Boolean, reference: HttpUrl = collection): HttpUrl {
  checkedUri(value, allowQuery)
  val result = reference.resolve(value) ?: throw IllegalArgumentException("Malformed WebDAV href")
  require(result.scheme == collection.scheme && result.host == collection.host && result.port == collection.port &&
   result.username.isEmpty() && result.password.isEmpty() && result.fragment == null) { "Cross-origin or unsafe WebDAV href" }
  return result
 }

 private fun listingUrl(value: String, reference: HttpUrl = collection): HttpUrl = checkedUrl(value, true, reference).also {
  require(it.encodedPath == collection.encodedPath) { "WebDAV continuation must use the same collection" }
 }

 private fun parseXml(bytes: ByteArray): Document = SafeDavXml.parse(bytes)

 private fun validateMultistatusRoot(root: Element) {
  require(root.isDav("multistatus")) { "Expected DAV multistatus listing" }
  require((0 until root.attributes.length).all { index ->
   val attribute = root.attributes.item(index)
   attribute.namespaceURI == XMLConstants.XMLNS_ATTRIBUTE_NS_URI || (attribute.namespaceURI == XMLConstants.XML_NS_URI && attribute.localName == "lang")
  }) { "Unsupported WebDAV listing attributes" }
 }

 private fun parseStatus(status: Element): Int {
  require(status.elements().isEmpty()) { "Malformed WebDAV status" }
  return Regex("HTTP/\\d+(?:\\.\\d+)? ([0-9]{3})(?: .*)?").matchEntire(status.textContent.trim())?.groupValues?.get(1)?.toInt()
   ?: throw IllegalArgumentException("Malformed WebDAV listing status")
 }

 private fun parseListing(bytes: ByteArray): List<String> {
  val root = parseXml(bytes).documentElement
  validateMultistatusRoot(root)
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

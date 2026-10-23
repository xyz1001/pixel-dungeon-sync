package pdsync

import okio.buffer

import pdsync.i18n.CoreValidationMessages

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
import java.nio.file.Path
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
 * No shared head is written. Hash records require create-only conditional PUT; UUID records are read-only.
 * AppendSync owns collision validation and readback; no LOCK or MOVE is used.
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
 client: OkHttpClient = OkHttpClient(),
 private val beforeRequest: WebDavRequestCheck = WebDavRequestCheck {}
): AppendStore {
 private val diagnosticClientId = nextDiagnosticClientId.incrementAndGet()
 private val client = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false)
  .followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS)
  .addNetworkInterceptor { chain -> beforeRequest.check(); chain.proceed(chain.request()) }.build()
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
 // Reject failed mutations before OkHttp's retry/follow-up interceptor can replay them.
 // A collision is returned to AppendSync for bounded fresh readback and content verification.
 private val writeClient = this.client.newBuilder().addNetworkInterceptor { chain ->
  val response = chain.proceed(chain.request())
  if (response.code !in setOf(200, 201, 204) && !(chain.request().method=="DELETE" && response.code==404)) {
   val error = WebDavException(response.code)
   response.close()
   throw error
  }
  response
 }.build()
 private val baseCollection: HttpUrl
 private val gameCollection: HttpUrl
 private val collection: HttpUrl
 private val auth: String
 private val readScopes = ThreadLocal<OperationObjectReadCache>()
 private val freshReadDepth = ThreadLocal<Int>()

 init {
  require(game.matches(SEGMENT) && profile.matches(SEGMENT)) { CoreValidationMessages.INVALID_GAME_OR_PROFILE.text() }
  require(!username.contains(':') && username.none { it.isISOControl() } && password.none { it.isISOControl() }) { CoreValidationMessages.INVALID_CREDENTIALS.text() }
  // Validate the raw input first: HttpUrl silently normalizes dot segments and backslashes.
  val rawBase = checkedUri(base, false)
  require(rawBase.isAbsolute && rawBase.rawAuthority != null && "//" !in (rawBase.rawPath ?: "")) { CoreValidationMessages.INVALID_WEBDAV_URL.text() }
  val url = try { base.toHttpUrl() } catch (_: IllegalArgumentException) { throw IllegalArgumentException(CoreValidationMessages.INVALID_WEBDAV_URL.text()) }
  require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { CoreValidationMessages.URL_MUST_NOT_CONTAIN_CREDENTIALS_QUERY_OR_FRAGMENT.text() }
  require(url.scheme == "https" || (url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost", "::1"))) { CoreValidationMessages.HTTPS_REQUIRED_EXCEPT_EXACT_LOOPBACK_HOSTS.text() }
  baseCollection = url.newBuilder().apply { if (url.pathSegments.last().isNotEmpty()) addPathSegment("") }.build()
  gameCollection = baseCollection.newBuilder().addPathSegment(game).addPathSegment("").build()
  collection = gameCollection.newBuilder().addPathSegment(profile).addPathSegment("").build()
  // Named-account registration and the DAV gate both use UTF-8 credentials.
  // OkHttp's default ISO-8859-1 encoding silently replaces many valid passwords.
  auth = Credentials.basic(username, password, StandardCharsets.UTF_8)
 }

 override fun <T> withReadScope(cacheDirectory: Path?, block: () -> T): T {
  if (readScopes.get() != null) return block()
  val cache = OperationObjectReadCache.open(cacheDirectory)
  readScopes.set(cache)
  return try { block() } finally {
   readScopes.remove()
   cache.close()
  }
 }

 override fun <T> withFreshReads(block: () -> T): T {
  val previous = freshReadDepth.get() ?: 0
  freshReadDepth.set(previous + 1)
  return try { block() } finally {
   if (previous == 0) freshReadDepth.remove() else freshReadDepth.set(previous)
  }
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
     require(response.header("Content-Range") == null && response.headers.values("Link").isEmpty()) { CoreValidationMessages.PARTIAL_NAMESPACE_VERIFICATION.text() }
     parseCollection(readBounded(response, MAX_LISTING_BYTES), url)
    }
    else -> throw namespaceHttpError(response.code)
   }
  }
 } catch (e: NamespaceProvisioningException) { throw e }
 catch (_: IllegalArgumentException) { throw NamespaceProvisioningException(NamespaceFailureReason.UNSAFE_RESPONSE) }
 catch (e: io.github.xyz1001.pdsync.android.OfficialSyncAccess.AccessException) { throw e }
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
  catch (e: io.github.xyz1001.pdsync.android.OfficialSyncAccess.AccessException) { throw e }
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
   entries.count { it.isDav("response") } == 1 && entries.count { it.isDav("responsedescription") } <= 1) { CoreValidationMessages.AMBIGUOUS_NAMESPACE_RESPONSE.text() }
  val children = entries.single { it.isDav("response") }.elements()
  require(children.all { it.namespaceURI == DAV && it.localName in setOf("href", "status", "propstat", "responsedescription") }) { CoreValidationMessages.UNSUPPORTED_NAMESPACE_RESPONSE.text() }
  val href = children.singleOrNull { it.isDav("href") } ?: throw IllegalArgumentException(CoreValidationMessages.MISSING_OR_AMBIGUOUS_NAMESPACE_HREF.text())
  require(href.elements().isEmpty()) { CoreValidationMessages.MALFORMED_NAMESPACE_HREF.text() }
  val actual = checkedUrl(href.textContent.trim(), false, expected)
  require(actual.encodedPath == expected.encodedPath || actual.encodedPath == expected.encodedPath.removeSuffix("/")) { CoreValidationMessages.UNEXPECTED_NAMESPACE_HREF.text() }
  val status = children.filter { it.isDav("status") }
  val properties = children.filter { it.isDav("propstat") }
  require(status.size <= 1) { CoreValidationMessages.AMBIGUOUS_NAMESPACE_STATUS.text() }
  if (status.isNotEmpty()) {
   val code = parseStatus(status.single())
   // Only a resource-level 404 proves absence. A missing resourcetype property does not.
   if (code == 404 && properties.isEmpty()) return CollectionState.MISSING
   if (code !in 200..299) throw namespaceHttpError(code)
  }
  require(properties.isNotEmpty()) { CoreValidationMessages.MISSING_NAMESPACE_RESOURCE_TYPE.text() }
  val resourceTypes = mutableListOf<Element>()
  for (propstat in properties) {
   val fields = propstat.elements()
   require(fields.all { it.namespaceURI == DAV && it.localName in setOf("prop", "status", "responsedescription") }) { CoreValidationMessages.UNSUPPORTED_NAMESPACE_PROPERTIES.text() }
   val propStatus = fields.singleOrNull { it.isDav("status") } ?: throw IllegalArgumentException(CoreValidationMessages.MISSING_NAMESPACE_PROPERTY_STATUS.text())
   val code = parseStatus(propStatus)
   if (code !in 200..299) throw namespaceHttpError(code)
   val prop = fields.singleOrNull { it.isDav("prop") } ?: throw IllegalArgumentException(CoreValidationMessages.MISSING_NAMESPACE_PROPERTIES.text())
   // Do not infer a collection from a nested or unrelated DAV:collection element.
   require(prop.elements().all { it.isDav("resourcetype") }) { CoreValidationMessages.UNEXPECTED_NAMESPACE_PROPERTY.text() }
   resourceTypes += prop.elements()
  }
  require(resourceTypes.size == 1) { CoreValidationMessages.MISSING_OR_AMBIGUOUS_NAMESPACE_RESOURCE_TYPE.text() }
  val types = resourceTypes.single().elements()
  require(types.size <= 1 && types.all { it.isDav("collection") && it.elements().isEmpty() }) { CoreValidationMessages.UNSUPPORTED_NAMESPACE_RESOURCE_TYPE.text() }
  return if (types.size == 1) CollectionState.COLLECTION else CollectionState.NOT_COLLECTION
 }

 override fun readCommit(id: String): ByteArray? = get(fileUrl(commitName(id)), MAX_MANIFEST_BYTES)
 override fun readCommit(id: String, cancellation: MetadataReadCancellation): ByteArray? =
  get(fileUrl(commitName(id)), MAX_MANIFEST_BYTES, cancellation)
 override val historyReadParallelism:Int get()=4
 override fun readHistoryObjects(ids:List<String>,cancellation:MetadataReadCancellation,limits:Map<String,Int>):Map<String,ByteArray?> {
  require(ids.size<=4)
  cancellation.checkActive()
  val cache=readScopes.get()
  val reuse=cache!=null && (freshReadDepth.get()?:0)==0
  val result=linkedMapOf<String,ByteArray?>()
  val missing=mutableListOf<String>()
  // Cache ownership stays on the calling thread; workers always perform fresh network reads.
  ids.forEach {id->
   beforeRequest.check();cancellation.checkActive()
   val key=fileUrl(objectName(id)).toString()
   val hit=if(reuse)cache!!.read(key) else null
   if(hit!=null) {
    result[id]=hit
    DiagnosticLog.currentTrace()?.transfers?.add(DiagnosticTransferKind.CACHE_HIT,0,0,0,0,hit.size.toLong(),DiagnosticResult.SUCCEEDED)
   } else {
    missing+=id
    DiagnosticLog.currentTrace()?.transfers?.add(DiagnosticTransferKind.CACHE_MISS,0,0,0,0,0,DiagnosticResult.SUCCEEDED)
   }
  }
  val fetched=DiagnosticTiming.measure(DiagnosticPhase.HISTORY_OBJECT_READ) {
   historyReadBatch(missing,cancellation) {id->
    val limit=limits[id]?:MAX_SNAPSHOT_BYTES;require(limit in 1..MAX_SNAPSHOT_BYTES)
    get(fileUrl(objectName(id)),limit,cancellation, true)
   }
  }
  fetched.forEach {(id,bytes)->
   val key=fileUrl(objectName(id)).toString()
   if(bytes==null)cache?.remove(key) else if(reuse)cache!!.remember(key,bytes)
   result[id]=bytes
  }
  cancellation.checkActive()
  return ids.associateWith {result[it]}
 }
 override fun readObject(id: String): ByteArray? = getObject(fileUrl(objectName(id)))
 override fun legacyPresent(): Boolean = get(fileUrl("head.json"), MAX_MANIFEST_BYTES) != null
 override fun legacyPresent(cancellation:MetadataReadCancellation):Boolean =
  get(fileUrl("head.json"),MAX_MANIFEST_BYTES,cancellation)!=null
 override fun writeCommit(id: String, bytes: ByteArray) {
  validateWritableId(id)
  put(fileUrl(commitName(id)), bytes, MAX_MANIFEST_BYTES, "application/json")
 }
 override fun writeObject(id: String, bytes: ByteArray) {
  validateWritableId(id)
  put(fileUrl(objectName(id)), bytes, MAX_SNAPSHOT_BYTES, "application/zip")
 }

 private fun deletionName(id:String):String {validateId(id);return "pdsync-deleted-$id.json"}
 override fun readDeletion(id:String)=get(fileUrl(deletionName(id)),MAX_MANIFEST_BYTES)
 override fun writeDeletion(id:String,bytes:ByteArray)=put(fileUrl(deletionName(id)),bytes,MAX_MANIFEST_BYTES,"application/json")
 override fun deleteCommit(id:String)=deleteFile(fileUrl(commitName(id)))
 override fun deleteObject(id:String)=deleteFile(fileUrl(objectName(id)))
 private fun deleteFile(url:HttpUrl) {
  readScopes.get()?.remove(url.toString())
  try {execute(request(url).delete().build(),writeClient) {response->
   if(response.code !in setOf(200,204,404))throw WebDavException(response.code)
   readBounded(response,MAX_MANIFEST_BYTES)
  }} finally {readScopes.get()?.remove(url.toString())}
 }

 override fun listCommits(cursor: String?): CommitPage = listCommitsImpl(cursor,null)
 override fun listCommits(cursor:String?,cancellation:MetadataReadCancellation):CommitPage = listCommitsImpl(cursor,cancellation)
 private fun listCommitsImpl(cursor:String?,cancellation:MetadataReadCancellation?):CommitPage {
  val url = if (cursor == null) collection else listingUrl(cursor)
  val request = request(url).header("Depth", "1").method("PROPFIND", PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
  return execute(request,cancellation=cancellation) { response ->
   if (response.code != 207) throw WebDavException(response.code)
   // Content-Range means this is not a complete response to our Depth:1 request.
   require(response.header("Content-Range") == null) { CoreValidationMessages.PARTIAL_WEBDAV_LISTING_IS_UNSUPPORTED.text() }
   val next = nextLink(response, url)
   require(next == null || next != url.toString()) { CoreValidationMessages.CYCLIC_WEBDAV_LISTING_CURSOR.text() }
   CommitPage(parseListing(readBounded(response, MAX_LISTING_BYTES)), next)
  }
 }

 private fun get(url: HttpUrl, limit: Int, cancellation: MetadataReadCancellation? = null, historyObject:Boolean = false): ByteArray? =
  execute(request(url).get().build(), cancellation = cancellation, historyObject = historyObject) { response ->
  when (response.code) {
   404 -> null
   200 -> readBounded(response, limit)
   else -> throw WebDavException(response.code)
  }
 }

 private fun getObject(url: HttpUrl): ByteArray? {
  val cache = readScopes.get()
  val key = url.toString()
  val reuse = cache != null && (freshReadDepth.get() ?: 0) == 0
  if (reuse && cache!!.contains(key)) {
   // A frozen byte hit must never bypass the current access/permission check.
   beforeRequest.check()
   cache.read(key)?.let {
    DiagnosticLog.currentTrace()?.transfers?.add(DiagnosticTransferKind.CACHE_HIT,0,0,0,0,it.size.toLong(),DiagnosticResult.SUCCEEDED)
    return it
   }
  }
  DiagnosticLog.currentTrace()?.transfers?.add(DiagnosticTransferKind.CACHE_MISS,0,0,0,0,0,DiagnosticResult.SUCCEEDED)
  val bytes = get(url, MAX_SNAPSHOT_BYTES)
  if (bytes == null) cache?.remove(key)
  else if (reuse) cache!!.remember(key, bytes)
  return bytes
 }

 private fun put(url: HttpUrl, bytes: ByteArray, limit: Int, mediaType: String) {
  readScopes.get()?.remove(url.toString())
  try {
   require(bytes.size <= limit) { CoreValidationMessages.UPLOAD_EXCEEDS_SIZE_LIMIT.text() }
   execute(request(url).header("If-None-Match", "*").put(bytes.toRequestBody(mediaType.toMediaType())).build(), writeClient) { response ->
    if (response.code !in setOf(200, 201, 204)) throw WebDavException(response.code)
    readBounded(response, MAX_MANIFEST_BYTES)
   }
  } finally {
   readScopes.get()?.remove(url.toString())
  }
 }

 private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", auth).header("Cache-Control", "no-cache, no-store")
 private fun <T> execute(request: Request, requestClient: OkHttpClient = client,
  cancellation: MetadataReadCancellation? = null, historyObject:Boolean = false, block: (Response) -> T): T {
  cancellation?.checkActive()
  beforeRequest.check()
  val requestStarted = System.nanoTime()
  val transferTrace=DiagnosticLog.currentTrace()
  val kind=when {
   historyObject -> DiagnosticTransferKind.HISTORY_OBJECT_GET
   request.method=="PROPFIND" -> DiagnosticTransferKind.LIST
   request.method=="MKCOL" -> DiagnosticTransferKind.NAMESPACE
   request.method=="GET" && request.url.encodedPath.endsWith(".zip") -> DiagnosticTransferKind.OBJECT_GET
   request.method=="PUT" && request.url.encodedPath.endsWith(".zip") -> DiagnosticTransferKind.OBJECT_PUT
   request.method=="GET" && request.url.encodedPath.substringAfterLast('/').startsWith("pdsync-") -> DiagnosticTransferKind.COMMIT_GET
   request.method=="PUT" -> DiagnosticTransferKind.COMMIT_PUT
   else -> DiagnosticTransferKind.OTHER
  }
  var sent=0L;var received=0L;var headersNanos=0L;var bodyNanos=0L
  var transferResult=DiagnosticResult.FAILED
  val measuredBody=request.body?.let { original -> object:okhttp3.RequestBody() {
   override fun contentType()=original.contentType()
   override fun contentLength()=original.contentLength()
   override fun isOneShot()=original.isOneShot()
   override fun isDuplex()=original.isDuplex()
   override fun writeTo(sink:okio.BufferedSink) {
    val counting=object:okio.ForwardingSink(sink) {
     override fun write(source:okio.Buffer,byteCount:Long) {super.write(source,byteCount);sent+=byteCount}
    }.buffer()
    original.writeTo(counting);counting.flush()
   }
  }}
  val call = requestClient.newCall(request.newBuilder().method(request.method,measuredBody).build())
  // Elapsed until response headers (or the observed transport failure), never request data.
  fun context(error:IOException? = null) = DiagnosticDetails.builder().networkTarget(DiagnosticDetails.NetworkTarget.DAV)
   .requestDurationMs(((System.nanoTime() - requestStarted) / 1_000_000).coerceAtLeast(0))
   .number(DiagnosticDetails.Field.TRANSPORT_CLIENT_ID,diagnosticClientId)
   .apply { if(error!=null) transportFailure(error,call.isCanceled(),Thread.currentThread().isInterrupted) }.build()
  val registration = cancellation?.register { call.cancel() }
  return try {
  cancellation?.checkActive()
  val headersStarted=System.nanoTime()
  val raw=try {call.execute()} finally {headersNanos=System.nanoTime()-headersStarted}
  raw.use {
   val response=raw.body?.let { original -> raw.newBuilder().body(object:okhttp3.ResponseBody() {
    private val counted=object:okio.ForwardingSource(original.source()) {
     override fun read(sink:okio.Buffer,byteCount:Long):Long {
      val start=System.nanoTime()
      return try {super.read(sink,byteCount).also {if(it>0)received+=it}} finally {bodyNanos+=System.nanoTime()-start}
     }
    }.buffer()
    override fun contentType()=original.contentType()
    override fun contentLength()=original.contentLength()
    override fun source()=counted
   }).build()} ?: raw
   // The recorder accepts only a closed method enum and status, never URL, headers or payload.
   DiagnosticLog.http(diagnosticMethod(request.method), response.code, context())
   block(response).also {transferResult=DiagnosticResult.SUCCEEDED}
  }
 } catch (e: NamespaceProvisioningException) {
  // The namespace interceptor rejects bad statuses before execute can expose its response.
  e.statusCode?.let { DiagnosticLog.http(diagnosticMethod(request.method), it, context()) }
  throw e
 }
 catch (e: WebDavException) {
  // Failed PUT statuses are intercepted before the response reaches this method.
  if (requestClient === writeClient) DiagnosticLog.http(diagnosticMethod(request.method), e.statusCode, context())
  throw e
 }
 catch (error: io.github.xyz1001.pdsync.android.OfficialSyncAccess.AccessException) { throw error }
 catch (error: IOException) {
  // A network change can invalidate several pooled sockets. Discard only idle connections;
  // the current request still fails and only a later explicit operation may reconnect.
  // This catch also covers response-body reads. Never treat cancellation, interruption,
  // timeouts, DNS, TLS or HTTP/business failures as evidence of stale pooled sockets.
  if (!call.isCanceled() && !Thread.currentThread().isInterrupted
   && (error is java.net.SocketException || error is java.io.EOFException)) {
   requestClient.connectionPool.evictAll()
  }
  DiagnosticLog.networkFailure(error, context(error))
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
 } finally {
  if(call.isCanceled() || Thread.currentThread().isInterrupted)transferResult=DiagnosticResult.CANCELED
  transferTrace?.transfers?.add(kind,headersNanos,bodyNanos,System.nanoTime()-requestStarted,sent,received,transferResult)
  registration?.close()
 }
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
  val body = response.body ?: throw IOException(CoreValidationMessages.WEBDAV_RESPONSE_BODY_MISSING.text())
  require(body.contentLength() <= limit) { CoreValidationMessages.REMOTE_RESPONSE_EXCEEDS_SIZE_LIMIT.text() }
  return body.byteStream().readUpTo(limit + 1).also { require(it.size <= limit) { CoreValidationMessages.REMOTE_RESPONSE_EXCEEDS_SIZE_LIMIT.text() } }
 }

 private fun fileUrl(name: String) = collection.newBuilder().addPathSegment(name).build()
 private fun commitName(id: String): String { validateId(id); return "${if (validContentHashId(id)) HASH_COMMIT_PREFIX else LEGACY_COMMIT_PREFIX}$id.json" }
 private fun objectName(id: String): String { validateId(id); return "pdsync-${if (validContentHashId(id)) "v3" else "v2"}-object-$id.zip" }
 private fun validateId(id: String) = require(validRecordId(id)) { CoreValidationMessages.INVALID_APPEND_ONLY_IDENTIFIER.text() }
 private fun validateWritableId(id: String) = require(validContentHashId(id)) { CoreValidationMessages.INVALID_APPEND_ONLY_IDENTIFIER.text() }

 /** Validate before HttpUrl can normalize traversal, backslashes or URL credentials away. */
 private fun checkedUri(value: String, allowQuery: Boolean): URI {
  require(value.isNotEmpty() && value.length <= MAX_HREF_LENGTH && value.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) { CoreValidationMessages.UNSAFE_WEBDAV_HREF.text() }
  val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException(CoreValidationMessages.MALFORMED_WEBDAV_HREF.text()) }
  require(!uri.isOpaque && uri.rawUserInfo == null && uri.rawFragment == null && (allowQuery || uri.rawQuery == null)) { CoreValidationMessages.UNSAFE_WEBDAV_HREF.text() }
  // Refuse percent-encoded traversal/separators and ambiguous double-encoding, even on siblings.
  val rawPath = uri.rawPath ?: ""
  require(rawPath.split('/').none { raw ->
   val decoded = try { java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name()) } catch (_: Exception) { throw IllegalArgumentException(CoreValidationMessages.MALFORMED_WEBDAV_HREF.text()) }
   decoded == "." || decoded == ".." || decoded.any { it == '/' || it == '\\' || it == '%' || it.isISOControl() }
  }) { CoreValidationMessages.UNSAFE_WEBDAV_HREF.text() }
  return uri
 }

 private fun checkedUrl(value: String, allowQuery: Boolean, reference: HttpUrl = collection): HttpUrl {
  checkedUri(value, allowQuery)
  val result = reference.resolve(value) ?: throw IllegalArgumentException(CoreValidationMessages.MALFORMED_WEBDAV_HREF.text())
  require(result.scheme == collection.scheme && result.host == collection.host && result.port == collection.port &&
   result.username.isEmpty() && result.password.isEmpty() && result.fragment == null) { CoreValidationMessages.CROSS_ORIGIN_OR_UNSAFE_WEBDAV_HREF.text() }
  return result
 }

 private fun listingUrl(value: String, reference: HttpUrl = collection): HttpUrl = checkedUrl(value, true, reference).also {
  require(it.encodedPath == collection.encodedPath) { CoreValidationMessages.WEBDAV_CONTINUATION_MUST_USE_THE_SAME_COLLECTION.text() }
 }

 private fun parseXml(bytes: ByteArray): Document = SafeDavXml.parse(bytes)

 private fun validateMultistatusRoot(root: Element) {
  require(root.isDav("multistatus")) { CoreValidationMessages.EXPECTED_DAV_MULTISTATUS_LISTING.text() }
  require((0 until root.attributes.length).all { index ->
   val attribute = root.attributes.item(index)
   attribute.namespaceURI == XMLConstants.XMLNS_ATTRIBUTE_NS_URI || (attribute.namespaceURI == XMLConstants.XML_NS_URI && attribute.localName == "lang")
  }) { CoreValidationMessages.UNSUPPORTED_WEBDAV_LISTING_ATTRIBUTES.text() }
 }

 private fun parseStatus(status: Element): Int {
  require(status.elements().isEmpty()) { CoreValidationMessages.MALFORMED_WEBDAV_STATUS.text() }
  return Regex("HTTP/\\d+(?:\\.\\d+)? ([0-9]{3})(?: .*)?").matchEntire(status.textContent.trim())?.groupValues?.get(1)?.toInt()
   ?: throw IllegalArgumentException(CoreValidationMessages.MALFORMED_WEBDAV_LISTING_STATUS.text())
 }

 private fun parseListing(bytes: ByteArray): List<String> {
  val root = parseXml(bytes).documentElement
  validateMultistatusRoot(root)
  val entries = root.elements()
  require(entries.size <= MAX_LISTING_RESPONSES + 1 && entries.count { it.isDav("response") } <= MAX_LISTING_RESPONSES) { CoreValidationMessages.WEBDAV_LISTING_ENTRY_LIMIT_EXCEEDED.text() }
  require(entries.all { it.isDav("response") || it.isDav("responsedescription") }) { CoreValidationMessages.UNSUPPORTED_OR_INCOMPLETE_WEBDAV_LISTING_EXTENSION.text() }
  val ids = linkedSetOf<String>()
  val paths = mutableSetOf<String>()
  for (entry in entries.filter { it.isDav("response") }) {
   val children = entry.elements()
   require(children.all { it.namespaceURI == DAV && it.localName in setOf("href", "status", "propstat", "responsedescription") }) { CoreValidationMessages.UNSUPPORTED_OR_FAILED_WEBDAV_LISTING_ENTRY.text() }
   val href = children.singleOrNull { it.isDav("href") } ?: throw IllegalArgumentException(CoreValidationMessages.MISSING_OR_AMBIGUOUS_WEBDAV_HREF.text())
   require(href.elements().isEmpty()) { CoreValidationMessages.MALFORMED_WEBDAV_HREF.text() }
   val url = checkedUrl(href.textContent.trim(), false)
   val path = url.encodedPath
   require(paths.add(path)) { CoreValidationMessages.DUPLICATE_WEBDAV_LISTING_ENTRY.text() }
   val own = path == collection.encodedPath || path == collection.encodedPath.removeSuffix("/")
   val relative = if (path.startsWith(collection.encodedPath)) path.removePrefix(collection.encodedPath) else null
   require(own || (relative != null && relative.isNotEmpty() && '/' !in relative.removeSuffix("/"))) { CoreValidationMessages.WEBDAV_HREF_IS_OUTSIDE_THE_REQUESTED_COLLECTION.text() }
   val statuses = children.filter { it.isDav("status") } + children.filter { it.isDav("propstat") }.map { propstat ->
    val properties = propstat.elements()
    require(properties.all { it.namespaceURI == DAV && it.localName in setOf("prop", "status", "responsedescription") }) { CoreValidationMessages.FAILED_WEBDAV_PROPERTY_LISTING.text() }
    properties.singleOrNull { it.isDav("status") } ?: throw IllegalArgumentException(CoreValidationMessages.MISSING_WEBDAV_PROPERTY_STATUS.text())
   }
   require(statuses.isNotEmpty()) { CoreValidationMessages.MISSING_WEBDAV_LISTING_STATUS.text() }
   statuses.forEach { status ->
    val code = Regex("HTTP/\\d+(?:\\.\\d+)? ([0-9]{3})(?: .*)?").matchEntire(status.textContent.trim())?.groupValues?.get(1)?.toInt()
     ?: throw IllegalArgumentException(CoreValidationMessages.MALFORMED_WEBDAV_LISTING_STATUS.text())
    if (code !in 200..299) throw WebDavException(code)
   }
   if (own) continue
   val name = url.pathSegments.last()
   val prefix = COMMIT_PREFIXES.firstOrNull { name.startsWith(it) }
   if (prefix != null) {
    require(!path.endsWith('/') && name.endsWith(".json") && relative == name) { CoreValidationMessages.MALFORMED_APPEND_ONLY_COMMIT_FILENAME.text() }
    val id = name.removePrefix(prefix).removeSuffix(".json")
    require(if (prefix == HASH_COMMIT_PREFIX) validContentHashId(id) else validCommitId(id)) { CoreValidationMessages.INVALID_APPEND_ONLY_IDENTIFIER.text() }
    require(entry.getElementsByTagNameNS(DAV, "collection").length == 0) { CoreValidationMessages.COMMIT_IS_A_COLLECTION.text() }
    require(ids.add(id)) { CoreValidationMessages.DUPLICATE_COMMIT_IDENTIFIER.text() }
   } else if (COMMIT_PREFIXES.any { relative!!.startsWith(it) }) {
    throw IllegalArgumentException(CoreValidationMessages.MALFORMED_APPEND_ONLY_COMMIT_FILENAME.text())
   }
  }
  return ids.sorted()
 }

 private fun nextLink(response: Response, reference: HttpUrl): String? {
  val headers = response.headers.values("Link")
  require(headers.sumOf { it.length } <= MAX_HREF_LENGTH * 4) { CoreValidationMessages.WEBDAV_PAGINATION_HEADER_TOO_LARGE.text() }
  val next = mutableListOf<String>()
  for (header in headers) for (link in splitLinks(header)) {
   val match = Regex("^\\s*<([^<>]+)>((?:\\s*;.*)?)\\s*$").matchEntire(link) ?: throw IllegalArgumentException(CoreValidationMessages.MALFORMED_WEBDAV_PAGINATION_HEADER.text())
   val params = parseLinkParameters(match.groupValues[2])
   val rel = params["rel"]?.split(Regex("\\s+")) ?: emptyList()
   if ("next" in rel) {
    require("anchor" !in params) { CoreValidationMessages.ANCHORED_WEBDAV_PAGINATION_IS_UNSUPPORTED.text() }
    next += listingUrl(match.groupValues[1], reference).toString()
   }
  }
  require(next.size <= 1) { CoreValidationMessages.AMBIGUOUS_WEBDAV_CONTINUATION.text() }
  return next.singleOrNull()
 }

 private fun splitLinks(value: String): List<String> {
  val parts = mutableListOf<String>(); var start = 0; var quoted = false; var angle = false; var escaped = false
  value.forEachIndexed { i, c ->
   if (escaped) escaped = false
   else when {
    quoted && c == '\\' -> escaped = true
    c == '"' && !angle -> quoted = !quoted
    c == '<' && !quoted -> { require(!angle) { CoreValidationMessages.MALFORMED_WEBDAV_PAGINATION_HEADER.text() }; angle = true }
    c == '>' && !quoted -> { require(angle) { CoreValidationMessages.MALFORMED_WEBDAV_PAGINATION_HEADER.text() }; angle = false }
    c == ',' && !quoted && !angle -> { parts += value.substring(start, i); start = i + 1 }
   }
  }
  require(!quoted && !angle && !escaped) { CoreValidationMessages.MALFORMED_WEBDAV_PAGINATION_HEADER.text() }
  parts += value.substring(start)
  return parts
 }

 private fun parseLinkParameters(value: String): Map<String, String> {
  val result = mutableMapOf<String, String>(); var remaining = value.trim()
  val parameter = Regex("""^;\s*([A-Za-z0-9!#$%&'*+.^_`|~-]+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([A-Za-z0-9!#$%&'*+.^_`|~:/-]+))\s*""")
  while (remaining.isNotEmpty()) {
   val match = parameter.find(remaining) ?: throw IllegalArgumentException(CoreValidationMessages.MALFORMED_WEBDAV_PAGINATION_PARAMETERS.text())
   val name = match.groupValues[1].lowercase(java.util.Locale.ROOT)
   require(name !in result) { CoreValidationMessages.AMBIGUOUS_WEBDAV_PAGINATION_PARAMETERS.text() }
   result[name] = match.groups[2]?.value?.replace(Regex("\\\\(.)"), "$1") ?: match.groupValues[3]
   remaining = remaining.substring(match.range.last + 1)
  }
  return result
 }

 private fun Element.isDav(name: String) = namespaceURI == DAV && localName == name
 private fun Element.elements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

 companion object {
  private val nextDiagnosticClientId = java.util.concurrent.atomic.AtomicLong()
  private const val DAV = "DAV:"
  private const val LEGACY_COMMIT_PREFIX = "pdsync-v2-commit-"
  private const val HASH_COMMIT_PREFIX = "pdsync-v3-commit-"
  private val COMMIT_PREFIXES = listOf(LEGACY_COMMIT_PREFIX, HASH_COMMIT_PREFIX)
  private const val MAX_LISTING_BYTES = 2 * 1024 * 1024
  private const val MAX_LISTING_RESPONSES = 10_000
  private const val MAX_HREF_LENGTH = 8192
  private val SEGMENT = Regex("[a-zA-Z0-9_-]+")
  private const val PROPFIND = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
 }
}

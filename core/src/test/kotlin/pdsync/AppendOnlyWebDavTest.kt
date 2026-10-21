package pdsync

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AppendOnlyWebDavTest {
 private val id = "11111111-1111-4111-8111-111111111111"
 private val id2 = "22222222-2222-4222-8222-222222222222"
 private val hash = "ab".repeat(32)
 private fun hashName(id: String = hash) = "pdsync-v3-commit-$id.json"
 private val folder = "/dav/shattered/hero/"
 private fun name(id: String = this.id) = "pdsync-v2-commit-$id.json"
 private fun store(server: MockWebServer, client: OkHttpClient = OkHttpClient()) = AppendOnlyWebDav(server.url("/dav/").toString(), "shattered", "hero", "player", "test-secret", client)
 private fun entry(href: String, status: String = "HTTP/1.1 200 OK", collection: Boolean = false): String =
  """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>$status</d:status></d:propstat></d:response>"""
 private fun xml(vararg entries: String) = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">${entries.joinToString("")}</d:multistatus>"""
 private fun listing(vararg entries: String) = MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(xml(*entries))

 @Test fun unicodePasswordsUseTheSameUtf8BytesAsOfficialRegistration() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setResponseCode(404));server.start()
  val password="合成密码-\u00e9-\ud83d\udd10"
  val dav=AppendOnlyWebDav(server.url("/dav/").toString(),"shattered","hero","player",password)
  assertNull(dav.readCommit(id))
  val authorization=server.takeRequest().getHeader("Authorization")!!
  assertEquals("player:$password",String(java.util.Base64.getDecoder().decode(authorization.removePrefix("Basic ")),Charsets.UTF_8))
  assertEquals(Credentials.basic("player",password,Charsets.UTF_8),authorization)
 }

 @Test fun hashWritesAreCreateOnlyAndNeverWriteHead() = MockWebServer().use { server ->
  // A minimal create-only DAV store requires no ETag or shared head.
  val objects = mutableMapOf<String, ByteArray>()
  server.dispatcher = object: Dispatcher() {
   override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
    "PUT" -> {
     if (request.getHeader("If-None-Match") != "*") MockResponse().setResponseCode(400)
     else if (objects.containsKey(request.path)) MockResponse().setResponseCode(412)
     else { objects[request.path!!] = request.body.readByteArray(); MockResponse().setResponseCode(201) }
    }
    "GET" -> objects[request.path]?.let { MockResponse().setBody(okio.Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
    else -> MockResponse().setResponseCode(405)
   }
  }
  server.start(); val dav = store(server)
  val commit = "{\"synthetic\":true}".encodeToByteArray(); val archive = byteArrayOf(0, 1, 127, -1)
  assertNull(dav.readCommit(id)); assertNull(dav.readObject(id)); assertFalse(dav.legacyPresent())
  dav.writeObject(hash, archive); dav.writeCommit(hash, commit)
  assertContentEquals(archive, dav.readObject(hash)); assertContentEquals(commit, dav.readCommit(hash))
  assertEquals(setOf(folder + "pdsync-v3-object-$hash.zip", folder + hashName()), objects.keys)
  repeat(server.requestCount) {
   val request = server.takeRequest(1, TimeUnit.SECONDS)!!
   assertEquals(Credentials.basic("player", "test-secret"), request.getHeader("Authorization"))
   assertNull(request.getHeader("If-Match")); assertEquals(if (request.method == "PUT") "*" else null, request.getHeader("If-None-Match"))
   if (request.path!!.endsWith("head.json")) assertEquals("GET", request.method)
   assertTrue(request.method in setOf("GET", "PUT"))
  }
 }

 @Test fun legacyRecordsRemainReadableButCannotBeWritten() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setBody("legacy commit")); server.enqueue(MockResponse().setBody("legacy zip")); server.start()
  val dav = store(server)
  assertEquals("legacy commit", dav.readCommit(id)!!.decodeToString())
  assertEquals("legacy zip", dav.readObject(id)!!.decodeToString())
  assertEquals(folder + name(), server.takeRequest().path)
  assertEquals(folder + "pdsync-v2-object-$id.zip", server.takeRequest().path)
  assertFailsWith<IllegalArgumentException> { dav.writeCommit(id, byteArrayOf(1)) }
  assertFailsWith<IllegalArgumentException> { dav.writeObject(id, byteArrayOf(1)) }
  assertEquals(2, server.requestCount)
 }

 @Test fun hashConflictsAndRetryableStatusesReturnOnceWithoutOverwritingOrRetrying() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  for (status in listOf(409, 412, 503)) for (commit in listOf(false, true)) {
   server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0"))
   val error = assertFailsWith<WebDavException> {
    if (commit) dav.writeCommit(hash, byteArrayOf(1)) else dav.writeObject(hash, byteArrayOf(2))
   }
   assertEquals(status, error.statusCode)
   val request = server.takeRequest()
   assertEquals("PUT", request.method); assertEquals("*", request.getHeader("If-None-Match"))
   assertEquals(folder + if (commit) hashName() else "pdsync-v3-object-$hash.zip", request.path)
  }
  assertEquals(6, server.requestCount)
 }

 @Test fun mixedVersionPaginationKeepsHashAndLegacyPathsAndCredentialsIsolated() = MockWebServer().use { server ->
  server.enqueue(listing(entry(folder + name())).setHeader("Link", "<?page=2>; rel=next"))
  server.enqueue(listing(entry(folder + hashName()), entry(folder + "pdsync-v3-object-$hash.zip")))
  server.enqueue(MockResponse().setBody("hash")); server.enqueue(MockResponse().setBody("legacy")); server.start()
  val dav = store(server); val first = dav.listCommits(); val second = dav.listCommits(first.next)
  assertEquals(listOf(id), first.ids); assertEquals(listOf(hash), second.ids); assertNull(second.next)
  dav.readCommit(hash); dav.readCommit(id)
  val requests = List(4) { server.takeRequest() }
  assertEquals(listOf(folder, folder + "?page=2", folder + hashName(), folder + name()), requests.map { it.path })
  requests.forEach { assertEquals(Credentials.basic("player", "test-secret"), it.getHeader("Authorization")) }
  assertFalse(first.next!!.contains("test-secret"))
 }

 @Test fun malformedHashAndWrongVersionNamesFailClosed() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  for (filename in listOf(hashName(hash.uppercase()), hashName(hash.drop(1)), hashName(id),
   "pdsync-v2-commit-$hash.json", hashName() + "/", hashName().replace(".json", ".zip"))) {
   server.enqueue(listing(entry(folder + filename)))
   assertFailsWith<IllegalArgumentException> { dav.listCommits() }
  }
  assertEquals(6, server.requestCount)
 }

 @Test fun legacyDetectionDoesNotParseOrWriteOldHead() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setBody("legacy or corrupt data remains untouched")); server.start()
  assertTrue(store(server).legacyPresent())
  val request = server.takeRequest(); assertEquals("GET", request.method); assertEquals(folder + "head.json", request.path)
  assertEquals(1, server.requestCount)
 }

 @Test fun depthOneListingFiltersOrdinaryFilesAndObjectsAndSortsCanonicalCommitIds() = MockWebServer().use { server ->
  server.enqueue(listing(entry(folder, collection = true), entry(folder + name(id2)), entry(name()),
   entry(folder + "pdsync-v2-object-$id.zip"), entry(folder + "head.json"), entry(folder + "notes/", collection = true)))
  server.start(); val page = store(server).listCommits()
  assertEquals(listOf(id, id2), page.ids); assertNull(page.next)
  val request = server.takeRequest(); assertEquals("PROPFIND", request.method); assertEquals("1", request.getHeader("Depth")); assertEquals(folder, request.path)
  assertTrue(request.body.readUtf8().contains("resourcetype")); assertNull(request.getHeader("If-Match"))
 }

 @Test fun api26Utf8DecoderKeepsUnicodeAndPlusInTheConfiguredCollection() = MockWebServer().use { server ->
  val base = "/%E5%AD%98%E6%A1%A3+a%2Bb/"
  val collection = base + "shattered/hero/"
  server.enqueue(listing(entry(collection, collection = true), entry(collection + name())))
  server.start()
  val dav = AppendOnlyWebDav(server.url(base).toString(), "shattered", "hero", "player", "test-secret")
  assertEquals(listOf(id), dav.listCommits().ids)
  val request = server.takeRequest()
  assertEquals("PROPFIND", request.method); assertEquals(collection, request.path)
 }

 @Test fun followsExplicitSameCollectionNextPageAndNeverCopiesCursorToObjectGet() = MockWebServer().use { server ->
  server.enqueue(listing(entry(folder + name())).setHeader("Link", "<$folder?cursor=private-token>; rel=\"next\""))
  server.enqueue(listing(entry(folder + name(id2))))
  server.enqueue(MockResponse().setBody("commit")); server.start()
  val dav = store(server); val page1 = dav.listCommits(); val page2 = dav.listCommits(page1.next)
  assertEquals(listOf(id), page1.ids); assertEquals(listOf(id2), page2.ids); assertNull(page2.next)
  assertNotNull(dav.readCommit(id2))
  assertEquals(folder, server.takeRequest().path)
  assertEquals(folder + "?cursor=private-token", server.takeRequest().path)
  assertEquals(folder + name(id2), server.takeRequest().path)
 }

 @Test fun relativeQueryLinkAndQuotedCommaWorkWithoutInventingPages() = MockWebServer().use { server ->
  server.enqueue(listing(entry(folder + name())).setHeader("Link", "<https://irrelevant.example/help>; rel=\"help\"; title=\"a,b\", <?page=2>; rel=\"next\""))
  server.enqueue(listing(entry(folder + name(id2)))); server.start()
  val dav = store(server); val first = dav.listCommits()
  assertEquals(server.url(folder + "?page=2").toString(), first.next)
  assertEquals(listOf(id2), dav.listCommits(first.next).ids)
  assertEquals(2, server.requestCount)
 }

 @Test fun paginationCannotChangeOriginFolderCredentialsOrFragments() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  val links = listOf("https://other.example$folder?page=2", "/dav/shattered/other/?page=2", "$folder${name()}?page=2",
   server.url(folder).toString().replace("://", "://user:secret@") + "?page=2", "$folder?page=2#private", "../hero/?page=2", "$folder%2e%2e/hero/?page=2")
  links.forEach { link ->
   server.enqueue(listing(entry(folder + name())).setHeader("Link", "<$link>; rel=\"next\""))
   val error = assertFailsWith<IllegalArgumentException> { dav.listCommits() }
   assertFalse(error.toString().contains("secret")); assertFalse(error.toString().contains("page=2"))
  }
  assertEquals(links.size, server.requestCount)
 }

 @Test fun unsafeCallerCursorRejectedBeforeNetworkAndQueryNotInErrors() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  listOf("https://other.example/?token=private", "/other/?token=private", "%%%?token=private", "$folder#private").forEach { cursor ->
   val error = assertFailsWith<IllegalArgumentException> { dav.listCommits(cursor) }
   assertFalse(error.toString().contains("private"))
  }
  assertEquals(0, server.requestCount)
 }

 @Test fun repeatedAmbiguousAnchoredMalformedAndPartialContinuationsFail() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  listOf("<$folder>; rel=next", "<?page=2>; rel=next, <?page=3>; rel=next", "<?page=2>; rel=next; anchor=\"/elsewhere\"", "<?page=2>; rel=\"next", "<?page=2>; rel=next; rel=prev").forEach { value ->
   server.enqueue(listing().setHeader("Link", value)); assertFailsWith<IllegalArgumentException> { dav.listCommits() }
  }
  server.enqueue(listing().setHeader("Content-Range", "items 0-9/20")); assertFailsWith<IllegalArgumentException> { dav.listCommits() }
 }

 @Test fun malformedUnsafeOrNoncanonicalHrefsNeverBecomeFetchRequests() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  listOf("https://attacker.example$folder${name()}", "/dav/shattered/other/${name()}", "../hero/${name()}",
   "$folder%2e%2e/hero/${name()}", "$folder%252e%252e/${name()}", "$folder${name()}?secret=token", "$folder${name()}#fragment",
   "$folder%2f${name()}", "$folder${name().replace("11111111", "AAAAAAAA")}", "$folder${name().replace(".json", ".json/")}",
   "$folder${name().replace("pdsync", "%70dsync")}", "$folder${name().replace("-4111-", "-111-")}", "$folder${name()}\\x", "%ZZ").forEach { href ->
   server.enqueue(listing(entry(href)))
   assertFailsWith<IllegalArgumentException>(href) { dav.listCommits() }
  }
  repeat(server.requestCount) { assertEquals("PROPFIND", server.takeRequest().method) }
 }

 @Test fun malformedPrefixedNamesCollectionsAndDuplicateEntriesFailClosed() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  listOf(listing(entry(folder + "pdsync-v2-commit-bad.json")), listing(entry(folder + name(), collection = true)),
   listing(entry(folder + name()), entry(folder + name())), listing(entry(folder + "pdsync-v2-commit-$id.txt"))).forEach {
   server.enqueue(it); assertFailsWith<IllegalArgumentException> { dav.listCommits() }
  }
 }

 @Test fun xmlExternalEntitiesDtdAndDeepNestingRejectedWithoutExternalRequests() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  val bad = listOf("""<?xml version="1.0"?><!DOCTYPE d:multistatus [<!ENTITY leak SYSTEM "${server.url("/forbidden")}">]><d:multistatus xmlns:d="DAV:">&leak;</d:multistatus>""",
   """<!DOCTYPE d:multistatus SYSTEM "${server.url("/forbidden")}"><d:multistatus xmlns:d="DAV:"/>""",
   "<d:multistatus xmlns:d=\"DAV:\">" + "<nested>".repeat(100) + "</nested>".repeat(100) + "</d:multistatus>",
   "<multistatus/>", "<d:multistatus xmlns:d=\"DAV:\"><d:response>unfinished", """<d:multistatus xmlns:d="DAV:" xmlns:p="urn:paging"><p:next>ignored page</p:next></d:multistatus>""")
  bad.forEach { body -> server.enqueue(MockResponse().setResponseCode(207).setBody(body)); assertFailsWith<IllegalArgumentException> { dav.listCommits() } }
  assertEquals(bad.size, server.requestCount)
 }

 @Test fun listingHttpAndPerResourceErrorsAreNeverAnEmptyHistory() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  for (status in listOf(401, 403, 404, 405, 500, 507)) {
   server.enqueue(MockResponse().setResponseCode(status))
   assertEquals(status, assertFailsWith<WebDavException> { dav.listCommits() }.statusCode)
  }
  for (status in listOf(401, 404, 507)) {
   server.enqueue(listing(entry(folder + name(), "HTTP/1.1 $status Failure")))
   assertEquals(status, assertFailsWith<WebDavException> { dav.listCommits() }.statusCode)
  }
 }

 @Test fun authenticationAndForbiddenAreNotTreatedAsMissingOnAnyRead() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  val reads: List<() -> Any?> = listOf({ dav.readCommit(id) }, { dav.readObject(id) }, { dav.legacyPresent() })
  for (read in reads) for (status in listOf(401, 403, 500)) {
   server.enqueue(MockResponse().setResponseCode(status))
   assertEquals(status, assertFailsWith<WebDavException> { read() }.statusCode)
  }
 }

 @Test fun redirectsNeverFollowEvenWithRedirectEnabledClientAndErrorsDoNotExposeLocation() = MockWebServer().use { server ->
  server.start(); val dav = store(server, OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build())
  for (action in listOf<() -> Any?>({ dav.readCommit(id) }, { dav.listCommits() }, { dav.writeCommit(hash, byteArrayOf(1)) })) {
   server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://other.example/?secret=private"))
   val error = assertFailsWith<WebDavException> { action() }
   assertEquals(307, error.statusCode); assertFalse(error.toString().contains("private"))
  }
  assertEquals(3, server.requestCount)
 }

 @Test fun oversizedDeclaredAndChunkedResponsesAndUploadsFailExplicitly() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", MAX_MANIFEST_BYTES + 1))
  assertFailsWith<IllegalArgumentException> { dav.readCommit(id) }
  server.enqueue(MockResponse().setChunkedBody("x".repeat(MAX_MANIFEST_BYTES + 1), 16384))
  assertFailsWith<IllegalArgumentException> { dav.readCommit(id) }
  server.enqueue(MockResponse().setResponseCode(207).setChunkedBody("x".repeat(MAX_MANIFEST_BYTES + 1), 16384))
  assertFailsWith<IllegalArgumentException> { dav.listCommits() }
  server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", MAX_SNAPSHOT_BYTES + 1))
  assertFailsWith<IllegalArgumentException> { dav.readObject(id) }
  assertFailsWith<IllegalArgumentException> { dav.writeCommit(hash, ByteArray(MAX_MANIFEST_BYTES + 1)) }
  assertEquals(4, server.requestCount)
 }

 @Test fun entryCountAndExplicitTruncationAttributesFailInsteadOfReturningPartialHistory() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  val entries = (0..10_000).joinToString("") { i -> "<d:response><d:href>ordinary-$i</d:href><d:status>HTTP/1.1 200 OK</d:status></d:response>" }
  server.enqueue(MockResponse().setResponseCode(207).setBody(xml(entries)))
  assertTrue(assertFailsWith<IllegalArgumentException> { dav.listCommits() }.message!!.contains("entry limit"))
  server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:" truncated="true"/>"""))
  assertFailsWith<IllegalArgumentException> { dav.listCommits() }
 }

 @Test fun invalidIdsCannotBecomePathsAndNonLoopbackHttpOrCredentialUrlsAreRejected() = MockWebServer().use { server ->
  server.start(); val dav = store(server)
  for (bad in listOf("../../head", id.uppercase().replace('1', 'A'), "1-1-1-1-1", UUID.randomUUID().toString() + "?query")) {
   assertFailsWith<IllegalArgumentException> { dav.readCommit(bad) }; assertFailsWith<IllegalArgumentException> { dav.readObject(bad) }
   assertFailsWith<IllegalArgumentException> { dav.writeCommit(bad, byteArrayOf()) }; assertFailsWith<IllegalArgumentException> { dav.writeObject(bad, byteArrayOf()) }
  }
  listOf("http://example.com", "http://127.0.0.2", "http://localhost.example", "https://user:secret@example.com", "https://example.com?secret=x", "https://example.com/#secret").forEach { base ->
   assertFailsWith<IllegalArgumentException> { AppendOnlyWebDav(base, "shattered", "hero", "player", "password") }
  }
  assertEquals(0, server.requestCount)
 }

 @Test fun noTrailingSlashBaseStillUsesExistingGameAndProfileCollection() = MockWebServer().use { server ->
  server.enqueue(listing(entry(folder + name()))); server.start()
  val dav = AppendOnlyWebDav(server.url("/dav").toString(), "shattered", "hero", "player", "password")
  assertEquals(listOf(id), dav.listCommits().ids); assertEquals(folder, server.takeRequest().path)
 }
}

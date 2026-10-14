package pdsync

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** All HTTP traffic stays in a synthetic loopback server; no real account or remote data. */
class NamespaceCreationTest {
 private val root = "/dav/"
 private val game = "/dav/shattered/"
 private val profile = "/dav/shattered/hero/"
 private fun store(server: MockWebServer, client: OkHttpClient = OkHttpClient()) =
  AppendOnlyWebDav(server.url(root).toString(), "shattered", "hero", "player", "test-secret", client)
 private fun entry(href: String, isCollection: Boolean = true, status: Int = 200) =
  """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>${if (isCollection) "<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 $status Result</d:status></d:propstat></d:response>"""
 private fun xml(vararg entries: String) = """<d:multistatus xmlns:d="DAV:">${entries.joinToString("")}</d:multistatus>"""
 private fun body(xml: String) = MockResponse().setResponseCode(207).setBody(xml)
 private fun collection(href: String, isCollection: Boolean = true) = body(xml(entry(href, isCollection)))
 private fun status(code: Int) = MockResponse().setResponseCode(code)
 private fun assertRequests(server: MockWebServer, vararg expected: Pair<String, String>) {
  assertEquals(expected.size, server.requestCount)
  expected.forEach { (method, path) ->
   val request = server.takeRequest(1, TimeUnit.SECONDS)!!
   assertEquals(method, request.method); assertEquals(path, request.path)
   assertEquals(Credentials.basic("player", "test-secret"), request.getHeader("Authorization"))
   assertNull(request.getHeader("If-Match")); assertNull(request.getHeader("If-None-Match"))
   assertNull(request.getHeader("Destination"))
   assertEquals("no-cache, no-store", request.getHeader("Cache-Control"))
   if (method == "PROPFIND") {
    assertEquals("0", request.getHeader("Depth")); assertTrue(request.body.readUtf8().contains("resourcetype"))
   } else {
    assertEquals("MKCOL", method); assertNull(request.getHeader("Depth")); assertEquals(0L, request.bodySize)
   }
  }
 }
 private fun failure(reason: NamespaceFailureReason, statusCode: Int? = null, action: () -> Unit): NamespaceProvisioningException =
  assertFailsWith<NamespaceProvisioningException>(block = action).also {
   assertEquals(reason, it.reason); assertEquals(statusCode, it.statusCode)
   assertFalse(it.toString().contains("test-secret")); assertFalse(it.toString().contains("http://"))
  }

 @Test fun existingDirectoriesNeedOnlyDepthZeroChecksAndNeverMkcolOrPut() = MockWebServer().use { server ->
  server.enqueue(collection(root)); server.enqueue(collection(game)); server.enqueue(collection(profile)); server.start()
  val dav = store(server); assertEquals(0, server.requestCount)
  dav.ensureNamespace()
  assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "PROPFIND" to profile)
 }

 @Test fun missingConfiguredRootIsNeverCreatedAndNoParentsAreVisited() = MockWebServer().use { server ->
  server.enqueue(status(404)); server.start()
  failure(NamespaceFailureReason.ROOT_MISSING, 404) { store(server).ensureNamespace() }
  assertRequests(server, "PROPFIND" to root)
 }

 @Test fun resourceLevel404AlsoProvesMissingRootWithoutWriting() = MockWebServer().use { server ->
  server.enqueue(body(xml("<d:response><d:href>$root</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>"))); server.start()
  failure(NamespaceFailureReason.ROOT_MISSING, 404) { store(server).ensureNamespace() }
  assertRequests(server, "PROPFIND" to root)
 }

 @Test fun configuredRootFileCannotBeMistakenForACollection() = MockWebServer().use { server ->
  server.enqueue(collection(root, false)); server.start()
  failure(NamespaceFailureReason.NOT_COLLECTION) { store(server).ensureNamespace() }
  assertRequests(server, "PROPFIND" to root)
 }

 @Test fun bothMissingChildrenAreCreatedInOrderWithReadbackBeforeTheNextChild() = MockWebServer().use { server ->
  server.enqueue(collection(root))
  server.enqueue(status(404)); server.enqueue(status(201)); server.enqueue(collection(game))
  server.enqueue(status(404)); server.enqueue(status(201)); server.enqueue(collection(profile)); server.start()
  store(server).ensureNamespace()
  assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game, "PROPFIND" to game,
   "PROPFIND" to profile, "MKCOL" to profile, "PROPFIND" to profile)
 }

 @Test fun onlyTheMissingProfileIsCreated() = MockWebServer().use { server ->
  server.enqueue(collection(root)); server.enqueue(collection(game))
  server.enqueue(status(404)); server.enqueue(status(201)); server.enqueue(collection(profile)); server.start()
  store(server).ensureNamespace()
  assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "PROPFIND" to profile, "MKCOL" to profile, "PROPFIND" to profile)
 }

 @Test fun aConcurrentCreator405SucceedsOnlyAfterCollectionReadback() = MockWebServer().use { server ->
  server.enqueue(collection(root)); server.enqueue(status(404)); server.enqueue(status(405))
  server.enqueue(collection(game)); server.enqueue(collection(profile)); server.start()
  store(server).ensureNamespace()
  assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game, "PROPFIND" to game, "PROPFIND" to profile)
 }

 @Test fun mkcol405IsNotSuccessWhenStillMissingOrAFile() {
  for (isFile in listOf(false, true)) MockWebServer().use { server ->
   server.enqueue(collection(root)); server.enqueue(status(404)); server.enqueue(status(405))
   server.enqueue(if (isFile) collection(game, false) else status(404)); server.start()
   failure(if (isFile) NamespaceFailureReason.NOT_COLLECTION else NamespaceFailureReason.VERIFICATION_FAILED,
    if (isFile) null else 404) { store(server).ensureNamespace() }
   assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game, "PROPFIND" to game)
  }
 }

 @Test fun mkcol201AlsoRequiresActualCollectionReadback() {
  for (isFile in listOf(false, true)) MockWebServer().use { server ->
   server.enqueue(collection(root)); server.enqueue(status(404)); server.enqueue(status(201))
   server.enqueue(if (isFile) collection(game, false) else status(404)); server.start()
   failure(if (isFile) NamespaceFailureReason.NOT_COLLECTION else NamespaceFailureReason.VERIFICATION_FAILED,
    if (isFile) null else 404) { store(server).ensureNamespace() }
   assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game, "PROPFIND" to game)
  }
 }

 @Test fun anExistingChildFileIsNeverReplaced() {
  for (atProfile in listOf(false, true)) MockWebServer().use { server ->
   server.enqueue(collection(root))
   if (atProfile) server.enqueue(collection(game))
   server.enqueue(collection(if (atProfile) profile else game, false)); server.start()
   failure(NamespaceFailureReason.NOT_COLLECTION) { store(server).ensureNamespace() }
   if (atProfile) assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "PROPFIND" to profile)
   else assertRequests(server, "PROPFIND" to root, "PROPFIND" to game)
  }
 }

 @Test fun authenticationForbiddenAndConflictStatusesRemainDistinctAtReadAndCreation() {
  val failures = mapOf(401 to NamespaceFailureReason.AUTHENTICATION, 403 to NamespaceFailureReason.FORBIDDEN,
   409 to NamespaceFailureReason.PARENT_MISSING, 405 to NamespaceFailureReason.HTTP_ERROR, 500 to NamespaceFailureReason.HTTP_ERROR)
  for ((code, reason) in failures) MockWebServer().use { server ->
   server.enqueue(status(code)); server.start()
   failure(reason, code) { store(server).ensureNamespace() }
   assertRequests(server, "PROPFIND" to root)
  }
  for ((code, reason) in failures.filterKeys { it != 405 } + mapOf(200 to NamespaceFailureReason.HTTP_ERROR, 204 to NamespaceFailureReason.HTTP_ERROR)) {
   MockWebServer().use { server ->
    server.enqueue(collection(root)); server.enqueue(status(404)); server.enqueue(status(code)); server.start()
    failure(reason, code) { store(server).ensureNamespace() }
    assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game)
   }
  }
 }

 @Test fun retryAfterZeroNeverReplaysNamespaceReadsOrMkcol() {
  for (duringCreation in listOf(false, true)) MockWebServer().use { server ->
   if (duringCreation) { server.enqueue(collection(root)); server.enqueue(status(404)) }
   server.enqueue(status(503).setHeader("Retry-After", "0"))
   server.enqueue(if (duringCreation) status(201) else collection(root)); server.start()
   failure(NamespaceFailureReason.HTTP_ERROR, 503) { store(server).ensureNamespace() }
   if (duringCreation) assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game)
   else assertRequests(server, "PROPFIND" to root)
  }
 }

 @Test fun suppliedAuthenticatorCannotReplayNamespaceAuthenticationFailures() = MockWebServer().use { server ->
  var authenticationAttempts = 0
  val client = OkHttpClient.Builder().authenticator { _, response ->
   authenticationAttempts++
   response.request.newBuilder().header("Authorization", "unexpected").build()
  }.build()
  server.enqueue(status(401)); server.start()
  failure(NamespaceFailureReason.AUTHENTICATION, 401) { store(server, client).ensureNamespace() }
  assertEquals(0, authenticationAttempts)
  assertRequests(server, "PROPFIND" to root)
 }

 @Test fun nestedAuthenticationAndForbiddenStatusesDoNotPermitCreation() {
  for (code in listOf(401, 403)) MockWebServer().use { server ->
   server.enqueue(collection(root)); server.enqueue(body(xml(entry(game, status = code)))); server.start()
   failure(if (code == 401) NamespaceFailureReason.AUTHENTICATION else NamespaceFailureReason.FORBIDDEN, code) { store(server).ensureNamespace() }
   assertRequests(server, "PROPFIND" to root, "PROPFIND" to game)
  }
 }

 @Test fun property404DoesNotMeanCollectionIsAbsentAndNeverTriggersMkcol() = MockWebServer().use { server ->
  server.enqueue(collection(root)); server.enqueue(body(xml(entry(game, status = 404)))); server.start()
  failure(NamespaceFailureReason.HTTP_ERROR, 404) { store(server).ensureNamespace() }
  assertRequests(server, "PROPFIND" to root, "PROPFIND" to game)
 }

 @Test fun disconnectAfterAcceptedMkcolIsResolvedByReadbackWithoutRepeatingWrite() = MockWebServer().use { server ->
  server.enqueue(collection(root)); server.enqueue(status(404))
  server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
  server.enqueue(collection(game)); server.enqueue(collection(profile)); server.start()
  store(server).ensureNamespace()
  assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game, "PROPFIND" to game, "PROPFIND" to profile)
 }

 @Test fun timeoutAfterAcceptedMkcolAlsoUsesExactlyOneReadback() = MockWebServer().use { server ->
  server.enqueue(collection(root)); server.enqueue(status(404))
  server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
  server.enqueue(collection(game)); server.enqueue(collection(profile)); server.start()
  store(server, OkHttpClient.Builder().readTimeout(150, TimeUnit.MILLISECONDS).build()).ensureNamespace()
  assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game, "PROPFIND" to game, "PROPFIND" to profile)
 }

 @Test fun uncertainWriteThatRemainsMissingDoesNotRetryOrCreateDescendants() = MockWebServer().use { server ->
  server.enqueue(collection(root)); server.enqueue(status(404))
  server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)); server.enqueue(status(404)); server.start()
  failure(NamespaceFailureReason.VERIFICATION_FAILED, 404) { store(server).ensureNamespace() }
  assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game, "PROPFIND" to game)
 }

 @Test fun initialNetworkFailureNeverCreatesAnything() = MockWebServer().use { server ->
  server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)); server.start()
  failure(NamespaceFailureReason.NETWORK) { store(server).ensureNamespace() }
  assertRequests(server, "PROPFIND" to root)
 }

 @Test fun failedPostWriteVerificationRetainsTypedAuthStatusAndStops() = MockWebServer().use { server ->
  server.enqueue(collection(root)); server.enqueue(status(404)); server.enqueue(status(201)); server.enqueue(status(403)); server.start()
  failure(NamespaceFailureReason.FORBIDDEN, 403) { store(server).ensureNamespace() }
  assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game, "PROPFIND" to game)
 }

 @Test fun redirectsFromRootOrMkcolNeverEscapeOrCountAsCreated() {
  for (duringCreation in listOf(false, true)) MockWebServer().use { server ->
   if (duringCreation) { server.enqueue(collection(root)); server.enqueue(status(404)) }
   server.enqueue(status(307).setHeader("Location", "http://localhost:1/outside?private=secret")); server.start()
   val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
   failure(NamespaceFailureReason.HTTP_ERROR, 307) { store(server, client).ensureNamespace() }
   if (duringCreation) assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game)
   else assertRequests(server, "PROPFIND" to root)
  }
 }

 @Test fun unsafeOrUnexpectedHrefsFailClosedBeforeAnyCreation() = MockWebServer().use { server ->
  server.start()
  val badHrefs = listOf("/", "/other/", "/dav/shattered/", "/dav-sibling/", "/d%61v/", "/dav/../dav/", "/dav/%2e%2e/dav/",
   "/dav%2f", "/dav%5c", "/dav/%252e%252e/", "https://other.example/dav/", "//other.example/dav/", "$root?cursor=secret", "$root#fragment")
  for (href in badHrefs) {
   server.enqueue(collection(href))
   failure(NamespaceFailureReason.UNSAFE_RESPONSE) { store(server).ensureNamespace() }
  }
  assertRequests(server, *Array(badHrefs.size) { "PROPFIND" to root })
 }

 @Test fun creationVerificationIsBoundToExactChildNotAnyCollectionUnderRoot() = MockWebServer().use { server ->
  server.enqueue(collection(root)); server.enqueue(status(404)); server.enqueue(status(201)); server.enqueue(collection(profile)); server.start()
  failure(NamespaceFailureReason.UNSAFE_RESPONSE) { store(server).ensureNamespace() }
  assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game, "PROPFIND" to game)
 }

 @Test fun unsafeRawBasePathsAreRejectedBeforeHttpUrlCanNormalizeThem() = MockWebServer().use { server ->
  server.start(); val origin = server.url("/").toString().removeSuffix("/")
  val paths = listOf("/dav/../other/", "/dav/./", "/dav/%2e%2e/", "/dav/.%2E/", "/dav/%2e/", "/dav%2Fother/", "/dav%5cother/",
   "/dav/%252e%252e/", "/dav/\\other/", "/dav//child/", "/dav/%00/", "/dav/%0a/", "/dav/%")
  paths.forEach { path -> assertFailsWith<IllegalArgumentException>(path) { AppendOnlyWebDav(origin + path, "shattered", "hero", "player", "test-secret") } }
  for ((game, profile) in listOf(".." to "hero", "shattered" to "../other", "shattered/other" to "hero", "shattered" to "%2e")) {
   assertFailsWith<IllegalArgumentException> { AppendOnlyWebDav(origin + root, game, profile, "player", "test-secret") }
  }
  assertEquals(0, server.requestCount)
 }

 @Test fun rootAndSingleSegmentInjectionUseOnlyTheConfiguredAnchor() = MockWebServer().use { server ->
  server.enqueue(collection("/")); server.enqueue(collection("/other-game/")); server.enqueue(collection("/other-game/isolated_profile/")); server.start()
  AppendOnlyWebDav(server.url("/").toString(), "other-game", "isolated_profile", "player", "test-secret").ensureNamespace()
  assertRequests(server, "PROPFIND" to "/", "PROPFIND" to "/other-game/", "PROPFIND" to "/other-game/isolated_profile/")
 }

 @Test fun originRootCreatesOnlyGameAndProfileCollectionsWithMatchingRootHref() = MockWebServer().use { server ->
  server.enqueue(collection("/"))
  server.enqueue(status(404)); server.enqueue(status(201)); server.enqueue(collection("/shattered/"))
  server.enqueue(status(404)); server.enqueue(status(201)); server.enqueue(collection("/shattered/hero/"))
  server.start()
  AppendOnlyWebDav(server.url("/").toString(), "shattered", "hero", "player", "test-secret").ensureNamespace()
  assertRequests(server, "PROPFIND" to "/", "PROPFIND" to "/shattered/", "MKCOL" to "/shattered/",
   "PROPFIND" to "/shattered/", "PROPFIND" to "/shattered/hero/", "MKCOL" to "/shattered/hero/", "PROPFIND" to "/shattered/hero/")
 }

 @Test fun originRootAppendTrafficStaysBelowGameProfileWithoutApiOrUploadRoutes() = MockWebServer().use { server ->
  val folder = "/shattered/hero/"
  val id = "11111111-1111-4111-8111-111111111111"
  val objectPath = folder + "pdsync-v2-object-$id.zip"
  val commitPath = folder + "pdsync-v2-commit-$id.json"
  server.enqueue(status(201)); server.enqueue(status(201))
  server.enqueue(body(xml(entry(folder), entry(commitPath, false))))
  server.enqueue(MockResponse().setBody("object")); server.enqueue(MockResponse().setBody("commit"))
  server.start()
  val dav = AppendOnlyWebDav(server.url("/").toString(), "shattered", "hero", "player", "test-secret")
  dav.writeObject(id, "object".encodeToByteArray()); dav.writeCommit(id, "commit".encodeToByteArray())
  assertEquals(listOf(id), dav.listCommits().ids)
  assertContentEquals("object".encodeToByteArray(), dav.readObject(id))
  assertContentEquals("commit".encodeToByteArray(), dav.readCommit(id))
  val expected = listOf("PUT" to objectPath, "PUT" to commitPath, "PROPFIND" to folder, "GET" to objectPath, "GET" to commitPath)
  assertEquals(expected.size, server.requestCount)
  expected.forEach { (method, path) ->
   val request = server.takeRequest(1, TimeUnit.SECONDS)!!
   assertEquals(method, request.method); assertEquals(path, request.path)
   assertEquals(Credentials.basic("player", "test-secret"), request.getHeader("Authorization"))
   if (method == "PROPFIND") assertEquals("1", request.getHeader("Depth"))
   assertFalse(request.path!!.startsWith("/api/")); assertFalse(request.path!!.startsWith("/upload/"))
  }
 }

 @Test fun baseWithoutTrailingSlashAndMatchingAbsoluteHrefsAreSupported() = MockWebServer().use { server ->
  server.start()
  server.enqueue(collection(server.url("/dav").toString())); server.enqueue(collection(server.url(game).toString())); server.enqueue(collection(profile))
  AppendOnlyWebDav(server.url("/dav").toString(), "shattered", "hero", "player", "test-secret").ensureNamespace()
  assertRequests(server, "PROPFIND" to root, "PROPFIND" to game, "PROPFIND" to profile)
 }

 @Test fun malformedAmbiguousAndUnsafeXmlNeverProvesCollectionExistence() = MockWebServer().use { server ->
  server.start()
  val documents = listOf(
   "<html/>", xml(), xml(entry(root), entry(root)), xml(entry(root), entry(game)),
   xml("<d:response><d:href>$root</d:href><d:status>HTTP/1.1 200 OK</d:status></d:response>"),
   xml(entry(root).replace("<d:href>", "<d:href><d:collection/>").replace("<d:collection/></d:resourcetype>", "</d:resourcetype>")),
   xml(entry(root).replace("<d:resourcetype><d:collection/></d:resourcetype>", "<d:resourcetype/><d:resourcetype><d:collection/></d:resourcetype>")),
   xml(entry(root).replace("<d:collection/>", "<x:collection xmlns:x=\"evil\"/>")),
   xml(entry(root).replace("<d:collection/>", "<d:collection><d:collection/></d:collection>")),
   xml(entry(root)).replace("<d:multistatus xmlns:d=\"DAV:\">", "<d:multistatus xmlns:d=\"DAV:\" truncated=\"true\">"),
   "<!DOCTYPE d:multistatus [<!ENTITY remote SYSTEM 'file:///etc/passwd'>]>" + xml(entry(root).replace(root, "&remote;")),
   xml("<x:next xmlns:x=\"evil\"/>", entry(root)),
   xml(entry(root).replace("<d:resourcetype><d:collection/></d:resourcetype>", "<d:resourcetype>" + "<d:collection>".repeat(70) + "</d:collection>".repeat(70) + "</d:resourcetype>"))
  )
  documents.forEach { document ->
   server.enqueue(body(document))
   failure(NamespaceFailureReason.UNSAFE_RESPONSE) { store(server).ensureNamespace() }
  }
  assertRequests(server, *Array(documents.size) { "PROPFIND" to root })
 }

 @Test fun partialAndOversizedProofsFailWithoutFollowingCursors() = MockWebServer().use { server ->
  server.start()
  val responses = listOf(collection(root).setHeader("Link", "</dav/?cursor=secret>; rel=\"next\""),
   collection(root).setHeader("Content-Range", "items 0-0/2"),
   body("x").setHeader("Content-Length", MAX_MANIFEST_BYTES + 1),
   MockResponse().setResponseCode(207).setChunkedBody("x".repeat(MAX_MANIFEST_BYTES + 1), 16384))
  responses.forEach { response ->
   server.enqueue(response); failure(NamespaceFailureReason.UNSAFE_RESPONSE) { store(server).ensureNamespace() }
  }
  assertRequests(server, *Array(responses.size) { "PROPFIND" to root })
 }

 @Test fun cancellationBeforeAnyRequestHasNoSideEffects() = MockWebServer().use { server ->
  server.start()
  failure(NamespaceFailureReason.CANCELLED) { store(server).ensureNamespace { false } }
  assertEquals(0, server.requestCount)
 }

 @Test fun cancellationIsCheckedBeforeEachRequestIncludingMkcolAndItsReadback() {
  for (allowedRequests in 1..6) MockWebServer().use { server ->
   server.enqueue(collection(root)); server.enqueue(status(404)); server.enqueue(status(201)); server.enqueue(collection(game))
   server.enqueue(status(404)); server.enqueue(status(201)); server.enqueue(collection(profile)); server.start()
   val expected = arrayOf("PROPFIND" to root, "PROPFIND" to game, "MKCOL" to game, "PROPFIND" to game,
    "PROPFIND" to profile, "MKCOL" to profile, "PROPFIND" to profile)
   failure(NamespaceFailureReason.CANCELLED) { store(server).ensureNamespace { server.requestCount < allowedRequests } }
   assertRequests(server, *expected.take(allowedRequests).toTypedArray())
  }
 }
}

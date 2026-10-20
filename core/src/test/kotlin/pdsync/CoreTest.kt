package pdsync
import kotlin.test.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.*
import okhttp3.mockwebserver.*

class CoreTest {
 private fun root() = Files.createTempDirectory("pds-test").also { it.resolve("game1").createDirectories(); it.resolve("game1/game.dat").writeText("alive"); it.resolve("game1/depth1.dat").writeText("floor"); it.resolve("rankings.dat").writeText("record"); it.resolve("settings.xml").writeText("device") }
 private fun snap() = Snapshots.capture(root(),Shattered,"test","v1",null)
 @Test fun roundtripAndRecords() { val s=snap(); val r=root(); val b=Files.createTempDirectory("backup"); Snapshots.restore(s,r,b,Shattered,"v1",{true}); assertEquals("record",r.resolve("rankings.dat").readText()); assertEquals("device",r.resolve("settings.xml").readText()); assertEquals(3,Snapshots.verify(s,Shattered).size) }
 @Test fun checksumFailure() { val s=snap(); assertFails { Snapshots.verify(s.copy(zip=byteArrayOf(1)),Shattered) } }
 @Test fun rollback() { val r=root(); r.resolve("game1/game.dat").writeText("original"); assertFails { Snapshots.restore(snap(),r,Files.createTempDirectory("backup"),Shattered,"v1",{true},{error("disk failure")}) }; assertEquals("original",r.resolve("game1/game.dat").readText()) }
 @Test fun deletionDoesNotResurrect() { val r=root(); r.resolve("game1/depth1.dat").deleteExisting(); r.resolve("game1/game.dat").deleteExisting(); r.resolve("game1").deleteExisting(); val s=Snapshots.capture(r,Shattered,"test","v1",null); val target=root(); Snapshots.restore(s,target,Files.createTempDirectory("backup"),Shattered,"v1",{true}); assertFalse(target.resolve("game1").exists()) }
 @Test fun aliases() { val r=root(); assertEquals(1,deduplicate(listOf(r,r.resolve(".")),Shattered).size) }
 private class Memory:Store {
  var current:RemoteHead?=null; val data=mutableMapOf<String,Snapshot>(); var failUpload=false; var corrupt=false; var failPublish=false
  override fun head()=current
  override fun upload(snapshot:Snapshot) { if(failUpload) error("interrupted"); data[snapshot.manifest.id]=snapshot }
  override fun download(manifest:Manifest)=data.getValue(manifest.id).let { if(corrupt) it.copy(zip=byteArrayOf(1)) else it }
  override fun publish(manifest:Manifest,expected:RemoteHead?) { if(failPublish) error("412"); current=RemoteHead(manifest,"\"1\"") }
 }
 @Test fun uploadInterruption() { val m=Memory(); m.failUpload=true; assertFails { Sync(m).push(snap()) }; assertNull(m.current) }
 @Test fun corruptUploadNotPublished() { val m=Memory(); m.corrupt=true; assertFails { Sync(m).push(snap()) }; assertNull(m.current) }
 @Test fun conditionFailure() { val m=Memory(); m.failPublish=true; assertFails { Sync(m).push(snap()) }; assertNull(m.current); assertEquals(1,m.data.size) }
 @Test fun offlineBranches() { val m=Memory(); val a=snap(); Sync(m).push(a); val b=snap(); val e=assertFailsWith<Conflict> { Sync(m).push(b) }; assertEquals(a.manifest.id,e.remote.manifest.id); assertEquals(b,e.local) }
 @Test fun authError() { MockWebServer().use { s -> s.enqueue(MockResponse().setResponseCode(401)); s.start(); assertFails { WebDav(s.url("/").toString(),"shattered","test","bad","bad").head() } } }
 @Test fun webdavConditionalWrite() { MockWebServer().use { s -> s.enqueue(MockResponse().setResponseCode(412)); s.start(); val m=snap().manifest; assertFails { WebDav(s.url("/").toString(),"shattered","test","a","b").publish(m,RemoteHead(m,"\"old\"")) }; assertEquals("\"old\"",s.takeRequest().getHeader("If-Match")) } }
 @Test fun runningDoesNotBlockRestoreButVersionIsStillRequired() { assertNotNull(Snapshots.restore(snap(),root(),Files.createTempDirectory("backup"),Shattered,"v1",{false})); assertFails { Snapshots.restore(snap(),root(),Files.createTempDirectory("backup"),Shattered,"v2",{true}) } }
 @Test fun gameRestartBeforeRestoreDoesNotOverwriteNewerSave() {
  val target=root(); val backup=Files.createTempDirectory("backup"); var guardCalls=0
  assertFailsWith<IllegalArgumentException> {
   Snapshots.restore(snap(),target,backup,Shattered,"v1",{
    guardCalls++
    if(guardCalls==1) true else {
     target.resolve("game1/game.dat").writeText("newer save from restarted game")
     false
    }
   })
  }
  assertEquals(2,guardCalls)
  assertEquals("newer save from restarted game",target.resolve("game1/game.dat").readText())
  assertTrue(Files.list(backup).use { it.count() } == 2L)
 }
 @Test fun actualHttpRoundtrip() { MockWebServer().use { server ->
  val snapshot=snap()
  server.enqueue(MockResponse().setResponseCode(404))
  server.enqueue(MockResponse().setResponseCode(201))
  server.enqueue(MockResponse().setResponseCode(201))
  server.enqueue(MockResponse().setBody(json.encodeToString(Manifest.serializer(),snapshot.manifest)))
  server.enqueue(MockResponse().setBody(okio.Buffer().write(snapshot.zip)))
  server.enqueue(MockResponse().setResponseCode(201))
  server.start()
  assertEquals(snapshot.manifest,Sync(WebDav(server.url("/").toString(),"shattered","test","demo","demo")).push(snapshot))
  val requests=(1..6).map { server.takeRequest() }
  assertEquals(listOf("GET","PUT","PUT","GET","GET","PUT"),requests.map { it.method })
  assertEquals("*",requests.last().getHeader("If-None-Match"))
 } }
 @Test fun zipTraversalRejected() {
  val output=java.io.ByteArrayOutputStream()
  java.util.zip.ZipOutputStream(output).use { it.putNextEntry(java.util.zip.ZipEntry("game1/../../evil")); it.write(byteArrayOf(1)); it.closeEntry() }
  val zip=output.toByteArray(); val s=snap()
  assertFails { Snapshots.verify(Snapshot(s.manifest.copy(sha256=hash(zip)),zip),Shattered) }
 }
 @Test fun fileChecksumMismatch() { val s=snap(); assertFails { Snapshots.verify(s.copy(manifest=s.manifest.copy(files=s.manifest.files + ("rankings.dat" to "bad"))),Shattered) } }
 @Test fun weakEtagBlocked() { val s=snap(); assertFails { WebDav("http://127.0.0.1:1","shattered","test","a","b").publish(s.manifest,RemoteHead(s.manifest,"W/\"1\"")) } }
 @Test fun symlinkRejected() { val r=root(); val alias=r.resolve("game1/link.dat"); Files.createSymbolicLink(alias,r.resolve("settings.xml")); assertFails { Snapshots.capture(r,Shattered,"test","v1",null) } }
 @Test fun symlinkCaptureRootRejected() {
  val target=root(); val holder=Files.createTempDirectory("pds-root-link"); val alias=holder.resolve("saves")
  Files.createSymbolicLink(alias,target)
  assertFailsWith<IllegalArgumentException> { Snapshots.capture(alias,Shattered,"test","v1",null) }
  assertEquals("alive",target.resolve("game1/game.dat").readText())
 }
 @Test fun regularFileCaptureRootRejected() {
  val target=Files.createTempFile("pds-root-file",".dat"); target.writeText("save file")
  assertFailsWith<IllegalArgumentException> { Snapshots.capture(target,Shattered,"test","v1",null) }
  assertEquals("save file",target.readText())
 }
 @Test fun backupAliasCannotOverlapSaves() { val r=root(); val holder=Files.createTempDirectory("pds-alias"); val alias=holder.resolve("saves"); Files.createSymbolicLink(alias,r); assertFails { Snapshots.restore(snap(),r,alias.resolve("backup"),Shattered,"v1",{true}) }; assertEquals("alive",r.resolve("game1/game.dat").readText()) }
 private fun archive(entries: Map<String,ByteArray>): Snapshot {
  val out=java.io.ByteArrayOutputStream()
  java.util.zip.ZipOutputStream(out).use { z -> entries.forEach { (name,bytes) -> z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(bytes); z.closeEntry() } }
  val zip=out.toByteArray(); return Snapshot(snap().manifest.copy(sha256=hash(zip),files=entries.mapValues { hash(it.value) }),zip)
 }
 @Test fun portablePathsRejected() {
  listOf("game1/./floor.dat","game1/../game2/game.dat","game1/NUL.dat","game1/floor.","game1/C:evil","/game1/game.dat","game1/evil\\name","game1//floor.dat").forEach { name ->
   val s=archive(mapOf("game1/game.dat" to byteArrayOf(1),name to byteArrayOf(2)))
   assertFails { Snapshots.verify(s,Shattered) }
  }
  assertFails { Snapshots.verify(archive(mapOf("game1/game.dat" to byteArrayOf(1),"game1/GAME.dat" to byteArrayOf(2))),Shattered) }
 }
 @Test fun expandedSizeLimit() {
  val out=java.io.ByteArrayOutputStream()
  java.util.zip.ZipOutputStream(out).use { z -> z.putNextEntry(java.util.zip.ZipEntry("game1/game.dat")); val chunk=ByteArray(8192); repeat(MAX_SNAPSHOT_BYTES/8192+1) { z.write(chunk) }; z.closeEntry() }
  val zip=out.toByteArray(); val s=snap(); assertFails { Snapshots.verify(Snapshot(s.manifest.copy(sha256=hash(zip)),zip),Shattered) }
 }
 @Test fun tooManyManifestEntries() { val s=snap(); val files=(0..MAX_SNAPSHOT_FILES).associate { "game1/file$it.dat" to "hash" }; assertFails { Snapshots.verify(s.copy(manifest=s.manifest.copy(files=files)),Shattered) } }
 @Test fun partialRestoreRollsBackAllRecords() {
  val target=root(); val original=Snapshots.capture(target,Shattered,"test","v1",null)
  val source=root(); source.resolve("game1/game.dat").writeText("changed"); source.resolve("rankings.dat").writeText("changed-record")
  val backup=Files.createTempDirectory("backup")
  assertFails { Snapshots.restore(Snapshots.capture(source,Shattered,"test","v1",null),target,backup,Shattered,"v1",{true},afterFileWrite={error("mid-write failure")}) }
  assertEquals(Snapshots.verify(original,Shattered).mapValues { hash(it.value) },Snapshots.capture(target,Shattered,"test","v1",null).manifest.files)
  assertTrue(Files.list(backup).use { it.count() } == 2L)
 }
 @Test fun symlinkRestoreTargetRejected() {
  val target=root(); Files.createSymbolicLink(target.resolve("game1/link.dat"),target.resolve("settings.xml"))
  assertFails { Snapshots.restore(snap(),target,Files.createTempDirectory("backup"),Shattered,"v1",{true}) }
  assertEquals("alive",target.resolve("game1/game.dat").readText())
 }
 @Test fun retryAlreadyPublishedSnapshot() { val m=Memory(); val s=snap(); Sync(m).push(s); m.failUpload=true; m.failPublish=true; assertEquals(s.manifest,Sync(m).push(s)); assertEquals(1,m.data.size) }
 @Test fun partialUploadRetryIsIdempotent() { MockWebServer().use { server ->
  val s=snap(); server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(MockResponse().setResponseCode(503))
  server.enqueue(MockResponse().setResponseCode(412)); server.enqueue(MockResponse().setBody(okio.Buffer().write(s.zip))); server.enqueue(MockResponse().setResponseCode(201)); server.start()
  val store=WebDav(server.url("/").toString(),"shattered","test","demo","demo")
  assertFails { store.upload(s) }; store.upload(s)
  val requests=(1..5).map { server.takeRequest() }
  assertEquals(requests[0].path,requests[2].path); assertEquals("*",requests[2].getHeader("If-None-Match"))
  assertEquals(listOf("PUT","PUT","PUT","GET","PUT"),requests.map { it.method })
 } }
 @Test fun retryRejectsDifferentImmutableObject() { MockWebServer().use { server -> server.enqueue(MockResponse().setResponseCode(412)); server.enqueue(MockResponse().setBody("different")); server.start(); assertFails { WebDav(server.url("/").toString(),"shattered","test","demo","demo").upload(snap()) }; assertEquals(2,server.requestCount) } }
 @Test fun credentialUrlsAndFakeLoopbackRejected() {
  listOf("http://localhost:80@evil.example/","http://localhost.evil.example/","https://user:password@example.com/","https://example.com/?token=secret","https://example.com/#secret").forEach { url -> assertFails { WebDav(url,"shattered","test","demo","demo") } }
 }
 @Test fun redirectsNeverFollowed() { MockWebServer().use { server -> server.start(); server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/other"))); assertFails { WebDav(server.url("/").toString(),"shattered","test","demo","demo").head() }; assertEquals(1,server.requestCount) } }
 @Test fun oversizedRemoteManifestRejected() { MockWebServer().use { server -> server.enqueue(MockResponse().setBody("x").setHeader("Content-Length",MAX_MANIFEST_BYTES+1)); server.start(); assertFails { WebDav(server.url("/").toString(),"shattered","test","demo","demo").head() } } }
 @Test fun malformedEtagRejected() { val s=snap(); listOf("\"a\"b\"","*","W/\"x\"",null).forEach { tag -> assertFails { WebDav("http://127.0.0.1:1","shattered","test","demo","demo").publish(s.manifest,RemoteHead(s.manifest,tag)) } } }
 @Test fun pendingSurvivesRestartWithoutCredentials() {
  val root=Files.createTempDirectory("pds-queue"); val queue=PendingSnapshots(root); val s=snap(); val endpoint="https://example.com/dav"
  queue.save(endpoint,s); val loaded=PendingSnapshots(root).load(endpoint)!!
  assertEquals(s.manifest,loaded.manifest); assertContentEquals(s.zip,loaded.zip)
  val marker=root.resolve("active.txt").readText(); assertFalse(marker.contains(endpoint)); assertFalse(marker.contains("password"))
  assertFails { queue.load("https://another.example.com") }; queue.clear(); assertNull(queue.load(endpoint))
 }
 @Test fun lostPublishResponseRetryReconcilesHead() { MockWebServer().use { server ->
  val s=snap()
  server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(MockResponse().setResponseCode(201))
  server.enqueue(MockResponse().setBody(s.manifest.toJson())); server.enqueue(MockResponse().setBody(okio.Buffer().write(s.zip)))
  server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
  server.enqueue(MockResponse().setBody(s.manifest.toJson()).setHeader("ETag","\"committed\""))
  server.enqueue(MockResponse().setBody(s.manifest.toJson())); server.enqueue(MockResponse().setBody(okio.Buffer().write(s.zip))); server.start()
  val store=WebDav(server.url("/").toString(),"shattered","test","demo","demo")
  assertFails { Sync(store).push(s) }; assertEquals(s.manifest,Sync(store).push(s))
  val requests=(1..9).map { server.takeRequest() }; assertEquals(3,requests.count { it.method == "PUT" })
 } }
 @Test fun chunkedManifestSizeLimit() { MockWebServer().use { server -> server.enqueue(MockResponse().setChunkedBody("x".repeat(MAX_MANIFEST_BYTES+1),8192)); server.start(); assertFails { WebDav(server.url("/").toString(),"shattered","test","demo","demo").head() } } }
 @Test fun compressedZipSizeLimit() { val s=snap(); assertFails { Snapshots.verify(s.copy(zip=ByteArray(MAX_SNAPSHOT_BYTES+1)),Shattered) } }
}

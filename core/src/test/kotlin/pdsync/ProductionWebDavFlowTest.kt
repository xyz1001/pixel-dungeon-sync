package pdsync

import okhttp3.Credentials
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import java.util.jar.JarOutputStream
import java.util.jar.JarEntry
import java.util.jar.Manifest as JarManifest
import kotlin.io.path.*
import kotlin.test.*

/** Real HTTP + production DAV/session/version/config paths. No CAS, ETag, external service or game launch. */
class ProductionWebDavFlowTest {
 @TempDir lateinit var temp:Path
 private class Dav:AutoCloseable {
  val server=MockWebServer()
  val contents=ConcurrentHashMap<String,ByteArray>()
  val folders=ConcurrentHashMap.newKeySet<String>().apply{add("/dav/")}
  val failCommitAfterAccept=AtomicBoolean(false);val failObjects=AtomicBoolean(false)
  val failCommitReads=AtomicBoolean(false)
  val invalidProtocol=AtomicBoolean(false)
  private fun entry(path:String,folder:Boolean)="<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype>${if(folder)"<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
  init {
   server.dispatcher=object:Dispatcher(){
    override fun dispatch(r:RecordedRequest):MockResponse {
     if(r.getHeader("If-Match")!=null || r.getHeader("If-None-Match")!=null || r.method !in setOf("GET","PUT","PROPFIND","MKCOL"))invalidProtocol.set(true)
     if(r.getHeader("Authorization")!=Credentials.basic("fixture",""))return MockResponse().setResponseCode(401)
     val path=r.path!!.substringBefore('?')
     return when(r.method){
      "MKCOL"->{folders.add(path);MockResponse().setResponseCode(201)}
      "PROPFIND"->{
       if(path !in folders)MockResponse().setResponseCode(404) else {
        val children=if(r.getHeader("Depth")=="1")contents.keys.filter{it.startsWith(path)}.sorted().joinToString(""){entry(it,false)} else ""
        MockResponse().setResponseCode(207).setHeader("Content-Type","application/xml").setBody("<d:multistatus xmlns:d=\"DAV:\">${entry(path,true)}$children</d:multistatus>")
       }
      }
      "GET"->if(path.endsWith(".zip")&&failObjects.get() || path.contains("-commit-")&&failCommitReads.get())MockResponse().setResponseCode(503) else contents[path]?.let{MockResponse().setBody(Buffer().write(it))} ?: MockResponse().setResponseCode(404)
      "PUT"->{
       if(path.endsWith("head.json"))invalidProtocol.set(true)
       val bytes=r.body.readByteArray();val prior=contents.putIfAbsent(path,bytes)
       if(prior!=null && !prior.contentEquals(bytes))invalidProtocol.set(true)
       val fail=path.contains("-commit-")&&failCommitAfterAccept.compareAndSet(true,false)
       if(fail)failCommitReads.set(true)
       MockResponse().setResponseCode(if(fail)503 else 201)
      }
      else->MockResponse().setResponseCode(405)
     }
    }
   };server.start()
  }
  val endpoint get()=server.url("/dav/").toString()
  fun store()=AppendOnlyWebDav(endpoint,"shattered","e2e","fixture","")
  fun fingerprint()=contents.mapValues{hash(it.value)}
  override fun close(){server.close();assertFalse(invalidProtocol.get(),"CAS/ETag/head rewrite or immutable overwrite attempted")}
 }
 private val target:Path by lazy {
  System.getenv("PDSYNC_TEST_GAME_JAR")?.let{Path.of(it)} ?: temp.resolve("format-fixture.jar").also { file ->
   // Metadata-only stand-in for portable CI; Windows validation supplies the existing official JAR.
   val m=JarManifest();m.mainAttributes.apply {
    putValue("Manifest-Version","1.0");putValue("Specification-Title","Shattered Pixel Dungeon");putValue("Specification-Version","3.3.8")
    putValue("Implementation-Title","com.shatteredpixel.shatteredpixeldungeon");putValue("Implementation-Version","896")
    putValue("Main-Class","com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher")
   }
   JarOutputStream(Files.newOutputStream(file),m).use{it.putNextEntry(JarEntry("com/shatteredpixel/shatteredpixeldungeon/desktop/DesktopLauncher.class"));it.write(byteArrayOf(1));it.closeEntry()}
  }
 }
 private fun gzip(value:String):ByteArray=java.io.ByteArrayOutputStream().also{out->GZIPOutputStream(out).use{it.write(value.encodeToByteArray())}}.toByteArray()
 private fun seed(root:Path,count:Int) {
  root.createDirectories();root.resolve("rankings.dat").writeBytes(gzip("{\"total\":7,\"records\":[]}"))
  repeat(count){i->val slot=root.resolve("game${i+1}").createDirectory();slot.resolve("game.dat").writeBytes(gzip("{\"version\":896,\"init_ver\":896,\"hero\":{\"name\":\"synthetic-${i+1}\"}}"));slot.resolve("depth1.dat").writeBytes(gzip("floor-${i+1}-base"));slot.resolve("depth2-branch1.dat").writeBytes(gzip("branch-${i+1}-base"))}
 }
 private fun config(device:String,dav:Dav):AppConfig {
  val root=temp.resolve(device).resolve("saves").createDirectories()
  return AppConfig(saveRoot=root.toString(),selectedSaveRoot=root.toString(),endpoint=dav.endpoint,profile="e2e",targetGame=target.toString(),username="fixture",password="",onboardingProgress=OnboardingProgress.COMPLETE)
 }
 private fun open(c:AppConfig)=AppendProfileSession(c,temp.resolve(Path.of(c.saveRoot).parent.fileName.toString()).resolve("app-state"),captureQuietMillis=0)
 private fun published(s:AppendProfileSession,dav:Dav)=assertIs<AppendPushResult.Success>(s.push(dav.store()){true}).commit
 private fun restore(s:AppendProfileSession,dav:Dav)=s.restoreRemote(s.readRemote(dav.store()).single().entry.manifest.id,dav.store()){true}
 private fun advance(s:AppendProfileSession,slot:Int,value:String) {
  s.root.resolve("game$slot/depth1.dat").writeBytes(gzip(value));s.confirmRunIdentity("game$slot",s.observedFiles(),true){true}
 }
 private fun configured(device:String,dav:Dav):AppConfig {
  val c=config(device,dav);val file=ConfigFile(temp.resolve(device).resolve("config.json"));val setup=ConnectionSetup(c)
  setup.update(c,"");assertIs<ConnectionTestResult.Success>(setup.connect());assertFalse(file.exists());setup.applyFilePassword(file)
  val loaded=file.load()!!;assertEquals("",loaded.password);assertEquals(c.selectedSaveRoot,loaded.selectedSaveRoot)
  return loaded.copy(saveRoot=loaded.selectedSaveRoot!!)
 }
 @Test fun twoDevicesMergeThroughProductionHttpAndPreserveMetadataOnRestart()=Dav().use{dav->
  val ca=configured("A",dav);val cb=configured("B",dav);seed(Path.of(ca.saveRoot),2)
  val a=open(ca);val b=open(cb);val base=published(a,dav);restore(b,dav)
  assertEquals(base.snapshot.files,b.observedFiles());assertEquals(base.snapshot.directoryMetadata!!.runs,(b.directoryStatus() as SidecarRead.Valid).value.runs)
  assertEquals(base.id,(b.directoryStatus() as SidecarRead.Valid).value.lastSynced!!.commitId)
  assertTrue(base.snapshot.files.keys.none(::isDirectoryMetadata));assertEquals(7,SaveSummary.read(b.root).plays)
  advance(a,1,"A1");published(a,dav);advance(b,2,"B1")
  val merge=assertIs<AppendPushResult.Success>(b.mergeCurrent(dav.store()){true}).commit
  assertEquals(2,merge.snapshot.saveVersions!!.runs.size);assertContentEquals(gzip("A1"),b.root.resolve("game1/depth1.dat").readBytes());assertContentEquals(gzip("B1"),b.root.resolve("game2/depth1.dat").readBytes())
  val files=b.observedFiles();b.renameCurrent("隔离进度"){true};assertEquals(files,b.observedFiles());val renamed=published(b,dav)
  assertEquals(files,renamed.snapshot.files);assertEquals("隔离进度",renamed.snapshot.directoryMetadata!!.displayName)
  val restarted=open(cb);assertEquals(renamed.id,restarted.baseline());assertEquals("隔离进度",restarted.syncHistory().currentName)
  restore(a,dav);advance(a,1,"left-fork");published(a,dav);advance(restarted,1,"right-fork")
  val fork=assertIs<AppendPushResult.Success>(restarted.mergeCurrent(dav.store()){true}).commit
  assertEquals(3,fork.snapshot.saveVersions!!.runs.size)
  val children=fork.snapshot.directoryMetadata!!.runs.values.filter{it.parentRunUUIDs.isNotEmpty()};assertEquals(2,children.size);assertEquals(2,children.map{it.runUUID}.distinct().size)
 }
 @Test fun sixSlotAndGlobalRecordPreflightFailuresNeverWriteRemoteOrPartiallyApply()=Dav().use{dav->
  val ca=configured("full-A",dav);val cb=configured("full-B",dav);seed(Path.of(ca.saveRoot),6)
  val a=open(ca);val b=open(cb);published(a,dav);restore(b,dav)
  advance(a,1,"left");published(a,dav);advance(b,1,"right")
  val local=b.observedFiles();val remote=dav.fingerprint()
  assertContains(assertFailsWith<RunMergeException>{b.mergeCurrent(dav.store()){true}}.message!!,"需要 7")
  assertEquals(local,b.observedFiles());assertEquals(remote,dav.fingerprint());assertFalse(b.hasPending())
  a.root.resolve("rankings.dat").writeBytes(gzip("{\"total\":8}"));published(a,dav)
  b.root.resolve("rankings.dat").writeBytes(gzip("{\"total\":9}"));val globalBefore=b.observedFiles();val remoteBefore=dav.fingerprint()
  assertContains(assertFailsWith<RunMergeException>{b.mergeCurrent(dav.store()){true}}.message!!,"全局记录")
  assertEquals(globalBefore,b.observedFiles());assertEquals(remoteBefore,dav.fingerprint())
 }
 @Test fun acceptedThenFailedUploadRetriesExactKeysAndReadFailureDoesNotRestore()=Dav().use{dav->
  val ca=configured("retry-A",dav);val cb=configured("retry-B",dav);seed(Path.of(ca.saveRoot),1)
  val a=open(ca);val b=open(cb);dav.failCommitAfterAccept.set(true)
  assertFails{a.push(dav.store()){true}};assertTrue(a.hasPending());val remote=dav.fingerprint()
  dav.failCommitReads.set(false)
  val restarted=open(ca);val retry=published(restarted,dav);assertEquals(remote,dav.fingerprint());assertFalse(restarted.hasPending())
  val observed=b.readRemote(dav.store()).single();dav.failObjects.set(true)
  assertFails{b.restoreRemote(observed.entry.manifest.id,dav.store()){true}};assertTrue(b.observedFiles().isEmpty());assertNull(b.baseline());assertFalse(b.hasPending())
  dav.failObjects.set(false);restore(b,dav);assertEquals(retry.snapshot.files,b.observedFiles())
  val before=dav.fingerprint();val duplicate=published(restarted,dav)
  assertEquals(retry.id,duplicate.id);assertEquals(before,dav.fingerprint(),"Repeated unchanged upload must not manufacture another branch")
  advance(b,1,"remote-advance");published(b,dav);val aheadBefore=dav.fingerprint()
  val ahead=assertIs<AppendPushResult.Success>(restarted.push(dav.store()){true})
  assertTrue(ahead.remoteAhead);assertEquals(retry.id,ahead.commit.id);assertEquals(aheadBefore,dav.fingerprint())
 }
 @Test fun metadataApplyFailureKeepsAppliedBytesAndRequiresExplicitRecoveryBeforeRetry()=Dav().use{dav->
  val ca=configured("restore-A",dav);val cb=configured("restore-B",dav);seed(Path.of(ca.saveRoot),1)
  val a=open(ca);val b=open(cb);published(a,dav);val chosen=b.readRemote(dav.store()).single().entry.manifest.id
  val meta=b.root.resolve(DIRECTORY_METADATA_FILE).createDirectory();meta.resolve("blocked").writeText("fixture")
  assertFails{b.restoreRemote(chosen,dav.store()){true}};assertEquals(a.observedFiles(),b.observedFiles());assertTrue(b.hasPending());assertFalse(b.retryablePending())
  assertFails{b.push(dav.store()){true}};assertTrue(b.history().none{it.reason.contains("备份")})
  meta.resolve("blocked").deleteExisting();meta.deleteExisting();b.archivePending();restore(b,dav)
  val files=b.observedFiles();restore(b,dav);assertEquals(files,b.observedFiles());assertFalse(b.hasPending())
  b.restoreRemote(chosen,dav.store()){false};assertEquals(files,b.observedFiles())
 }
 private fun rankingRecord(id:String,score:Int,win:Boolean=false):kotlinx.serialization.json.JsonObject = json.parseToJsonElement("""{
  "__className":"com.shatteredpixel.shatteredpixeldungeon.Rankings${'$'}Record",
  "gameID":"$id","score":$score,"win":$win,"custom_seed":"","daily":false,
  "class":"WARRIOR","tier":1,"level":1,"depth":1,"ascending":false,
  "date":"2026-10-11","version":"v3.3.8","gameData":{"opaque":{"keep":9223372036854775807}}
 }""").let { it as kotlinx.serialization.json.JsonObject }
 private fun rankings(vararg records:kotlinx.serialization.json.JsonObject):ByteArray {
  val wins=records.count { it["win"].toString()=="true" }
  return gzip("""{"records":[${records.joinToString(",")}],"total":${records.size},"won":$wins,"latest":${if(records.isEmpty())-1 else 0},"daily_history_dates":[],"daily_history_scores":[],"extension":{"preserve":true}}""")
 }
 private fun globals(s:AppendProfileSession,badge:String,record:kotlinx.serialization.json.JsonObject?,item:String) {
  s.root.resolve("badges.dat").writeBytes(gzip("""{"badges":[$badge]}"""))
  s.root.resolve("rankings.dat").writeBytes(if(record==null)rankings() else rankings(record))
  s.root.resolve("journal.dat").writeBytes(gzip(if(item.isEmpty()) """{"documents":{}}""" else """{"catalog_classes":["$item"],"catalog_seen":[true],"catalog_uses":[1],"documents":{}}"""))
 }
 @Test fun globalDomainsMergeThroughProductionHttpAndDuplicateEventsStaySingle()=Dav().use { dav ->
  val ca=configured("global-A",dav);val cb=configured("global-B",dav);seed(Path.of(ca.saveRoot),1)
  val a=open(ca);val b=open(cb);globals(a,"",null,"");published(a,dav);restore(b,dav)
  val left=rankingRecord(java.util.UUID.randomUUID().toString(),100,true)
  val right=rankingRecord(java.util.UUID.randomUUID().toString(),90)
  globals(a,"\"MONSTERS_SLAIN_1\"",left,"com.shatteredpixel.shatteredpixeldungeon.items.food.Food");published(a,dav)
  globals(b,"\"GOLD_COLLECTED_1\"",right,"com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing")
  val merged=assertIs<AppendPushResult.Success>(b.mergeCurrent(dav.store()){true}).commit
  val records=GlobalBundles.decode(b.root.resolve("rankings.dat").readBytes(),"rankings.dat")
  assertEquals(2,globalInt(records["total"],"total"));assertEquals(1,globalInt(records["won"],"won"))
  assertEquals(2,(records["records"] as kotlinx.serialization.json.JsonArray).size)
  assertEquals(setOf("MONSTERS_SLAIN_1","GOLD_COLLECTED_1"),globalStringSet(GlobalBundles.decode(b.root.resolve("badges.dat").readBytes(),"badges.dat")["badges"],"badges"))
  val journal=GlobalBundles.decode(b.root.resolve("journal.dat").readBytes(),"journal.dat")
  assertEquals(2,(journal["catalog_classes"] as kotlinx.serialization.json.JsonArray).size)
  assertEquals(2,merged.parents.size);assertTrue(merged.snapshot.files.keys.none(::isDirectoryMetadata))
  val content=b.observedFiles();val remote=dav.fingerprint();assertEquals(merged.id,published(b,dav).id)
  assertEquals(content,b.observedFiles());assertEquals(remote,dav.fingerprint())
  restore(a,dav);assertEquals(content,a.observedFiles())

  // One genuine completed-game ID copied to both branches must increase totals only once.
  val common=rankingRecord(java.util.UUID.randomUUID().toString(),110)
  a.root.resolve("rankings.dat").writeBytes(rankings(common,left,right));published(a,dav)
  b.root.resolve("rankings.dat").writeBytes(rankings(common,left,right))
  b.root.resolve("badges.dat").writeBytes(gzip("""{"badges":["MONSTERS_SLAIN_1","GOLD_COLLECTED_1","ITEM_LEVEL_1"]}"""))
  val dedup=assertIs<AppendPushResult.Success>(b.mergeCurrent(dav.store()){true}).commit
  assertEquals(3,SaveSummary.read(b.root).plays)
  val after=b.observedFiles();assertIs<AppendPushResult.Success>(b.mergeCurrent(dav.store()){true})
  assertEquals(after,b.observedFiles());assertEquals(3,SaveSummary.read(b.root).plays)
 }
 @Test fun highestElevenRankingRowsKeepFullEventCountersThroughProductionHttp()=Dav().use { dav ->
  val ca=configured("top-A",dav);val cb=configured("top-B",dav);seed(Path.of(ca.saveRoot),1)
  val a=open(ca);val b=open(cb)
  val rows=(1..10).map { rankingRecord("00000000-0000-0000-0000-${it.toString().padStart(12,'0')}",it*100) }
  a.root.resolve("rankings.dat").writeBytes(rankings(*rows.toTypedArray()));published(a,dav);restore(b,dav)
  val active=b.observedFiles().filterKeys { it.startsWith("game1/") }
  val high=rankingRecord("00000000-0000-0000-0000-000000000011",10000)
  val lowWin=rankingRecord("00000000-0000-0000-0000-000000000012",1,true)
  a.root.resolve("rankings.dat").writeBytes(rankings(*(rows+high).toTypedArray()));published(a,dav)
  b.root.resolve("rankings.dat").writeBytes(rankings(*(rows+lowWin).toTypedArray()))
  val merged=assertIs<AppendPushResult.Success>(b.mergeCurrent(dav.store()){true}).commit
  val output=GlobalBundles.decode(b.root.resolve("rankings.dat").readBytes(),"rankings.dat")
  val kept=output["records"] as kotlinx.serialization.json.JsonArray
  assertEquals(11,kept.size);assertEquals(high,kept.first());assertFalse(kept.contains(lowWin))
  assertEquals(12,globalInt(output["total"],"total"));assertEquals(1,globalInt(output["won"],"won"))
  assertEquals(active,b.observedFiles().filterKeys { it.startsWith("game1/") });assertEquals(2,merged.parents.size)
  val before=b.observedFiles();val remote=dav.fingerprint()
  assertIs<AppendPushResult.Success>(b.mergeCurrent(dav.store()){true})
  assertEquals(before,b.observedFiles());assertTrue(remote.all { (path,hash) -> dav.fingerprint()[path]==hash })
  assertEquals(12,SaveSummary.read(b.root).plays)
 }
 @Test fun journalConcurrentCountsKeepMaximumThroughProductionHttp()=Dav().use { dav ->
  val ca=configured("max-A",dav);val cb=configured("max-B",dav);seed(Path.of(ca.saveRoot),1)
  val a=open(ca);val b=open(cb)
  fun counts(n:Int)=gzip("""{"catalog_classes":["com.shatteredpixel.shatteredpixeldungeon.items.food.Food"],"catalog_seen":[true],"catalog_uses":[$n],"documents":{}}""")
  a.root.resolve("journal.dat").writeBytes(counts(2));published(a,dav);restore(b,dav)
  a.root.resolve("journal.dat").writeBytes(counts(4));published(a,dav)
  b.root.resolve("journal.dat").writeBytes(counts(7))
  assertIs<AppendPushResult.Success>(b.mergeCurrent(dav.store()){true})
  fun current()=GlobalBundles.decode(b.root.resolve("journal.dat").readBytes(),"journal.dat")["catalog_uses"].toString()
  assertEquals("[7]",current())
  val bytes=b.root.resolve("journal.dat").readBytes()
  assertIs<AppendPushResult.Success>(b.mergeCurrent(dav.store()){true})
  assertContentEquals(bytes,b.root.resolve("journal.dat").readBytes());assertEquals("[7]",current())
 }
 @Test fun rankingMissingEvidenceAndSoleResetRetainProgressThroughProductionHttp()=Dav().use { dav ->
  val ca=configured("fallback-A",dav);val cb=configured("fallback-B",dav);seed(Path.of(ca.saveRoot),1)
  val a=open(ca);val b=open(cb)
  val old=rankingRecord("00000000-0000-0000-0000-000000000101",100)
  val left=rankingRecord("00000000-0000-0000-0000-000000000102",300)
  val right=rankingRecord("00000000-0000-0000-0000-000000000103",200,true)
  fun counts(rows:List<kotlinx.serialization.json.JsonObject>,total:Int,won:Int):ByteArray {
   val value=GlobalBundles.decode(rankings(*rows.toTypedArray()),"rankings.dat")
   return GlobalBundles.encode(kotlinx.serialization.json.JsonObject(value+mapOf("total" to kotlinx.serialization.json.JsonPrimitive(total),"won" to kotlinx.serialization.json.JsonPrimitive(won))))
  }
  a.root.resolve("rankings.dat").writeBytes(counts(listOf(old),100,20));published(a,dav);restore(b,dav)
  a.root.resolve("rankings.dat").writeBytes(counts(listOf(old,left),101,20));published(a,dav)
  b.root.resolve("rankings.dat").writeBytes(counts(listOf(old,right),106,21)) // Missing five event IDs: do not add this delta.
  assertIs<AppendPushResult.Success>(b.mergeCurrent(dav.store()){true})
  fun current()=GlobalBundles.decode(b.root.resolve("rankings.dat").readBytes(),"rankings.dat")
  assertEquals(106,globalInt(current()["total"],"total"));assertEquals(21,globalInt(current()["won"],"won"))
  assertEquals(3,(current()["records"] as kotlinx.serialization.json.JsonArray).size)
  restore(a,dav)
  a.root.resolve("rankings.dat").writeBytes(counts(emptyList(),0,0));published(a,dav)
  assertIs<AppendPushResult.Success>(b.mergeCurrent(dav.store()){true}) // Only the remote rankings file changed.
  assertEquals(106,globalInt(current()["total"],"total"));assertEquals(21,globalInt(current()["won"],"won"))
  assertEquals(3,(current()["records"] as kotlinx.serialization.json.JsonArray).size)
  val bytes=b.root.resolve("rankings.dat").readBytes()
  assertIs<AppendPushResult.Success>(b.mergeCurrent(dav.store()){true})
  assertContentEquals(bytes,b.root.resolve("rankings.dat").readBytes())
 }
 @Test fun malformedGlobalCountersFailPreflightWithoutAnyWrites()=Dav().use { dav ->
  val ca=configured("ambiguous-A",dav);val cb=configured("ambiguous-B",dav);seed(Path.of(ca.saveRoot),1)
  val a=open(ca);val b=open(cb);globals(a,"",null,"");published(a,dav);restore(b,dav)
  val item="com.shatteredpixel.shatteredpixeldungeon.items.food.Food"
  globals(a,"\"MONSTERS_SLAIN_1\"",null,item);published(a,dav)
  globals(b,"\"GOLD_COLLECTED_1\"",null,item)
  b.root.resolve("journal.dat").writeBytes(gzip("""{"catalog_classes":["$item"],"catalog_seen":[true],"catalog_uses":["broken"],"documents":{}}"""))
  val local=b.observedFiles();val remote=dav.fingerprint();val baseline=b.baseline()
  val sidecar=b.root.resolve(DIRECTORY_METADATA_FILE);val sidecarBytes=sidecar.readBytes();val sidecarTime=Files.getLastModifiedTime(sidecar)
  assertContains(assertFailsWith<RunMergeException>{b.mergeCurrent(dav.store()){true}}.message!!,"非负整数")
  assertEquals(local,b.observedFiles());assertEquals(remote,dav.fingerprint());assertEquals(baseline,b.baseline());assertFalse(b.hasPending())
  assertContentEquals(sidecarBytes,sidecar.readBytes());assertEquals(sidecarTime,Files.getLastModifiedTime(sidecar))
 }

}

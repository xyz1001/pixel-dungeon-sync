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
 @Test fun metadataApplyFailureRollsBackAndRequiresExplicitRecoveryBeforeRetry()=Dav().use{dav->
  val ca=configured("restore-A",dav);val cb=configured("restore-B",dav);seed(Path.of(ca.saveRoot),1)
  val a=open(ca);val b=open(cb);published(a,dav);val chosen=b.readRemote(dav.store()).single().entry.manifest.id
  val meta=b.root.resolve(DIRECTORY_METADATA_FILE).createDirectory();meta.resolve("blocked").writeText("fixture")
  assertFails{b.restoreRemote(chosen,dav.store()){true}};assertTrue(b.observedFiles().isEmpty());assertTrue(b.hasPending());assertFalse(b.retryablePending())
  assertFails{b.push(dav.store()){true}};assertTrue(b.history().any{it.reason.contains("备份")})
  meta.resolve("blocked").deleteExisting();meta.deleteExisting();b.archivePending();restore(b,dav)
  val files=b.observedFiles();restore(b,dav);assertEquals(files,b.observedFiles());assertFalse(b.hasPending())
  assertFails{b.restoreRemote(chosen,dav.store()){false}};assertEquals(files,b.observedFiles())
 }
}

package pdsync

import kotlinx.serialization.decodeFromString

import okhttp3.Credentials
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import java.util.jar.JarOutputStream
import java.util.jar.JarEntry
import java.util.jar.Manifest as JarManifest
import kotlin.io.path.*
import kotlin.test.*

/** Real HTTP + production DAV/session/version/config paths. Create-only PUT, no ETag, external service or game launch. */
class ProductionWebDavFlowTest {
 @TempDir lateinit var temp:Path
 private class Dav:AutoCloseable {
  val server=MockWebServer()
  val contents=ConcurrentHashMap<String,ByteArray>()
  val methods=CopyOnWriteArrayList<String>()
  val folders=ConcurrentHashMap.newKeySet<String>().apply{add("/dav/")}
  val failCommitAfterAccept=AtomicBoolean(false);val failObjects=AtomicBoolean(false)
  val failCommitReads=AtomicBoolean(false)
  val invalidProtocol=AtomicBoolean(false)
  private fun entry(path:String,folder:Boolean)="<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype>${if(folder)"<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
  init {
   server.dispatcher=object:Dispatcher(){
    override fun dispatch(r:RecordedRequest):MockResponse {
     methods.add(requireNotNull(r.method))
     if(r.getHeader("If-Match")!=null ||
      r.getHeader("If-None-Match") != (if(r.method=="PUT") "*" else null) ||
      r.method !in setOf("GET","PUT","PROPFIND","MKCOL"))invalidProtocol.set(true)
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
       if(prior!=null)return MockResponse().setResponseCode(412)
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
  override fun close(){server.close();assertFalse(invalidProtocol.get(),"Missing create-only PUT, ETag/head rewrite or unsupported method attempted")}
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

}

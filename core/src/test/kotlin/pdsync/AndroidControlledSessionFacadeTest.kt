package pdsync

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.android.app.*
import kotlin.io.path.*
import kotlin.test.*

/** Exercises the same separate activity/service facades used by Android with gzip game bundles. */
class AndroidControlledSessionFacadeTest {
 @TempDir lateinit var temp:Path
 private val identity=AndroidGameIdentity("com.shatteredpixel.shatteredpixeldungeon","Synthetic","a".repeat(64),"test")
 private val connection=AndroidConnection("https://dav.example.test/","user","synthetic","test")
 private inner class Bridge:AndroidGameBridge {
  var bytes=mapOf("game1/game.dat" to controlledRun(),"game1/depth1.dat" to byteArrayOf(2))
  var target=AndroidGameTarget(identity,"synthetic",896,"b".repeat(64))
  override fun targetGame()=target
  override fun exportTo(emptyDestination:Path){bytes.forEach {(name,data)->val path=emptyDestination.resolve(name);path.parent.createDirectories();path.writeBytes(data)}}
  override fun restoreFrom(preparedDirectory:Path,expectedCurrentFiles:Map<String,String>)=error("No game writes expected")
 }
 private class Store:AppendStore {
  val commits=linkedMapOf<String,ByteArray>();val objects=linkedMapOf<String,ByteArray>()
  override fun listCommits(cursor:String?)=CommitPage(commits.keys.toList())
  override fun readCommit(id:String)=commits[id]
  override fun readObject(id:String)=objects[id]
  override fun writeCommit(id:String,bytes:ByteArray){commits[id]=bytes}
  override fun writeObject(id:String,bytes:ByteArray){objects[id]=bytes}
  override fun legacyPresent()=false
  fun latest()=json.decodeFromString<AppendCommit>(commits.values.last().decodeToString())
 }
 private val bridge=Bridge();private val store=Store()
 private fun facade()=AndroidSyncFacade(temp,connection,bridge,identity,store,0)
 private fun publish():String {assertEquals("UPLOADED",facade().autoUpload().status);return requireNotNull(store.latest().snapshot.directoryMetadata!!.runs.getValue("game1").runUUID)}
 private fun progress(duration:Double=3.0,seed:Long=5352470251118){bridge.bytes=bridge.bytes+("game1/game.dat" to controlledRun(duration,seed))}
 private fun current()=store.latest().snapshot.directoryMetadata!!.runs.getValue("game1")
 @Test fun activityLaunchServiceForegroundActivityInspectThenServiceUploadRetainIdentity() {
  val id=publish();val parent=store.latest().id
  val launcher=facade();val token=launcher.beginControlledSession();val service=facade()
  service.observeControlledSession(token,true,true);progress()
  val activity=facade();activity.inspect() // This wins the return race, before the service's background poll.
  assertEquals("UPLOADED",service.autoUpload().status)
  assertEquals(id,current().runUUID);assertNull(current().previousRunUUID)
  assertEquals(listOf(parent),store.latest().parents);assertEquals(bridge.bytes.mapValues {hash(it.value)},store.latest().snapshot.files)
  progress(4.0);assertEquals("UPLOADED",service.autoUpload().status);assertNull(current().runUUID);assertEquals(id,current().previousRunUUID)
 }
 @Test fun serviceFirstUploadAlsoRetainsIdentityAcrossFacadeInstances() {
  val id=publish();val token=facade().beginControlledSession();val service=facade()
  service.observeControlledSession(token,true,true);progress();service.observeControlledSession(token,true,false)
  assertEquals("UPLOADED",service.autoUpload().status);assertEquals(id,current().runUUID)
 }
 @Test fun offlineChangesAtLaunchCannotBeReconfirmedByWindow() {
  val id=publish();progress();val token=facade().beginControlledSession();val service=facade()
  service.observeControlledSession(token,true,true);progress(4.0)
  assertEquals("UPLOADED",service.autoUpload().status);assertNull(current().runUUID);assertEquals(id,current().previousRunUUID)
 }
 @Test fun unknownIdentityWithPreviousUuidIsNeverPromoted() {
  val id=publish();progress();assertEquals("UPLOADED",facade().autoUpload().status);assertNull(current().runUUID)
  val token=facade().beginControlledSession();val service=facade();service.observeControlledSession(token,true,true);progress(4.0)
  assertEquals("UPLOADED",service.autoUpload().status);assertNull(current().runUUID);assertEquals(id,current().previousRunUUID)
 }
 @Test fun cancelledUnobservedAndUnknownWindowsKeepNormalUploadsButNotIdentity() {
  val id=publish();val launcher=facade();val token=launcher.beginControlledSession();val service=facade()
  service.observeControlledSession(token,true,true);launcher.cancelControlledSession(token);progress()
  assertEquals("UPLOADED",service.autoUpload().status);assertNull(current().runUUID);assertEquals(id,current().previousRunUUID)
 }
 @Test fun sameSlotNewRunAndSeedReplaysCannotInheritIdentity() {
  val id=publish();val token=facade().beginControlledSession();val service=facade()
  service.observeControlledSession(token,true,true);progress(1.0,99)
  assertEquals("UPLOADED",service.autoUpload().status);assertNull(current().runUUID);assertEquals(id,current().previousRunUUID)
 }
 @Test fun launchDoesNotMintIdentityWhenNoSidecarExists() {
  val token=facade().beginControlledSession();facade().observeControlledSession(token,true,true);progress()
  val mirror=Files.walk(temp).use {it.filter {p->p.fileName.toString()=="mirror"}.findFirst().orElseThrow()}
  assertFalse(mirror.resolve(DIRECTORY_METADATA_FILE).exists())
 }
}

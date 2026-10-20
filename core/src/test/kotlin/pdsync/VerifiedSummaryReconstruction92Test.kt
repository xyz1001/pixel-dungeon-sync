package pdsync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pdsync.android.app.*
import kotlin.io.path.*
import kotlin.test.*

/** New tests for summary/format reuse reconstructed from the shipped APK. */
class VerifiedSummaryReconstruction92Test {
 @TempDir lateinit var temp:Path
 private val entries=linkedMapOf("game1/game.dat" to "{\"version\":896}".encodeToByteArray(),"game1/depth1.dat" to "level".encodeToByteArray(),"rankings.dat" to "{\"total\":17}".encodeToByteArray())
 @Test fun verifiedSummaryMatchesOrdinaryReadWithActualLatestSlotMtime() {
  entries.entries.forEachIndexed {i,(name,bytes)->val p=temp.resolve(name);p.parent.createDirectories();p.writeBytes(bytes);Files.setLastModifiedTime(p,FileTime.fromMillis(1_700_000_000_000L+i*1000))}
  assertEquals(SaveSummary.read(temp),SaveSummary.readVerified(temp,entries))
  assertEquals("2023-11-14T22:13:21Z",SaveSummary.readVerified(temp,entries).slots["game1"])
  // The narrow verified helper consumes supplied bytes; it does not re-capture or rebuild a ZIP.
  temp.resolve("rankings.dat").deleteExisting()
  assertEquals(17,SaveSummary.readVerified(temp,entries).plays)
 }
 @Test fun snapshotSummaryKeepsRecordedTimestampSemantics() {
  val snapshot=Snapshots.fromFiles(entries,Shattered,"test","test")
  val context=SnapshotMetadata(device=DeviceIdentity(java.util.UUID.randomUUID().toString(),"Synthetic"),capturedAt="2023-01-02T00:00:00Z",fileModifiedAt=entries.keys.associateWith {if(it=="game1/depth1.dat")"2023-01-01T00:00:00Z" else "2022-01-01T00:00:00Z"})
  val summary=SaveSummary.from(snapshot.copy(manifest=snapshot.manifest.copy(metadata=context)))
  assertEquals("2023-01-01T00:00:00Z",summary.slots["game1"])
 }
 @Test fun verifiedVersionDetectionUsesSameFormatRulesAndFailureCategory() {
  val identity=AndroidGameIdentity("com.shatteredpixel.shatteredpixeldungeon","Synthetic","a".repeat(64))
  val adapter=AndroidGameAdapter(identity)
  val versions=AndroidProfileVersions(identity,adapter) {AndroidGameTarget(identity,"896",896,"b".repeat(64))}
  val snapshot=Snapshots.fromFiles(entries,adapter,"test","test")
  assertEquals(versions.detect(snapshot),versions.detectVerifiedEntries(entries))
  val failure=assertFailsWith<UnsupportedAndroidSaveFormatException> {versions.detectVerifiedEntries(entries+("game1/game.dat" to "malformed".encodeToByteArray()))}
  assertEquals(SyncBlockReason.UNSUPPORTED_SAVE_FORMAT,failure.syncBlockReason)
 }
}

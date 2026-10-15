package pdsync

import io.github.xyz1001.pdsync.android.SharedSavePathControl
import io.github.xyz1001.pdsync.android.SharedSavePathInteractions
import io.github.xyz1001.pdsync.android.SharedSavePathReadStatus
import io.github.xyz1001.pdsync.android.savePathSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

/** Production dispatch + desktop callbacks + real, isolated save directories. No native UI fixtures. */
class DesktopSharedSavePathFlowTest {
 @TempDir lateinit var temp: Path

 private val location get() = DefaultProfileLocation(SavePathContext("Linux", temp.toString(), emptyMap()))
 private fun saves(name: String, plays: Int? = 23): Path {
  val root = temp.resolve(name).createDirectory()
  root.resolve("game1").createDirectory()
  root.resolve("game1/game.dat").writeText("""{"version":896}""")
  plays?.let { root.resolve("rankings.dat").writeText("""{"total":$it}""") }
  return root
 }
 private fun adapter(root: Path, default: Path? = null) =
  DesktopSavePathAdapter(root.toString(), default?.toString(), location).apply { pageChanged(true) }
 private fun inspect(adapter: DesktopSavePathAdapter): Boolean {
  val request = assertNotNull(adapter.beginPreview())
  return adapter.completePreview(request, runCatching { adapter.inspect(request) })
 }
 private fun files(root: Path) = Files.walk(root).use { paths ->
  paths.filter(Files::isRegularFile).toList().associate { root.relativize(it).toString() to it.readBytes().toList() }
 }
 private fun interactions(adapter: DesktopSavePathAdapter, choose: () -> Unit = {}, next: () -> Unit = {},
                          canAct: () -> Boolean = { true }, back: () -> Unit = {}): SharedSavePathInteractions {
  val actions = DesktopSavePathActions(adapter, canAct, choose, back, next)
  return SharedSavePathInteractions({ adapter.state }, { actions })
 }

 @Test fun sharedPresentationUsesActualSlotsTimesAndPlayCountWithoutWriting() {
  val root = saves("actual")
  val before = files(root)
  val adapter = adapter(root)
  assertFalse(inspect(adapter))
  val summary = assertNotNull(adapter.state.summary)
  assertEquals(setOf("game1"), summary.slots.keys)
  assertNotNull(summary.slots["game1"])
  assertEquals(23, summary.plays)
  assertEquals("已进行23场游戏 · 存档 1 个", assertNotNull(savePathSummary(summary)).headline)
  assertEquals(before, files(root))
  assertEquals(SharedSavePathReadStatus.READY, adapter.state.readStatus)
  assertTrue(adapter.state.canContinue)
  assertTrue(adapter.state.editable)
  assertTrue(adapter.state.canChooseDirectory)
  assertFalse(adapter.state.canInitializeGame)
  assertNull(adapter.beginPreview(), "successful inspection must not automatically repeat")
 }

 @Test fun unknownPlayCountRemainsNullThroughDesktopAndSharedPresentation() {
  val root = saves("without-rankings", plays = null)
  val adapter = adapter(root)
  inspect(adapter)
  assertNull(assertNotNull(adapter.state.summary).plays)
  assertEquals("已进行0场游戏 · 存档 1 个", assertNotNull(savePathSummary(adapter.state.summary)).headline)
  assertFalse(root.resolve("rankings.dat").exists())
 }

 @Test fun nextJoinsPendingReadWithoutRevisionChangeAndCompletionPersistsOnlyOnce() {
  val root = saves("queued")
  val adapter = adapter(root)
  val pending = assertNotNull(adapter.beginPreview())
  var inspections = 0
  var persisted = 0
  val configFile = ConfigFile(temp.resolve("saved.json"))
  val actions = interactions(adapter, next = { error("ordinary Next must join the existing read") })
  val revision = adapter.revision
  repeat(3) { actions.perform(SharedSavePathControl.NEXT) }
  assertEquals(revision, adapter.revision)
  assertTrue(adapter.state.continueAfterRead)
  assertFalse(adapter.state.busy)
  assertNull(adapter.beginPreview())
  val result = runCatching { inspections++; adapter.inspect(pending) }
  fun publish() {
   if (adapter.completePreview(pending, result)) {
    val selected = location.requireSelected(adapter.state.path, true)
    configFile.save(AppConfig(selectedSaveRoot = selected.toString()))
    persisted++
   }
  }
  publish(); publish()
  assertEquals(1, inspections)
  assertEquals(1, persisted)
  assertEquals(root.toString(), configFile.load()!!.selectedSaveRoot)
  assertFalse(adapter.state.continueAfterRead)
 }

 @Test fun nextBeforeEffectStartsQueuesWithoutInventingAnAdditionalRead() {
  val adapter = adapter(saves("unread"))
  val revision = adapter.revision
  interactions(adapter).perform(SharedSavePathControl.NEXT)
  assertEquals(SharedSavePathReadStatus.UNREAD, adapter.state.readStatus)
  assertTrue(adapter.state.continueAfterRead)
  assertEquals(revision, adapter.revision)
  assertTrue(inspect(adapter))
  assertNull(adapter.beginPreview())
 }

 @Test fun editAndBackDuringQueuedReadInvalidateSummaryAndDiscardLateSuccessAndFailure() {
  val first = saves("first", 11)
  val second = saves("second", 27)
  val adapter = adapter(first)
  val old = assertNotNull(adapter.beginPreview())
  val oldResult = Result.success(adapter.inspect(old))
  val actions = interactions(adapter, back = { adapter.pageChanged(false) })
  actions.perform(SharedSavePathControl.NEXT)
  actions.editPath(second.toString())
  assertFalse(adapter.state.continueAfterRead)
  assertNull(adapter.state.summary)
  assertFalse(adapter.completePreview(old, oldResult))
  assertFalse(adapter.completePreview(old, Result.failure(IllegalStateException("stale"))))
  val current = assertNotNull(adapter.beginPreview())
  actions.perform(SharedSavePathControl.NEXT)
  actions.perform(SharedSavePathControl.BACK)
  assertFalse(adapter.completePreview(current, Result.success(adapter.inspect(current))))
  assertFalse(adapter.state.continueAfterRead)
  assertNull(adapter.state.summary)
  assertNull(adapter.beginPreview())
  adapter.pageChanged(true)
  inspect(adapter)
  val currentState = adapter.state
  assertFalse(adapter.completePreview(current, Result.failure(IllegalStateException("old page"))))
  assertEquals(currentState, adapter.state)
  assertEquals(27, adapter.state.summary!!.plays)
 }

 @Test fun queuedFailureStopsAndFooterRetryStartsOneFreshQueuedRead() {
  val adapter = adapter(saves("retry"))
  val pending = assertNotNull(adapter.beginPreview())
  val actions = interactions(adapter)
  actions.perform(SharedSavePathControl.NEXT)
  assertFalse(adapter.completePreview(pending, Result.failure(IllegalStateException("读取失败"))))
  assertEquals(SharedSavePathReadStatus.FAILED, adapter.state.readStatus)
  assertFalse(adapter.state.continueAfterRead)
  assertFalse(adapter.state.canContinue)
  assertNull(adapter.state.summary)
  assertNull(adapter.beginPreview())
  val revision = adapter.revision
  actions.perform(SharedSavePathControl.NEXT)
  assertTrue(adapter.revision > revision)
  val retryRevision = adapter.revision
  actions.perform(SharedSavePathControl.NEXT)
  assertEquals(retryRevision, adapter.revision)
  assertTrue(adapter.state.continueAfterRead)
  assertTrue(inspect(adapter))
  assertEquals(SharedSavePathReadStatus.READY, adapter.state.readStatus)
 }

 @Test fun nativeChooserCancellationKeepsTypedPathAndCompletedSummary() = runBlocking {
  val root = saves("selected")
  val adapter = adapter(root)
  inspect(adapter)
  val before = adapter.state
  val revision = adapter.revision
  val picker = CompletableDeferred<String?>()
  var busy = false
  val job = launch(start = CoroutineStart.UNDISPATCHED) {
   adapter.chooseDirectoryUsing({ initial -> assertEquals(root.toString(), initial); picker.await() },
    { true }, { busy = it })
  }
  assertTrue(busy)
  picker.complete(NativeFolderChooser.selection("linux", 1, byteArrayOf()))
  job.join()
  assertFalse(busy)
  assertEquals(before, adapter.state)
  assertEquals(revision, adapter.revision)
 }

 @Test fun chooserIsBlockedDuringReadEvenBeforeContinuationIsQueued() = runBlocking {
  val adapter = adapter(saves("reading"))
  val pending = assertNotNull(adapter.beginPreview())
  var chooserCalls = 0
  var busyCallbacks = 0
  interactions(adapter, choose = { chooserCalls++ }).perform(SharedSavePathControl.CHOOSE_DIRECTORY)
  adapter.chooseDirectoryUsing({ chooserCalls++; null }, { true }, { busyCallbacks++ })
  assertEquals(0, chooserCalls)
  assertEquals(0, busyCallbacks)
  assertFalse(adapter.state.busy)
  assertFalse(adapter.completePreview(pending, Result.success(adapter.inspect(pending))))
  interactions(adapter, choose = { chooserCalls++ }).perform(SharedSavePathControl.CHOOSE_DIRECTORY)
  assertEquals(1, chooserCalls)
 }

 @Test fun selectionInvalidatesOnlyAfterPickerReleasesBusyAndDefaultClearsQueuedRead() = runBlocking {
  val first = saves("picker-first", 1)
  val second = saves("picker-second", 2)
  val default = saves("default", 3)
  val adapter = adapter(first, default)
  inspect(adapter)
  val before = adapter.revision
  val transitions = mutableListOf<Pair<Boolean, Long>>()
  adapter.chooseDirectoryUsing({ second.toString() }, { true }, { transitions += it to adapter.revision })
  assertEquals(listOf(true to before, false to before), transitions)
  assertTrue(adapter.revision > before)
  assertEquals(second.toString(), adapter.state.path)
  assertNull(adapter.state.summary)
  val selected = assertNotNull(adapter.beginPreview())
  interactions(adapter).perform(SharedSavePathControl.NEXT)
  interactions(adapter).perform(SharedSavePathControl.USE_DEFAULT)
  assertEquals(default.toString(), adapter.state.path)
  assertFalse(adapter.state.continueAfterRead)
  assertFalse(adapter.completePreview(selected, Result.success(adapter.inspect(selected))))
  inspect(adapter)
  assertEquals(3, adapter.state.summary!!.plays)
 }

 @Test fun chooserCannotPublishAcrossPageLifetimes() = runBlocking {
  val first = saves("first-page")
  val second = saves("second-page")
  val adapter = adapter(first)
  inspect(adapter)
  val picker = CompletableDeferred<String?>()
  val job = launch(start = CoroutineStart.UNDISPATCHED) {
   adapter.chooseDirectoryUsing({ picker.await() }, { true }, {})
  }
  adapter.pageChanged(false)
  adapter.pageChanged(true)
  picker.complete(second.toString())
  job.join()
  assertEquals(first.toString(), adapter.state.path)
  assertNull(adapter.state.summary)
 }

 @Test fun safeMissingPreviewNeverCreatesDirectoryOrInventsSummaryAndOnlyNextPreparesIt() {
  val missing = temp.resolve("not-created/child")
  val adapter = adapter(missing)
  assertFalse(inspect(adapter))
  assertFalse(missing.exists())
  assertNull(adapter.state.summary)
  assertEquals(SharedSavePathReadStatus.PREPARE_REQUIRED, adapter.state.readStatus)
  assertTrue(adapter.state.canContinue)
  var persists = 0
  interactions(adapter, next = {
   location.requireSelected(adapter.state.path, true)
   persists++
   adapter.pageChanged(false)
  }).perform(SharedSavePathControl.NEXT)
  assertEquals(1, persists)
  assertTrue(missing.isDirectory())
 }

 @Test fun queuedExplicitNextMayPrepareOnlyAfterTypedMissingProof() {
  val missing = temp.resolve("queued-create")
  val adapter = adapter(missing)
  interactions(adapter).perform(SharedSavePathControl.NEXT)
  assertFalse(missing.exists())
  assertTrue(inspect(adapter))
  assertNull(adapter.state.summary)
  assertFalse(missing.exists(), "inspection remains read-only even with an explicit queued Next")
  location.requireSelected(adapter.state.path, true)
  assertTrue(missing.isDirectory())
 }

 @Test fun unsafePathsAndErrorMessagesCannotEnableMissingDirectoryPreparation() {
  val file = temp.resolve("file").apply { writeText("not a directory") }
  for (path in listOf(file, file.resolve("child"), temp.resolve("../escape"))) {
   val adapter = adapter(path)
   inspect(adapter)
   assertEquals(SharedSavePathReadStatus.FAILED, adapter.state.readStatus)
   assertFalse(adapter.state.canContinue)
   assertNull(adapter.state.summary)
  }
  val adapter = adapter(temp.resolve("untrusted"))
  val pending = assertNotNull(adapter.beginPreview())
  adapter.completePreview(pending, Result.failure(IllegalArgumentException("目录尚不存在；点击下一步可创建空目录")))
  assertEquals(SharedSavePathReadStatus.FAILED, adapter.state.readStatus)
  assertFalse(adapter.state.canContinue)
 }

 @Test fun unavailableDefaultAndRetainedCallbacksCannotChangeCurrentDraft() {
  val adapter = adapter(saves("unchanged"))
  inspect(adapter)
  var available = true
  var effects = 0
  val actions = interactions(adapter, choose = { effects++ }, next = { effects++ },
   canAct = { available }, back = { effects++ })
  val before = adapter.state
  actions.perform(SharedSavePathControl.USE_DEFAULT)
  assertEquals(before, adapter.state)
  available = false
  actions.editPath("/stale-draft")
  listOf(SharedSavePathControl.CHOOSE_DIRECTORY, SharedSavePathControl.RETRY,
   SharedSavePathControl.BACK, SharedSavePathControl.NEXT).forEach(actions::perform)
  assertEquals(before, adapter.state)
  assertEquals(0, effects)
 }

 @Test fun hardFailureCannotBeOverwrittenByEarlierPreview() {
  val adapter = adapter(saves("failed-next"))
  val pending = assertNotNull(adapter.beginPreview())
  val oldResult = Result.success(adapter.inspect(pending))
  adapter.showError("此目录没有可识别存档，请选择档案根目录或空目录")
  val failed = adapter.state
  assertFalse(adapter.completePreview(pending, oldResult))
  assertEquals(failed, adapter.state)
  assertNull(adapter.beginPreview())
  interactions(adapter).perform(SharedSavePathControl.NEXT)
  assertTrue(inspect(adapter))
 }
 @Test fun unrelatedNonemptyDirectoryIsFailedRatherThanAnInventedEmptyPreview() {
  val root=temp.resolve("unrelated").createDirectory()
  root.resolve("important.txt").writeText("keep this unrelated document")
  val before=files(root)
  val adapter=adapter(root)
  assertFalse(inspect(adapter))
  assertEquals(SharedSavePathReadStatus.FAILED,adapter.state.readStatus)
  assertNull(adapter.state.summary)
  assertFalse(adapter.state.canContinue)
  assertEquals(before,files(root))
 }

}

package pdsync
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import kotlin.io.path.*
import kotlin.test.*

class AtomicStateWriteTest {
 @TempDir lateinit var temp:Path
 @Test fun unsupportedAtomicReplacementKeepsOldBytesAndCleansOwnTemporaryFile() {
  val state=temp.resolve("baseline.txt");state.writeText("verified-old")
  assertFailsWith<AtomicMoveNotSupportedException> {
   SafePaths.atomicWrite(state,"new".encodeToByteArray()){from,to->throw AtomicMoveNotSupportedException(from.toString(),to.toString(),"injected")}
  }
  assertEquals("verified-old",state.readText())
  assertEquals(listOf("baseline.txt"),Files.list(temp).use{it.map{p->p.fileName.toString()}.toList()})
  SafePaths.atomicWrite(state,"verified-new".encodeToByteArray());assertEquals("verified-new",state.readText())
 }
}

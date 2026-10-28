package pdsync

import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.*

class HashEncoding125Test {
 private fun oldHash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
 private fun oldContent(files:Map<String,String>):String {
  val sorted=files.keys.sortedWith {a,b->
   val l=a.encodeToByteArray();val r=b.encodeToByteArray();var result=0
   for(i in 0 until minOf(l.size,r.size)){result=(l[i].toInt() and 255)-(r[i].toInt() and 255);if(result!=0)break}
   if(result==0)l.size-r.size else result
  }
  val bytes=ByteArrayOutputStream()
  DataOutputStream(bytes).use {out->
   out.write(CONTENT_HASH_ALGORITHM.encodeToByteArray());out.writeInt(sorted.size)
   sorted.forEach {name->val path=name.encodeToByteArray();out.writeInt(path.size);out.write(path)
    out.write(files.getValue(name).chunked(2).map {it.toInt(16).toByte()}.toByteArray())}
  }
  return oldHash(bytes.toByteArray())
 }
 @Test fun exactDigestAndCanonicalIdentityStayUnchanged() {
  val random=java.util.Random(125)
  for(size in listOf(0,1,31,32,255,256,4096,65204)) {
   val bytes=ByteArray(size).also(random::nextBytes);assertEquals(oldHash(bytes),hash(bytes))
  }
  for(count in listOf(0,1,30,100)) {
   val files=(0 until count).associate {"game1/depth$it.dat" to hash(ByteArray(32).also(random::nextBytes))}
   assertEquals(oldContent(files),contentSha256(files));assertEquals(oldContent(files),contentSha256(files.entries.reversed().associate {it.toPair()}))
  }
 }
 @Test fun formatRemovalBenchmarkRetainsAllSamplesAndDoesNotAssertTiming() {
  val bytes=ByteArray(1024){it.toByte()};val old=mutableListOf<Long>();val now=mutableListOf<Long>()
  repeat(200){oldHash(bytes);hash(bytes)}
  repeat(11){round->
   var start=System.nanoTime();repeat(1000){oldHash(bytes)};old+=(System.nanoTime()-start)/1000
   start=System.nanoTime();repeat(1000){hash(bytes)};now+=(System.nanoTime()-start)/1000
  }
  println("HASH125_WINDOWS 1000x1024B old_us=$old direct_us=$now; not phone timings")
 }
}

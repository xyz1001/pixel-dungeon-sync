package pdsync.android.app

import pdsync.*
import java.util.Locale
import java.util.zip.ZipInputStream

/** Consumes the bounded, operation-local backend archive without extracting or recompressing it. */
internal class AndroidArchiveSnapshot private constructor(
 val entries:Map<String,ByteArray>, val files:Map<String,String>, val modified:Map<String,Long>
) {
 companion object {
  internal fun read(archive:ByteArray,adapter:GameAdapter,digest:(ByteArray)->String=::hash):AndroidArchiveSnapshot {
   require(archive.size in 22..MAX_SNAPSHOT_BYTES)
   val entries=linkedMapOf<String,ByteArray>();val files=linkedMapOf<String,String>()
   val modified=linkedMapOf<String,Long>();val aliases=hashSetOf<String>();var total=0L
   ZipInputStream(archive.inputStream()).use {zip->
    while(true) {
     if(Thread.currentThread().isInterrupted)throw InterruptedException()
     val entry=zip.nextEntry ?: break;val name=entry.name
     require(!entry.isDirectory && safePath(name) && adapter.includes(name) && !isDirectoryMetadata(name))
     require(aliases.add(name.lowercase(Locale.ROOT)) && entries.size<MAX_SNAPSHOT_FILES)
     val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
     while(true) {
      if(Thread.currentThread().isInterrupted)throw InterruptedException()
      val count=zip.read(buffer);if(count<0)break
      total+=count;require(total<=MAX_SNAPSHOT_BYTES)
      output.write(buffer,0,count)
     }
     zip.closeEntry()
     val bytes=output.toByteArray();entries[name]=bytes;files[name]=digest(bytes)
     if(entry.time>=0)modified[name]=entry.time
    }
   }
   entries.keys.forEach {name->
    var parent=name.substringBeforeLast('/',"")
    while(parent.isNotEmpty()) {require(parent !in entries);parent=parent.substringBeforeLast('/',"")}
   }
   return AndroidArchiveSnapshot(entries,files,modified)
  }
 }
}
